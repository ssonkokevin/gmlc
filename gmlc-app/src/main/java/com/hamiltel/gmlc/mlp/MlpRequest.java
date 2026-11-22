package com.hamiltel.gmlc.mlp;

import java.util.List;

/**
 * The part of an MLP request this adapter understands. {@code service} is the
 * request element name ({@code slir}, {@code tlrr}, ...) so an unsupported
 * service can still be answered with its own answer element.
 */
public record MlpRequest(
        String service,
        String answerElement,
        String answerVersion,
        String serviceId,
        List<MlpSubject> subjects,
        Integer accuracyMeters,
        String priority) {

    public record MlpSubject(String type, String value) {

        public boolean msisdn() {
            return "MSISDN".equals(type);
        }
    }
}
