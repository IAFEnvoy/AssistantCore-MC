package com.iafenvoy.assistant.registry;

import com.iafenvoy.assistant.AssistantCore;
import com.iafenvoy.assistant.action.AgentAction;
import com.iafenvoy.assistant.credential.Credential;
import com.iafenvoy.assistant.credential.SpeechCredentials;
import com.iafenvoy.assistant.request.*;
import com.iafenvoy.assistant.response.AgentResponse;
import com.iafenvoy.assistant.response.CommandSuggestionsResponse;
import com.iafenvoy.assistant.text.TextAnnotationProcessor;
import com.iafenvoy.assistant.text.TextAnnotations;
import com.iafenvoy.assistant.text.TextAnnotations.Annotation;
import com.mojang.serialization.MapCodec;
import net.minecraft.core.Registry;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.NewRegistryEvent;
import net.neoforged.neoforge.registries.RegistryBuilder;

@EventBusSubscriber
public final class AssistantRegistries {
    public static final Registry<MapCodec<? extends AgentAction>> ACTION_TYPE = new RegistryBuilder<>(AssistantResourceKeys.ACTION_TYPE).create();
    public static final Registry<MapCodec<? extends Credential>> CREDENTIAL_TYPE = new RegistryBuilder<>(AssistantResourceKeys.CREDENTIAL_TYPE).create();
    public static final Registry<TextAnnotationProcessor> ANNOTATION_PROCESSOR = new RegistryBuilder<>(AssistantResourceKeys.ANNOTATION_PROCESSOR).create();
    public static final Registry<StreamCodec<? super RegistryFriendlyByteBuf, ? extends Annotation>> ANNOTATION_TYPE = new RegistryBuilder<>(AssistantResourceKeys.ANNOTATION_TYPE).sync(true).create();

    public static final Registry<StreamCodec<? super RegistryFriendlyByteBuf, ? extends AgentRequest>> AGENT_REQUEST_TYPE = new RegistryBuilder<>(AssistantResourceKeys.AGENT_REQUEST_TYPE).sync(true).create();
    public static final Registry<StreamCodec<? super RegistryFriendlyByteBuf, ? extends AgentResponse>> AGENT_RESPONSE_TYPE = new RegistryBuilder<>(AssistantResourceKeys.AGENT_RESPONSE_TYPE).sync(true).create();

    public static final DeferredRegister<StreamCodec<? super RegistryFriendlyByteBuf, ? extends AgentRequest>> REQUESTS = DeferredRegister.create(AssistantResourceKeys.AGENT_REQUEST_TYPE, AssistantCore.MOD_ID);
    public static final DeferredRegister<StreamCodec<? super RegistryFriendlyByteBuf, ? extends AgentResponse>> RESPONSES = DeferredRegister.create(AssistantResourceKeys.AGENT_RESPONSE_TYPE, AssistantCore.MOD_ID);
    public static final DeferredRegister<MapCodec<? extends Credential>> CREDENTIALS = DeferredRegister.create(AssistantResourceKeys.CREDENTIAL_TYPE, AssistantCore.MOD_ID);
    public static final DeferredRegister<StreamCodec<? super RegistryFriendlyByteBuf, ? extends Annotation>> ANNOTATIONS = DeferredRegister.create(AssistantResourceKeys.ANNOTATION_TYPE, AssistantCore.MOD_ID);

    static {
        ANNOTATIONS.register("reference", () -> TextAnnotations.Reference.STREAM_CODEC);
        ANNOTATIONS.register("quantity", () -> TextAnnotations.Quantity.STREAM_CODEC);
        REQUESTS.register("chat", () -> ChatRequest.STREAM_CODEC);
        REQUESTS.register("invoke", () -> InvokeRequest.STREAM_CODEC);
        REQUESTS.register("control", () -> ControlRequest.STREAM_CODEC);
        REQUESTS.register("preferences", () -> PreferencesRequest.STREAM_CODEC);
        REQUESTS.register("command_suggestions", () -> CommandSuggestionsRequest.STREAM_CODEC);
        RESPONSES.register("command_suggestions", () -> CommandSuggestionsResponse.STREAM_CODEC);
        CREDENTIALS.register("deepseek", () -> SpeechCredentials.DeepSeek.CODEC);
        CREDENTIALS.register("dashscope", () -> SpeechCredentials.DashScope.CODEC);
        CREDENTIALS.register("aliyun_nls", () -> SpeechCredentials.AliyunNls.CODEC);
    }

    private AssistantRegistries() {
    }

    public static void bind(IEventBus bus) {
        ANNOTATIONS.register(bus);
        CREDENTIALS.register(bus);
        REQUESTS.register(bus);
        RESPONSES.register(bus);
    }

    @SubscribeEvent
    public static void register(NewRegistryEvent event) {
        event.register(ACTION_TYPE);
        event.register(CREDENTIAL_TYPE);
        event.register(ANNOTATION_PROCESSOR);
        event.register(ANNOTATION_TYPE);
        event.register(AGENT_REQUEST_TYPE);
        event.register(AGENT_RESPONSE_TYPE);
    }
}
