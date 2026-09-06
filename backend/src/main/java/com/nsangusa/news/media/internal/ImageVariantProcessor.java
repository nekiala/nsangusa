package com.nsangusa.news.media.internal;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import javax.imageio.ImageIO;
import org.springframework.stereotype.Component;

@Component
class ImageVariantProcessor {
  record Variant(String name, int width, int height, byte[] bytes, String contentType) {}

  List<Variant> variants(byte[] source, String contentType) {
    return List.of(
        resize("hero", 1600, 900, source, contentType),
        resize("thumbnail", 640, 360, source, contentType),
        resize("social", 1200, 630, source, contentType));
  }

  private Variant resize(String name, int width, int height, byte[] source, String contentType) {
    if ("image/svg+xml".equals(contentType)) {
      String svg =
          new String(source, StandardCharsets.UTF_8)
              .replaceFirst("width=\"[0-9]+\"", "width=\"" + width + "\"")
              .replaceFirst("height=\"[0-9]+\"", "height=\"" + height + "\"");
      return new Variant(
          name, width, height, svg.getBytes(StandardCharsets.UTF_8), "image/svg+xml");
    }
    try {
      BufferedImage original = ImageIO.read(new ByteArrayInputStream(source));
      if (original == null) {
        throw new IllegalArgumentException("Unsupported generated image content");
      }
      BufferedImage target = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
      Graphics2D graphics = target.createGraphics();
      graphics.setRenderingHint(
          RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
      graphics.drawImage(original, 0, 0, width, height, null);
      graphics.dispose();
      var output = new ByteArrayOutputStream();
      ImageIO.write(target, "png", output);
      return new Variant(name, width, height, output.toByteArray(), "image/png");
    } catch (java.io.IOException exception) {
      throw new IllegalArgumentException("Unable to optimize generated image", exception);
    }
  }
}
