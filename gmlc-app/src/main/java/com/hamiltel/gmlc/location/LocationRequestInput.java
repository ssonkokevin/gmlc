package com.hamiltel.gmlc.location;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Le request body. The subject is named by MSISDN, IMSI or both; which one is
 * required is decided by the service so the error can name the field pair.
 */
public record LocationRequestInput(

        @Pattern(regexp = "[0-9]{7,15}", message = "must be 7-15 E.164 digits")
        String msisdn,

        @Pattern(regexp = "[0-9]{14,15}", message = "must be a 14 or 15 digit IMSI")
        String imsi,

        @NotBlank
        @Pattern(regexp = "[A-Z_]{3,32}", message = "must be an uppercase purpose code")
        String purpose,

        @Min(1) @Max(100_000)
        Integer accuracyMeters,

        @Pattern(regexp = "LOW|NORMAL|HIGH", message = "must be LOW, NORMAL or HIGH")
        String priority,

        @Size(max = 80)
        String idempotencyKey) {

    public String priorityOrDefault() {
        return priority == null ? "NORMAL" : priority;
    }

    public String idempotencyOrHeader(String headerValue) {
        return idempotencyKey != null && !idempotencyKey.isBlank() ? idempotencyKey : headerValue;
    }
}
