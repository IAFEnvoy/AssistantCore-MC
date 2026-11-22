package com.iafenvoy.assistant;

import java.util.Objects;

public final class AssistantException extends RuntimeException {
    public enum Reason {
        BUSY, CANCELLED, BUDGET_EXCEEDED, MODEL_NOT_CONFIGURED, MODEL_TIMEOUT, MODEL_UNAVAILABLE, MODEL_INVALID,
        INVALID_INPUT, UNSUPPORTED, CAPABILITY_UNAVAILABLE, NOT_AUTHORIZED, CONFIRM_REQUIRED,
        EXPIRED, EFFECT_UNKNOWN, TOOL_FAILED, MODE_NOT_ALLOWED, RATE_LIMITED, AI_FORBIDDEN, MODEL_DISABLED, FREE_CHAT_DISABLED
    }

    private final Reason reason;

    public AssistantException(Reason reason) {
        this(reason, null);
    }

    public AssistantException(Reason reason, Throwable cause) {
        super(Objects.requireNonNull(reason).name(), cause);
        this.reason = reason;
    }

    public Reason reason() {
        return this.reason;
    }
}
