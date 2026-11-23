package com.iafenvoy.assistant.tool;


import com.iafenvoy.assistant.AssistantException;
import com.iafenvoy.assistant.AssistantException.Reason;

import java.util.*;

/**
 * A request-local tool capability: handlers, owners and used results never leak into another loop.
 */
public final class ToolContext<C extends ToolContext.Execution<C>> {
    private final C execution;
    private final Map<String, ToolRegistry.ScopedTool<?, ?, C>> tools;
    private final Set<String> used = new HashSet<>();
    private final String prefix;
    private int calls;

    public ToolContext(C execution, Map<String, ToolRegistry.ScopedTool<?, ?, C>> tools, String prefix) {
        this.execution = execution;
        this.tools = Collections.unmodifiableMap(new LinkedHashMap<>(tools));
        this.prefix = prefix;
    }

    public Collection<ToolRegistry.Tool<?, ?, C>> tools() {
        return this.tools.values().stream().<ToolRegistry.Tool<?, ?, C>>map(ToolRegistry.ScopedTool::tool).toList();
    }

    public <I, O> O call(ToolRegistry.Type<I, O> type, I input) {
        ToolRegistry.ScopedTool<I, O, C> tool = this.require(type);
        this.used.add(type.id());
        return this.execution.scopedTool(tool, this.prefix + "/" + this.calls++, input);
    }

    @SuppressWarnings("unchecked")
    private <I, O> ToolRegistry.ScopedTool<I, O, C> require(ToolRegistry.Type<I, O> type) {
        ToolRegistry.ScopedTool<?, ?, C> tool = this.tools.get(type.id());
        if (tool == null || tool.tool().type() != type) throw new AssistantException(Reason.NOT_AUTHORIZED);
        this.execution.checkToolOwner(tool);
        return (ToolRegistry.ScopedTool<I, O, C>) tool;
    }

    public void check() {
        this.execution.ensureMemoryCurrent();
        for (String id : this.used) this.execution.checkToolOwner(this.tools.get(id));
    }

    public interface Execution<C> {
        <I, O> O scopedTool(ToolRegistry.ScopedTool<I, O, C> scoped, String stepId, I input);

        void checkToolOwner(ToolRegistry.ScopedTool<?, ?, C> scoped);

        void ensureMemoryCurrent();
    }
}
