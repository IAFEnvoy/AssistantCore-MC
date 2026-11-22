package com.iafenvoy.assistant.react;

import com.google.gson.JsonObject;
import com.iafenvoy.assistant.AssistantException;
import com.iafenvoy.assistant.AssistantException.Reason;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.MapCodec;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Shared decide -> act -> observe loop. A round is one decision attempt, including final synthesis and errors.
 */
public final class ReActLoop {

    private ReActLoop() {
    }

    public static <A, O, F, R> R run(ReActContext<O> context, MapCodec<A> answerCodec, JsonObject answerSchema,
                                     F iterationLimit, Function<RuntimeException, F> recoverableFailure,
                                     Predicate<F> stopAfterFailure, Function<A, R> completion, Function<F, R> fallback) throws Exception {
        Map<String, MapCodec<? extends Step<A, O>>> types = new LinkedHashMap<>();
        types.put("answer", answerCodec.xmap(Final<A, O>::new, Final::answer));
        for (ReActTool<?, O> tool : context.tools()) types.put(tool.id(), choice(tool));
        Codec<Step<A, O>> codec = Codec.STRING.partialDispatch("type", step -> DataResult.success(step.type()),
                id -> types.containsKey(id) ? DataResult.success(types.get(id)) : DataResult.error(() -> "Unavailable ReAct tool " + id));
        boolean finalOnly = false;
        F failure = iterationLimit;
        context.check();
        while (context.canContinue()) {
            context.check();
            finalOnly |= context.shouldFinish();
            try {
                Step<A, O> step = finalOnly
                        ? new Final<>(context.decide(answerCodec.codec(), answerSchema, true)) : context.decide(codec, answerSchema, false);
                if (step instanceof Final<A, O>(A answer1)) {
                    R result = completion.apply(answer1);
                    context.check();
                    return result;
                }
                if (!context.observations().isEmpty() && context.shortOnTime()) {
                    finalOnly = true;
                    continue;
                }
                if (!step.perform(context)) finalOnly = true;
            } catch (RuntimeException error) {
                // Authority/cancellation wins over recovery, and no failed tool is automatically reissued.
                context.check();
                failure = recoverableFailure.apply(error);
                if (context.observations().isEmpty() || failure == null) throw error;
                if (finalOnly || stopAfterFailure.test(failure))
                    break;
                finalOnly = true;
            }
        }
        context.check();
        return fallback.apply(failure);
    }

    private static <A, I, O> MapCodec<Step<A, O>> choice(ReActTool<I, O> tool) {
        return tool.inputCodec().codec().fieldOf("input").xmap(input -> new Call<>(tool, input), step -> {
            if (!(step instanceof Call<?, ?, ?>(ReActTool<?, ?> tool1, Object input1)) || tool1 != tool)
                throw new IllegalArgumentException("Incorrect ReAct tool codec");
            @SuppressWarnings("unchecked") I input = (I) input1;
            return input;
        });
    }

    private sealed interface Step<A, O> permits Final, Call {
        String type();

        default boolean perform(ReActContext<O> context) throws Exception {
            throw new AssistantException(Reason.MODEL_INVALID);
        }
    }

    private record Final<A, O>(A answer) implements Step<A, O> {
        @Override
        public String type() {
            return "answer";
        }
    }

    private record Call<A, I, O>(ReActTool<I, O> tool, I input) implements Step<A, O> {
        @Override
        public String type() {
            return this.tool.id();
        }

        @Override
        public boolean perform(ReActContext<O> context) throws Exception {
            return context.perform(this.tool, this.input);
        }
    }

}
