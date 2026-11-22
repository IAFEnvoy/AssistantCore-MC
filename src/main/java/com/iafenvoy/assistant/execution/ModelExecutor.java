package com.iafenvoy.assistant.execution;

import com.iafenvoy.assistant.AssistantException;
import com.iafenvoy.assistant.AssistantException.Reason;
import com.iafenvoy.assistant.model.ModelClient;
import com.iafenvoy.assistant.model.ModelGateway;
import com.iafenvoy.assistant.util.CodecIO;
import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * Applies a task's model budget, concurrency limit and deadline around the provider gateway.
 */
public final class ModelExecutor {
    private final ModelGateway gateway;
    private final Semaphore slots;
    private final String instructionRole;

    public ModelExecutor(ModelGateway gateway, int concurrency, String instructionRole) {
        this.gateway = gateway;
        this.slots = new Semaphore(concurrency, true);
        this.instructionRole = instructionRole;
    }

    public <O> O execute(UUID player, String instruction, Supplier<String> content, List<ModelClient.Function> functions,
                         ModelClient.Options options, ExecutionBudget budget, ExecutionPolicy policy, LongSupplier timeout,
                         Runnable authorization, Runnable beforeReserve, Runnable persist,
                         Function<ModelClient.Reply, O> decoder, BiConsumer<ModelClient.Reply, RuntimeException> invalidReply) throws Exception {
        authorization.run();
        this.gateway.checkConfigured();
        if (!budget.step(policy.steps()) || !budget.canCallModel(policy.modelCalls()))
            throw new AssistantException(Reason.BUDGET_EXCEEDED);
        String promptInput = content.get();
        List<ModelClient.Function> calls = List.copyOf(functions);
        long inputBytes = inputBytes(instruction, promptInput, calls);
        if (!budget.fitsModelInput(inputBytes, policy.inputBytes(), policy.outputTokens(), policy.totalTokens()))
            throw new AssistantException(Reason.BUDGET_EXCEEDED);
        int estimated = (int) inputBytes;
        if (!this.slots.tryAcquire(timeout.getAsLong(), TimeUnit.MILLISECONDS))
            throw new AssistantException(Reason.BUSY);
        try {
            beforeReserve.run();
            budget.reserveModel(estimated, policy.outputTokens());
            persist.run();
            ModelClient.Reply reply = this.gateway.complete(player, List.of(new ModelClient.Message(this.instructionRole, instruction),
                    new ModelClient.Message("user", promptInput)), calls, options, policy.outputTokens(), Duration.ofMillis(timeout.getAsLong()));
            budget.settleModel(reply.totalTokens(), estimated, policy.outputTokens());
            persist.run();
            authorization.run();
            if (budget.usage().tokens() > policy.totalTokens()) throw new AssistantException(Reason.BUDGET_EXCEEDED);
            try {
                return decoder.apply(reply);
            } catch (RuntimeException error) {
                invalidReply.accept(reply, error);
                throw new AssistantException(Reason.MODEL_INVALID, error);
            }
        } finally {
            this.slots.release();
        }
    }

    public static long inputBytes(String instruction, String content, List<ModelClient.Function> functions) {
        long bytes = (long) instruction.getBytes(StandardCharsets.UTF_8).length + content.getBytes(StandardCharsets.UTF_8).length;
        if (!functions.isEmpty())
            bytes += CodecIO.encode(ModelClient.Function.CODEC.listOf(), functions).getBytes(StandardCharsets.UTF_8).length;
        return bytes;
    }

    public record Input<I>(I input, RequestContext context) {
        public static <I> Codec<Input<I>> codec(MapCodec<I> inputCodec) {
            return RecordCodecBuilder.create(i -> i.group(
                    inputCodec.forGetter(Input<I>::input),
                    RequestContext.CODEC.fieldOf("requestContext").forGetter(Input<I>::context)
            ).apply(i, Input<I>::new));
        }
    }

    public record RequestContext(String playerLanguage) {
        static final Codec<RequestContext> CODEC = Codec.STRING.fieldOf("playerLanguage")
                .xmap(RequestContext::new, RequestContext::playerLanguage).codec();
    }
}
