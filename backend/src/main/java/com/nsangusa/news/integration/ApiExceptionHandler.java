package com.nsangusa.news.integration;

import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.util.Map;
import org.slf4j.MDC;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
class ApiExceptionHandler {
  @ExceptionHandler({
    IllegalArgumentException.class,
    MethodArgumentNotValidException.class,
    jakarta.validation.ConstraintViolationException.class
  })
  ProblemDetail badRequest(Exception exception, HttpServletRequest request) {
    return problem(HttpStatus.BAD_REQUEST, exception.getMessage(), request);
  }

  @ExceptionHandler(IllegalStateException.class)
  ProblemDetail conflict(IllegalStateException exception, HttpServletRequest request) {
    return problem(HttpStatus.CONFLICT, exception.getMessage(), request);
  }

  @ExceptionHandler(OptimisticLockingFailureException.class)
  ProblemDetail optimisticLock(
      OptimisticLockingFailureException exception, HttpServletRequest request) {
    return problem(
        HttpStatus.CONFLICT,
        "The resource changed since it was loaded. Refresh and retry.",
        request);
  }

  private ProblemDetail problem(HttpStatus status, String detail, HttpServletRequest request) {
    var problem = ProblemDetail.forStatusAndDetail(status, detail);
    problem.setType(URI.create("https://news.example.invalid/problems/" + status.value()));
    problem.setTitle(status.getReasonPhrase());
    problem.setInstance(URI.create(request.getRequestURI()));
    problem.setProperties(
        Map.of(
            "traceId", java.util.Objects.toString(MDC.get("traceId"), ""),
            "timestamp", java.time.Instant.now().toString()));
    return problem;
  }
}
