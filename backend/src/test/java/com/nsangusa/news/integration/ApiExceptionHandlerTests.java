package com.nsangusa.news.integration;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

class ApiExceptionHandlerTests {
  private MockMvc mvc;

  @BeforeEach
  void setUp() {
    mvc =
        MockMvcBuilders.standaloneSetup(new ErrorProbeController())
            .setControllerAdvice(new ApiExceptionHandler())
            .build();
  }

  @Test
  void domainInputErrorReturnsProblemDetails() throws Exception {
    mvc.perform(get("/api-test/missing"))
        .andExpect(status().isBadRequest())
        .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.type").value("https://news.example.invalid/problems/400"))
        .andExpect(jsonPath("$.detail").value("Published article not found"))
        .andExpect(jsonPath("$.instance").value("/api-test/missing"));
  }

  @Test
  void staleMutationReturnsStableOptimisticLockConflict() throws Exception {
    mvc.perform(post("/api-test/stale"))
        .andExpect(status().isConflict())
        .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(
            jsonPath("$.detail")
                .value("The resource changed since it was loaded. Refresh and retry."))
        .andExpect(jsonPath("$.type").value("https://news.example.invalid/problems/409"));
  }

  @Test
  void beanValidationErrorsUseThePublicErrorContract() throws Exception {
    mvc.perform(
            post("/api-test/validated")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"value\":\"\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.status").value(400));
  }

  @RestController
  static class ErrorProbeController {
    @GetMapping("/api-test/missing")
    void missing() {
      throw new IllegalArgumentException("Published article not found");
    }

    @PostMapping("/api-test/stale")
    void stale() {
      throw new OptimisticLockingFailureException("stale");
    }

    @PostMapping("/api-test/validated")
    void validated(@Valid @RequestBody ValidatedRequest request) {}
  }

  record ValidatedRequest(@NotBlank String value) {}
}
