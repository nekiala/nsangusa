package com.nsangusa.news.contracts;

import static com.nsangusa.news.contracts.ContractSchemas.invalid;
import static com.nsangusa.news.contracts.ContractSchemas.valid;

import java.util.List;
import org.junit.jupiter.api.Test;

class ContractSchemasTests {
  @Test
  void emailFormatValidatesSyntaxWithoutRequiringADelegatedTopLevelDomain() {
    var schema =
        ContractSchemas.compile(
            ContractSchemas.JSON.createObjectNode().put("type", "string").put("format", "email"));
    for (String email :
        List.of(
            "reader@example.test",
            "reader@example.invalid",
            "reader@example.com",
            "reader+tag@sub.example.test",
            "\"quoted user\"@example.test",
            "reader@[192.0.2.1]",
            "reader@[IPv6:2001:db8::1]")) {
      valid(schema, ContractSchemas.JSON.getNodeFactory().textNode(email));
    }
    for (String email :
        List.of(
            "reader",
            "reader@",
            "@example.test",
            "reader@@example.test",
            "reader..name@example.test",
            "reader@bad_host.test",
            "reader@-bad.test",
            "reader@bad-.test",
            "reader@example..test",
            "Reader <reader@example.test>",
            "reader@[999.0.0.1]",
            "reader@[IPv6:not-an-ip]",
            "reader@" + "a".repeat(64) + ".test")) {
      invalid(schema, ContractSchemas.JSON.getNodeFactory().textNode(email));
    }
  }

  @Test
  void otherFormatAssertionsRemainEnabled() {
    for (var example :
        List.of(
            new String[] {"uuid", "not-a-uuid"},
            new String[] {"date-time", "2026-02-30T10:00:00Z"},
            new String[] {"uri", "not a uri"})) {
      var schema =
          ContractSchemas.compile(
              ContractSchemas.JSON
                  .createObjectNode()
                  .put("type", "string")
                  .put("format", example[0]));
      invalid(schema, ContractSchemas.JSON.getNodeFactory().textNode(example[1]));
    }
  }
}
