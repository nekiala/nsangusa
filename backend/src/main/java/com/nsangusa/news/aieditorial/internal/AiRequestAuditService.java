package com.nsangusa.news.aieditorial.internal;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nsangusa.news.aieditorial.EditorialProviders.AnalysisResult;
import com.nsangusa.news.aieditorial.EditorialProviders.ProviderConfiguration;
import com.nsangusa.news.aieditorial.EditorialProviders.SafetyResult;
import com.nsangusa.news.aieditorial.EditorialRequestService;
import com.nsangusa.news.integration.NewsEvents.ArticleDraftGenerated;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
class AiRequestAuditService implements EditorialRequestService {
  private AiProviderSetupService setup;

  @org.springframework.beans.factory.annotation.Autowired(required = false)
  void useProviderSetup(AiProviderSetupService setup) {
    this.setup = setup;
  }

  private final AiRequestRepository requests;
  private final AiResultRepository results;
  private final ObjectMapper mapper;
  private final JdbcTemplate jdbc;
  private final long dailyTokenBudget;
  private final long reservationTokens;

  AiRequestAuditService(
      AiRequestRepository requests,
      AiResultRepository results,
      ObjectMapper mapper,
      JdbcTemplate jdbc,
      @Value("${news.providers.ai.daily-token-budget:1000000}") long dailyTokenBudget,
      @Value("${news.providers.ai.max-request-bytes:131072}") int maxRequestBytes,
      @Value("${news.providers.ai.max-output-tokens:4096}") int maxOutputTokens) {
    if (dailyTokenBudget < 1
        || dailyTokenBudget > 1_000_000_000L
        || maxRequestBytes < 1024
        || maxRequestBytes > 262_144
        || maxOutputTokens < 256
        || maxOutputTokens > 16_384) {
      throw new IllegalArgumentException("Invalid AI token budget");
    }
    this.requests = requests;
    this.results = results;
    this.mapper = mapper;
    this.jdbc = jdbc;
    this.dailyTokenBudget = dailyTokenBudget;
    this.reservationTokens = (long) maxRequestBytes + maxOutputTokens;
  }

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public ProviderConfiguration snapshot(
      UUID eventId, String operation, ProviderConfiguration fallback) {
    return snapshot(eventId, operation, () -> fallback);
  }

  @Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW)
  public ProviderConfiguration snapshot(
      UUID eventId, String operation, java.util.function.Supplier<ProviderConfiguration> fallback) {
    return requests
        .findFirstByEventIdAndOperationOrderByCreatedAtAsc(eventId, operation)
        .filter(request -> request.configurationVersion != null)
        .map(AiRequestRecord::configuration)
        .orElseGet(fallback);
  }

  @Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW)
  public ProviderConfiguration workflowSnapshot(
      UUID eventId,
      UUID storyId,
      UUID causationId,
      String operation,
      java.util.function.Supplier<ProviderConfiguration> fallback) {
    var exact =
        requests.findFirstByEventIdAndStoryCandidateIdAndOperationOrderByCreatedAtAsc(
            eventId, storyId, operation);
    if (exact.isPresent()) return capturedConfiguration(exact.get());
    var sibling =
        requests.findFirstByEventIdAndStoryCandidateIdOrderByCreatedAtAsc(eventId, storyId);
    if (sibling.isPresent()) return capturedConfiguration(sibling.get());
    if (causationId != null && ("draft".equals(operation) || "draft-safety".equals(operation))) {
      // Draft events point to their analysis event. Never silently substitute a newer selection.
      return requests
          .findFirstByEventIdAndStoryCandidateIdAndOperationOrderByCreatedAtAsc(
              causationId, storyId, "analysis")
          .map(AiRequestAuditService::capturedConfiguration)
          .orElseThrow(() -> new AiProviderException("analysis_configuration_unavailable"));
    }
    return fallback.get();
  }

  private static ProviderConfiguration capturedConfiguration(AiRequestRecord request) {
    if (request.configurationVersion == null) {
      throw new AiProviderException("configuration_snapshot_unavailable");
    }
    return request.configuration();
  }

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public UUID begin(
      UUID eventId, UUID storyId, String operation, ProviderConfiguration configuration) {
    return requests.saveAndFlush(new AiRequestRecord(storyId, eventId, operation, configuration))
        .id;
  }

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public boolean budgetExhausted(UUID id) {
    var request = requests.findById(id).orElseThrow();
    if (!"openai".equals(request.provider)) {
      return false;
    }
    jdbc.execute("select pg_advisory_xact_lock(1936613735)");
    Long reserved =
        jdbc.queryForObject(
            """
        select coalesce(sum(case when status in ('pending', 'failed', 'redacted')
          then greatest(reserved_tokens, input_tokens + output_tokens)
          else input_tokens + output_tokens end), 0)
        from ai_requests
        where provider = 'openai'
          and (created_at >= date_trunc('day', now() at time zone 'UTC') at time zone 'UTC'
            or completed_at >= date_trunc('day', now() at time zone 'UTC') at time zone 'UTC'
            or status = 'pending')
        """,
            Long.class);
    long budget = dailyTokenBudget;
    if (setup != null)
      budget = Math.min(budget, setup.runtimeSettings(request.configuration()).dailyTokenBudget());
    if (reserved + reservationTokens > budget) {
      return true;
    }
    // UTF-8 request bytes conservatively bound text tokens, including schema/instruction overhead.
    request.reservedTokens = reservationTokens;
    return false;
  }

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void complete(UUID id, Object result) {
    var request = requests.findById(id).orElseThrow();
    var sourceStates =
        jdbc.queryForList(
            """
        select source.status from source_posts source
        join story_candidate_sources link on link.source_post_id = source.id
        where link.story_candidate_id = ? for share of source
        """,
            String.class,
            request.storyCandidateId);
    if (sourceStates.isEmpty()
        || sourceStates.stream().anyMatch(state -> !"active".equals(state))
        || "redacted"
            .equals(
                jdbc.queryForObject(
                    "select status from ai_requests where id = ?", String.class, id))) {
      throw new AiProviderException("source_content_changed");
    }
    var usage = usage(result);
    if (request.configurationVersion != null
        && (!request.promptVersion.equals(usage.configuration().promptVersion())
            || !request.provider.equals(usage.configuration().provider()))) {
      throw new AiProviderException("configuration_provenance_mismatch");
    }
    if (request.configurationVersion == null) {
      request.promptVersion = usage.configuration().promptVersion();
    }
    request.complete(
        usage.configuration().provider(),
        usage.configuration().model(),
        usage.inputTokens(),
        usage.outputTokens());
    if (result instanceof SafetyResult safety && !safety.allowed()) {
      request.block("content_safety");
    }
    try {
      results.save(
          new AiResultRecord(
              id,
              mapper.writeValueAsString(result),
              result instanceof AnalysisResult analysis
                  ? analysis.confidence()
                  : result instanceof ArticleDraftGenerated draft ? draft.confidence() : null));
    } catch (JsonProcessingException exception) {
      throw new IllegalStateException("Cannot persist typed AI result", exception);
    }
  }

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void fail(UUID id, RuntimeException failure, Object rejectedResult) {
    var request = requests.findById(id).orElseThrow();
    if (rejectedResult != null) {
      var usage = usage(rejectedResult);
      request.model = usage.configuration().model();
      if (request.configurationVersion == null) {
        request.provider = usage.configuration().provider();
        request.promptVersion = usage.configuration().promptVersion();
      }
      request.inputTokens = Math.max(0, usage.inputTokens());
      request.outputTokens = Math.max(0, usage.outputTokens());
    }
    if (failure instanceof AiProviderException providerFailure && providerFailure.model() != null) {
      request.model = providerFailure.model();
      request.inputTokens = providerFailure.inputTokens();
      request.outputTokens = providerFailure.outputTokens();
    }
    request.fail(errorCode(failure));
  }

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void reviewDraft(UUID eventId, ArticleDraftGenerated draft, boolean allowed) {
    var request =
        requests
            .findFirstByEventIdAndOperationAndStatusOrderByCreatedAtDesc(
                eventId, "draft", "completed")
            .or(
                () ->
                    requests.findFirstByEventIdAndOperationAndStatusOrderByCreatedAtDesc(
                        eventId, "draft", "blocked"))
            .orElseThrow(() -> new AiProviderException("source_content_changed"));
    complete(request.id, draft);
    if (!allowed) {
      request.block("generated_content_safety");
    }
  }

  @Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW)
  public <T> Optional<T> completed(UUID eventId, String operation, Class<T> type) {
    var completed =
        requests.findFirstByEventIdAndOperationAndStatusOrderByCreatedAtDesc(
            eventId, operation, "completed");
    if (completed.isEmpty()
        && (type == SafetyResult.class || type == ArticleDraftGenerated.class)) {
      completed =
          requests.findFirstByEventIdAndOperationAndStatusOrderByCreatedAtDesc(
              eventId, operation, "blocked");
    }
    return completed
        .flatMap(request -> results.findByRequestId(request.id))
        .map(result -> read(result.resultJson, type));
  }

  @Override
  @Transactional(readOnly = true)
  public List<AiRequestView> list(UUID storyCandidateId) {
    return requests.findTop100ByStoryCandidateIdOrderByCreatedAtDesc(storyCandidateId).stream()
        .map(
            request ->
                new AiRequestView(
                    request.id,
                    request.storyCandidateId,
                    request.operation,
                    request.provider,
                    request.model,
                    request.promptVersion,
                    request.status,
                    request.errorCode,
                    request.createdAt,
                    request.completedAt,
                    request.inputTokens,
                    request.outputTokens,
                    inspectableResult(request),
                    request.configurationVersion == null ? null : request.configuration()))
        .toList();
  }

  private Object inspectableResult(AiRequestRecord request) {
    if (!"completed".equals(request.status) || "safety".equals(request.operation)) {
      return null;
    }
    return results
        .findByRequestId(request.id)
        .map(
            result ->
                switch (request.operation) {
                  case "analysis" -> read(result.resultJson, AnalysisResult.class);
                  case "draft" -> read(result.resultJson, ArticleDraftGenerated.class);
                  default -> null;
                })
        .orElse(null);
  }

  private <T> T read(String json, Class<T> type) {
    try {
      return mapper.readValue(json, type);
    } catch (JsonProcessingException exception) {
      throw new IllegalStateException("Stored AI result is incompatible", exception);
    }
  }

  static Usage usage(Object result) {
    return switch (result) {
      case AnalysisResult analysis ->
          new Usage(
              new ProviderConfiguration(
                  analysis.provider(), analysis.model(), analysis.promptVersion()),
              analysis.inputTokens(),
              analysis.outputTokens());
      case ArticleDraftGenerated draft ->
          new Usage(
              new ProviderConfiguration(draft.provider(), draft.model(), draft.promptVersion()),
              draft.inputTokens(),
              draft.outputTokens());
      case SafetyResult safety ->
          new Usage(
              new ProviderConfiguration(safety.provider(), safety.model(), safety.promptVersion()),
              safety.inputTokens(),
              safety.outputTokens());
      default -> throw new IllegalArgumentException("Unsupported AI result type");
    };
  }

  static String errorCode(RuntimeException exception) {
    if (exception instanceof AiProviderException failure) {
      return failure.code();
    }
    if (exception instanceof org.springframework.web.client.ResourceAccessException) {
      return "provider_network";
    }
    if (exception instanceof org.springframework.web.client.HttpServerErrorException) {
      return "provider_unavailable";
    }
    if (exception
        instanceof org.springframework.web.client.HttpClientErrorException.TooManyRequests) {
      return "provider_rate_limited";
    }
    if (exception instanceof jakarta.validation.ConstraintViolationException) {
      return "invalid_output";
    }
    return "operation_failed";
  }

  record Usage(ProviderConfiguration configuration, long inputTokens, long outputTokens) {}
}
