package com.nsangusa.news.audit.internal;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nsangusa.news.audit.AuditService;
import java.util.Map;
import java.util.UUID;
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
}
