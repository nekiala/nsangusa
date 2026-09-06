package com.nsangusa.news.publication;

import java.time.Instant;
import java.util.UUID;

public interface PublicationService {
  UUID schedule(UUID articleId, Instant publishAt, UUID editorId);
}
