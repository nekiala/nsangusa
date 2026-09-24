package com.nsangusa.news.contracts;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SupportedV1BaselineTests {
  @Test
  void supportedV1ExamplesAreImmutableRatherThanRegeneratedFromCurrentDtos() throws Exception {
    Map<String, String> checksums =
        Map.of(
            "events.json", "6c7421d05d00986efdd886c3b19cf00c8d997c9c38209196ea8965512d1f423d",
            "http.json", "bc41925ee0e3dfa56db90957c2d80fc2a0e919c7698dca38dd696ebff0b504e6",
            "legacy-image-consumer.schema.json",
                "b9c1b602c4b4ea9e3761c5bec9f9a47ebb57a7a0ce0f8ecbf3973888634f05cd");
    for (var entry : checksums.entrySet()) {
      byte[] bytes =
          Files.readAllBytes(
              ContractSchemas.ROOT.resolve("compatibility/v1").resolve(entry.getKey()));
      assertEquals(
          entry.getValue(),
          HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)),
          "Do not rewrite supported-v1 history. Add a new explicitly versioned baseline instead: "
              + entry.getKey());
    }
  }
}
