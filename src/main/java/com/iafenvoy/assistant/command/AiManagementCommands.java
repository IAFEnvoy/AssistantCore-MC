package com.iafenvoy.assistant.command;

import com.iafenvoy.assistant.chat.AiAccess;
import com.iafenvoy.assistant.chat.AiAccessData;
import com.iafenvoy.assistant.chat.ConversationMode;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerPlayer;

import java.util.UUID;
import java.util.function.Function;
import java.util.function.UnaryOperator;

/**
 * Administrator commands persist UUID overrides; the runtime owns invalidation and player feedback.
 */
public final class AiManagementCommands {
    private final Function<CommandSourceStack, ? extends Host> host;

    private AiManagementCommands(Function<CommandSourceStack, ? extends Host> host) {
        this.host = host;
    }

    public static void register(LiteralArgumentBuilder<CommandSourceStack> root, Function<CommandSourceStack, ? extends Host> host) {
        new AiManagementCommands(host).register(root);
    }

    private void register(LiteralArgumentBuilder<CommandSourceStack> root) {
        root.then(Commands.literal("ai").requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                .then(this.defaults())
                .then(Commands.argument("player", EntityArgument.player())
                        .executes(this::status)
                        .then(Commands.literal("status").executes(this::status))
                        .then(this.change("enable", rule -> rule.withAi(true)))
                        .then(this.change("disable", rule -> rule.withAi(false)))
                        .then(Commands.literal("freechat")
                                .then(this.change("enable", rule -> rule.withFreeChat(true)))
                                .then(this.change("disable", rule -> rule.withFreeChat(false))))
                        .then(Commands.literal("limit").then(Commands.argument("calls", IntegerArgumentType.integer(0))
                                .executes(context -> this.change(context, rule -> rule.withModelCallsPerDay(IntegerArgumentType.getInteger(context, "calls"))))))
                        .then(this.change("reset", _ -> AiAccess.Rule.INHERIT))));
    }

    private LiteralArgumentBuilder<CommandSourceStack> defaults() {
        LiteralArgumentBuilder<CommandSourceStack> command = Commands.literal("default").executes(context -> {
            Host runtime = this.host.apply(context.getSource());
            if (runtime == null) return 0;
            context.getSource().sendSuccess(() -> Component.translatable("assistant_core.command.ai.default_status",
                    Component.translatable(runtime.aiAccess().get().defaultMode().labelKey())), false);
            return 1;
        });
        // Literal children supply vanilla completion and reject unknown modes without a separate parser.
        for (ConversationMode mode : ConversationMode.values())
            command.then(Commands.literal(mode.getSerializedName()).executes(context -> {
                Host runtime = this.host.apply(context.getSource());
                if (runtime == null) return 0;
                runtime.aiAccess().update(access -> access.withDefaultMode(mode));
                runtime.refreshAiAccess();
                context.getSource().sendSuccess(() -> Component.translatable("assistant_core.command.ai.default_updated",
                        Component.translatable(mode.labelKey())), true);
                return 1;
            }));
        return command;
    }

    private LiteralArgumentBuilder<CommandSourceStack> change(String name, UnaryOperator<AiAccess.Rule> change) {
        return Commands.literal(name).executes(context -> this.change(context, change));
    }

    private int change(CommandContext<CommandSourceStack> context, UnaryOperator<AiAccess.Rule> change) throws CommandSyntaxException {
        CommandSourceStack source = context.getSource();
        Host runtime = this.host.apply(source);
        if (runtime == null) return 0;
        ServerPlayer player = EntityArgument.getPlayer(context, "player");
        // A command edits only its target field; optional overrides and every personal preference survive.
        runtime.aiAccess().update(access -> access.updatePlayer(player.getUUID(), change));
        runtime.refreshAiAccess();
        source.sendSuccess(() -> status("assistant_core.command.ai.updated", player, runtime), true);
        return 1;
    }

    private int status(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        ServerPlayer player = EntityArgument.getPlayer(context, "player");
        Host runtime = this.host.apply(context.getSource());
        if (runtime == null) return 0;
        context.getSource().sendSuccess(() -> status("assistant_core.command.ai.status", player, runtime), false);
        return 1;
    }

    private static Component status(String key, ServerPlayer player, Host runtime) {
        AiAccess.Permissions permissions = runtime.permissions(player.getUUID());
        MutableComponent message = Component.translatable(key, player.getName(), permission(permissions.allowAi()),
                permission(permissions.allowFreeChat()), permissions.modelCallsPerDay(), Component.translatable(permissions.maxMode().labelKey()));
        if (!permissions.modelConfigured())
            message.append("\n").append(Component.translatable("assistant_core.ai.model_missing_hint"));
        return message;
    }

    public static Component permission(boolean allowed) {
        return Component.translatable(allowed ? "assistant_core.ai.allowed" : "assistant_core.ai.denied");
    }

    public interface Host {
        AiAccessData aiAccess();

        void refreshAiAccess();

        AiAccess.Permissions permissions(UUID player);
    }
}
