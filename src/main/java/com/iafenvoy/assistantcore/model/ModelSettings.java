package com.iafenvoy.assistantcore.model;

import java.net.URI;
import java.util.Objects;
import java.util.Set;

public record ModelSettings(String model, URI chatCompletionsEndpoint, boolean deepSeek, boolean thinkingEnabled,
                            String reasoningEffort, boolean apiKeyRequired, boolean jsonMode, String outputTokenParameter) {
    public ModelSettings {
        if (model.isBlank()) throw new IllegalArgumentException("A model name is required");
        Objects.requireNonNull(reasoningEffort);
        if (!Set.of("max_tokens", "max_completion_tokens").contains(outputTokenParameter))
            throw new IllegalArgumentException("Unsupported output token parameter");
        URI endpoint = chatCompletionsEndpoint;
        String host = endpoint.getHost();
        boolean loopback = "localhost".equalsIgnoreCase(host) || "127.0.0.1".equals(host) || "[::1]".equals(host);
        if (host == null || endpoint.getUserInfo() != null || endpoint.getQuery() != null || endpoint.getFragment() != null
                || !("https".equalsIgnoreCase(endpoint.getScheme()) || "http".equalsIgnoreCase(endpoint.getScheme()) && loopback)
                || endpoint.getPort() < -1 || endpoint.getPort() == 0 || endpoint.getPort() > 65535)
            throw new IllegalArgumentException("A fixed HTTPS model endpoint or loopback HTTP endpoint is required");
    }
}
