package com.iafenvoy.assistant.react;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.iafenvoy.assistant.AssistantException;
import com.iafenvoy.assistant.AssistantException.Reason;
import com.iafenvoy.assistant.model.ModelClient;
import com.mojang.logging.LogUtils;
import com.mojang.serialization.Codec;
import com.mojang.serialization.JsonOps;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import org.slf4j.Logger;

import java.util.*;
import java.util.function.Function;

/**
 * One loop owns one prompt, allowlist and evidence trace; ExecutionContext remains the shared hard budget.
 */
public final class ReActContext<O> {
    private static final Logger LOGGER = LogUtils.getLogger();
    private final Execution execution;
    private final String instruction;
    private final String question;
    private final Map<String, ReActTool<?, O>> tools;
    private final MapCodec<Input<O>> inputCodec;
    private final Runnable guard;
    private final long toolReserveMillis;
    private final int maxIterations;
    private final List<O> observations = new ArrayList<>();
    private final Set<String> attempted = new HashSet<>();
    private int iterations;

    public ReActContext(Execution execution, String instruction, String question, Codec<O> observationCodec,
                        List<ReActTool<?, O>> tools, Runnable guard, long toolReserveMillis) {
        this.execution = Objects.requireNonNull(execution);
        this.question = Objects.requireNonNull(question);
        this.inputCodec = Input.codec(observationCodec);
        this.guard = Objects.requireNonNull(guard);
        this.toolReserveMillis = toolReserveMillis;
        this.maxIterations = execution.reactIterations();
        Map<String, ReActTool<?, O>> bindings = new LinkedHashMap<>();
        for (ReActTool<?, O> tool : tools.stream().sorted(Comparator
                .comparing((ReActTool<?, O> value) -> value.priority()).thenComparing(ReActTool::id)).toList()) {
            if (bindings.putIfAbsent(tool.id(), tool) != null)
                throw new IllegalArgumentException("Duplicate ReAct tool " + tool.id());
        }
        this.tools = Collections.unmodifiableMap(bindings);
        this.instruction = Objects.requireNonNull(instruction);
    }

    public List<O> observations() {
        return List.copyOf(this.observations);
    }

    public int iterations() {
        return this.iterations;
    }

    Collection<ReActTool<?, O>> tools() {
        return this.tools.values();
    }

    void check() {
        this.execution.ensureMemoryCurrent();
        this.guard.run();
    }

    boolean canContinue() {
        return this.iterations < this.maxIterations && this.execution.remainingModelCalls() > 0;
    }

    boolean shouldFinish() {
        return this.maxIterations - this.iterations <= 1 || this.execution.remainingModelCalls() <= 1
                || this.execution.remainingToolCalls() == 0 || this.execution.remainingSteps() <= 2 || this.tools.isEmpty()
                || !this.observations.isEmpty() && this.shortOnTime();
    }

    boolean shortOnTime() {
        return this.execution.remainingMillis() <= this.execution.modelTimeoutMillis() + this.toolReserveMillis;
    }

    <S> S decide(Codec<S> codec, JsonObject answerSchema, boolean finalOnly) {
        // Native function names cannot contain ':' or '.'. Aliases are unique within this request.
        Map<String, ReActTool<?, O>> aliases = new LinkedHashMap<>();
        List<ModelClient.Function> functions = new ArrayList<>();
        if (!finalOnly) for (ReActTool<?, O> tool : this.tools.values()) {
            String alias = "tool_" + aliases.size();
            aliases.put(alias, tool);
            functions.add(tool.function(alias));
        }
        functions.add(new ModelClient.Function("answer", "Finish with the final answer from available evidence. Does not perform a game action.", answerSchema));
        int remaining = this.maxIterations - this.iterations;
        this.iterations++;
        try {
            return this.execution.model(this.instruction, this.inputCodec,
                    new Input<>(this.question, this.observations(), remaining,
                            this.execution.remainingToolCalls(), finalOnly), functions, reply -> {
                        if (reply.toolCalls().size() != 1)
                            throw new IllegalArgumentException("Expected exactly one function call");
                        ModelClient.ToolCall call = reply.toolCalls().getFirst();
                        JsonObject arguments = JsonParser.parseString(call.arguments()).getAsJsonObject();
                        JsonObject decision;
                        if (call.name().equals("answer")) {
                            decision = arguments;
                            if (!finalOnly) decision.addProperty("type", "answer");
                        } else {
                            ReActTool<?, O> tool = aliases.get(call.name());
                            if (tool == null) throw new IllegalArgumentException("Unavailable function " + call.name());
                            decision = new JsonObject();
                            decision.addProperty("type", tool.id());
                            decision.add("input", arguments);
                        }
                        return this.execution.codec(codec).parse(JsonOps.INSTANCE, decision).getOrThrow();
                    });
        } catch (Exception error) {
            LOGGER.warn("ReAct decision failed: task={}, iteration={}, finalOnly={}, observations={}, modelCalls={}, remainingMillis={}, failure={}",
                    this.execution.taskId(), this.iterations, finalOnly, this.observations.size(), this.execution.modelCalls(),
                    this.execution.remainingMillis(), error.getMessage(), error);
            throw error;
        }
    }

    <I> boolean perform(ReActTool<I, O> tool, I input) {
        if (this.tools.get(tool.id()) != tool) throw new AssistantException(Reason.NOT_AUTHORIZED);
        // Failed attempts are remembered too; a loop never silently retries an already paid operation.
        if (!this.attempted.add(tool.id() + ":" + tool.repetitionKey().apply(input))) return false;
        O observation;
        try {
            observation = tool.handler().execute(input);
        } catch (Exception error) {
            LOGGER.warn("ReAct tool failed: task={}, iteration={}, tool={}, observations={}, failure={}",
                    this.execution.taskId(), this.iterations, tool.id(), this.observations.size(), error.getMessage(), error);
            throw error;
        }
        this.check();
        this.observations.add(Objects.requireNonNull(observation));
        return true;
    }

    public interface Execution {
        void ensureMemoryCurrent();

        int reactIterations();

        int remainingModelCalls();

        int remainingToolCalls();

        int remainingSteps();

        long remainingMillis();

        long modelTimeoutMillis();

        String taskId();

        int modelCalls();

        <T> Codec<T> codec(Codec<T> codec);

        <I, O> O model(String instruction, MapCodec<I> inputCodec, I input, List<ModelClient.Function> functions,
                       Function<ModelClient.Reply, O> decoder);
    }

    private record Input<O>(String question, List<O> observations,
                            int remainingIterations, int remainingTools, boolean finalOnly) {
        static <O> MapCodec<Input<O>> codec(Codec<O> observationCodec) {
            return RecordCodecBuilder.mapCodec(i -> i.group(
                    Codec.STRING.fieldOf("question").forGetter(Input<O>::question),
                    observationCodec.listOf().fieldOf("observations").forGetter(Input<O>::observations),
                    Codec.INT.fieldOf("remainingIterations").forGetter(Input<O>::remainingIterations),
                    Codec.INT.fieldOf("remainingTools").forGetter(Input<O>::remainingTools),
                    Codec.BOOL.fieldOf("finalOnly").forGetter(Input<O>::finalOnly)
            ).apply(i, Input<O>::new));
        }
    }
}
