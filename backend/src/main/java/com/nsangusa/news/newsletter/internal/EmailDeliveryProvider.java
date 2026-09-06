package com.nsangusa.news.newsletter.internal;

import jakarta.mail.internet.MimeMessage;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;

interface EmailDeliveryProvider {
  String send(String recipient, String subject, String text, String html);
}

@Component
class JavaMailDeliveryProvider implements EmailDeliveryProvider {
  private final JavaMailSender sender;
  private final String fromAddress;

  JavaMailDeliveryProvider(
      JavaMailSender sender, @Value("${news.newsletter.from-address}") String fromAddress) {
    this.sender = sender;
    this.fromAddress = fromAddress;
  }

  @Override
  public String send(String recipient, String subject, String text, String html) {
    try {
      MimeMessage message = sender.createMimeMessage();
      var helper =
          new MimeMessageHelper(message, false, java.nio.charset.StandardCharsets.UTF_8.name());
      helper.setFrom(fromAddress);
      helper.setTo(recipient);
      helper.setSubject(subject);
      helper.setText(text, html);
      sender.send(message);
      return UUID.randomUUID().toString();
    } catch (jakarta.mail.MessagingException exception) {
      throw new IllegalStateException("Unable to construct newsletter message", exception);
    }
  }
}
