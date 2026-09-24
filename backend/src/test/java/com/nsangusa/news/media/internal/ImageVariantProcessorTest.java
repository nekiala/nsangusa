package com.nsangusa.news.media.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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

  @Test
  void rasterVariantsContainRealPngContentWithRequiredDimensions() throws Exception {
    byte[] source =
        java.util.Base64.getDecoder()
            .decode(
                "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=");

    for (var variant : new ImageVariantProcessor().variants(source, "image/png")) {
      var input =
          new javax.imageio.stream.MemoryCacheImageInputStream(
              new java.io.ByteArrayInputStream(variant.bytes()));
      var image = javax.imageio.ImageIO.read(input);
      assertThat(image.getWidth()).isEqualTo(variant.width());
      assertThat(image.getHeight()).isEqualTo(variant.height());
      assertThat(variant.contentType()).isEqualTo("image/png");
    }
  }

  @Test
  void oversizedRasterDimensionsAreRejectedBeforeDecompression() {
    byte[] source =
        java.util.Base64.getDecoder()
            .decode(
                "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=");
    java.nio.ByteBuffer.wrap(source, 16, 4).putInt(9000);
    var crc = new java.util.zip.CRC32();
    crc.update(source, 12, 17);
    java.nio.ByteBuffer.wrap(source, 29, 4).putInt((int) crc.getValue());

    assertThatThrownBy(() -> new ImageVariantProcessor().variants(source, "image/png"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("dimensions");
  }

  @Test
  void activeAndExternalSvgContentIsRejected() {
    for (String source :
        new String[] {
          "<svg xmlns=\"http://www.w3.org/2000/svg\"><script>alert(1)</script></svg>",
          "<svg xmlns=\"http://www.w3.org/2000/svg\" onload=\"alert(1)\"/>",
          "<svg xmlns=\"http://www.w3.org/2000/svg\"><rect fill=\"url(https://host/track)\"/></svg>",
          "<!DOCTYPE svg [<!ENTITY external SYSTEM \"file:///private\">]>"
              + "<svg xmlns=\"http://www.w3.org/2000/svg\">&external;</svg>",
          "<?xml-stylesheet href=\"https://host/track\"?>"
              + "<svg xmlns=\"http://www.w3.org/2000/svg\"/>"
        }) {
      assertThatThrownBy(
              () ->
                  new ImageVariantProcessor()
                      .variants(source.getBytes(StandardCharsets.UTF_8), "image/svg+xml"))
          .isInstanceOf(IllegalArgumentException.class);
    }
  }
}
