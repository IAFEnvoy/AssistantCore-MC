package com.iafenvoy.assistant.model;

import java.net.URI;
import java.util.Objects;
import java.util.Set;

public record ModelSettings(String model, URI chatCompletionsEndpoint, boolean deepSeek, boolean thinkingEnabled,
                            String reasoningEffort, boolean apiKeyRequired, boolean jsonMode,
                            String outputTokenParameter) {
    public ModelSettings {
        if (model.isBlank()) throw new IllegalArgumentException("A model name is required");
        Objects.requireNonNull(reasoningEffort);
        if (!Set.of("max_tokens", "max_completion_tokens").contains(outputTokenParameter))
            throw new IllegalArgumentException("Unsupported output token parameter");
        String host = chatCompletionsEndpoint.getHost();
        boolean loopback = "localhost".equalsIgnoreCase(host) || "127.0.0.1".equals(host) || "[::1]".equals(host);
        if (host == null || chatCompletionsEndpoint.getUserInfo() != null || chatCompletionsEndpoint.getQuery() != null || chatCompletionsEndpoint.getFragment() != null
                || !("https".equalsIgnoreCase(chatCompletionsEndpoint.getScheme()) || "http".equalsIgnoreCase(chatCompletionsEndpoint.getScheme()) && loopback)
                || chatCompletionsEndpoint.getPort() < -1 || chatCompletionsEndpoint.getPort() == 0 || chatCompletionsEndpoint.getPort() > 65535)
            throw new IllegalArgumentException("A fixed HTTPS model endpoint or loopback HTTP endpoint is required");
    }
}
