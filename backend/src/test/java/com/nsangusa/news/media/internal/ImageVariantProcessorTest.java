package com.nsangusa.news.media.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class ImageVariantProcessorTest {
  @Test
  void createsRequiredEditorialVariants() {
    byte[] source =
        """
        <svg xmlns="http://www.w3.org/2000/svg" width="1600" height="900"></svg>
        """
            .getBytes(StandardCharsets.UTF_8);

    var variants = new ImageVariantProcessor().variants(source, "image/svg+xml");

    assertThat(variants)
        .extracting(ImageVariantProcessor.Variant::name)
        .containsExactly("hero", "thumbnail", "social");
    assertThat(variants)
        .extracting(ImageVariantProcessor.Variant::width)
        .containsExactly(1600, 640, 1200);
    assertThat(new String(variants.get(2).bytes(), StandardCharsets.UTF_8))
        .contains("width=\"1200\"", "height=\"630\"");
  }
}
