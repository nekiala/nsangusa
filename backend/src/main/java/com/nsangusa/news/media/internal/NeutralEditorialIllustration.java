package com.nsangusa.news.media.internal;

import java.awt.Color;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import javax.imageio.ImageIO;
import javax.imageio.stream.MemoryCacheImageOutputStream;

final class NeutralEditorialIllustration {
  static final String PROVIDER = "nsangusa-editorial";
  static final String MODEL = "neutral-illustration-v1";
  static final String PROMPT =
      "Original neutral editorial fallback illustration; a stylized newspaper and geometric"
          + " shapes; no text, logos, people, or depiction of a reported event.";
  static final String RIGHTS = "Original programmatic artwork; no third-party assets.";

  byte[] render() {
    var image = new BufferedImage(1600, 900, BufferedImage.TYPE_INT_RGB);
    var graphics = image.createGraphics();
    try {
      graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
      graphics.setColor(new Color(0xEDE9E1));
      graphics.fillRect(0, 0, 1600, 900);
      graphics.setColor(new Color(0xD5D3CE));
      graphics.fillOval(1180, 80, 270, 270);
      graphics.setColor(new Color(0xDEDAD2));
      graphics.fillRoundRect(120, 580, 280, 210, 35, 35);
      graphics.setColor(new Color(0xBDBBB6));
      graphics.fillRoundRect(360, 205, 920, 550, 28, 28);
      graphics.setColor(new Color(0xFAF8F3));
      graphics.fillRoundRect(320, 165, 920, 550, 28, 28);
      graphics.setColor(new Color(0x242B35));
      graphics.fillRoundRect(375, 220, 805, 45, 10, 10);
      graphics.setColor(new Color(0xDEDCD6));
      graphics.fillRoundRect(375, 310, 350, 340, 12, 12);
      graphics.setColor(new Color(0x969794));
      graphics.fillOval(435, 365, 220, 220);
      graphics.setColor(new Color(0xAEB0AD));
      for (int row = 0; row < 6; row++) {
        graphics.fillRoundRect(775, 320 + row * 57, row == 5 ? 270 : 405, 18, 8, 8);
      }
    } finally {
      graphics.dispose();
    }
    var output = new ByteArrayOutputStream();
    try (var stream = new MemoryCacheImageOutputStream(output)) {
      if (!ImageIO.write(image, "png", stream)) {
        throw new IllegalStateException("PNG encoder unavailable");
      }
      stream.flush();
      return output.toByteArray();
    } catch (IOException exception) {
      throw new IllegalStateException("Unable to render neutral editorial illustration", exception);
    }
  }
}
