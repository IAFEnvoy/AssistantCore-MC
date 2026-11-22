package com.iafenvoy.assistant.command;

import com.iafenvoy.assistant.AssistantException;
import com.iafenvoy.assistant.AssistantException.Reason;
import com.iafenvoy.assistant.action.AgentAction;
import com.iafenvoy.assistant.util.CodecIO;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.Suggestions;
import net.minecraft.resources.Identifier;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.Predicate;

/**
 * One per-engine Brigadier tree serves parsing, usage and completion; sources hold per-request authority.
 */
public final class CommandRegistry {
    private final CommandDispatcher<AssistantCommandSource> dispatcher = new CommandDispatcher<>();
    private final Map<String, Identifier> owners = new LinkedHashMap<>();
    private final String namespace;
    private AgentAction root;

    public CommandRegistry(String namespace) {
        this.namespace = namespace;
    }

    public void validate(List<AgentCommand> commands) {
        Set<String> names = new HashSet<>(this.owners.keySet());
        for (AgentCommand command : commands)
            for (String name : command.names())
                if (name.equals(this.namespace) || !names.add(name))
                    throw new IllegalArgumentException("Duplicate command alias " + name);
    }

    public void register(Identifier id, List<AgentCommand> commands) {
        for (AgentCommand command : commands)
            for (String name : command.names()) {
                LiteralArgumentBuilder<AssistantCommandSource> node = command.node().apply(name);
                Predicate<AssistantCommandSource> requirement = node.getRequirement();
                this.dispatcher.register(node.requires(source -> source.available(id) && requirement.test(source)));
                this.owners.put(name, id);
            }
    }

    public void root(AgentAction input) {
        if (this.root != null) throw new IllegalArgumentException("Duplicate root command");
        this.root = input;
    }

    public List<String> suggestions(Predicate<Identifier> available) {
        AssistantCommandSource source = new AssistantCommandSource("", available);
        return this.dispatcher.getRoot().getChildren().stream().filter(node -> node.canUse(source))
                .map(node -> node.getName() + (node.getChildren().isEmpty() ? "" : " ")).toList();
    }

    public CompletableFuture<Suggestions> suggest(String input, Predicate<Identifier> available) {
        AssistantCommandInput command = AssistantCommandInput.of(input, this.namespace);
        AssistantCommandSource source = new AssistantCommandSource(input, available);
        CommandDispatcher<AssistantCommandSource> filtered = CommandTrees.available(this.dispatcher, source);
        return filtered.getCompletionSuggestions(filtered.parse(command.reader(filtered.getRoot()), source));
    }

    public List<String> usages(Predicate<Identifier> available) {
        return Arrays.stream(this.dispatcher.getAllUsage(this.dispatcher.getRoot(), new AssistantCommandSource("", available), true))
                .map(usage -> "/" + usage).toList();
    }

    public AgentAction parse(String text, Predicate<Identifier> available) {
        AssistantCommandInput command = AssistantCommandInput.of(text.stripTrailing(), this.namespace);
        if (command.explicit() && command.start() == command.text().length()) {
            if (!command.text().strip().equalsIgnoreCase("/" + this.namespace))
                throw new AssistantException(Reason.UNSUPPORTED);
            if (this.root == null) throw new AssistantException(Reason.UNSUPPORTED);
            if (!available.test(this.root.id())) throw new AssistantException(Reason.CAPABILITY_UNAVAILABLE);
            return this.root;
        }
        Identifier owner = this.owners.get(command.name().toLowerCase(Locale.ROOT));
        if (owner == null) {
            if (command.explicit()) throw new AssistantException(Reason.UNSUPPORTED);
            return null;
        }
        if (!available.test(owner)) throw new AssistantException(Reason.CAPABILITY_UNAVAILABLE);
        AssistantCommandSource source = new AssistantCommandSource(command.text(), available);
        try {
            this.dispatcher.execute(command.reader(this.dispatcher.getRoot()), source);
            AgentAction action = source.action();
            if (action == null || !action.id().equals(owner)) throw new AssistantException(Reason.INVALID_INPUT);
            // Native argument parsing still enters the same Codec and workflow checks as network actions.
            CodecIO.write(AgentAction.CODEC, action);
            return action;
        } catch (CommandSyntaxException error) {
            if (!command.explicit()) return null;
            throw new AssistantException(Reason.INVALID_INPUT);
        } catch (RuntimeException error) {
            throw new AssistantException(Reason.INVALID_INPUT);
        }
    }
}
