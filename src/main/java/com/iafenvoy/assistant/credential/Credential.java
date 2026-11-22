package com.iafenvoy.assistant.credential;

import com.iafenvoy.assistant.registry.AssistantRegistries;
import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;

import java.util.function.Function;

public interface Credential {
    Codec<Credential> CODEC = AssistantRegistries.CREDENTIAL_TYPE.byNameCodec().dispatch("type", Credential::codec, Function.identity());

    MapCodec<? extends Credential> codec();

    /**
     * Resolve at use time without storing environment secrets in the serialized fields.
     */
    static String resolveEnvironment(String value, String... names) {
        if (!value.isBlank()) return value.strip();
        for (String name : names) {
            String environment = System.getenv(name);
            if (environment != null && !environment.isBlank()) return environment.strip();
        }
        return "";
    }
}
