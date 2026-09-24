package com.nsangusa.news.aieditorial.internal;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.UUID;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
class AiCredentialCipher {
  private final SecretKeySpec key;
  private final SecureRandom random = new SecureRandom();

  AiCredentialCipher(@Value("${news.providers.ai.credential-master-key:}") String masterKey) {
    SecretKeySpec parsed = null;
    try {
      byte[] bytes = Base64.getDecoder().decode(masterKey);
      if (bytes.length == 32) parsed = new SecretKeySpec(bytes, "AES");
      java.util.Arrays.fill(bytes, (byte) 0);
    } catch (IllegalArgumentException ignored) {
      // Missing or invalid deployment keys disable credential operations, never plaintext storage.
    }
    key = parsed;
  }

  boolean available() {
    return key != null;
  }

  String encrypt(UUID id, String plaintext) {
    requireKey();
    byte[] nonce = new byte[12];
    random.nextBytes(nonce);
    byte[] bytes = plaintext.getBytes(StandardCharsets.UTF_8);
    try {
      byte[] encrypted = cipher(Cipher.ENCRYPT_MODE, id, nonce).doFinal(bytes);
      return Base64.getEncoder()
          .encodeToString(
              ByteBuffer.allocate(nonce.length + encrypted.length)
                  .put(nonce)
                  .put(encrypted)
                  .array());
    } catch (GeneralSecurityException exception) {
      throw new IllegalStateException("AI credential encryption unavailable");
    } finally {
      java.util.Arrays.fill(bytes, (byte) 0);
    }
  }

  String decrypt(UUID id, String ciphertext) {
    requireKey();
    byte[] plaintext = null;
    try {
      byte[] bytes = Base64.getDecoder().decode(ciphertext);
      if (bytes.length < 29) throw new GeneralSecurityException();
      byte[] nonce = java.util.Arrays.copyOfRange(bytes, 0, 12);
      plaintext = cipher(Cipher.DECRYPT_MODE, id, nonce).doFinal(bytes, 12, bytes.length - 12);
      return new String(plaintext, StandardCharsets.UTF_8);
    } catch (GeneralSecurityException | IllegalArgumentException exception) {
      throw new IllegalStateException(
          "AI credential cannot be decrypted; restore the deployment key or rotate the credential");
    } finally {
      if (plaintext != null) java.util.Arrays.fill(plaintext, (byte) 0);
    }
  }

  private Cipher cipher(int mode, UUID id, byte[] nonce) throws GeneralSecurityException {
    Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
    cipher.init(mode, key, new GCMParameterSpec(128, nonce));
    cipher.updateAAD(("nsangusa:ai:openai:v1:" + id).getBytes(StandardCharsets.UTF_8));
    return cipher;
  }

  private void requireKey() {
    if (!available()) {
      throw new IllegalStateException(
          "Set AI_CREDENTIAL_MASTER_KEY to a base64-encoded 32-byte deployment secret before storing credentials");
    }
  }
}
