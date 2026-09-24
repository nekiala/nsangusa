package com.nsangusa.news.aieditorial.internal;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Redacts rejected credential values before generic validation handlers can expose them. */
@RestControllerAdvice(assignableTypes = AiProviderSetupController.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
class AiProviderSetupErrorHandler {
  @ExceptionHandler({MethodArgumentNotValidException.class, HttpMessageNotReadableException.class})
  ProblemDetail invalidPayload(Exception ignored) {
    return ProblemDetail.forStatusAndDetail(
        HttpStatus.BAD_REQUEST,
        "Invalid AI provider setup payload; check the documented fields and bounds. Credentials are never returned.");
  }
}
