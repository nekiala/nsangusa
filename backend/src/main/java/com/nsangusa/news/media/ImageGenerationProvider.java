package com.nsangusa.news.media;

public interface ImageGenerationProvider {
  GeneratedImage generate(String prompt, String altText);

  record GeneratedImage(
      byte[] bytes,
      String contentType,
      String provider,
      String model,
      String altText,
      String renderedPrompt,
      String promptVersion,
      String providerRequestId) {
    public GeneratedImage(
        byte[] bytes, String contentType, String provider, String model, String altText) {
      this(bytes, contentType, provider, model, altText, null, null, null);
    }
  }
}
