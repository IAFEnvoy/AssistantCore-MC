package com.iafenvoy.assistant.execution;

import com.iafenvoy.assistant.AgentResult;
import com.iafenvoy.assistant.AssistantException;
import com.iafenvoy.assistant.AssistantException.Reason;
import com.iafenvoy.assistant.action.AgentAction;
import com.iafenvoy.assistant.module.AgentModule;
import com.iafenvoy.assistant.module.ModuleRegistry;
import com.iafenvoy.assistant.storage.AgentJournal;
import com.iafenvoy.assistant.storage.AgentStore;
import com.iafenvoy.assistant.storage.EventTask;
import com.iafenvoy.assistant.storage.State;
import net.minecraft.resources.Identifier;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Durable event scheduling and recovery, with saved policies and idempotent step receipts.
 */
public final class EventExecutor<P, C, R> implements AutoCloseable {
    private final Host<P, C, R> engine;
    private final TaskExecutor events;
    private final Set<EventKey> activeEvents = ConcurrentHashMap.newKeySet();
    private final Map<UUID, Set<String>> enabledRules = new ConcurrentHashMap<>();

    public EventExecutor(Host<P, C, R> engine, String name, int workers, int capacity) {
        this.engine = engine;
        this.events = new TaskExecutor(name, workers, capacity);
    }

    public CompletionStage<AgentResult<R>> trigger(UUID player, String key, String ruleId, AgentAction input) {
        Identifier moduleId = input.id();
        // Event keys identify durable workflows; resuming reuses the saved policy, budget and step receipts.
        long incarnation = this.engine.incarnation(player);
        AgentStore<P> store = this.engine.playerStore(player, incarnation);
        if (this.engine.closed()) {
            return CompletableFuture.completedFuture(this.engine.eventFailure(new AssistantException(Reason.CANCELLED)));
        }
        if (!this.engine.available(player, moduleId))
            return CompletableFuture.completedFuture(this.engine.eventFailure(new AssistantException(Reason.CAPABILITY_UNAVAILABLE)));
        EventKey activeKey = new EventKey(player, incarnation, key);
        if (!this.activeEvents.add(activeKey)) {
            return CompletableFuture.completedFuture(this.engine.duplicateEvent());
        }
        return this.events.submit(() -> {
                    try {
                        ModuleRegistry.Call<?, C, R> selected = this.engine.modules().call(input, owner -> this.engine.moduleEnabled(player, owner));
                        AgentModule<?, C, R> module = selected.module();
                        if (!module.descriptor().event())
                            throw new AssistantException(Reason.CAPABILITY_UNAVAILABLE);
                        EventTask<R> task = store.update(player, data -> {
                            this.engine.checkBinding(data);
                            AgentJournal<R> storage = this.engine.journal(data);
                            EventTask<R> t = storage.getTask(key);
                            if (t != null) return t;
                            storage.assertTaskCount();
                            EventTask<R> value = new EventTask<>();
                            value.id = UUID.randomUUID().toString();
                            value.key = key;
                            value.ruleId = ruleId;
                            value.scope = selected.scope();
                            value.moduleVersion = module.descriptor().version();
                            value.call = input;
                            value.policy = this.engine.policy(module);
                            value.created = System.currentTimeMillis();
                            value.expires = value.created + value.policy.ttlMillis();
                            storage.putTask(key, value);
                            return value;
                        });
                        if (!task.pending()) {
                            return task.result;
                        }
                        if (!task.call.id().equals(moduleId) || task.moduleVersion != module.descriptor().version()
                                || !task.scope.equals(selected.scope()))
                            throw new AssistantException(Reason.CAPABILITY_UNAVAILABLE);
                        if (!this.enabledRules.getOrDefault(player, Set.of()).contains(task.ruleId))
                            throw new AssistantException(Reason.NOT_AUTHORIZED);
                        C context = this.engine.eventContext(player, key, task, store, incarnation);
                        store.update(player, data -> {
                            this.engine.journal(data).setTaskState(key, State.RUNNING);
                            return null;
                        });
                        AgentResult<R> completed = this.engine.execute(context, task.call, task.policy);
                        store.update(player, data -> {
                            this.engine.journal(data).completeTask(key, completed);
                            return null;
                        });
                        return completed;
                    } catch (Exception error) {
                        AgentResult<R> failure = this.engine.eventFailure(error);
                        try {
                            failure = store.update(player, data -> {
                                AgentJournal<R> storage = this.engine.journal(data);
                                EventTask<R> task = storage.getTask(key);
                                AgentResult<R> result = this.engine.eventFailure(error);
                                if (task != null && task.pending()) {
                                    boolean partial = storage.hasCompletedOperation(task.id);
                                    if (partial)
                                        result = result.partial();
                                    storage.completeTask(key, result);
                                }
                                return result;
                            });
                        } catch (Exception unavailable) { /* Preserve the original failure when storage cannot record it. */ }
                        return failure;
                    }
                }).handle((result, error) -> error == null ? result : this.engine.eventFailure(error))
                .whenComplete((_, _) -> this.activeEvents.remove(activeKey));
    }

    public void resume(UUID player) {
        long incarnation = this.engine.incarnation(player);
        AgentStore<P> store = this.engine.playerStore(player, incarnation);
        this.engine.maintenance(() -> {
            List<EventTask<R>> pending = store.read(player, data -> this.engine.journal(data).streamTasks().filter(EventTask::pending).toList());
            for (EventTask<R> task : pending) {
                if (!this.engine.current(player, incarnation)) break;
                this.trigger(player, task.key, task.ruleId, task.call);
            }
            return null;
        });
    }

    public void enableRules(UUID player, Set<String> rules) {
        this.enabledRules.put(player, Set.copyOf(rules));
    }

    public void disableRules(UUID player) {
        this.enabledRules.remove(player);
    }

    private record EventKey(UUID player, long incarnation, String key) {
    }

    public interface Host<P, C, R> {
        boolean closed();

        long incarnation(UUID player);

        boolean current(UUID player, long incarnation);

        AgentStore<P> playerStore(UUID player, long incarnation);

        ModuleRegistry<C, R> modules();

        boolean moduleEnabled(UUID player, Identifier owner);

        boolean available(UUID player, Identifier action);

        ExecutionPolicy policy(AgentModule<?, C, R> module);

        void checkBinding(P data);

        AgentJournal<R> journal(P data);

        C eventContext(UUID player, String key, EventTask<R> task, AgentStore<P> store, long incarnation);

        AgentResult<R> execute(C context, AgentAction action, ExecutionPolicy policy);

        AgentResult<R> duplicateEvent();

        AgentResult<R> eventFailure(Throwable error);

        <T> CompletionStage<T> maintenance(Callable<T> work);
    }

    @Override
    public void close() {
        this.events.close();
    }
}
