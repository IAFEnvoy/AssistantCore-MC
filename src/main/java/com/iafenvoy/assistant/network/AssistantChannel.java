package com.iafenvoy.assistant.network;

import com.iafenvoy.assistant.request.AgentRequest;
import com.iafenvoy.assistant.response.AgentResponse;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.client.network.event.RegisterClientPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.MainThreadPayloadHandler;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * A host owns its channel IDs and may append one typed attachment to a request.
 */
public final class AssistantChannel<A> {
    private final CustomPacketPayload.Type<Request<A>> requestType;
    private final CustomPacketPayload.Type<Response> responseType;
    private final StreamCodec<RegistryFriendlyByteBuf, Request<A>> requestCodec;
    private final StreamCodec<RegistryFriendlyByteBuf, Response> responseCodec;

    public AssistantChannel(String namespace, StreamCodec<? super RegistryFriendlyByteBuf, A> attachmentCodec) {
        this.requestType = new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath(namespace, "assistant_request_c2s"));
        this.responseType = new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath(namespace, "assistant_response_s2c"));
        this.requestCodec = StreamCodec.composite(
                ByteBufCodecs.stringUtf8(36), Request::id,
                AgentRequest.STREAM_CODEC, Request::request,
                ByteBufCodecs.optional(attachmentCodec), Request<A>::attachment,
                (id, request, attachment) -> new Request<>(this.requestType, id, request, attachment));
        this.responseCodec = AgentResponse.STREAM_CODEC.map(this::response, Response::response);
    }

    public void register(PayloadRegistrar registrar, RequestHandler<A> handler, RequestErrorHandler errors) {
        registrar.playToServer(this.requestType, this.requestCodec, new MainThreadPayloadHandler<>((payload, context) -> {
            if (!(context.player() instanceof ServerPlayer player)) return;
            try {
                UUID.fromString(payload.id());
                handler.handle(player, payload.id(), payload.request(), payload.attachment());
            } catch (RuntimeException error) {
                errors.handle(player, payload.id(), error);
            }
        })).playToClient(this.responseType, this.responseCodec);
    }

    public void registerClient(RegisterClientPayloadHandlersEvent event, Consumer<AgentResponse> handler) {
        event.register(this.responseType, new MainThreadPayloadHandler<>((payload, _) -> handler.accept(payload.response())));
    }

    public Request<A> request(String id, AgentRequest request) {
        return this.request(id, request, Optional.empty());
    }

    public Request<A> request(String id, AgentRequest request, Optional<A> attachment) {
        return new Request<>(this.requestType, id, request, attachment);
    }

    public Response response(AgentResponse response) {
        return new Response(this.responseType, response);
    }

    public record Request<A>(Type<Request<A>> type, String id, AgentRequest request,
                             Optional<A> attachment) implements CustomPacketPayload {
    }

    public record Response(Type<Response> type, AgentResponse response) implements CustomPacketPayload {
    }

    @FunctionalInterface
    public interface RequestHandler<A> {
        void handle(ServerPlayer player, String requestId, AgentRequest request, Optional<A> attachment);
    }

    @FunctionalInterface
    public interface RequestErrorHandler {
        void handle(ServerPlayer player, String requestId, RuntimeException error);
    }
}
