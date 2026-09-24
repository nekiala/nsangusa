package com.nsangusa.news.aieditorial.internal;

final class AiProviderException extends IllegalArgumentException {
  private final String code;
  private String model;
  private long inputTokens;
  private long outputTokens;

  AiProviderException(String code) {
    super("AI operation rejected: " + code);
    this.code = code;
  }

  String code() {
    return code;
  }

  AiProviderException withUsage(String model, long inputTokens, long outputTokens) {
    this.model = model;
    this.inputTokens = inputTokens;
    this.outputTokens = outputTokens;
    return this;
  }

  String model() {
    return model;
  }

  long inputTokens() {
    return inputTokens;
  }

  long outputTokens() {
    return outputTokens;
  }
}
