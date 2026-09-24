package com.nsangusa.news.storyprocessing.internal;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

final class StoryClustering {
  private static final int MAX_TERMS = 64;
  private static final Set<String> STOP_WORDS =
      Set.of(
          "about", "after", "again", "avec", "dans", "from", "have", "https", "mais", "more",
          "pour", "that", "the", "their", "this", "une", "with");

  private StoryClustering() {}

  static Set<String> terms(String text) {
    return Arrays.stream(text.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}]+"))
        .map(String::trim)
        .filter(token -> token.length() >= 4)
        .filter(token -> !STOP_WORDS.contains(token))
        .limit(MAX_TERMS)
        .collect(Collectors.toCollection(LinkedHashSet::new));
  }

  static double similarity(Set<String> left, Set<String> right) {
    if (left.isEmpty() || right.isEmpty()) {
      return 0;
    }
    long intersection = left.stream().filter(right::contains).count();
    long union = left.size() + right.size() - intersection;
    return union == 0 ? 0 : (double) intersection / union;
  }

  static Set<String> merge(Set<String> left, Set<String> right) {
    return java.util.stream.Stream.concat(left.stream(), right.stream())
        .sorted()
        .limit(MAX_TERMS)
        .collect(Collectors.toCollection(LinkedHashSet::new));
  }

  static String serialize(Set<String> terms) {
    return terms.stream().sorted().limit(MAX_TERMS).collect(Collectors.joining(" "));
  }

  static Set<String> deserialize(String serialized) {
    if (serialized == null || serialized.isBlank()) {
      return Set.of();
    }
    return Arrays.stream(serialized.split(" "))
        .filter(term -> !term.isBlank())
        .collect(Collectors.toCollection(LinkedHashSet::new));
  }
}
