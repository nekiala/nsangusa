package com.nsangusa.news.sourceingestion.internal;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;

@Entity
@Table(name = "source_relationships")
class SourceRelationshipEntity {
  @Id UUID id;
  UUID sourcePostId;
  String relatedPostId;
  String relationshipType;

  protected SourceRelationshipEntity() {}
}
