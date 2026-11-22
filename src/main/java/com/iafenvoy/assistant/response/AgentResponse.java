package com.iafenvoy.assistant.response;

import com.iafenvoy.assistant.registry.AssistantResourceKeys;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

import java.util.function.Function;

/**
 * Response kinds distinguish replies, notifications and navigation without reserving request IDs. The kind travels as
 * the synced registry id of its stream codec, so no codec-level dispatch is involved.
 */
public interface AgentResponse {
    StreamCodec<RegistryFriendlyByteBuf, AgentResponse> STREAM_CODEC = ByteBufCodecs.registry(AssistantResourceKeys.AGENT_RESPONSE_TYPE).dispatch(AgentResponse::streamCodec, Function.identity());

    StreamCodec<? super RegistryFriendlyByteBuf, ? extends AgentResponse> streamCodec();
}
