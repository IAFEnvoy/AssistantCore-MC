package com.iafenvoy.assistant.response;

import com.iafenvoy.assistant.AgentResult;
import com.mojang.serialization.Codec;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Binds replies and unsolicited notices to one host's result codec.
 */
public final class ResultResponses<C> {
    private final StreamCodec<RegistryFriendlyByteBuf, Reply<C>> replyCodec;
    private final StreamCodec<RegistryFriendlyByteBuf, Notice<C>> noticeCodec;

    public ResultResponses(Codec<AgentResult<C>> codec) {
        StreamCodec<RegistryFriendlyByteBuf, AgentResult<C>> resultCodec = ByteBufCodecs.fromCodecWithRegistriesTrusted(codec);
        this.replyCodec = StreamCodec.composite(
                ByteBufCodecs.stringUtf8(36), Reply::requestId,
                resultCodec, Reply<C>::result,
                this::reply);
        this.noticeCodec = resultCodec.map(this::notice, Notice<C>::result);
    }

    public StreamCodec<RegistryFriendlyByteBuf, Reply<C>> replyCodec() {
        return this.replyCodec;
    }

    public StreamCodec<RegistryFriendlyByteBuf, Notice<C>> noticeCodec() {
        return this.noticeCodec;
    }

    public Reply<C> reply(String requestId, AgentResult<C> result) {
        return new Reply<>(this, requestId, result);
    }

    public Notice<C> notice(AgentResult<C> result) {
        return new Notice<>(this, result);
    }

    public boolean dispatch(AgentResponse response, BiConsumer<String, AgentResult<C>> reply, Consumer<AgentResult<C>> notice) {
        if (response instanceof Reply<?>(
                ResultResponses<?> protocol1, String requestId, AgentResult<?> result1
        ) && protocol1 == this) {
            reply.accept(requestId, this.result(result1));
            return true;
        }
        if (response instanceof Notice<?>(ResultResponses<?> protocol, AgentResult<?> result) && protocol == this) {
            notice.accept(this.result(result));
            return true;
        }
        return false;
    }

    // Each typed message constructor takes its result and protocol with the same type parameter.
    @SuppressWarnings("unchecked")
    private AgentResult<C> result(AgentResult<?> result) {
        return (AgentResult<C>) result;
    }

    public record Reply<C>(ResultResponses<C> protocol, String requestId,
                           AgentResult<C> result) implements AgentResponse {
        @Override
        public StreamCodec<RegistryFriendlyByteBuf, Reply<C>> streamCodec() {
            return this.protocol.replyCodec;
        }
    }

    public record Notice<C>(ResultResponses<C> protocol, AgentResult<C> result) implements AgentResponse {
        @Override
        public StreamCodec<RegistryFriendlyByteBuf, Notice<C>> streamCodec() {
            return this.protocol.noticeCodec;
        }
    }
}
