package com.nsangusa.news.audit.internal;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nsangusa.news.audit.AuditService;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Map;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class AuditApplicationService implements AuditService {
  private final AuditRepository records;
  private final ObjectMapper objectMapper;

  AuditApplicationService(AuditRepository records, ObjectMapper objectMapper) {
    this.records = records;
    this.objectMapper = objectMapper;
  }

  @Override
  @Transactional
  public void record(
      UUID actorId, String action, String targetType, UUID targetId, Map<String, String> metadata) {
    try {
      records.save(
          new AuditEntity(
              actorId, action, targetType, targetId, objectMapper.writeValueAsString(metadata)));
    } catch (JsonProcessingException exception) {
      throw new IllegalArgumentException("Audit metadata is not serializable", exception);
    }
  }

  @Override
  @Transactional(readOnly = true)
  public AuditPage list(
      String action,
      String targetType,
      UUID actorId,
      UUID targetId,
      Instant from,
      Instant to,
      int page,
      int size) {
    Instant until = to == null ? Instant.now() : to;
    Instant since = from == null ? until.minus(Duration.ofDays(7)) : from;
    if (page < 0 || page > 10_000 || size < 1 || size > 100) {
      throw new IllegalArgumentException("Invalid audit page or size");
    }
    if (!since.isBefore(until)
        || Duration.between(since, until).compareTo(Duration.ofDays(90)) > 0) {
      throw new IllegalArgumentException("Audit time range must be positive and at most 90 days");
    }
    for (String value : new String[] {action, targetType}) {
      if (value != null && (value.isBlank() || value.length() > 100)) {
        throw new IllegalArgumentException("Audit filters must contain 1-100 characters");
      }
    }
    var result =
        records.findAll(
            (root, query, builder) -> {
              var predicates = new ArrayList<jakarta.persistence.criteria.Predicate>();
              predicates.add(builder.greaterThanOrEqualTo(root.get("occurredAt"), since));
              predicates.add(builder.lessThanOrEqualTo(root.get("occurredAt"), until));
              if (action != null) predicates.add(builder.equal(root.get("action"), action));
              if (targetType != null)
                predicates.add(builder.equal(root.get("targetType"), targetType));
              if (actorId != null) predicates.add(builder.equal(root.get("actorId"), actorId));
              if (targetId != null) predicates.add(builder.equal(root.get("targetId"), targetId));
              return builder.and(predicates.toArray(jakarta.persistence.criteria.Predicate[]::new));
            },
            PageRequest.of(
                page, size, Sort.by(Sort.Order.desc("occurredAt"), Sort.Order.desc("id"))));
    return new AuditPage(
        result.stream().map(this::view).toList(), page, size, result.getTotalElements());
  }

  private AuditView view(AuditEntity record) {
    try {
      Map<String, Object> metadata =
          objectMapper.readValue(
              record.metadata,
              new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
      return new AuditView(
          record.id,
          record.actorId,
          record.action,
          record.targetType,
          record.targetId,
          record.occurredAt,
          metadata);
    } catch (JsonProcessingException exception) {
      throw new IllegalStateException("Stored audit metadata is invalid", exception);
    }
  }
}
