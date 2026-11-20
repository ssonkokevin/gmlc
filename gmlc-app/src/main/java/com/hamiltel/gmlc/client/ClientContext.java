package com.hamiltel.gmlc.client;

import jakarta.servlet.http.HttpServletRequest;

/**
 * The authenticated client is placed on the request by {@link ApiKeyFilter} and
 * read by controllers through {@link CurrentClient}.
 */
public final class ClientContext {

    public static final String ATTRIBUTE = ClientContext.class.getName();

    private ClientContext() {
    }

    public static ApiClient require(HttpServletRequest request) {
        Object client = request.getAttribute(ATTRIBUTE);
        if (client instanceof ApiClient apiClient) {
            return apiClient;
        }
        throw new IllegalStateException(
                "no authenticated client on the request; this endpoint is missing from the filter's protected paths");
    }
}
