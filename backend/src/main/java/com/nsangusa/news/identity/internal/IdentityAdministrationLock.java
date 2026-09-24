package com.nsangusa.news.identity.internal;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class IdentityAdministrationLock {
  private final JdbcClient jdbc;

  IdentityAdministrationLock(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  void acquire() {
    jdbc.sql("select id from identity_administration_guard where id = 1 for update")
        .query(Integer.class)
        .single();
  }
}
