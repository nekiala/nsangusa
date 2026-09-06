package com.nsangusa.news.publication.internal;

import com.nsangusa.news.articles.ArticleService;
import com.nsangusa.news.publication.PublicationService;
import java.time.Instant;
import java.util.UUID;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class PublicationApplicationService implements PublicationService {
  private final ScheduledPublicationRepository schedules;
  private final ArticleService articles;

  PublicationApplicationService(ScheduledPublicationRepository schedules, ArticleService articles) {
    this.schedules = schedules;
    this.articles = articles;
  }

  @Override
  @Transactional
  public UUID schedule(UUID articleId, Instant publishAt, UUID editorId) {
    if (!publishAt.isAfter(Instant.now())) {
      throw new IllegalArgumentException("Scheduled publication must be in the future");
    }
    articles.markScheduled(articleId, editorId);
    return schedules.save(new ScheduledPublication(articleId, publishAt)).id;
  }

  @Scheduled(fixedDelayString = "${news.publication.schedule-poll-interval:10000}")
  @Transactional
  void publishDue() {
    for (var schedule :
        schedules.findByStatusAndScheduledForLessThanEqual("scheduled", Instant.now())) {
      articles.publish(schedule.articleId, schedule.id, schedule.articleId, schedule.id);
      schedule.status = "published";
    }
  }
}
