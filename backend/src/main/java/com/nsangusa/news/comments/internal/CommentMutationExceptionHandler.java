package com.nsangusa.news.comments.internal;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = CommentController.class)
class CommentMutationExceptionHandler {
  @ExceptionHandler(DataIntegrityViolationException.class)
  ProblemDetail conflict() {
    return ProblemDetail.forStatusAndDetail(
        HttpStatus.CONFLICT,
        "The comment resource changed or is no longer available. Refresh and retry.");
  }
}
