package com.nsangusa.news.contracts;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

class AiProviderConfigurationContractTests {
  private static final String ROOT = "/api/v1/admin/ai-configuration/setup";

  @Test
  void setupWritesAreAdministratorOnlyVersionedAndRequireCsrfAndDurableKeys() throws Exception {
    var document = ContractSchemas.openApi();
    for (String pair :
        new String[] {"put ", "put /credential", "delete /credential", "post /activate"}) {
      String[] parts = pair.split(" ", -1);
      var operation = document.path("paths").path(ROOT + parts[1]).path(parts[0]);
      assertEquals("ADMINISTRATOR", operation.path("x-required-roles").get(0).asText());
      assertTrue(operation.path("parameters").toString().contains("CsrfToken"));
      assertTrue(operation.path("parameters").toString().contains("AiSetupIdempotencyKey"));
      assertTrue(operation.path("responses").has("409"));
    }
    var draft =
        ContractSchemas.JSON.readTree(
            """
        {"expectedVersion":0,"provider":"openai","model":"gpt-5-mini","promptVersion":"editorial-v1",
         "timeoutSeconds":10,"maxOutputTokens":1024,"dailyTokenBudget":500000,"reason":"Reviewed settings"}
        """);
    var schema = ContractSchemas.request(ROOT, "put");
    ContractSchemas.valid(schema, draft);
    var invalid = (ObjectNode) draft.deepCopy();
    invalid.put("baseUrl", "https://127.0.0.1");
    ContractSchemas.invalid(schema, invalid);
    invalid = (ObjectNode) draft.deepCopy();
    invalid.put("timeoutSeconds", 121);
    ContractSchemas.invalid(schema, invalid);
    var activation =
        ContractSchemas.JSON.readTree(
            """
        {"expectedVersion":2,"expectedConfigurationVersion":0,"acknowledgeOutboundDataAndCost":true,"reason":"Rights and costs reviewed"}
        """);
    ContractSchemas.valid(ContractSchemas.request(ROOT + "/activate", "post"), activation);
    ((ObjectNode) activation).remove("acknowledgeOutboundDataAndCost");
    ContractSchemas.invalid(ContractSchemas.request(ROOT + "/activate", "post"), activation);
  }

  @Test
  void credentialsAreWriteOnlyAndNeverPartOfSetupResponses() throws Exception {
    var credential =
        ContractSchemas.JSON.readTree(
            """
        {"expectedVersion":1,"apiKey":"sk-test-only-not-an-external-credential","reason":"Rotate project key"}
        """);
    ContractSchemas.valid(ContractSchemas.request(ROOT + "/credential", "put"), credential);
    assertTrue(
        ContractSchemas.openApi()
            .path("components")
            .path("schemas")
            .path("AiProviderCredentialRequest")
            .path("properties")
            .path("apiKey")
            .path("writeOnly")
            .asBoolean());
    var status =
        (ObjectNode)
            ContractSchemas.JSON.readTree(
                """
        {"version":2,"draft":null,"active":null,"liveActive":false,"liveEnabled":false,
         "masterKeyConfigured":false,"credentialStatus":"NOT_CONFIGURED","canActivate":false,
         "activationBlockers":["Operator must configure AI_CREDENTIAL_MASTER_KEY"],
         "limits":{"timeoutSeconds":20,"maxOutputTokens":4096,"dailyTokenBudget":1000000},
         "endpoint":"https://api.openai.com/v1/responses","imageProvider":"fake (simulated)","sourceProvider":"fake (simulated)"}
        """);
    var schema = ContractSchemas.response(ROOT, "get", 200, "application/json");
    ContractSchemas.valid(schema, status);
    for (String sensitive : new String[] {"apiKey", "credentialCiphertext", "masterKey"}) {
      var leaked = status.deepCopy().put(sensitive, "not-allowed");
      ContractSchemas.invalid(schema, leaked);
    }
  }
}
