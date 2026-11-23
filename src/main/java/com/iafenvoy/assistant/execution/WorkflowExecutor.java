package com.iafenvoy.assistant.execution;

import com.iafenvoy.assistant.AgentResult;
import com.iafenvoy.assistant.AssistantException;
import com.iafenvoy.assistant.AssistantException.Reason;
import com.iafenvoy.assistant.action.AgentAction;
import com.iafenvoy.assistant.module.AgentModule;
import com.iafenvoy.assistant.module.ModuleRegistry;
import com.iafenvoy.assistant.registry.ModuleScope;
import com.iafenvoy.assistant.routing.RouteSource;
import net.minecraft.resources.Identifier;

import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Common authority checks for structured, routed and event workflow execution.
 */
public final class WorkflowExecutor<C extends WorkflowExecutor.Context, R> {
    private final ModuleRegistry<C, R> modules;
    private final Function<AgentModule<?, C, R>, ExecutionPolicy> policy;

    public WorkflowExecutor(ModuleRegistry<C, R> modules, Function<AgentModule<?, C, R>, ExecutionPolicy> policy) {
        this.modules = modules;
        this.policy = policy;
    }

    public AgentResult<R> execute(C context, AgentAction input, Predicate<Identifier> scopeAvailable,
                                  ExecutionPolicy policyOverride) {
        ModuleRegistry.Call<?, C, R> call = this.modules.call(input, scopeAvailable);
        AgentModule.Descriptor descriptor = call.module().descriptor();
        if (!context.available(descriptor.id()) || !(context.isEvent() ? descriptor.event() : descriptor.interactive()))
            throw new AssistantException(Reason.CAPABILITY_UNAVAILABLE);
        RouteSource source = context.routeSource();
        if (!context.isEvent() && source.automatic() && !this.modules.modelSelectable(descriptor.id()))
            throw new AssistantException(Reason.CONFIRM_REQUIRED);
        if ((source == RouteSource.SEMANTIC || source == RouteSource.ROUTE_CACHE) && !this.modules.semanticSelectable(descriptor.id()))
            throw new AssistantException(Reason.NOT_AUTHORIZED);
        context.select(call.scope(), descriptor, policyOverride == null ? this.policy.apply(call.module()) : policyOverride);
        context.ensureMemoryCurrent();
        return call.execute(context);
    }

    public interface Context {
        boolean available(Identifier workflow);

        boolean isEvent();

        RouteSource routeSource();

        void select(ModuleScope scope, AgentModule.Descriptor descriptor, ExecutionPolicy policy);

        void ensureMemoryCurrent();
    }
}
