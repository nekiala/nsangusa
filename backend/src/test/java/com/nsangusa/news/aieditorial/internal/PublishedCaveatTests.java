package com.nsangusa.news.aieditorial.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.nsangusa.news.integration.ArticleTranslation;
import com.nsangusa.news.integration.NewsEvents.ArticleDraftGenerated;
import com.nsangusa.news.integration.NewsEvents.Claim;
import com.nsangusa.news.integration.NewsEvents.SourceReference;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PublishedCaveatTests {
  @Test
  void attributedReportingCarriesNoWarning() {
    assertThat(
            EditorialWorkflowConsumer.publishedCaveatWarning(
                draft("La banque centrale a annoncé des taux indicatifs, selon son compte X.")))
        .isEmpty();
  }

  @Test
  void aVerificationStatementInReaderFacingTextIsFlaggedForTheEditorInEitherLanguage() {
    for (String body :
        List.of(
            "Ces informations n’ont pas été vérifiées de manière indépendante.",
            "Ils ne constituent pas une confirmation indépendante du décès.",
            "Une vérification supplémentaire et une revue humaine sont nécessaires.",
            "These details have not been independently verified.",
            "The claim is unconfirmed and awaits human review.")) {
      assertThat(EditorialWorkflowConsumer.publishedCaveatWarning(draft(body)))
          .as(body)
          .contains(EditorialWorkflowConsumer.CAVEAT_IN_TEXT);
    }
    var translated =
        draft("La banque centrale a annoncé des taux indicatifs.")
            .withLanguages(
                "fr",
                List.of(
                    new ArticleTranslation(
                        "en",
                        "Headline",
                        "Summary",
                        "Not independently confirmed.",
                        null,
                        "Title",
                        "Description",
                        "Alt")));
    assertThat(EditorialWorkflowConsumer.publishedCaveatWarning(translated)).isPresent();
  }

  private static ArticleDraftGenerated draft(String body) {
    UUID source = UUID.randomUUID();
    return new ArticleDraftGenerated(
        UUID.randomUUID(),
        "Titre",
        "Résumé",
        body,
        null,
        "Titre",
        "Description",
        "titre",
        Set.of("économie"),
        "économie",
        List.of(
            new SourceReference(
                source, "BCC_RDC", "1", "https://x.com/BCC_RDC/status/1", Instant.now())),
        List.of(new Claim("Une affirmation", "REPORTED", List.of(source))),
        new BigDecimal("0.8"),
        List.of(),
        List.of(),
        true,
        "Une illustration",
        "Illustration",
        "Aperçu",
        "openai",
        "gpt-5-mini",
        "editorial-v1",
        1,
        1,
        Instant.now());
  }
}
