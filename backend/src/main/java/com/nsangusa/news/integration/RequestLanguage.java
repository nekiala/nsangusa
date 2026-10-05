package com.nsangusa.news.integration;

import java.util.Locale;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/** The reader's site language for the current HTTP request, as sent in {@code Accept-Language}. */
public final class RequestLanguage {
  public static final String FRENCH = "fr";
  public static final String ENGLISH = "en";

  private RequestLanguage() {}

  /**
   * French, the publication's primary language, unless the request asks for English. Work outside a
   * request, which has no reader, is English.
   */
  public static String current() {
    if (!(RequestContextHolder.getRequestAttributes()
        instanceof ServletRequestAttributes request)) {
      return ENGLISH;
    }
    String header = request.getRequest().getHeader("Accept-Language");
    return header != null && header.trim().toLowerCase(Locale.ROOT).startsWith(ENGLISH)
        ? ENGLISH
        : FRENCH;
  }

  /** The site path for a language: English pages live under {@code /en}. */
  public static String path(String language, String path) {
    return ENGLISH.equals(language) ? "/" + ENGLISH + path : path;
  }
}
