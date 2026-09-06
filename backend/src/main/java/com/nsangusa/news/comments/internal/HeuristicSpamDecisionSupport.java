package com.nsangusa.news.comments.internal;

import com.nsangusa.news.comments.SpamDecisionSupport;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

@Component
class HeuristicSpamDecisionSupport implements SpamDecisionSupport {
  private static final Pattern URL = Pattern.compile("https?://|www\\.", Pattern.CASE_INSENSITIVE);
  private static final Pattern REPEATED = Pattern.compile("(.)\\1{7,}");
  private static final List<String> SPAM_PHRASES =
      List.of("buy now", "free money", "guaranteed income", "crypto giveaway", "click here");

  @Override
  public SpamAssessment assess(String body) {
    String normalized = body.toLowerCase(Locale.ROOT);
    List<String> signals = new ArrayList<>();
    double score = 0;
    long links = URL.matcher(body).results().count();
    if (links > 0) {
      score += Math.min(0.45, links * 0.15);
      signals.add("links:" + links);
    }
    if (REPEATED.matcher(body).find()) {
      score += 0.2;
      signals.add("repeated-characters");
    }
    long phrases = SPAM_PHRASES.stream().filter(normalized::contains).count();
    if (phrases > 0) {
      score += Math.min(0.6, phrases * 0.3);
      signals.add("spam-phrases:" + phrases);
    }
    long letters = body.chars().filter(Character::isLetter).count();
    long upper = body.chars().filter(Character::isUpperCase).count();
    if (letters >= 20 && upper * 100 / letters >= 75) {
      score += 0.15;
      signals.add("excessive-uppercase");
    }
    return new SpamAssessment(Math.min(1, score), signals);
  }
}
