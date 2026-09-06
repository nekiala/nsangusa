package com.nsangusa.news.media;

public interface ObjectStorage {
  void put(String objectKey, byte[] bytes, String contentType);

  void delete(String objectKey);
}
