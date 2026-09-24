package com.nsangusa.news.storyprocessing.internal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;

@Entity
@Table(name = "story_candidates")
class StoryCandidate {
  @Id UUID id;

  @Column(nullable = false)
  UUID primarySourcePostId;

  @Column(nullable = false)
  String topic;

  String conversationId;

  @Column(nullable = false, columnDefinition = "text")
  String clusterTerms;

  @Column(nullable = false)
  String status;

  @Column(nullable = false)
  Instant createdAt;

  @Column(nullable = false)
  Instant lastSourceAt;

  Instant analysisRequestedAt;

  @Column(nullable = false)
  int sourceCount;

  @Column(nullable = false)
  UUID correlationId;

  UUID lastSourceEventId;
  UUID draftArticleId;
  UUID regeneratedFromId;

  @Version long version;

  protected StoryCandidate() {}

  StoryCandidate(UUID id, UUID primarySourcePostId, String topic) {
    this(id, primarySourcePostId, topic, null, Set.of(), id, null);
  }

  StoryCandidate(
      UUID id,
      UUID primarySourcePostId,
      String topic,
      String conversationId,
      Set<String> clusterTerms,
      UUID correlationId,
      UUID lastSourceEventId) {
    this.id = id;
    this.primarySourcePostId = primarySourcePostId;
    this.topic = topic;
    this.conversationId = conversationId;
    this.clusterTerms = StoryClustering.serialize(clusterTerms);
    this.status = "collecting";
    this.createdAt = Instant.now();
    this.lastSourceAt = this.createdAt;
    this.sourceCount = 1;
    this.correlationId = correlationId;
    this.lastSourceEventId = lastSourceEventId;
  }

  boolean matches(String incomingConversationId, Set<String> incomingTerms, double threshold) {
    if (conversationId != null && conversationId.equals(incomingConversationId)) {
      return true;
    }
    return StoryClustering.similarity(StoryClustering.deserialize(clusterTerms), incomingTerms)
        >= threshold;
  }

  void addSource(Set<String> incomingTerms, UUID sourceEventId) {
    clusterTerms =
        StoryClustering.serialize(
            StoryClustering.merge(StoryClustering.deserialize(clusterTerms), incomingTerms));
    sourceCount++;
    lastSourceAt = Instant.now();
    lastSourceEventId = sourceEventId;
  }

  void analysisRequested(Instant requestedAt) {
    if (!"collecting".equals(status)) {
      throw new IllegalStateException("Story candidate is not collecting sources");
    }
    status = "analyzing";
    analysisRequestedAt = requestedAt;
  }

  boolean blockForSafety() {
    if (!"analyzing".equals(status)) {
      return false;
    }
    status = "blocked_safety";
    return true;
  }

  boolean markDrafted() {
    if (!"analyzing".equals(status)) {
      return false;
    }
    status = "drafted";
    return true;
  }
}
