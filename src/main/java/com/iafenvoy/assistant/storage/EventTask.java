package com.iafenvoy.assistant.storage;

import com.iafenvoy.assistant.AgentResult;
import com.iafenvoy.assistant.action.AgentAction;
import com.iafenvoy.assistant.execution.ExecutionPolicy;
import com.iafenvoy.assistant.registry.ModuleScope;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import java.util.Optional;

public final class EventTask<C> {
    public static <C> Codec<EventTask<C>> codec(Codec<AgentResult<C>> resultCodec) {
        return RecordCodecBuilder.create(i -> i.group(
                Codec.STRING.optionalFieldOf("id").forGetter(v -> Optional.ofNullable(v.id)),
                Codec.STRING.optionalFieldOf("key").forGetter(v -> Optional.ofNullable(v.key)),
                ModuleScope.CODEC.optionalFieldOf("owner", ModuleScope.Global.INSTANCE).forGetter(v -> v.scope),
                Codec.INT.optionalFieldOf("moduleVersion", 0).forGetter(v -> v.moduleVersion),
                AgentAction.CODEC.fieldOf("action").forGetter(v -> v.call),
                Codec.STRING.optionalFieldOf("ruleId", "").forGetter(v -> v.ruleId),
                ExecutionPolicy.CODEC.optionalFieldOf("policy").forGetter(v -> Optional.ofNullable(v.policy)),
                Codec.LONG.optionalFieldOf("created", 0L).forGetter(v -> v.created),
                Codec.LONG.optionalFieldOf("expires", 0L).forGetter(v -> v.expires),
                State.CODEC.optionalFieldOf("state", State.QUEUED).forGetter(v -> v.state),
                Codec.INT.optionalFieldOf("modelCalls", 0).forGetter(v -> v.modelCalls),
                Codec.INT.optionalFieldOf("toolCalls", 0).forGetter(v -> v.toolCalls),
                Codec.INT.optionalFieldOf("workflowSteps", 0).forGetter(v -> v.workflowSteps),
                Codec.INT.optionalFieldOf("writeSteps", 0).forGetter(v -> v.writeSteps),
                Codec.INT.optionalFieldOf("tokens", 0).forGetter(v -> v.tokens),
                resultCodec.optionalFieldOf("result").forGetter(v -> Optional.ofNullable(v.result))
        ).apply(i, EventTask::new));
    }

    public String id, key, ruleId = "";
    public ModuleScope scope = ModuleScope.Global.INSTANCE;
    public int moduleVersion;
    public AgentAction call;
    public ExecutionPolicy policy;
    public long created, expires;
    public State state = State.QUEUED;
    public int modelCalls, toolCalls, workflowSteps, writeSteps, tokens;
    public AgentResult<C> result;

    public EventTask() {
    }

    private EventTask(Optional<String> id, Optional<String> key, ModuleScope scope, int moduleVersion, AgentAction call, String ruleId,
                      Optional<ExecutionPolicy> policy, long created, long expires, State state, int modelCalls,
                      int toolCalls, int workflowSteps, int writeSteps, int tokens, Optional<AgentResult<C>> result) {
        this.id = id.orElse(null);
        this.key = key.orElse(null);
        this.scope = scope;
        this.moduleVersion = moduleVersion;
        this.call = call;
        this.ruleId = ruleId;
        this.policy = policy.orElse(null);
        this.created = created;
        this.expires = expires;
        this.state = state;
        this.modelCalls = modelCalls;
        this.toolCalls = toolCalls;
        this.workflowSteps = workflowSteps;
        this.writeSteps = writeSteps;
        this.tokens = tokens;
        this.result = result.orElse(null);
    }

    public boolean pending() {
        return switch (this.state) {
            case QUEUED, RUNNING, WAITING -> true;
            default -> false;
        };
    }

    public void complete(AgentResult<C> result, boolean waiting) {
        this.state = waiting ? State.WAITING : switch (result.status()) {
            case SUCCEEDED -> State.SUCCEEDED;
            case FAILED -> State.FAILED;
            case PARTIALLY_SUCCEEDED -> State.PARTIALLY_SUCCEEDED;
        };
        this.result = result;
    }

    public EventTask<C> copy() {
        return new EventTask<>(Optional.ofNullable(this.id), Optional.ofNullable(this.key), this.scope, this.moduleVersion, this.call,
                this.ruleId, Optional.ofNullable(this.policy), this.created, this.expires, this.state, this.modelCalls,
                this.toolCalls, this.workflowSteps, this.writeSteps, this.tokens, Optional.ofNullable(this.result));
    }
}
