package com.nsangusa.news.media.internal;

import java.io.IOException;
import java.io.InputStream;

final class StoredImages {
  static final int MAX_BYTES = 20_000_000;

  private StoredImages() {}

  static void validateKey(String objectKey) {
    if (objectKey == null
        || objectKey.length() > 1000
        || !objectKey.matches("[A-Za-z0-9][A-Za-z0-9._/-]*")) {
      throw new IllegalArgumentException("Invalid object key");
    }
    for (String segment : objectKey.split("/", -1)) {
      if (segment.isEmpty() || ".".equals(segment) || "..".equals(segment)) {
        throw new IllegalArgumentException("Invalid object key");
      }
    }
  }

  static String contentType(String objectKey) {
    if (objectKey.endsWith(".png")) {
      return "image/png";
    }
    if (objectKey.endsWith(".svg")) {
      return "image/svg+xml";
    }
    throw new IllegalArgumentException("Unsupported stored image type");
  }

  static void validateImage(String objectKey, byte[] bytes, String contentType) {
    validateKey(objectKey);
    if (!contentType(objectKey).equals(contentType)) {
      throw new IllegalArgumentException("Stored image type does not match its key");
    }
    if (bytes == null || bytes.length == 0 || bytes.length > MAX_BYTES) {
      throw new IllegalArgumentException("Stored image exceeds size limits");
    }
  }

  static byte[] readBounded(InputStream stream, int maxBytes) throws IOException {
    byte[] bytes = stream.readNBytes(maxBytes + 1);
    if (bytes.length > maxBytes) {
      throw new IllegalArgumentException("Image response exceeds size limits");
    }
    if (bytes.length == 0) {
      throw new IllegalArgumentException("Empty image response");
    }
    return bytes;
  }

  static String sha256(byte[] bytes) {
    try {
      return java.util.HexFormat.of()
          .formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (java.security.NoSuchAlgorithmException exception) {
      throw new IllegalStateException(exception);
    }
  }
}
