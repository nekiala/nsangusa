package com.nsangusa.news.newsletter.internal;

import jakarta.mail.internet.MimeMessage;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;

interface EmailDeliveryProvider {
  String send(String idempotencyKey, String recipient, String subject, String text, String html);

  default String sendNewsletter(
      String idempotencyKey,
      String recipient,
      String subject,
      String text,
      String html,
      String oneClickUnsubscribeUrl) {
    return send(idempotencyKey, recipient, subject, text, html);
  }

  default boolean supportsIdempotency() {
    return false;
  }
}

@Component
class JavaMailDeliveryProvider implements EmailDeliveryProvider {
  private final JavaMailSender sender;
  private final String fromAddress;
  private final String provider;

  JavaMailDeliveryProvider(
      JavaMailSender sender,
      @Value("${news.newsletter.from-address}") String fromAddress,
      @Value("${news.newsletter.provider:local-smtp}") String provider) {
    this.sender = sender;
    if (fromAddress == null
        || fromAddress.isBlank()
        || fromAddress.contains("\r")
        || fromAddress.contains("\n")) {
      throw new IllegalArgumentException("Newsletter sender address is required");
    }
    if (!"local-smtp".equalsIgnoreCase(provider) && !"resend".equalsIgnoreCase(provider)) {
      throw new IllegalArgumentException("Unsupported newsletter delivery provider");
    }
    this.fromAddress = fromAddress;
    this.provider = provider;
  }

  @Override
  public boolean supportsIdempotency() {
    return "resend".equalsIgnoreCase(provider);
  }

  @Override
  public String send(
      String idempotencyKey, String recipient, String subject, String text, String html) {
    return sendMessage(idempotencyKey, recipient, subject, text, html, null);
  }

  @Override
  public String sendNewsletter(
      String idempotencyKey,
      String recipient,
      String subject,
      String text,
      String html,
      String oneClickUnsubscribeUrl) {
    return sendMessage(idempotencyKey, recipient, subject, text, html, oneClickUnsubscribeUrl);
  }

  private String sendMessage(
      String idempotencyKey,
      String recipient,
      String subject,
      String text,
      String html,
      String oneClickUnsubscribeUrl) {
    if (idempotencyKey == null || !idempotencyKey.matches("[A-Za-z0-9._:/-]{1,256}")) {
      throw new IllegalArgumentException("Invalid email idempotency key");
    }
    try {
      MimeMessage message = sender.createMimeMessage();
      var helper =
          new MimeMessageHelper(message, true, java.nio.charset.StandardCharsets.UTF_8.name());
      helper.setFrom(fromAddress);
      helper.setTo(recipient);
      helper.setSubject(subject);
      helper.setText(text, html);
      if (oneClickUnsubscribeUrl != null) {
        if (!oneClickUnsubscribeUrl.matches("https?://[^\\s<>]+")) {
          throw new IllegalArgumentException("Invalid one-click unsubscribe URL");
        }
        message.setHeader("List-Unsubscribe", "<" + oneClickUnsubscribeUrl + ">");
        message.setHeader("List-Unsubscribe-Post", "List-Unsubscribe=One-Click");
      }
      message.saveChanges();
      message.setHeader(
          "Message-ID",
          "<"
              + java.util.UUID.nameUUIDFromBytes(
                  idempotencyKey.getBytes(java.nio.charset.StandardCharsets.UTF_8))
              + "@nsangusa>");
      if ("resend".equalsIgnoreCase(provider)) {
        message.setHeader("Resend-Idempotency-Key", idempotencyKey);
      }
      sender.send(message);
      return message.getMessageID() == null ? idempotencyKey : message.getMessageID();
    } catch (jakarta.mail.MessagingException exception) {
      throw new IllegalStateException("Unable to construct newsletter message", exception);
    }
  }
}
