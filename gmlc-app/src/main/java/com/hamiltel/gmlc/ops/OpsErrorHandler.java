package com.hamiltel.gmlc.ops;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

/** RFC 7807 for the console surface, matching the shape the Le API returns. */
@RestControllerAdvice
public class OpsErrorHandler {

    @ExceptionHandler(OpsAuthenticationException.class)
    public ProblemDetail authentication(OpsAuthenticationException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED,
                "the username, password or code is not valid");
        problem.setTitle("Unauthorised");
        problem.setProperty("code", "BAD_CREDENTIALS");
        return problem;
    }

    @ExceptionHandler(OpsLockedException.class)
    public ResponseEntity<ProblemDetail> locked(OpsLockedException e) {
        long seconds = Math.max(1, Duration.between(Instant.now(), e.lockedUntil()).toSeconds());
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.LOCKED,
                "the account is locked after repeated failed sign-ins");
        problem.setTitle("Locked");
        problem.setProperty("code", "ACCOUNT_LOCKED");
        problem.setProperty("lockedUntil", e.lockedUntil());
        problem.setProperty("retryAfterSeconds", seconds);
        return ResponseEntity.status(HttpStatus.LOCKED)
                .header(HttpHeaders.RETRY_AFTER, Long.toString(seconds))
                .body(problem);
    }

    @ExceptionHandler(OpsForbiddenException.class)
    public ProblemDetail forbidden(OpsForbiddenException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN,
                "this role does not carry the required permission");
        problem.setTitle("Forbidden");
        problem.setProperty("code", "PERMISSION_DENIED");
        problem.setProperty("permission", e.permission().name());
        return problem;
    }

    @ExceptionHandler(OpsBreakGlassRejectedException.class)
    public ProblemDetail breakGlassRejected(OpsBreakGlassRejectedException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN, e.getMessage());
        problem.setTitle("Forbidden");
        problem.setProperty("code", "BREAK_GLASS_CODE_INVALID");
        return problem;
    }

    @ExceptionHandler(OpsNotFoundException.class)
    public ProblemDetail notFound(OpsNotFoundException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND,
                "no such " + e.resource());
        problem.setTitle("Not found");
        return problem;
    }

    @ExceptionHandler(OpsConflictException.class)
    public ProblemDetail conflict(OpsConflictException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
        problem.setTitle("Conflict");
        problem.setProperty("code", e.code());
        return problem;
    }

    @ExceptionHandler(OpsBadRequestException.class)
    public ProblemDetail badRequest(OpsBadRequestException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
        problem.setTitle("Invalid request");
        problem.setProperty("code", e.code());
        return problem;
    }
}
