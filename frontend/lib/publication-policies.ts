import type { Locale } from "@/lib/i18n";

export const policyVersion = "2026-09-v1";
export type PolicyPage = { title: string; sections: { heading: string; paragraphs: string[] }[] };
type Environment = Record<string, string | undefined>;

function contact(value: string | undefined, name: string): string | undefined {
  if (!value?.trim()) return undefined;
  const email = value.trim();
  if (!/^[^\s@<>]+@[^\s@<>]+\.[^\s@<>]+$/.test(email) || /[\r\n]/.test(email)) {
    throw new Error(`${name} must be a valid contact email address`);
  }
  return email;
}

/**
 * The publication's policy pages in the reader's language. Both languages are the same policy
 * version: change them together. Operator-supplied details are shown as supplied.
 */
export function publicationPolicies(environment: Environment = process.env, locale: Locale = "en") {
  const publisher = environment.PUBLICATION_PUBLISHER?.trim();
  const editorialContact = contact(environment.PUBLICATION_CONTACT_EMAIL, "PUBLICATION_CONTACT_EMAIL");
  const privacyContact = contact(environment.PUBLICATION_PRIVACY_EMAIL, "PUBLICATION_PRIVACY_EMAIL");
  const jurisdiction = environment.PUBLICATION_JURISDICTION?.trim();
  const retention = environment.PUBLICATION_RETENTION_SUMMARY?.trim();
  const effectiveDate = environment.PUBLICATION_POLICY_EFFECTIVE_DATE?.trim();
  const approval = environment.PUBLICATION_POLICY_APPROVED_VERSION?.trim();
  if (effectiveDate && (!/^\d{4}-\d{2}-\d{2}$/.test(effectiveDate)
    || Number.isNaN(Date.parse(effectiveDate)) || new Date(effectiveDate).toISOString().slice(0, 10) !== effectiveDate)) {
    throw new Error("PUBLICATION_POLICY_EFFECTIVE_DATE must be a valid YYYY-MM-DD date");
  }
  if (approval && approval !== policyVersion) {
    throw new Error("PUBLICATION_POLICY_APPROVED_VERSION must match the current policy template version");
  }
  const details = { publisher, editorialContact, privacyContact, jurisdiction, retention, effectiveDate };
  if (approval && Object.values(details).some((value) => !value
    || /example\.(?:com|org|net|test|invalid)|replace[- ]me|placeholder/i.test(value))) {
    throw new Error("Approved publication policies require real publisher, contact, jurisdiction, retention and effective-date details");
  }
  const approved = approval === policyVersion;
  const pages: Record<string, PolicyPage> = locale === "fr" ? frenchPages(details) : {
    privacy: {
      title: "Privacy",
      sections: [
        { heading: "Publisher and contact", paragraphs: [
          publisher ? `${publisher} is responsible for this publication.` : "The publisher's legal identity must be supplied before launch.",
          privacyContact ? `Contact ${privacyContact} about your information or a privacy request.` : "The publisher's privacy contact must be supplied before launch.",
          jurisdiction ? `Applicable jurisdiction: ${jurisdiction}.` : "The publisher must confirm the applicable jurisdiction and privacy obligations before launch."
        ] },
        { heading: "Information used by the service", paragraphs: [
          "Accounts use an email address, display name, password hash or linked external sign-in identity, verification status and server-side sessions. We also retain newsletter consent and preferences, delivery records, comments, abuse reports and records of administrative actions.",
          "Essential session and anti-forgery cookies support sign-in and protect actions. Authentication tokens are not stored in browser local storage.",
          "Configured infrastructure, identity and email providers process information needed to operate the service. Public source material may be sent to approved editorial and image providers to prepare reporting. Source material and AI output are subject to editorial review."
        ] },
        { heading: "Your choices", paragraphs: [
          "Your profile provides account details, newsletter preferences, an account-data export, session revocation and account deletion. The account export is not an export of all published contributions or operational records.",
          "Account deletion disables sign-in, anonymizes the profile, disconnects external identities and unsubscribes the linked newsletter. Published contributions and required audit or consent records may remain. Contact the publisher about additional access, correction or deletion requests.",
          "Newsletter subscriptions require confirmation. You can unsubscribe using the link in an email or manage preferences through a time-limited link; an account is not required."
        ] },
        { heading: "Retention", paragraphs: [
          retention || "The publisher must approve and publish its retention periods, exceptions and deletion procedure before launch.",
          "Account deletion is not a promise of immediate erasure from every backup or provider system. Retention, legal holds and restored-backup deletion must follow the publisher's approved policy."
        ] }
      ]
    },
    terms: {
      title: "Terms of use",
      sections: [
        { heading: "Reading and sources", paragraphs: [
          "Reporting distinguishes source claims from independently verified information and editorial context. External source links lead to services with their own terms. An illustration is not evidence that an event occurred.",
          "Contact the publisher for reuse or licensing questions. Source material remains subject to its applicable rights and permissions."
        ] },
        { heading: "Accounts and participation", paragraphs: [
          "Keep your credentials private and revoke unfamiliar sessions from your profile. Report suspected account misuse to the publisher.",
          "Comments may require approval and may be edited, rejected, marked as spam or removed under the moderation policy. Do not submit harassment, unlawful content, private information about others or material you are not entitled to share. Commenting privileges may be suspended."
        ] },
        { heading: "Publisher contact", paragraphs: [
          editorialContact ? `Contact ${editorialContact} about the publication or these terms.` : "The publisher must supply its public contact before launch.",
          jurisdiction ? `Applicable jurisdiction: ${jurisdiction}.` : "Jurisdiction-specific terms require publisher approval before launch."
        ] }
      ]
    },
    editorial: {
      title: "Editorial standards",
      sections: [
        { heading: "Evidence and attribution", paragraphs: [
          "Articles retain source links and distinguish reporting from editorial context. A social post is a reported claim, not independent confirmation. Editors consider uncertainty, conflicting accounts and missing context before publication.",
          "AI tools assist analysis and draft preparation. They do not authorize publication, establish the truth of a claim or replace editorial responsibility. Production publication requires human review."
        ] },
        { heading: "Images", paragraphs: [
          "Generated images are labeled as editorial illustrations rather than documentary photographs. Neutral fallback illustrations are not AI-generated evidence and require explicit editorial selection and approval.",
          "Images must not imply proof of an unverified event. Editors review the illustration and alternative text separately from the article."
        ] },
        { heading: "Questions and accountability", paragraphs: [
          editorialContact ? `Send source concerns, requests for a response or editorial questions to ${editorialContact}.` : "A public editorial contact must be configured before launch."
        ] }
      ]
    },
    corrections: {
      title: "Corrections",
      sections: [
        { heading: "Report an issue", paragraphs: [
          editorialContact ? `Send the article URL, the disputed statement and supporting evidence to ${editorialContact}. Avoid including unnecessary personal information.` : "The publisher must approve and configure its public corrections contact before launch. Do not send personal information to example or demonstration addresses."
        ] },
        { heading: "Correction and withdrawal", paragraphs: [
          "Material corrections withdraw the article during renewed editorial review. Republishing retains the canonical URL and original publication date and displays the correction note and updated date.",
          "Unpublication removes an article from new public website reads, listings, search, feeds and sitemaps and prevents new comments. It cannot retract copies already downloaded or emails already delivered.",
          "Correction or restoration does not automatically send another publication newsletter. The publisher retains editorial history and handles source deletion or legal restrictions under its approved retention policy."
        ] }
      ]
    }
  };
  return { pages, approved, publisher: publisher || "Nsangusa", effectiveDate, version: policyVersion };
}

type Details = { publisher?: string; editorialContact?: string; privacyContact?: string; jurisdiction?: string; retention?: string };

function frenchPages({ publisher, editorialContact, privacyContact, jurisdiction, retention }: Details): Record<string, PolicyPage> {
  return {
    privacy: {
      title: "Confidentialité",
      sections: [
        { heading: "Éditeur et contact", paragraphs: [
          publisher ? `${publisher} est responsable de cette publication.` : "L’identité juridique de l’éditeur doit être fournie avant le lancement.",
          privacyContact ? `Écrivez à ${privacyContact} au sujet de vos informations ou pour toute demande relative à la confidentialité.` : "Le contact de l’éditeur pour la confidentialité doit être fourni avant le lancement.",
          jurisdiction ? `Juridiction applicable : ${jurisdiction}.` : "L’éditeur doit confirmer la juridiction applicable et ses obligations en matière de confidentialité avant le lancement."
        ] },
        { heading: "Informations utilisées par le service", paragraphs: [
          "Les comptes utilisent une adresse e-mail, un nom affiché, une empreinte de mot de passe ou une identité de connexion externe liée, un statut de vérification et des sessions côté serveur. Nous conservons également le consentement et les préférences d’infolettre, les registres d’envoi, les commentaires, les signalements d’abus et les traces des actions administratives.",
          "Des cookies essentiels de session et de protection contre la falsification de requêtes permettent la connexion et protègent les actions. Les jetons d’authentification ne sont pas conservés dans le stockage local du navigateur.",
          "Les prestataires d’infrastructure, d’identité et d’e-mail configurés traitent les informations nécessaires au fonctionnement du service. Des contenus sources publics peuvent être transmis à des prestataires éditoriaux et d’images approuvés pour préparer les articles. Les contenus sources et les productions de l’IA sont soumis à une relecture éditoriale."
        ] },
        { heading: "Vos choix", paragraphs: [
          "Votre profil donne accès aux informations du compte, aux préférences d’infolettre, à un export des données du compte, à la révocation des sessions et à la suppression du compte. L’export du compte n’est pas un export de l’ensemble des contributions publiées ni des enregistrements opérationnels.",
          "La suppression du compte désactive la connexion, anonymise le profil, déconnecte les identités externes et désabonne l’infolettre liée. Les contributions publiées et les enregistrements d’audit ou de consentement requis peuvent être conservés. Contactez l’éditeur pour toute autre demande d’accès, de rectification ou de suppression.",
          "Les abonnements à l’infolettre nécessitent une confirmation. Vous pouvez vous désabonner à l’aide du lien présent dans un e-mail ou gérer vos préférences au moyen d’un lien à durée limitée ; aucun compte n’est requis."
        ] },
        { heading: "Conservation", paragraphs: [
          retention || "L’éditeur doit approuver et publier ses durées de conservation, ses exceptions et sa procédure de suppression avant le lancement.",
          "La suppression d’un compte ne garantit pas un effacement immédiat de toutes les sauvegardes ni des systèmes de tous les prestataires. La conservation, les obligations légales de conservation et la suppression après restauration d’une sauvegarde doivent suivre la politique approuvée par l’éditeur."
        ] }
      ]
    },
    terms: {
      title: "Conditions d’utilisation",
      sections: [
        { heading: "Lecture et sources", paragraphs: [
          "Les articles distinguent les affirmations des sources des informations vérifiées de manière indépendante et du contexte éditorial. Les liens vers des sources externes mènent à des services soumis à leurs propres conditions. Une illustration ne constitue pas la preuve qu’un événement a eu lieu.",
          "Contactez l’éditeur pour toute question de réutilisation ou de licence. Les contenus sources restent soumis aux droits et autorisations qui leur sont applicables."
        ] },
        { heading: "Comptes et participation", paragraphs: [
          "Gardez vos identifiants confidentiels et révoquez depuis votre profil les sessions que vous ne reconnaissez pas. Signalez à l’éditeur tout soupçon d’utilisation abusive d’un compte.",
          "Les commentaires peuvent nécessiter une approbation et peuvent être modifiés, rejetés, marqués comme spam ou supprimés conformément à la politique de modération. Ne soumettez pas de contenu relevant du harcèlement, de contenu illicite, d’informations privées concernant des tiers ni de contenu que vous n’êtes pas autorisé à partager. Le droit de commenter peut être suspendu."
        ] },
        { heading: "Contact de l’éditeur", paragraphs: [
          editorialContact ? `Écrivez à ${editorialContact} au sujet de la publication ou des présentes conditions.` : "L’éditeur doit fournir son contact public avant le lancement.",
          jurisdiction ? `Juridiction applicable : ${jurisdiction}.` : "Les conditions propres à une juridiction nécessitent l’approbation de l’éditeur avant le lancement."
        ] }
      ]
    },
    editorial: {
      title: "Charte éditoriale",
      sections: [
        { heading: "Preuves et attribution", paragraphs: [
          "Les articles conservent les liens vers leurs sources et distinguent les faits rapportés du contexte éditorial. Une publication sur un réseau social est une affirmation rapportée, et non une confirmation indépendante. La rédaction prend en compte l’incertitude, les versions contradictoires et le contexte manquant avant publication.",
          "Des outils d’IA assistent l’analyse et la préparation des brouillons. Ils n’autorisent pas la publication, n’établissent pas la véracité d’une affirmation et ne remplacent pas la responsabilité éditoriale. En production, toute publication exige une relecture humaine."
        ] },
        { heading: "Images", paragraphs: [
          "Les images générées sont présentées comme des illustrations éditoriales et non comme des photographies documentaires. Les illustrations de secours neutres ne sont pas des éléments de preuve générés par IA et exigent une sélection et une approbation éditoriales explicites.",
          "Les images ne doivent pas laisser entendre qu’un événement non vérifié est avéré. La rédaction examine l’illustration et son texte alternatif séparément de l’article."
        ] },
        { heading: "Questions et responsabilité", paragraphs: [
          editorialContact ? `Adressez vos remarques sur les sources, vos demandes de droit de réponse ou vos questions éditoriales à ${editorialContact}.` : "Un contact éditorial public doit être configuré avant le lancement."
        ] }
      ]
    },
    corrections: {
      title: "Rectificatifs",
      sections: [
        { heading: "Signaler un problème", paragraphs: [
          editorialContact ? `Envoyez l’adresse de l’article, le passage contesté et les éléments à l’appui à ${editorialContact}. Évitez d’inclure des informations personnelles inutiles.` : "L’éditeur doit approuver et configurer son contact public pour les rectificatifs avant le lancement. N’envoyez pas d’informations personnelles à des adresses d’exemple ou de démonstration."
        ] },
        { heading: "Rectification et retrait", paragraphs: [
          "Une rectification substantielle retire l’article le temps d’une nouvelle relecture éditoriale. La republication conserve l’URL canonique et la date de publication d’origine, et affiche la note de rectification ainsi que la date de mise à jour.",
          "La dépublication retire un article des nouvelles consultations publiques du site, des listes, de la recherche, des flux et des plans de site, et empêche tout nouveau commentaire. Elle ne peut pas rappeler les copies déjà téléchargées ni les e-mails déjà distribués.",
          "Une rectification ou un rétablissement n’entraîne pas automatiquement un nouvel envoi d’infolettre de publication. L’éditeur conserve l’historique éditorial et traite les suppressions de sources ou les restrictions légales conformément à sa politique de conservation approuvée."
        ] }
      ]
    }
  };
}
