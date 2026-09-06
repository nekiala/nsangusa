package com.nsangusa.news.storyprocessing.internal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "story_candidates")
class StoryCandidate {
  @Id UUID id;

  @Column(nullable = false)
  UUID primarySourcePostId;

  @Column(nullable = false)
  String topic;

  @Column(nullable = false)
  String status;

  @Column(nullable = false)
  Instant createdAt;

  @Version long version;

  protected StoryCandidate() {}

  StoryCandidate(UUID id, UUID primarySourcePostId, String topic) {
    this.id = id;
    this.primarySourcePostId = primarySourcePostId;
    this.topic = topic;
    this.status = "analyzing";
    this.createdAt = Instant.now();
  }
}
