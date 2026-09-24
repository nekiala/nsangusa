package com.nsangusa.news.articles;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nsangusa.news.articles.ArticleContent.Block;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ArticleContentTests {
  @Test
  void canonicalPlainTextRetainsEverySafeBlockWithoutHtmlInterpretation() throws Exception {
    var content =
        new ArticleContent(
            1,
            List.of(
                new Block("heading", "  Heading  ", null, null),
                new Block("paragraph", "<em>Literal text</em>", null, null),
                new Block("quote", "Attributed quote", null, null),
                new Block("unordered_list", null, List.of("First", "Second"), null),
                new Block("ordered_list", null, List.of("Third", "Fourth"), null),
                new Block("link", "Evidence", null, "https://example.test/report?a=1&b=2")));
    assertThat(content.plainText())
        .isEqualTo(
            "Heading\n\n<em>Literal text</em>\n\nAttributed quote\n\nFirst\nSecond\n\nThird\nFourth\n\nEvidence (https://example.test/report?a=1&b=2)");
    var jackson2 = new com.fasterxml.jackson.databind.ObjectMapper();
    var jackson3 = tools.jackson.databind.json.JsonMapper.builder().build();
    String json = jackson2.writeValueAsString(content);
    assertThat(jackson2.readValue(json, ArticleContent.class)).isEqualTo(content);
    assertThat(jackson3.readValue(json, ArticleContent.class)).isEqualTo(content);
    assertThat(json).doesNotContain("\"url\":null", "\"items\":null");
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "javascript:alert(1)",
        "data:text/html,unsafe",
        "/relative",
        "//example.test/path",
        "https://user:secret@example.test",
        "https://@example.test",
        "https://example.test/\npath",
        "https://example.test/%0D%0apath",
        "https://example.test\\@evil.test",
        "https://example.test:99999",
        " https://example.test",
        "https://"
      })
  void rejectsUnsafeLinkTargets(String url) {
    assertThatThrownBy(() -> new Block("link", "Evidence", null, url))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "{\"version\":2,\"blocks\":[{\"type\":\"paragraph\",\"text\":\"Text\"}]}",
        "{\"version\":\"1\",\"blocks\":[{\"type\":\"paragraph\",\"text\":\"Text\"}]}",
        "{\"version\":1.2,\"blocks\":[{\"type\":\"paragraph\",\"text\":\"Text\"}]}",
        "{\"blocks\":[{\"type\":\"paragraph\",\"text\":\"Text\"}]}",
        "{\"version\":1,\"blocks\":[]}",
        "{\"version\":1,\"blocks\":[null]}",
        "{\"version\":1,\"html\":\"<b>bad</b>\",\"blocks\":[{\"type\":\"paragraph\",\"text\":\"Text\"}]}",
        "{\"version\":1,\"blocks\":[{\"type\":\"embed\",\"url\":\"https://example.test\"}]}",
        "{\"version\":1,\"blocks\":[{\"type\":\"heading\",\"text\":\"Heading\",\"level\":1}]}",
        "{\"version\":1,\"blocks\":[{\"type\":\"paragraph\",\"text\":\"Text\",\"html\":\"<b>bad</b>\"}]}",
        "{\"version\":1,\"blocks\":[{\"type\":\"paragraph\",\"text\":42}]}",
        "{\"version\":1,\"blocks\":[{\"type\":\"paragraph\",\"text\":\"Text\",\"items\":[\"Unexpected\"]}]}",
        "{\"version\":1,\"blocks\":[{\"type\":\"ordered_list\",\"items\":[42]}]}",
        "{\"version\":1,\"blocks\":[{\"type\":\"unordered_list\",\"items\":[\" \"]}]}",
        "{\"version\":1,\"blocks\":[{\"type\":\"link\",\"text\":\"Text\",\"url\":\"javascript:alert(1)\"}]}"
      })
  void bothJacksonVersionsRejectUnknownOrMalformedContent(String json) {
    assertThatThrownBy(
            () ->
                new com.fasterxml.jackson.databind.ObjectMapper()
                    .readValue(json, ArticleContent.class))
        .isInstanceOf(Exception.class);
    assertThatThrownBy(
            () ->
                tools.jackson.databind.json.JsonMapper.builder()
                    .build()
                    .readValue(json, ArticleContent.class))
        .isInstanceOf(Exception.class);
  }

  @Test
  void acceptsUnusedNullFieldsFromRecordSerializersWithoutInterpretingThem() throws Exception {
    String json =
        """
        {"version":1,"blocks":[
          {"type":"heading","text":"Heading","items":null,"url":null},
          {"type":"ordered_list","text":null,"items":["Item"],"url":null},
          {"type":"link","text":"Evidence","items":null,"url":"https://example.test"}
        ]}
        """;
    var jackson2 =
        new com.fasterxml.jackson.databind.ObjectMapper().readValue(json, ArticleContent.class);
    var jackson3 =
        tools.jackson.databind.json.JsonMapper.builder()
            .build()
            .readValue(json, ArticleContent.class);
    assertThat(jackson2).isEqualTo(jackson3);
    assertThat(jackson2.plainText())
        .isEqualTo("Heading\n\nItem\n\nEvidence (https://example.test)");
  }

  @Test
  void boundsCountsAndAggregateTextAndCopiesLists() {
    var paragraph = new Block("paragraph", "Text", null, null);
    assertThatThrownBy(() -> new ArticleContent(1, Collections.nCopies(201, paragraph)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () -> new Block("ordered_list", null, Collections.nCopies(101, "item"), null))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new ArticleContent(
                    1,
                    List.of(
                        new Block("paragraph", "x".repeat(50000), null, null),
                        new Block("paragraph", "x".repeat(50000), null, null))))
        .isInstanceOf(IllegalArgumentException.class);
    var items = new ArrayList<>(List.of("Original"));
    var blocks = new ArrayList<>(List.of(new Block("ordered_list", null, items, null)));
    var content = new ArticleContent(1, blocks);
    items.set(0, "Changed");
    blocks.clear();
    assertThat(content.plainText()).isEqualTo("Original");
    assertThatThrownBy(() -> content.blocks().clear())
        .isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  void legacyConversionPreservesTextEvenWithMoreThanTwoHundredParagraphs() {
    String body = String.join("\n\n", Collections.nCopies(250, "Legacy paragraph"));
    var content = ArticleContent.fromBody(body);
    assertThat(content.blocks()).hasSize(200);
    assertThat(content.plainText()).isEqualTo(body);
  }
}
