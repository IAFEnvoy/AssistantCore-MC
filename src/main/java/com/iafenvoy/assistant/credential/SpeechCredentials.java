package com.iafenvoy.assistant.credential;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import org.jspecify.annotations.NonNull;

public final class SpeechCredentials {
    private SpeechCredentials() {
    }

    public record DeepSeek(String apiKey) implements Credential {
        public static final MapCodec<DeepSeek> CODEC = Codec.STRING.optionalFieldOf("api_key", "")
                .xmap(DeepSeek::new, DeepSeek::apiKey);

        @Override
        public MapCodec<DeepSeek> codec() {
            return CODEC;
        }

        @Override
        public @NonNull String toString() {
            return "DeepSeek";
        }
    }

    public record DashScope(String apiKey, String workspace) implements Credential {
        public static final MapCodec<DashScope> CODEC = RecordCodecBuilder.mapCodec(builder -> builder.group(
                Codec.STRING.optionalFieldOf("api_key", "").forGetter(DashScope::apiKey),
                Codec.STRING.optionalFieldOf("workspace", "").forGetter(DashScope::workspace)
        ).apply(builder, DashScope::new));

        @Override
        public MapCodec<DashScope> codec() {
            return CODEC;
        }

        @Override
        public @NonNull String toString() {
            return "DashScope";
        }
    }

    public record AliyunNls(String appKey, String accessKeyId, String accessKeySecret) implements Credential {
        public static final MapCodec<AliyunNls> CODEC = RecordCodecBuilder.mapCodec(builder -> builder.group(
                Codec.STRING.optionalFieldOf("app_key", "").forGetter(AliyunNls::appKey),
                Codec.STRING.optionalFieldOf("access_key_id", "").forGetter(AliyunNls::accessKeyId),
                Codec.STRING.optionalFieldOf("access_key_secret", "").forGetter(AliyunNls::accessKeySecret)
        ).apply(builder, AliyunNls::new));

        @Override
        public MapCodec<AliyunNls> codec() {
            return CODEC;
        }

        @Override
        public @NonNull String toString() {
            return "AliyunNls";
        }
    }
}
