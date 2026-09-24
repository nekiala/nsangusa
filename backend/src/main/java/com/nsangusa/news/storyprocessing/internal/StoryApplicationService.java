package com.nsangusa.news.storyprocessing.internal;

import com.nsangusa.news.audit.AuditService;
import com.nsangusa.news.integration.NewsEvents.XPostNormalized;
import com.nsangusa.news.sourceingestion.SourceIngestionService;
import com.nsangusa.news.storyprocessing.StoryService;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
class StoryApplicationService implements StoryService {
  private final StoryCandidateRepository stories;
  private final StoryCandidateSourceRepository memberships;
  private final SourceIngestionService sources;
  private final AuditService audit;

  StoryApplicationService(
      StoryCandidateRepository stories,
      StoryCandidateSourceRepository memberships,
      SourceIngestionService sources,
      AuditService audit) {
    this.stories = stories;
    this.memberships = memberships;
    this.sources = sources;
    this.audit = audit;
  }

  @Override
  @Transactional(readOnly = true)
  public StoryPage list(int page, int size) {
    if (page < 0 || size < 1 || size > 100) {
      throw new IllegalArgumentException("Page must be nonnegative and size between 1 and 100");
    }
    var result =
        stories.findAll(
            PageRequest.of(
                page, size, Sort.by(Sort.Order.desc("createdAt"), Sort.Order.asc("id"))));
    return new StoryPage(
        result.getContent().stream()
            .map(
                story ->
                    new StoryView(
                        story.id,
                        story.topic,
                        story.status,
                        story.createdAt,
                        memberships.findByStoryCandidateIdOrderByPublishedAtAsc(story.id).stream()
                            .map(source -> source.sourcePostId)
                            .toList(),
                        story.draftArticleId))
            .toList(),
        page,
        size,
        result.getTotalElements());
  }

  @Override
  @Transactional
  public UUID regenerate(UUID candidateId, UUID actorId) {
    var original =
        stories
            .findLockedById(candidateId)
            .orElseThrow(
                () ->
                    new ResponseStatusException(HttpStatus.NOT_FOUND, "Story candidate not found"));
    if (!"drafted".equals(original.status) && !"blocked_safety".equals(original.status)) {
      throw new IllegalStateException(
          "Only a completed or safety-blocked candidate can be regenerated");
    }
    var previous = memberships.findByStoryCandidateIdOrderByPublishedAtAsc(candidateId);
    if (previous.isEmpty()) {
      throw new IllegalStateException("Story candidate has no sources");
    }
    sources.assertSourcesPublishable(previous.stream().map(source -> source.sourcePostId).toList());
    var current = previous.stream().map(source -> sources.getSource(source.sourcePostId)).toList();
    UUID id = UUID.randomUUID();
    var candidate =
        new StoryCandidate(
            id,
            current.getFirst().id(),
            original.topic,
            original.conversationId,
            StoryClustering.deserialize(original.clusterTerms),
            id,
            null);
    candidate.regeneratedFromId = candidateId;
    candidate.sourceCount = current.size();
    stories.saveAndFlush(candidate);
    // The migration trigger inserts the primary membership for old and current writers.
    for (var source : current) {
      if (!source.id().equals(candidate.primarySourcePostId)) {
        memberships.save(
            new StoryCandidateSource(
                id,
                new XPostNormalized(
                    source.id(),
                    source.postId(),
                    source.handle(),
                    source.canonicalUrl(),
                    source.permittedText(),
                    source.publishedAt(),
                    Set.of(original.topic),
                    original.conversationId)));
      }
    }
    audit.record(
        actorId,
        "STORY_REGENERATION_REQUESTED",
        "story_candidate",
        id,
        Map.of("previousCandidateId", candidateId.toString()));
    return id;
  }
}
