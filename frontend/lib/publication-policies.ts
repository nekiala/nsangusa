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

export function publicationPolicies(environment: Environment = process.env) {
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
  const pages: Record<string, PolicyPage> = {
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
