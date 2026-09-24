package com.nsangusa.news.newsletter.internal;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
class UnsubscribeTokenService {
  private final byte[] secret;

  UnsubscribeTokenService(@Value("${news.newsletter.token-secret}") String secret) {
    if (secret.length() < 32) {
      throw new IllegalStateException(
          "Newsletter token secret must contain at least 32 characters");
    }
    this.secret = secret.getBytes(StandardCharsets.UTF_8);
  }

  String tokenFor(UUID subscriptionId) {
    return sign(subscriptionId.toString());
  }

  String preferenceTokenFor(
      UUID requestId,
      UUID subscriptionId,
      java.time.Instant expiresAt,
      java.time.Instant verifiedAt) {
    return requestId
        + "."
        + sign(
            "newsletter-preferences:v1:"
                + requestId
                + ":"
                + subscriptionId
                + ":"
                + expiresAt.getEpochSecond()
                + ":"
                + verifiedAt.getEpochSecond());
  }

  private String sign(String value) {
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(secret, "HmacSHA256"));
      return Base64.getUrlEncoder()
          .withoutPadding()
          .encodeToString(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
    } catch (java.security.GeneralSecurityException exception) {
      throw new IllegalStateException("Unable to sign unsubscribe token", exception);
    }
  }
}
