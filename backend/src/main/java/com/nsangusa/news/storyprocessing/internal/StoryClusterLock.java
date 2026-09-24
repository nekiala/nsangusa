package com.nsangusa.news.storyprocessing.internal;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class StoryClusterLock {
  private final JdbcClient jdbc;

  StoryClusterLock(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  void lock(String clusterScope) {
    jdbc.sql("select pg_advisory_xact_lock(hashtextextended(:scope, 0))")
        .param("scope", clusterScope)
        .query((result, row) -> Boolean.TRUE)
        .single();
  }
}
