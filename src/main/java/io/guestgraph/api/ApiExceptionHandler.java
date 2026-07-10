package io.guestgraph.api;

import java.net.URI;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Maps domain exceptions to RFC 9457 problem-details responses (Constitution V). Framework-raised
 * errors (validation, unparseable bodies, unknown paths) are emitted as problem details by Spring
 * itself (spring.mvc.problemdetails.enabled); LOWEST_PRECEDENCE keeps this advice — and its
 * catch-all — behind the framework's own handlers.
 */
@RestControllerAdvice
@Order(Ordered.LOWEST_PRECEDENCE)
public class ApiExceptionHandler {

  private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

  private static final String PROBLEM_BASE = "https://guestgraph.io/problems/";

  @ExceptionHandler(NotFoundException.class)
  public ProblemDetail notFound(NotFoundException e) {
    return problem(HttpStatus.NOT_FOUND, "not-found", "Resource not found", e.getMessage());
  }

  @ExceptionHandler(ConflictException.class)
  public ProblemDetail conflict(ConflictException e) {
    return problem(HttpStatus.CONFLICT, "conflict", "Conflict", e.getMessage());
  }

  @ExceptionHandler(BadRequestException.class)
  public ProblemDetail badRequest(BadRequestException e) {
    return problem(HttpStatus.BAD_REQUEST, "invalid-request", "Invalid request", e.getMessage());
  }

  @ExceptionHandler(io.guestgraph.resolution.InvalidUnmergeException.class)
  public ProblemDetail invalidUnmerge(io.guestgraph.resolution.InvalidUnmergeException e) {
    return problem(HttpStatus.BAD_REQUEST, "invalid-unmerge", "Invalid unmerge", e.getMessage());
  }

  @ExceptionHandler(io.guestgraph.resolution.ReviewNotFoundException.class)
  public ProblemDetail reviewNotFound(io.guestgraph.resolution.ReviewNotFoundException e) {
    return problem(HttpStatus.NOT_FOUND, "not-found", "Resource not found", e.getMessage());
  }

  @ExceptionHandler(io.guestgraph.resolution.ReviewAlreadyDecidedException.class)
  public ProblemDetail reviewAlreadyDecided(
      io.guestgraph.resolution.ReviewAlreadyDecidedException e) {
    return problem(HttpStatus.CONFLICT, "review-already-decided", "Conflict", e.getMessage());
  }

  /** FR-019: even unexpected failures answer as problem details — never a default error body. */
  @ExceptionHandler(Exception.class)
  public ProblemDetail internalError(Exception e) {
    log.error("Unhandled exception while serving request", e);
    return problem(
        HttpStatus.INTERNAL_SERVER_ERROR,
        "internal-error",
        "Internal server error",
        "An unexpected error occurred");
  }

  private ProblemDetail problem(HttpStatus status, String typeSlug, String title, String detail) {
    ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
    problem.setType(URI.create(PROBLEM_BASE + typeSlug));
    problem.setTitle(title);
    return problem;
  }
}
