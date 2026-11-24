package com.iafenvoy.assistant.registry;

import com.iafenvoy.assistant.AssistantCore;
import com.iafenvoy.assistant.action.AgentAction;
import com.iafenvoy.assistant.credential.Credential;
import com.iafenvoy.assistant.request.AgentRequest;
import com.iafenvoy.assistant.response.AgentResponse;
import com.iafenvoy.assistant.text.TextAnnotationProcessor;
import com.iafenvoy.assistant.text.TextAnnotations.Annotation;
import com.mojang.serialization.MapCodec;
import net.minecraft.core.Registry;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;

public final class AssistantResourceKeys {
    public static final ResourceKey<Registry<MapCodec<? extends AgentAction>>> ACTION_TYPE = registry("action_type");
    public static final ResourceKey<Registry<MapCodec<? extends Credential>>> CREDENTIAL_TYPE = registry("credential_type");
    public static final ResourceKey<Registry<TextAnnotationProcessor>> ANNOTATION_PROCESSOR = registry("annotation_processor");
    public static final ResourceKey<Registry<StreamCodec<? super RegistryFriendlyByteBuf, ? extends Annotation>>> ANNOTATION_TYPE = registry("annotation_type");

    public static final ResourceKey<Registry<StreamCodec<? super RegistryFriendlyByteBuf, ? extends AgentRequest>>> AGENT_REQUEST_TYPE = registry("agent_request_type");
    public static final ResourceKey<Registry<StreamCodec<? super RegistryFriendlyByteBuf, ? extends AgentResponse>>> AGENT_RESPONSE_TYPE = registry("agent_response_type");

    private AssistantResourceKeys() {
    }

    private static <T> ResourceKey<Registry<T>> registry(String path) {
        return ResourceKey.createRegistryKey(Identifier.fromNamespaceAndPath(AssistantCore.MOD_ID, path));
    }
}
