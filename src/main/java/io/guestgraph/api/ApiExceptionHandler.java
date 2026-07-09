package io.guestgraph.api;

import java.net.URI;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Maps domain exceptions to RFC 9457 problem-details responses (Constitution V).
 * Framework-raised errors (validation, unparseable bodies, unknown paths) are
 * emitted as problem details by Spring itself (spring.mvc.problemdetails.enabled).
 */
@RestControllerAdvice
public class ApiExceptionHandler {

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

    private ProblemDetail problem(HttpStatus status, String typeSlug, String title, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setType(URI.create(PROBLEM_BASE + typeSlug));
        problem.setTitle(title);
        return problem;
    }
}
