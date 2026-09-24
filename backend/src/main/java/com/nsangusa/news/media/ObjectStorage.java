package com.nsangusa.news.media;

import java.util.Optional;

public interface ObjectStorage {
  void put(String objectKey, byte[] bytes, String contentType);

  Optional<StoredObject> read(String objectKey);

  void delete(String objectKey);

  record StoredObject(byte[] bytes, String contentType) {}
}
