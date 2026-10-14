package com.nsangusa.news.integration;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** A complete version of a draft's reader-facing text in another language. */
public record ArticleTranslation(
    @NotBlank @Pattern(regexp = "fr|en") String language,
    @NotBlank @Size(max = 300) String headline,
    @NotBlank @Size(max = 2000) String summary,
    @NotBlank @Size(max = 30_000) String body,
    @Size(max = 5000) String editorialContext,
    @NotBlank @Size(max = 300) String seoTitle,
    @NotBlank @Size(max = 500) String seoDescription,
    @NotBlank @Size(max = 500) String imageAltText) {}
