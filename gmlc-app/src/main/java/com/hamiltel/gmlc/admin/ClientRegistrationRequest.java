package com.hamiltel.gmlc.admin;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * Registration input. Purposes are uppercase and limited to the operator's
 * permitted set; the controller rejects anything else, including lower case,
 * rather than guessing what the caller meant.
 */
public record ClientRegistrationRequest(

        @NotBlank
        @Pattern(regexp = "[a-z0-9][a-z0-9-]{2,63}", message = "must be 3-64 lower-case letters, digits or hyphens")
        String name,

        @NotEmpty
        @Size(max = 8)
        List<@Pattern(regexp = "[A-Z_]{3,32}", message = "must be an uppercase purpose code") String> purposes,

        @Min(1) @Max(100_000)
        Integer maxAccuracyM,

        @Min(1) @Max(1_000)
        Integer ratePerSecond,

        @Min(1) @Max(1_000_000)
        Integer ratePerDay,

        List<@Pattern(regexp = "[0-9A-Fa-f:.]+(/[0-9]{1,3})?", message = "must be an IPv4/IPv6 address or CIDR")
                String> ipAllowlist) {

    public int maxAccuracyMOrDefault() {
        return maxAccuracyM == null ? 5_000 : maxAccuracyM;
    }

    public int ratePerSecondOrDefault() {
        return ratePerSecond == null ? 5 : ratePerSecond;
    }

    public int ratePerDayOrDefault() {
        return ratePerDay == null ? 1_000 : ratePerDay;
    }
}
