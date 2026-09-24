package com.nsangusa.news.articles;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.io.Serializable;
import java.net.URI;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

public record ArticleContent(
    @Min(1) @Max(1) int version,
    @NotNull @Size(min = 1, max = 200) List<@NotNull @Valid Block> blocks)
    implements Serializable {
  public static final int MAX_BLOCKS = 200;
  public static final int MAX_TEXT = 100_000;

  public ArticleContent {
    if (version != 1) {
      throw new IllegalArgumentException("Unsupported article content version");
    }
    if (blocks == null
        || blocks.isEmpty()
        || blocks.size() > MAX_BLOCKS
        || blocks.stream().anyMatch(java.util.Objects::isNull)) {
      throw new IllegalArgumentException("Article content requires 1–200 valid blocks");
    }
    blocks = List.copyOf(blocks);
    long total = (blocks.size() - 1L) * 2;
    for (Block block : blocks) {
      total +=
          block.items() == null
              ? block.text().length() + (block.url() == null ? 0 : block.url().length() + 3L)
              : block.items().stream().mapToLong(String::length).sum() + block.items().size() - 1L;
    }
    if (total > MAX_TEXT) {
      throw new IllegalArgumentException("Article content exceeds 100000 characters");
    }
  }

  @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
  public static ArticleContent fromJson(Map<String, Object> value) {
    if (value == null
        || !Set.of("version", "blocks").equals(value.keySet())
        || !(value.get("version") instanceof Integer version)
        || !(value.get("blocks") instanceof List<?> blocks)
        || blocks.isEmpty()
        || blocks.size() > MAX_BLOCKS) {
      throw new IllegalArgumentException(
          "Article content requires an integer version and blocks only");
    }
    return new ArticleContent(
        version,
        blocks.stream()
            .map(
                item -> {
                  if (!(item instanceof Map<?, ?> block)) {
                    throw new IllegalArgumentException("Article blocks must be objects");
                  }
                  return Block.fromJson(block);
                })
            .toList());
  }

  public String plainText() {
    return plainText(blocks);
  }

  public static ArticleContent fromBody(String body) {
    if (body == null || body.isBlank() || body.length() > MAX_TEXT) {
      throw new IllegalArgumentException("Article body requires 1–100000 characters");
    }
    return new ArticleContent(
        1,
        Arrays.stream(body.trim().split("\\n\\s*\\n", MAX_BLOCKS))
            .filter(value -> !value.isBlank())
            .map(value -> new Block("paragraph", value, null, null))
            .toList());
  }

  private static String plainText(List<Block> blocks) {
    return blocks.stream().map(Block::plainText).collect(Collectors.joining("\n\n"));
  }

  public static boolean safeLink(String url) {
    if (url == null
        || url.length() > 2048
        || url.codePoints().anyMatch(c -> Character.isISOControl(c) || Character.isWhitespace(c))
        || url.contains("\\")
        || url.matches("(?is).*%(?:0[0-9a-f]|1[0-9a-f]|7f).*")) {
      return false;
    }
    try {
      URI uri = URI.create(url);
      return ("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme()))
          && uri.getHost() != null
          && !uri.getHost().isBlank()
          && uri.getRawUserInfo() == null
          && (uri.getPort() == -1 || uri.getPort() <= 65535);
    } catch (IllegalArgumentException invalid) {
      return false;
    }
  }

  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record Block(
      @NotBlank @Pattern(regexp = "paragraph|heading|quote|unordered_list|ordered_list|link") String type,
      @Size(max = MAX_TEXT) String text,
      @Size(min = 1, max = 100) List<@NotBlank @Size(max = MAX_TEXT) String> items,
      @Size(max = 2048) String url)
      implements Serializable {
    public Block {
      if (type == null) {
        throw new IllegalArgumentException("An article block type is required");
      }
      switch (type) {
        case "paragraph", "heading", "quote", "link" -> {
          if (text == null || text.isBlank() || text.length() > MAX_TEXT || items != null) {
            throw new IllegalArgumentException("Text blocks require text and cannot contain items");
          }
          text = text.trim();
          if ("link".equals(type)) {
            if (!safeLink(url)) {
              throw new IllegalArgumentException(
                  "Links require an absolute HTTP(S) URL without credentials or control characters");
            }
          } else if (url != null) {
            throw new IllegalArgumentException("Only link blocks can contain a URL");
          }
        }
        case "unordered_list", "ordered_list" -> {
          if (text != null
              || url != null
              || items == null
              || items.isEmpty()
              || items.size() > 100
              || items.stream()
                  .anyMatch(item -> item == null || item.isBlank() || item.length() > MAX_TEXT)) {
            throw new IllegalArgumentException(
                "List blocks require 1–100 nonblank items and no text or URL");
          }
          items = items.stream().map(String::trim).toList();
        }
        default -> throw new IllegalArgumentException("Unsupported article block type: " + type);
      }
    }

    @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
    public static Block fromJson(Map<?, ?> value) {
      if (value == null
          || !Set.of("type", "text", "items", "url").containsAll(value.keySet())
          || !(value.get("type") instanceof String type)
          || (value.get("text") != null && !(value.get("text") instanceof String))
          || (value.get("url") != null && !(value.get("url") instanceof String))
          || (value.get("items") != null && !(value.get("items") instanceof List<?>))) {
        throw new IllegalArgumentException("Invalid article block fields");
      }
      Set<String> fields =
          switch (type) {
            case "paragraph", "heading", "quote" -> Set.of("type", "text");
            case "ordered_list", "unordered_list" -> Set.of("type", "items");
            case "link" -> Set.of("type", "text", "url");
            default ->
                throw new IllegalArgumentException("Unsupported article block type: " + type);
          };
      if (!value.keySet().containsAll(fields)
          || value.entrySet().stream()
              .anyMatch(entry -> !fields.contains(entry.getKey()) && entry.getValue() != null)) {
        throw new IllegalArgumentException("Invalid fields for article block type: " + type);
      }
      List<String> items = null;
      if (value.get("items") instanceof List<?> values) {
        if (values.isEmpty() || values.size() > 100) {
          throw new IllegalArgumentException("List blocks require 1–100 items");
        }
        items =
            values.stream()
                .map(
                    item -> {
                      if (!(item instanceof String text)) {
                        throw new IllegalArgumentException("List items must be strings");
                      }
                      return text;
                    })
                .toList();
      }
      return new Block(type, (String) value.get("text"), items, (String) value.get("url"));
    }

    public String plainText() {
      return switch (type) {
        case "ordered_list", "unordered_list" -> String.join("\n", items);
        case "link" -> text + " (" + url + ")";
        default -> text;
      };
    }
  }
}
