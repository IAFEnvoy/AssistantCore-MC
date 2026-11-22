package com.iafenvoy.assistant.network;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import java.util.function.BiConsumer;

/**
 * Main-thread connection generations also invalidate asynchronous replies when a host resets a session.
 */
public final class ConnectionSessions {
    private final Map<UUID, UUID> generations = new HashMap<>();

    public void connected(ServerPlayer player) {
        this.generations.put(player.getUUID(), UUID.randomUUID());
    }

    public void disconnected(ServerPlayer player) {
        this.generations.remove(player.getUUID());
    }

    public UUID id(ServerPlayer player) {
        return this.generations.computeIfAbsent(player.getUUID(), ignored -> UUID.randomUUID());
    }

    public <T> void whenCurrent(ServerPlayer player, UUID generation, CompletionStage<T> work, BiConsumer<T, Throwable> callback) {
        MinecraftServer server = player.level().getServer();
        UUID playerId = player.getUUID();
        work.whenComplete((result, error) -> server.execute(() -> {
            if (server.getPlayerList().getPlayer(playerId) == player && generation.equals(this.generations.get(playerId)))
                callback.accept(result, error);
        }));
    }
}
