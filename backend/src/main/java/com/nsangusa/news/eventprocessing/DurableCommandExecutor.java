package com.nsangusa.news.eventprocessing;

import java.util.UUID;
import java.util.function.Supplier;

/** Commits a successful command, its outbox events and its replay receipt together. */
public interface DurableCommandExecutor {
  /**
   * Replays the original result for the same actor, key, operation and canonical request. The
   * operation must include the target path, and the request must include any expected version.
   *
   * <p>A null key explicitly opts into legacy execution without a receipt. Other keys must contain
   * 8-200 printable, non-whitespace ASCII characters. Keyed operations are limited to 500
   * characters and results to 200 characters. Commands return null for void responses or a stable,
   * non-sensitive identifier for creates. Only a SHA-256 fingerprint of the canonical request is
   * retained. Commands must share the PostgreSQL READ_COMMITTED transaction, not use REQUIRES_NEW
   * or external side effects. Authorization must be checked before calling this method, including
   * on replays.
   */
  String execute(
      UUID actorId, String key, String operation, Object request, Supplier<String> command);
}
