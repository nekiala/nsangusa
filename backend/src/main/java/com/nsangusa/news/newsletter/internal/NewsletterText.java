package com.nsangusa.news.newsletter.internal;

import com.nsangusa.news.integration.RequestLanguage;

/** Wording of newsletter emails in each supported language. */
record NewsletterText(
    String language,
    String confirmSubject,
    String confirmIntro,
    String confirmAction,
    String confirmIgnore,
    String preferencesSubject,
    String preferencesAction,
    String preferencesNote,
    String dailyDigest,
    String weeklyDigest,
    String readArticle,
    String unsubscribe,
    String managePreferences) {
  private static final NewsletterText ENGLISH =
      new NewsletterText(
          RequestLanguage.ENGLISH,
          "Confirm your newsletter subscription",
          "Confirm your subscription:",
          "Confirm subscription",
          "If you did not request this newsletter, ignore this message.",
          "Manage your newsletter preferences",
          "Manage newsletter preferences",
          "This private link expires at %s (30 minutes after the request). Opening it makes no"
              + " changes. If you did not request it, ignore this email.",
          "Your Nsangusa daily digest",
          "Your Nsangusa weekly digest",
          "Read the article",
          "Unsubscribe",
          "Manage preferences");

  private static final NewsletterText FRENCH =
      new NewsletterText(
          RequestLanguage.FRENCH,
          "Confirmez votre abonnement à l’infolettre",
          "Confirmez votre abonnement :",
          "Confirmer l’abonnement",
          "Si vous n’avez pas demandé cette infolettre, ignorez ce message.",
          "Gérez vos préférences d’infolettre",
          "Gérer les préférences d’infolettre",
          "Ce lien privé expire à %s (30 minutes après la demande). L’ouvrir ne modifie rien. Si"
              + " vous ne l’avez pas demandé, ignorez cet e-mail.",
          "Votre résumé quotidien Nsangusa",
          "Votre résumé hebdomadaire Nsangusa",
          "Lire l’article",
          "Se désabonner",
          "Gérer les préférences");

  static NewsletterText in(String language) {
    return RequestLanguage.FRENCH.equals(language) ? FRENCH : ENGLISH;
  }

  String digest(String type) {
    return "daily".equals(type) ? dailyDigest : weeklyDigest;
  }

  /** A site URL in this language. */
  String url(String publicBaseUrl, String path) {
    return publicBaseUrl + RequestLanguage.path(language, path);
  }
}
