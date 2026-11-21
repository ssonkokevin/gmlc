package com.hamiltel.gmlc.consent;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

import java.time.Instant;

/**
 * A client attesting that the subscriber agreed to be located for a purpose.
 * The evidence is free-form JSON (call recording reference, SMS keyword,
 * webhook receipt); the gateway stores it verbatim.
 */
public record ConsentGrantRequest(

        @Pattern(regexp = "[0-9]{7,15}", message = "must be 7-15 E.164 digits")
        String msisdn,

        @Pattern(regexp = "[0-9]{14,15}", message = "must be a 14 or 15 digit IMSI")
        String imsi,

        @NotBlank
        @Pattern(regexp = "[A-Z_]{3,32}", message = "must be an uppercase purpose code")
        String purpose,

        @Pattern(regexp = "[A-Za-z0-9_.:-]{2,16}", message = "must be a channel code of 2-16 characters")
        String channel,

        @Future
        Instant expiresAt,

        JsonNode evidence) {
}
