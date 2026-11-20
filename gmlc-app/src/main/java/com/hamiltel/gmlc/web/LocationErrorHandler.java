package com.hamiltel.gmlc.web;

import com.hamiltel.gmlc.capability.CapabilityProfileUnavailableException;
import com.hamiltel.gmlc.capability.UnknownTenantException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

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
}
