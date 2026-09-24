package com.nsangusa.news.storyprocessing.internal;

import com.nsangusa.news.integration.NewsEvents.SourceReference;
import com.nsangusa.news.integration.NewsEvents.XPostNormalized;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "story_candidate_sources")
class StoryCandidateSource {
  @Id UUID id;

  @Column(nullable = false)
  UUID storyCandidateId;

  @Column(nullable = false)
  UUID sourcePostId;

  @Column(nullable = false)
  String account;

  @Column(nullable = false)
  String postId;

  @Column(nullable = false)
  String url;

  @Column(nullable = false)
  Instant publishedAt;

  @Column(nullable = false, columnDefinition = "text")
  String normalizedText;

  @Column(nullable = false)
  Instant addedAt;

  protected StoryCandidateSource() {}

  StoryCandidateSource(UUID storyCandidateId, XPostNormalized source) {
    this.id = UUID.randomUUID();
    this.storyCandidateId = storyCandidateId;
    this.sourcePostId = source.sourcePostId();
    this.account = source.handle();
    this.postId = source.postId();
    this.url = source.canonicalUrl();
    this.publishedAt = source.publishedAt();
    this.normalizedText = source.normalizedText();
    this.addedAt = Instant.now();
  }

  SourceReference reference() {
    return new SourceReference(sourcePostId, account, postId, url, publishedAt);
  }
}
