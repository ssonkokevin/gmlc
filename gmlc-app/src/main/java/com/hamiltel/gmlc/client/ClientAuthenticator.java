package com.hamiltel.gmlc.client;

import org.springframework.stereotype.Component;

@Component
public class ClientAuthenticator {

    private final ApiClientRepository clients;

    public ClientAuthenticator(ApiClientRepository clients) {
        this.clients = clients;
    }

    public ApiClient authenticate(String rawKey, String remoteAddress) {
        if (rawKey == null || rawKey.isBlank()) {
            throw new ClientAuthenticationException(ClientAuthenticationException.Reason.MISSING_KEY);
        }
        ApiClientRepository.StoredClient stored = clients.findByKeyHash(ApiKeyHasher.hash(rawKey))
                .orElseThrow(() -> new ClientAuthenticationException(
                        ClientAuthenticationException.Reason.UNKNOWN_KEY));
        if (!stored.enabled()) {
            throw new ClientAuthenticationException(ClientAuthenticationException.Reason.DISABLED);
        }
        if (!stored.tenantActive()) {
            throw new ClientAuthenticationException(ClientAuthenticationException.Reason.TENANT_INACTIVE);
        }
        if (!stored.client().addressAllowed(remoteAddress)) {
            throw new ClientAuthenticationException(
                    ClientAuthenticationException.Reason.ADDRESS_NOT_ALLOWED);
        }
        return stored.client();
    }
}
