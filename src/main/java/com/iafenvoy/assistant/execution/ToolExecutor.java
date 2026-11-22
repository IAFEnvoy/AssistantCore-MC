package com.iafenvoy.assistant.execution;

import com.iafenvoy.assistant.AssistantException;
import com.iafenvoy.assistant.AssistantException.Reason;
import com.iafenvoy.assistant.routing.RouteSource;
import com.iafenvoy.assistant.storage.AgentJournal;
import com.iafenvoy.assistant.storage.AgentStore;
import com.iafenvoy.assistant.storage.State;
import com.iafenvoy.assistant.storage.Step;
import com.iafenvoy.assistant.tool.ToolPriority;
import com.iafenvoy.assistant.tool.ToolRegistry;
import com.iafenvoy.assistant.util.CodecIO;
import com.mojang.serialization.Dynamic;
import com.mojang.serialization.JsonOps;

import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;

/**
 * Executes a resolved tool with shared deadlines, budgets and durable write receipts.
 */
public final class ToolExecutor<P, C extends ToolExecutor.Context, R> {
    private final Function<P, AgentJournal<R>> journal;

    public ToolExecutor(Function<P, AgentJournal<R>> journal) {
        this.journal = journal;
    }

    public <I, O> O execute(C context, ToolRegistry.Tool<I, O, C> tool, String stepId, String operationKey, I input,
                            AgentStore<P> store, ExecutionBudget budget, ExecutionPolicy policy, Runnable persist) throws Exception {
        ToolRegistry.Type<I, O> type = tool.type();
        String id = type.id();
        UUID player = context.player();
        context.ensureMemoryCurrent();
        if (tool.priority() == ToolPriority.PAID) context.requireExternalServices();
        requireAvailable(context, id);
        RouteSource source = context.routeSource();
        if ((source == RouteSource.SEMANTIC || source == RouteSource.ROUTE_CACHE) && tool.write())
            throw new AssistantException(Reason.NOT_AUTHORIZED);
        if (context.isEvent() && !tool.allowEvent()) throw new AssistantException(Reason.NOT_AUTHORIZED);

        if (tool.write()) {
            String fingerprint = id + ":" + CodecIO.encode(store.codec(type.inputCodec().codec()), input);
            Dynamic<?> previous = store.read(player, data -> this.replay(tool, operationKey, fingerprint, data));
            if (previous != null) {
                context.completedWrite(stepId);
                return store.codec(type.resultCodec()).parse(previous).getOrThrow(_ -> new AssistantException(Reason.INVALID_INPUT));
            }
            this.chargeBudget(tool, budget, policy, persist);
            // Record intent before calling a write, so an interrupted non-idempotent call is never repeated.
            store.update(player, data -> {
                AgentJournal<R> storage = this.journal.apply(data);
                storage.assertOperationCount(operationKey);
                Step receipt = new Step();
                receipt.fingerprint = fingerprint;
                storage.putOperation(operationKey, receipt);
                return null;
            });
        } else this.chargeBudget(tool, budget, policy, persist);

        try {
            O result = tool.handler().execute(context, input).toCompletableFuture().get(context.remainingMillis(), TimeUnit.MILLISECONDS);
            if (tool.write()) {
                store.update(player, data -> {
                    this.journal.apply(data).completeOperation(operationKey, new Dynamic<>(JsonOps.INSTANCE,
                            store.codec(type.resultCodec()).encodeStart(JsonOps.INSTANCE, result).getOrThrow()));
                    return null;
                });
                context.completedWrite(stepId);
            } else {
                context.ensureMemoryCurrent();
                requireAvailable(context, id);
            }
            return result;
        } catch (ExecutionException error) {
            if (error.getCause() instanceof RuntimeException cause
                    && (cause instanceof AssistantException || context.knownToolFailure(cause)))
                throw cause;
            throw new AssistantException(tool.write() ? Reason.EFFECT_UNKNOWN : Reason.TOOL_FAILED, error);
        } catch (TimeoutException error) {
            context.cancel();
            throw new AssistantException(tool.write() ? Reason.EFFECT_UNKNOWN : Reason.EXPIRED, error);
        }
    }

    private Dynamic<?> replay(ToolRegistry.Tool<?, ?, C> tool, String key, String fingerprint, P data) {
        Step receipt = this.journal.apply(data).getOperation(key);
        if (receipt == null) return null;
        if (!fingerprint.equals(receipt.fingerprint)) throw new AssistantException(Reason.INVALID_INPUT);
        if (receipt.state == State.DONE) return receipt.result;
        if (!tool.idempotent()) throw new AssistantException(Reason.EFFECT_UNKNOWN);
        return null;
    }

    private void chargeBudget(ToolRegistry.Tool<?, ?, C> tool, ExecutionBudget budget, ExecutionPolicy policy, Runnable persist) {
        if (!budget.tool(policy.steps(), policy.toolCalls(), tool.write(), policy.writeSteps()))
            throw new AssistantException(Reason.BUDGET_EXCEEDED);
        persist.run();
    }

    private static void requireAvailable(Context context, String id) {
        if (!context.registeredToolAvailable(id)) throw new AssistantException(Reason.CAPABILITY_UNAVAILABLE);
    }

    public interface Context {
        UUID player();

        RouteSource routeSource();

        boolean isEvent();

        void ensureMemoryCurrent();

        void requireExternalServices();

        boolean registeredToolAvailable(String id);

        long remainingMillis();

        void cancel();

        void completedWrite(String stepId);

        boolean knownToolFailure(Throwable failure);
    }
}
