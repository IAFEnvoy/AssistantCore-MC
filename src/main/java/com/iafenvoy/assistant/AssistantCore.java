package com.iafenvoy.assistant;

import com.iafenvoy.assistant.registry.AssistantRegistries;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;

@Mod(AssistantCore.MOD_ID)
public final class AssistantCore {
    public static final String MOD_ID = "assistant_core";

    public AssistantCore(IEventBus bus) {
        AssistantRegistries.CREDENTIALS.register(bus);
        AssistantRegistries.REQUESTS.register(bus);
        AssistantRegistries.RESPONSES.register(bus);
    }
}
