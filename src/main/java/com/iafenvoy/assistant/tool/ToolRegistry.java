package com.iafenvoy.assistant.tool;

import com.iafenvoy.assistant.AssistantException;
import com.iafenvoy.assistant.AssistantException.Reason;
import com.iafenvoy.assistant.registry.ModuleScope;
import com.iafenvoy.assistant.registry.ScopedRegistry;
import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import net.minecraft.resources.Identifier;

import java.util.*;
import java.util.concurrent.CompletionStage;
import java.util.function.Predicate;

/**
 * Each tool performs one typed operation; modules register their complete tool lists together.
 */
public final class ToolRegistry<C> {
    private final ScopedRegistry<String, Tool<?, ?, C>> registry = new ScopedRegistry<>(ToolRegistry::sameContract);

    private final Predicate<MapCodec<?>> schemaAvailable;

    public ToolRegistry(Predicate<MapCodec<?>> schemaAvailable) {
        this.schemaAvailable = Objects.requireNonNull(schemaAvailable);
    }

    public void registerAll(List<Tool<?, ?, C>> values) {
        this.registerScoped(ModuleScope.Global.INSTANCE, values);
    }

    /**
     * Exported operations keep their feature owner even when several workflows share the same handler.
     */
    public void registerAll(Identifier owner, List<Tool<?, ?, C>> values) {
        this.registerExported(new ModuleScope.Definition(owner), values);
    }

    public void registerExported(ModuleScope scope, List<Tool<?, ?, C>> values) {
        this.registry.registerExported(scope, this.byId(values));
    }

    public void registerVariant(Identifier owner, List<Tool<?, ?, C>> values) {
        this.registerScoped(ModuleScope.of(owner), values);
    }

    public void registerScoped(ModuleScope scope, List<Tool<?, ?, C>> values) {
        this.registry.register(scope, this.byId(values));
    }

    private Map<String, Tool<?, ?, C>> byId(List<Tool<?, ?, C>> values) {
        Map<String, Tool<?, ?, C>> entries = new LinkedHashMap<>();
        for (Tool<?, ?, C> tool : values) {
            if (!tool.reasoningDescription().isBlank() && !this.schemaAvailable.test(tool.type().inputCodec()))
                throw new IllegalArgumentException("Reasoning tools must provide an input schema");
            Tool<?, ?, C> previous = entries.putIfAbsent(tool.id(), tool);
            if (previous != null && previous != tool)
                throw new IllegalArgumentException("Conflicting tool " + tool.id());
        }
        return entries;
    }

    private static <C> boolean sameContract(Tool<?, ?, C> previous, Tool<?, ?, C> next) {
        return previous.type() == next.type() && previous.write() == next.write()
                && previous.idempotent() == next.idempotent() && previous.allowEvent() == next.allowEvent()
                && previous.priority() == next.priority();
    }

    public boolean available(String id, Predicate<Identifier> available) {
        return this.registry.available(id, available);
    }

    public void freeze() {
        this.registry.freeze();
    }

    public boolean contains(String id) {
        return this.registry.canonical(id) != null;
    }

    public boolean writes(String id) {
        Tool<?, ?, C> tool = this.registry.canonical(id);
        if (tool == null) throw new AssistantException(Reason.CAPABILITY_UNAVAILABLE);
        return tool.write();
    }

    /**
     * Only explicitly advertised read operations enter general reasoning, using an enabled owner's handler.
     */
    public Map<String, ScopedTool<?, ?, C>> reasoning(Predicate<Identifier> available) {
        return this.advertised(available, tool -> !tool.reasoningDescription().isBlank() && tool.priority() != ToolPriority.PAID);
    }

    /**
     * Provider additions only widen the read catalogue; owners, exact type identity and schemas still apply.
     */
    public Map<String, ScopedTool<?, ?, C>> chat(Predicate<Identifier> available, Collection<Type<?, ?>> additions) {
        Set<Type<?, ?>> selected = Collections.newSetFromMap(new IdentityHashMap<>());
        selected.addAll(additions);
        return this.advertised(available, tool -> !tool.write() && this.schemaAvailable.test(tool.type().inputCodec())
                && (!tool.reasoningDescription().isBlank() || selected.contains(tool.type())));
    }

    private Map<String, ScopedTool<?, ?, C>> advertised(Predicate<Identifier> available, Predicate<Tool<?, ?, C>> select) {
        Map<String, ScopedTool<?, ?, C>> result = new LinkedHashMap<>();
        this.registry.advertised(available, select, Comparator.naturalOrder())
                .forEach((id, entry) -> result.put(id, scoped(entry.getKey(), entry.getValue())));
        return Collections.unmodifiableMap(result);
    }

    private static <I, O, C> ScopedTool<I, O, C> scoped(ModuleScope owner, Tool<I, O, C> tool) {
        return new ScopedTool<>(owner, tool);
    }

    public record ScopedTool<I, O, C>(ModuleScope scope, Tool<I, O, C> tool) {
    }

    // Identity checks keep a caller-created key or handler from replacing an authorized operation.
    @SuppressWarnings("unchecked")
    public <I, O> Tool<I, O, C> require(Type<I, O> type) {
        Tool<?, ?, C> tool = this.registry.canonical(type.id());
        if (tool == null) throw new AssistantException(Reason.CAPABILITY_UNAVAILABLE);
        if (tool.type() != type) throw new AssistantException(Reason.NOT_AUTHORIZED);
        return (Tool<I, O, C>) tool;
    }

    public <I, O> Tool<I, O, C> require(Type<I, O> type, ModuleScope scope, Predicate<Identifier> available) {
        return this.scope(type, scope, available).tool();
    }

    public <I, O> ScopedTool<I, O, C> scope(Type<I, O> type, ModuleScope scope, Predicate<Identifier> available) {
        Map.Entry<ModuleScope, Tool<?, ?, C>> entry = this.registry.resolve(type.id(), scope, available);
        if (entry == null) throw new AssistantException(Reason.CAPABILITY_UNAVAILABLE);
        return checked(entry.getKey(), type, entry.getValue());
    }

    /**
     * Member dispatch never falls back to another member or a globally exported handler.
     */
    public <I, O> ScopedTool<I, O, C> member(Type<I, O> type, Identifier owner, Predicate<Identifier> available) {
        ModuleScope scope = new ModuleScope.Definition(owner);
        Tool<?, ?, C> tool = this.registry.get(scope, type.id());
        if (tool == null || !scope.available(available)) throw new AssistantException(Reason.CAPABILITY_UNAVAILABLE);
        return checked(scope, type, tool);
    }

    @SuppressWarnings("unchecked")
    private static <I, O, C> ScopedTool<I, O, C> checked(ModuleScope scope, Type<I, O> type, Tool<?, ?, C> tool) {
        if (tool.type() != type) throw new AssistantException(Reason.NOT_AUTHORIZED);
        return new ScopedTool<>(scope, (Tool<I, O, C>) tool);
    }

    public record Type<I, O>(String id, MapCodec<I> inputCodec, Codec<O> resultCodec) {
        public Type {
            if (id == null || !id.matches("[a-z0-9_.-]+(?:[.:][a-z0-9_./-]+)+"))
                throw new IllegalArgumentException("Invalid tool ID");
            Objects.requireNonNull(inputCodec);
            Objects.requireNonNull(resultCodec);
        }
    }

    public record Tool<I, O, C>(Type<I, O> type, boolean write, boolean idempotent, boolean allowEvent,
                                Handler<I, O, C> handler, String reasoningDescription, ToolPriority priority) {
        public Tool {
            Objects.requireNonNull(type);
            Objects.requireNonNull(handler);
            Objects.requireNonNull(reasoningDescription);
            Objects.requireNonNull(priority);
            if (!reasoningDescription.isBlank() && write)
                throw new IllegalArgumentException("Reasoning tools must be read-only and provide an input schema");
        }

        public Tool(Type<I, O> type, boolean write, boolean idempotent, boolean allowEvent, Handler<I, O, C> handler) {
            this(type, write, idempotent, allowEvent, handler, "", ToolPriority.LOCAL);
        }

        public Tool<I, O, C> forReasoning(String description) {
            return new Tool<>(this.type, this.write, this.idempotent, this.allowEvent, this.handler, description, this.priority);
        }

        public Tool<I, O, C> withPriority(ToolPriority priority) {
            return new Tool<>(this.type, this.write, this.idempotent, this.allowEvent, this.handler, this.reasoningDescription, priority);
        }

        public String id() {
            return this.type.id();
        }
    }

    @FunctionalInterface
    public interface Handler<I, O, C> {
        CompletionStage<O> execute(C context, I input);
    }
}
