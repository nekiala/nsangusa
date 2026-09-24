package com.nsangusa.news.media.internal;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import javax.imageio.ImageIO;
import javax.imageio.stream.MemoryCacheImageInputStream;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.springframework.stereotype.Component;
import org.w3c.dom.Element;

@Component
class ImageVariantProcessor {
  record Variant(String name, int width, int height, byte[] bytes, String contentType) {}

  List<Variant> variants(byte[] source, String contentType) {
    if (source == null || source.length == 0 || source.length > StoredImages.MAX_BYTES) {
      throw new IllegalArgumentException("Generated image exceeds size limits");
    }
    if ("image/svg+xml".equals(contentType)) {
      validateSvg(source);
      String svg = new String(source, StandardCharsets.UTF_8);
      return List.of(
          svgVariant("hero", 1600, 900, svg),
          svgVariant("thumbnail", 640, 360, svg),
          svgVariant("social", 1200, 630, svg));
    }
    if (!"image/png".equals(contentType) && !"image/jpeg".equals(contentType)) {
      throw new IllegalArgumentException("Unsupported generated image content type");
    }
    BufferedImage original = readRaster(source);
    return List.of(
        resize("hero", 1600, 900, original),
        resize("thumbnail", 640, 360, original),
        resize("social", 1200, 630, original));
  }

  private BufferedImage readRaster(byte[] source) {
    try (var input = new MemoryCacheImageInputStream(new ByteArrayInputStream(source))) {
      var readers = ImageIO.getImageReaders(input);
      if (!readers.hasNext()) {
        throw new IllegalArgumentException("Unsupported generated image content");
      }
      var reader = readers.next();
      try {
        reader.setInput(input, true, true);
        int width = reader.getWidth(0);
        int height = reader.getHeight(0);
        if (width <= 0
            || height <= 0
            || width > 8192
            || height > 8192
            || (long) width * height > 20_000_000) {
          throw new IllegalArgumentException("Generated image dimensions exceed limits");
        }
        return reader.read(0);
      } finally {
        reader.dispose();
      }
    } catch (java.io.IOException exception) {
      throw new IllegalArgumentException("Unable to decode generated image", exception);
    }
  }

  private Variant resize(String name, int width, int height, BufferedImage original) {
    BufferedImage target = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
    Graphics2D graphics = target.createGraphics();
    try {
      graphics.setRenderingHint(
          RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
      graphics.drawImage(original, 0, 0, width, height, null);
    } finally {
      graphics.dispose();
    }
    var output = new ByteArrayOutputStream();
    try (var stream = new MemoryCacheImageOutputStream(output)) {
      if (!ImageIO.write(target, "png", stream)) {
        throw new IllegalStateException("PNG encoder unavailable");
      }
      stream.flush();
      return new Variant(name, width, height, output.toByteArray(), "image/png");
    } catch (java.io.IOException exception) {
      throw new IllegalArgumentException("Unable to optimize generated image", exception);
    }
  }

  private Variant svgVariant(String name, int width, int height, String svg) {
    String resized =
        svg.replaceFirst("width=\"[0-9]+\"", "width=\"" + width + "\"")
            .replaceFirst("height=\"[0-9]+\"", "height=\"" + height + "\"");
    return new Variant(
        name, width, height, resized.getBytes(StandardCharsets.UTF_8), "image/svg+xml");
  }

  private void validateSvg(byte[] source) {
    if (source.length > 100_000) {
      throw new IllegalArgumentException("Illustration exceeds size limits");
    }
    try {
      var factory = DocumentBuilderFactory.newInstance();
      factory.setNamespaceAware(true);
      factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
      factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
      factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
      factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
      factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
      factory.setXIncludeAware(false);
      factory.setExpandEntityReferences(false);
      var builder = factory.newDocumentBuilder();
      builder.setErrorHandler(
          new org.xml.sax.helpers.DefaultHandler() {
            @Override
            public void fatalError(org.xml.sax.SAXParseException exception)
                throws org.xml.sax.SAXException {
              throw exception;
            }
          });
      var document = builder.parse(new ByteArrayInputStream(source));
      if (!"svg".equals(document.getDocumentElement().getTagName())) {
        throw new IllegalArgumentException("Invalid illustration root");
      }
      var elements = document.getElementsByTagName("*");
      Set<String> tags = Set.of("svg", "rect", "path", "circle", "metadata");
      Set<String> attributes =
          Set.of(
              "xmlns",
              "width",
              "height",
              "viewBox",
              "fill",
              "stroke",
              "stroke-width",
              "opacity",
              "d",
              "cx",
              "cy",
              "r",
              "x",
              "y");
      for (int i = 0; i < elements.getLength(); i++) {
        var element = (Element) elements.item(i);
        if (!tags.contains(element.getTagName())
            || !"http://www.w3.org/2000/svg".equals(element.getNamespaceURI())) {
          throw new IllegalArgumentException("Active illustration content is not supported");
        }
        var attrs = element.getAttributes();
        for (int j = 0; j < attrs.getLength(); j++) {
          var attribute = attrs.item(j);
          String value = attribute.getNodeValue().toLowerCase(Locale.ROOT);
          if (!attributes.contains(attribute.getNodeName())
              || value.contains("url(")
              || value.contains("\\")
              || value.contains("&")) {
            throw new IllegalArgumentException("External illustration content is not supported");
          }
        }
      }
      for (var node = document.getFirstChild(); node != null; node = node.getNextSibling()) {
        if (node.getNodeType() == org.w3c.dom.Node.PROCESSING_INSTRUCTION_NODE) {
          throw new IllegalArgumentException(
              "Illustration processing instructions are not supported");
        }
      }
    } catch (java.io.IOException
        | org.xml.sax.SAXException
        | javax.xml.parsers.ParserConfigurationException exception) {
      throw new IllegalArgumentException("Invalid generated illustration", exception);
    }
  }
}
