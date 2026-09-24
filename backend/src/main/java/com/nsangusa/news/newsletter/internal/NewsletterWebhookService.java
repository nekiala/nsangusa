package com.nsangusa.news.newsletter.internal;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class NewsletterWebhookService {
  private static final Duration RESEND_TIMESTAMP_TOLERANCE = Duration.ofMinutes(5);

  private final ProviderWebhookRepository webhooks;
  private final NewsletterDeliveryRepository deliveries;
  private final NewsletterSubscriptionRepository subscriptions;
  private final SuppressionEntryRepository suppressions;
  private final ObjectMapper mapper;
  private final byte[] secret;
  private final String configuredProvider;

  NewsletterWebhookService(
      ProviderWebhookRepository webhooks,
      NewsletterDeliveryRepository deliveries,
      NewsletterSubscriptionRepository subscriptions,
      SuppressionEntryRepository suppressions,
      ObjectMapper mapper,
      @Value("${news.newsletter.webhook-secret}") String secret,
      @Value("${news.newsletter.provider:local-smtp}") String configuredProvider) {
    if (secret.length() < 32) {
      throw new IllegalStateException(
          "Newsletter webhook secret must contain at least 32 characters");
    }
    this.webhooks = webhooks;
    this.deliveries = deliveries;
    this.subscriptions = subscriptions;
    this.suppressions = suppressions;
    this.mapper = mapper;
    this.configuredProvider = configuredProvider;
    this.secret =
        "resend".equalsIgnoreCase(configuredProvider)
            ? decodeResendSecret(secret)
            : secret.getBytes(StandardCharsets.UTF_8);
  }

  @Transactional
  void process(
      String provider,
      String svixId,
      String svixTimestamp,
      String svixSignature,
      String legacySignature,
      String body) {
    if (!configuredProvider.equalsIgnoreCase(provider)) {
      throw new org.springframework.security.access.AccessDeniedException(
          "Unexpected provider webhook");
    }
    boolean resend = "resend".equalsIgnoreCase(provider);
    if (!(resend
        ? validResendSignature(svixId, svixTimestamp, svixSignature, body)
        : validLegacySignature(legacySignature, body))) {
      throw new org.springframework.security.access.AccessDeniedException(
          "Invalid provider webhook signature");
    }
    try {
      var payload = mapper.readTree(body);
      String externalId = resend ? svixId : payload.required("id").asText();
      if (webhooks.existsByProviderAndExternalEventId(provider, externalId)) {
        return;
      }
      var webhook = webhooks.save(new ProviderWebhook(provider, externalId, true, sha256(body)));
      String type = payload.required("type").asText();
      var data = resend ? payload.required("data") : payload;
      String messageId = data.path(resend ? "message_id" : "messageId").asText();
      String email =
          (resend ? data.path("to").path(0) : data.path("email"))
              .asText()
              .trim()
              .toLowerCase(java.util.Locale.ROOT);
      String suppressionReason =
          switch (type) {
            case "email.bounced", "bounce" -> "bounce";
            case "email.complained", "complaint" -> "complaint";
            default -> null;
          };
      if ("email.delivered".equals(type) || "delivered".equals(type)) {
        if (messageId.isBlank())
          throw new IllegalArgumentException("Provider webhook is missing delivery identity");
        deliveries
            .findByProviderMessageId(messageId)
            .ifPresent(NewsletterDelivery::confirmDelivery);
      }
      if (suppressionReason != null) {
        if (messageId.isBlank() || email.isBlank()) {
          throw new IllegalArgumentException("Provider webhook is missing delivery identity");
        }
        deliveries.findByProviderMessageId(messageId).ifPresent(delivery -> delivery.bounced(type));
        subscriptions
            .findByEmail(email)
            .ifPresent(
                subscription -> {
                  subscription.suppress();
                  String emailHash = sha256(email);
                  if (!suppressions.existsByEmailHash(emailHash)) {
                    suppressions.save(new SuppressionEntry(emailHash, suppressionReason));
                  }
                });
      }
      webhook.processed();
    } catch (com.fasterxml.jackson.core.JsonProcessingException exception) {
      throw new IllegalArgumentException("Malformed provider webhook", exception);
    }
  }

  private boolean validResendSignature(
      String messageId, String timestamp, String signatures, String body) {
    if (messageId == null || timestamp == null || signatures == null) {
      return false;
    }
    long epochSeconds;
    try {
      epochSeconds = Long.parseLong(timestamp);
    } catch (NumberFormatException exception) {
      return false;
    }
    if (Duration.between(Instant.ofEpochSecond(epochSeconds), Instant.now())
            .abs()
            .compareTo(RESEND_TIMESTAMP_TOLERANCE)
        > 0) {
      return false;
    }
    String expected =
        Base64.getEncoder()
            .encodeToString(
                hmac((messageId + "." + timestamp + "." + body).getBytes(StandardCharsets.UTF_8)));
    return java.util.Arrays.stream(signatures.trim().split("\\s+"))
        .map(value -> value.split(",", 2))
        .filter(parts -> parts.length == 2 && "v1".equals(parts[0]))
        .map(parts -> parts[1])
        .anyMatch(candidate -> constantTimeEquals(expected, candidate));
  }

  private boolean validLegacySignature(String signature, String body) {
    return signature != null
        && constantTimeEquals(
            HexFormat.of().formatHex(hmac(body.getBytes(StandardCharsets.UTF_8))), signature);
  }

  private byte[] hmac(byte[] content) {
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(secret, "HmacSHA256"));
      return mac.doFinal(content);
    } catch (java.security.GeneralSecurityException exception) {
      throw new IllegalStateException("Unable to validate webhook", exception);
    }
  }

  private static boolean constantTimeEquals(String expected, String supplied) {
    return supplied != null
        && MessageDigest.isEqual(
            expected.getBytes(StandardCharsets.US_ASCII),
            supplied.getBytes(StandardCharsets.US_ASCII));
  }

  private static byte[] decodeResendSecret(String secret) {
    if (!secret.startsWith("whsec_")) {
      throw new IllegalStateException("Resend webhook secret must start with whsec_");
    }
    try {
      return Base64.getDecoder().decode(secret.substring("whsec_".length()));
    } catch (IllegalArgumentException exception) {
      throw new IllegalStateException("Resend webhook secret must contain valid Base64", exception);
    }
  }

  private static String sha256(String value) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (java.security.NoSuchAlgorithmException exception) {
      throw new IllegalStateException(exception);
    }
  }
}
