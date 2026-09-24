package com.nsangusa.news.eventprocessing.internal;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nsangusa.news.eventprocessing.DurableCommandExecutor;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
class JpaDurableCommandExecutor implements DurableCommandExecutor {
  private static final Pattern KEY = Pattern.compile("[!-~]{8,200}");
  private static final int MAX_OPERATION_LENGTH = 500;
  private static final int MAX_RESULT_LENGTH = 200;

  private final JdbcClient jdbc;
  private final ObjectMapper objectMapper;

  JpaDurableCommandExecutor(JdbcClient jdbc, ObjectMapper objectMapper) {
    this.jdbc = jdbc;
    this.objectMapper = objectMapper;
  }

  @Override
  @Transactional(propagation = Propagation.REQUIRED)
  public String execute(
      UUID actorId, String key, String operation, Object request, Supplier<String> command) {
    if (key == null) {
      return command.get();
    }
    if (!KEY.matcher(key).matches()) {
      throw conflict(
          "Idempotency-Key must contain 8-200 printable non-whitespace ASCII characters");
    }
    if (actorId == null || operation == null || operation.isBlank()) {
      throw new IllegalArgumentException("Idempotent commands require an actor and operation");
    }
    if (operation.length() > MAX_OPERATION_LENGTH) {
      throw new IllegalArgumentException("Command operation must not exceed 500 characters");
    }
    String requestHash = requestHash(request);
    jdbc.sql("select pg_advisory_xact_lock(hashtextextended(:scope, 0))")
        .param("scope", "http-command:" + actorId + ":" + key)
        .query((result, row) -> Boolean.TRUE)
        .single();
    var receipt =
        jdbc.sql(
                """
                select operation, request_hash, result
                  from request_idempotency
                 where actor_id = :actor and idempotency_key = :key
                """)
            .param("actor", actorId)
            .param("key", key)
            .query(
                (result, row) ->
                    new Receipt(
                        result.getString("operation"),
                        result.getString("request_hash"),
                        result.getString("result")))
            .optional();
    if (receipt.isPresent()) {
      var previous = receipt.get();
      if (!previous.operation().equals(operation) || !previous.requestHash().equals(requestHash)) {
        throw conflict("Idempotency-Key was already used for a different operation or request");
      }
      return previous.result();
    }

    String result = command.get();
    if (result != null && result.length() > MAX_RESULT_LENGTH) {
      throw new IllegalArgumentException("Command result must not exceed 200 characters");
    }
    jdbc.sql(
            """
            insert into request_idempotency
              (actor_id, idempotency_key, operation, request_hash, status, result)
            values (:actor, :key, :operation, :hash, 'succeeded', :result)
            """)
        .param("actor", actorId)
        .param("key", key)
        .param("operation", operation)
        .param("hash", requestHash)
        .param("result", result)
        .update();
    return result;
  }

  private String requestHash(Object request) {
    try {
      byte[] json = objectMapper.writeValueAsBytes(canonicalize(objectMapper.valueToTree(request)));
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(json));
    } catch (JsonProcessingException exception) {
      throw new IllegalArgumentException("Command request is not serializable", exception);
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 is unavailable", exception);
    }
  }

  private JsonNode canonicalize(JsonNode node) {
    if (node.isObject()) {
      var fields = new TreeMap<String, JsonNode>();
      node.properties().forEach(field -> fields.put(field.getKey(), field.getValue()));
      var result = objectMapper.createObjectNode();
      for (Map.Entry<String, JsonNode> field : fields.entrySet()) {
        result.set(field.getKey(), canonicalize(field.getValue()));
      }
      return result;
    }
    if (node.isArray()) {
      var result = objectMapper.createArrayNode();
      node.forEach(element -> result.add(canonicalize(element)));
      return result;
    }
    return node;
  }

  private ResponseStatusException conflict(String message) {
    return new ResponseStatusException(HttpStatus.CONFLICT, message);
  }

  private record Receipt(String operation, String requestHash, String result) {}
}
