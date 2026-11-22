package com.iafenvoy.assistant.util;

import com.google.gson.JsonParser;
import com.mojang.serialization.Codec;
import com.mojang.serialization.JsonOps;

public final class CodecIO {
    private CodecIO() {
    }

    public static <A> A parse(Codec<A> codec, String text) {
        return codec.parse(JsonOps.INSTANCE, JsonParser.parseString(text)).getOrThrow();
    }

    public static <A> String write(Codec<A> codec, A value) {
        return codec.encodeStart(JsonOps.INSTANCE, value).getOrThrow().toString();
    }
}
