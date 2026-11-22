package com.iafenvoy.assistant;

import com.iafenvoy.assistant.action.AgentAction;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.resources.Identifier;
import net.minecraft.util.StringRepresentable;
import org.jetbrains.annotations.NotNull;
import org.jspecify.annotations.NonNull;

import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * An Agent outcome with optional host-defined presentation data.
 * Partial completion is excluded from success-only route caches.
 */
public record AgentResult<C>(Status status, String messageKey, String text, List<C> cards, boolean silent) {
    public static <C> Codec<AgentResult<C>> codec(Codec<C> cardCodec) {
        return RecordCodecBuilder.create(i -> i.group(
                Status.CODEC.fieldOf("status").forGetter(AgentResult<C>::status),
                Codec.STRING.fieldOf("messageKey").forGetter(AgentResult<C>::messageKey),
                Codec.STRING.optionalFieldOf("text", "").forGetter(AgentResult<C>::text),
                cardCodec.listOf().optionalFieldOf("cards", List.of()).forGetter(AgentResult<C>::cards),
                Codec.BOOL.optionalFieldOf("silent", false).forGetter(AgentResult<C>::silent)
        ).apply(i, AgentResult::new));
    }

    public AgentResult {
        Objects.requireNonNull(status);
        Objects.requireNonNull(messageKey);
        Objects.requireNonNull(text);
        cards = List.copyOf(cards);
    }

    public boolean isSuccess() {
        return this.status == Status.SUCCEEDED;
    }

    public boolean isFailure() {
        return !this.isSuccess();
    }

    /**
     * A partial result still has a failure, so it must not populate success-only route caches.
     */
    public AgentResult<C> partial() {
        return new AgentResult<>(Status.PARTIALLY_SUCCEEDED, this.messageKey, this.text, this.cards(), this.silent);
    }

    public AgentResult<C> withPresentation(String key, String text, List<C> cards, boolean silent) {
        return new AgentResult<>(this.status, key, text, cards, silent);
    }

    public enum Status implements StringRepresentable {
        SUCCEEDED, FAILED, PARTIALLY_SUCCEEDED;
        public static final Codec<Status> CODEC = StringRepresentable.fromEnum(Status::values);

        @Override
        public @NonNull String getSerializedName() {
            return this.name().toLowerCase(Locale.ROOT);
        }
    }

    public record Action(@NotNull String labelKey, @NotNull AgentAction call) {
        public static final Codec<Action> CODEC = RecordCodecBuilder.create(i -> i.group(
                Codec.STRING.fieldOf("labelKey").forGetter(Action::labelKey),
                AgentAction.CODEC.fieldOf("call").forGetter(Action::call)
        ).apply(i, Action::new));

        public Identifier moduleId() {
            return this.call.id();
        }
    }
}
