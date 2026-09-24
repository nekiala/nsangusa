package com.nsangusa.news.eventprocessing.internal;

import com.nsangusa.news.eventprocessing.WorkflowHealthService;
import java.time.Instant;
import java.util.LinkedHashMap;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class WorkflowHealthQuery implements WorkflowHealthService {
  private final JdbcClient jdbc;

  WorkflowHealthQuery(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  @Transactional(readOnly = true)
  public WorkflowHealth snapshot() {
    var outbox =
        jdbc.sql(
                "select count(*) as pending, min(created_at) as oldest from outbox_events where published_at is null")
            .query(
                (rs, row) ->
                    new PendingOutbox(
                        rs.getLong("pending"),
                        rs.getTimestamp("oldest") == null
                            ? null
                            : rs.getTimestamp("oldest").toInstant()))
            .single();
    var failed = new LinkedHashMap<String, Long>();
    jdbc.sql("select status, count(*) as total from failed_events group by status order by status")
        .query((rs, row) -> java.util.Map.entry(rs.getString("status"), rs.getLong("total")))
        .list()
        .forEach(entry -> failed.put(entry.getKey(), entry.getValue()));
    long replays =
        jdbc.sql(
                "select count(*) from event_replay_requests where status in ('pending','processing')")
            .query(Long.class)
            .single();
    return new WorkflowHealth(Instant.now(), outbox.count(), outbox.oldest(), failed, replays);
  }

  private record PendingOutbox(long count, Instant oldest) {}
}
