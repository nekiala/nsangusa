package com.nsangusa.news.aieditorial.internal;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.springframework.data.jpa.repository.JpaRepository;

@Entity
@Table(name = "ai_results")
class AiResultRecord {
  @Id UUID id;
  UUID requestId;
  int schemaVersion;

  @JdbcTypeCode(SqlTypes.JSON)
  String resultJson;

  BigDecimal confidence;
  Instant createdAt;

  protected AiResultRecord() {}

  AiResultRecord(UUID requestId, String json, BigDecimal confidence) {
    this.id = requestId;
    this.requestId = requestId;
    this.schemaVersion = 1;
    this.resultJson = json;
    this.confidence = confidence;
    this.createdAt = Instant.now();
  }
}

interface AiResultRepository extends JpaRepository<AiResultRecord, UUID> {
  java.util.Optional<AiResultRecord> findByRequestId(UUID requestId);
}
