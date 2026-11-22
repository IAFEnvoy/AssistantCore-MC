package com.iafenvoy.assistant.request;

import com.iafenvoy.assistant.registry.AssistantResourceKeys;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

import java.util.function.Function;

/**
 * Requests carry one registered type from the client through the network to the server handler. The kind travels as
 * the synced registry id of its stream codec, so no codec-level dispatch is involved.
 */
public interface AgentRequest {
    StreamCodec<RegistryFriendlyByteBuf, AgentRequest> STREAM_CODEC = ByteBufCodecs.registry(AssistantResourceKeys.AGENT_REQUEST_TYPE).dispatch(AgentRequest::streamCodec, Function.identity());

    StreamCodec<? super RegistryFriendlyByteBuf, ? extends AgentRequest> streamCodec();

    String text();
}
