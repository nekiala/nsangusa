package com.nsangusa.news.aieditorial.internal;

import static org.assertj.core.api.Assertions.*;

import java.util.Base64;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AiCredentialCipherTests {
  static final String KEY =
      Base64.getEncoder()
          .encodeToString(
              "01234567890123456789012345678901".getBytes(java.nio.charset.StandardCharsets.UTF_8));

  @Test
  void encryptsWithFreshNoncesAndBindsCiphertextToCredentialIdentity() {
    var cipher = new AiCredentialCipher(KEY);
    UUID id = UUID.randomUUID();
    String secret = "sk-test-only-not-an-external-credential";
    String encrypted = cipher.encrypt(id, secret);
    assertThat(encrypted).doesNotContain(secret).isNotEqualTo(cipher.encrypt(id, secret));
    assertThat(cipher.decrypt(id, encrypted)).isEqualTo(secret);
    assertThatThrownBy(() -> cipher.decrypt(UUID.randomUUID(), encrypted))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageNotContaining(secret);
    byte[] bytes = Base64.getDecoder().decode(encrypted);
    bytes[bytes.length - 1] ^= 1;
    assertThatThrownBy(() -> cipher.decrypt(id, Base64.getEncoder().encodeToString(bytes)))
        .isInstanceOf(IllegalStateException.class);
    var changedKey = new AiCredentialCipher(Base64.getEncoder().encodeToString(new byte[32]));
    assertThatThrownBy(() -> changedKey.decrypt(id, encrypted))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void missingAndInvalidMasterKeysFailClosedWithoutPlaintextFallback() {
    for (String key :
        java.util.List.of("", "not-base64", Base64.getEncoder().encodeToString(new byte[16]))) {
      var cipher = new AiCredentialCipher(key);
      assertThat(cipher.available()).isFalse();
      assertThatThrownBy(() -> cipher.encrypt(UUID.randomUUID(), "secret"))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("AI_CREDENTIAL_MASTER_KEY");
    }
  }
}
