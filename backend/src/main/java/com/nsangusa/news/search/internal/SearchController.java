package com.nsangusa.news.search.internal;

import com.nsangusa.news.search.SearchService;
import com.nsangusa.news.search.SearchService.Facet;
import com.nsangusa.news.search.SearchService.SearchPage;
import java.time.Duration;
import java.util.List;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
class SearchController {
  private final SearchService search;

  SearchController(SearchService search) {
    this.search = search;
  }

  @GetMapping("/search")
  ResponseEntity<SearchPage> search(
      @RequestParam String q,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size) {
    return publicResponse(search.search(q, page, size));
  }

  @GetMapping("/topics")
  ResponseEntity<List<Facet>> topics() {
    return publicResponse(search.topics());
  }

  @GetMapping("/topics/{topic}")
  ResponseEntity<SearchPage> topic(
      @PathVariable String topic,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size) {
    return publicResponse(search.byTopic(topic, page, size));
  }

  @GetMapping("/tags")
  ResponseEntity<List<Facet>> tags() {
    return publicResponse(search.tags());
  }

  @GetMapping("/tags/{tag}")
  ResponseEntity<SearchPage> tag(
      @PathVariable String tag,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size) {
    return publicResponse(search.byTag(tag, page, size));
  }

  private static <T> ResponseEntity<T> publicResponse(T body) {
    return ResponseEntity.ok()
        .cacheControl(CacheControl.maxAge(Duration.ofSeconds(30)).cachePublic())
        .body(body);
  }
}
