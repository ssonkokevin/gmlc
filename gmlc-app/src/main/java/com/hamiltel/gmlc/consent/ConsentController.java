package com.hamiltel.gmlc.consent;

import com.hamiltel.gmlc.client.ApiClient;
import com.hamiltel.gmlc.client.CurrentClient;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.UUID;

/**
 * Consent is recorded and revoked by the client that relies on it. The
 * response identifies the subject by pseudonym only.
 */
@RestController
@RequestMapping("/v1/consents")
public class ConsentController {

    private final ConsentService consents;

    public ConsentController(ConsentService consents) {
        this.consents = consents;
    }

    @PostMapping
    public ResponseEntity<ConsentView> grant(@CurrentClient ApiClient client,
                                             @Valid @RequestBody ConsentGrantRequest request) {
        ConsentRepository.Consent granted = consents.grant(client, request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .header("Location", "/v1/consents/" + granted.consentId())
                .body(ConsentView.of(granted));
    }

    @GetMapping("/{consentId}")
    public ConsentView get(@CurrentClient ApiClient client,
                           @PathVariable("consentId") UUID consentId) {
        return ConsentView.of(consents.find(client.tenantId(), client.clientId(), consentId));
    }

    @DeleteMapping("/{consentId}")
    public ResponseEntity<Void> revoke(@CurrentClient ApiClient client,
                                       @PathVariable("consentId") UUID consentId) {
        consents.revoke(client.tenantId(), client.clientId(), consentId);
        return ResponseEntity.noContent().build();
    }

    public record ConsentView(UUID consentId, String subjectRef, String purpose, String status,
                              Instant grantedAt, Instant expiresAt) {

        static ConsentView of(ConsentRepository.Consent consent) {
            return new ConsentView(consent.consentId(), consent.subjectRef(), consent.purpose(),
                    consent.status(), consent.grantedAt(), consent.expiresAt());
        }
    }
}
