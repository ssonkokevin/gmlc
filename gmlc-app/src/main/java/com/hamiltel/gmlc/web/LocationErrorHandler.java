package com.hamiltel.gmlc.web;

import com.hamiltel.gmlc.admin.AdminApiDisabledException;
import com.hamiltel.gmlc.admin.AdminClientController;
import com.hamiltel.gmlc.admin.AdminKeyRejectedException;
import com.hamiltel.gmlc.cell.CellNotFoundException;
import com.hamiltel.gmlc.cell.InvalidCellException;
import com.hamiltel.gmlc.capability.CapabilityProfileUnavailableException;
import com.hamiltel.gmlc.capability.UnknownTenantException;
import com.hamiltel.gmlc.consent.ConsentNotFoundException;
import com.hamiltel.gmlc.location.PrivacyNotSatisfiedException;
import com.hamiltel.gmlc.location.RequestNotFoundException;
import com.hamiltel.gmlc.location.RequestRejectedException;
import com.hamiltel.gmlc.tracking.TrackedSubjectNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.LinkedHashMap;
import java.util.Map;

@RestControllerAdvice
public class LocationErrorHandler {

    private static final Logger log = LoggerFactory.getLogger(LocationErrorHandler.class);

    @ExceptionHandler(UnknownTenantException.class)
    public ProblemDetail unknownTenant(UnknownTenantException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.NOT_FOUND, "no capability profile for this tenant");
        problem.setTitle("Unknown tenant");
        problem.setProperty("tenantId", e.tenantId());
        return problem;
    }

    @ExceptionHandler(CapabilityProfileUnavailableException.class)
    public ProblemDetail profileUnavailable(CapabilityProfileUnavailableException e) {
        log.error("capability profile unavailable: {}", e.getMessage());
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.SERVICE_UNAVAILABLE,
                "the capability profile is invalid; the gateway will not serve a claim it cannot honour");
        problem.setTitle("Capability profile unavailable");
        return problem;
    }

    @ExceptionHandler(RequestRejectedException.class)
    public ProblemDetail requestRejected(RequestRejectedException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
        problem.setTitle("Invalid request");
        problem.setProperty("code", e.code());
        return problem;
    }

    @ExceptionHandler(PrivacyNotSatisfiedException.class)
    public ProblemDetail privacyNotSatisfied(PrivacyNotSatisfiedException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN,
                "no consent covers this subject for that purpose");
        problem.setTitle("Privacy not satisfied");
        problem.setProperty("code", "PRIVACY_NOT_SATISFIED");
        problem.setProperty("purpose", e.purpose());
        return problem;
    }

    @ExceptionHandler(RequestNotFoundException.class)
    public ProblemDetail requestNotFound(RequestNotFoundException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND,
                "no such location request");
        problem.setTitle("Not found");
        problem.setProperty("requestId", e.requestId());
        return problem;
    }

    @ExceptionHandler(TrackedSubjectNotFoundException.class)
    public ProblemDetail trackedSubjectNotFound(TrackedSubjectNotFoundException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND,
                "no such tracked subject");
        problem.setTitle("Not found");
        problem.setProperty("code", "TRACKED_SUBJECT_NOT_FOUND");
        problem.setProperty("trackedId", e.trackedId());
        return problem;
    }

    @ExceptionHandler(ConsentNotFoundException.class)
    public ProblemDetail consentNotFound(ConsentNotFoundException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND,
                "no such consent");
        problem.setTitle("Not found");
        problem.setProperty("consentId", e.consentId());
        return problem;
    }

    @ExceptionHandler(CellNotFoundException.class)
    public ProblemDetail cellNotFound(CellNotFoundException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND,
                "no such cell");
        problem.setTitle("Not found");
        problem.setProperty("rat", e.rat());
        problem.setProperty("cellIdentity", e.cellIdentity());
        return problem;
    }

    @ExceptionHandler(InvalidCellException.class)
    public ProblemDetail invalidCell(InvalidCellException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST,
                "one or more cell fields are invalid");
        problem.setTitle("Invalid cell");
        problem.setProperty("fields", e.fields());
        return problem;
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ProblemDetail invalidBody(MethodArgumentNotValidException e) {
        Map<String, String> fields = new LinkedHashMap<>();
        e.getBindingResult().getFieldErrors()
                .forEach(error -> fields.putIfAbsent(error.getField(), error.getDefaultMessage()));
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.BAD_REQUEST, "one or more fields are invalid");
        problem.setTitle("Invalid request");
        problem.setProperty("fields", fields);
        return problem;
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ProblemDetail unreadableBody(HttpMessageNotReadableException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.BAD_REQUEST, "the request body could not be read as JSON");
        problem.setTitle("Invalid request");
        return problem;
    }

    @ExceptionHandler(DuplicateKeyException.class)
    public ProblemDetail duplicate(DuplicateKeyException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.CONFLICT, "a client with that name already exists");
        problem.setTitle("Conflict");
        return problem;
    }

    @ExceptionHandler(AdminClientController.PurposeNotAllowedException.class)
    public ProblemDetail purposeNotAllowed(AdminClientController.PurposeNotAllowedException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.BAD_REQUEST, "one or more purposes are not permitted");
        problem.setTitle("Invalid purposes");
        problem.setProperty("rejected", e.purposes());
        problem.setProperty("allowed", AdminClientController.ALLOWED_PURPOSES.stream().sorted().toList());
        return problem;
    }

    @ExceptionHandler(AdminClientController.InvalidAllowlistRuleException.class)
    public ProblemDetail invalidAllowlistRule(AdminClientController.InvalidAllowlistRuleException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.BAD_REQUEST, "the address allowlist contains an unsupported rule");
        problem.setTitle("Invalid allowlist");
        problem.setProperty("rejected", e.rule());
        return problem;
    }

    @ExceptionHandler(AdminApiDisabledException.class)
    public ProblemDetail adminDisabled(AdminApiDisabledException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.NOT_FOUND, "the admin API is not enabled on this deployment");
        problem.setTitle("Not found");
        return problem;
    }

    @ExceptionHandler(AdminKeyRejectedException.class)
    public ProblemDetail adminRejected(AdminKeyRejectedException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.UNAUTHORIZED, "a valid admin key is required");
        problem.setTitle("Unauthorised");
        return problem;
    }
}
