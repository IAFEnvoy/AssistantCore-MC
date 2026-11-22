package com.iafenvoy.assistant.action;

import com.iafenvoy.assistant.registry.AssistantRegistries;
import com.iafenvoy.assistant.util.CodecIO;
import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import net.minecraft.resources.Identifier;

import java.util.Objects;
import java.util.function.Function;

/**
 * An action is its typed input; the registered MapCodec supplies its identity and dispatch format.
 */
public interface AgentAction {
    Codec<AgentAction> CODEC = AssistantRegistries.ACTION_TYPE.byNameCodec().dispatch("moduleId", AgentAction::codec, Function.identity());

    MapCodec<? extends AgentAction> codec();

    default Identifier id() {
        return Objects.requireNonNull(AssistantRegistries.ACTION_TYPE.getKey(this.codec()), "Unregistered Agent action codec");
    }

    /**
     * Java parsers obey the same type identity and field constraints as decoded network inputs.
     */
    static <I extends AgentAction> I validate(MapCodec<I> codec, I input) {
        if (input.codec() != codec) throw new IllegalArgumentException("Incorrect Agent action codec");
        CodecIO.write(CODEC, input);
        return input;
    }
}
