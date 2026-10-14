package com.nsangusa.news.integration;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

class RequestLanguageTests {
  @AfterEach
  void clear() {
    RequestContextHolder.resetRequestAttributes();
  }

  @Test
  void readersGetFrenchUnlessTheirPageIsEnglishAndWorkWithoutARequestIsEnglish() {
    assertThat(RequestLanguage.current()).isEqualTo("en");

    assertThat(languageFor(null)).isEqualTo("fr");
    assertThat(languageFor("fr")).isEqualTo("fr");
    assertThat(languageFor("de-DE,de;q=0.9")).isEqualTo("fr");
    assertThat(languageFor("en")).isEqualTo("en");
    assertThat(languageFor("EN-gb,en;q=0.8")).isEqualTo("en");
  }

  @Test
  void englishPagesLiveUnderTheEnPrefix() {
    assertThat(RequestLanguage.path("fr", "/verify-email?token=a"))
        .isEqualTo("/verify-email?token=a");
    assertThat(RequestLanguage.path("en", "/verify-email?token=a"))
        .isEqualTo("/en/verify-email?token=a");
  }

  private static String languageFor(String acceptLanguage) {
    var request = new MockHttpServletRequest();
    if (acceptLanguage != null) {
      request.addHeader("Accept-Language", acceptLanguage);
    }
    RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    return RequestLanguage.current();
  }
}
