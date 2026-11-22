package com.iafenvoy.assistant.react;

import com.google.gson.JsonObject;
import com.iafenvoy.assistant.model.ModelClient;
import com.iafenvoy.assistant.tool.ToolPriority;
import com.iafenvoy.assistant.util.CodecIO;
import com.mojang.serialization.MapCodec;

import java.util.Objects;
import java.util.function.Function;

/**
 * A typed, loop-local binding owns argument validation and projects its tool result into an observation.
 */
public record ReActTool<I, O>(String id, String description, MapCodec<I> inputCodec, JsonObject schema,
                              Function<I, String> repetitionKey, Handler<I, O> handler, ToolPriority priority) {
    public ReActTool {
        Objects.requireNonNull(id);
        Objects.requireNonNull(description);
        Objects.requireNonNull(inputCodec);
        schema = schema.deepCopy();
        Objects.requireNonNull(repetitionKey);
        Objects.requireNonNull(handler);
        Objects.requireNonNull(priority);
        if (id.equals("answer")) throw new IllegalArgumentException("Reserved ReAct tool ID");
    }

    public ReActTool(String id, String description, MapCodec<I> inputCodec, JsonObject schema, Handler<I, O> handler) {
        this(id, description, inputCodec, schema, input -> CodecIO.write(inputCodec.codec(), input), handler);
    }

    public ReActTool(String id, String description, MapCodec<I> inputCodec, JsonObject schema, Function<I, String> repetitionKey, Handler<I, O> handler) {
        this(id, description, inputCodec, schema, repetitionKey, handler, ToolPriority.LOCAL);
    }

    public ReActTool<I, O> withPriority(ToolPriority priority) {
        return new ReActTool<>(this.id, this.description, this.inputCodec, this.schema, this.repetitionKey, this.handler, priority);
    }

    public ModelClient.Function function(String name) {
        return new ModelClient.Function(name, "Capability: " + this.id + ". Priority: " + this.priority.getSerializedName()
                + ". " + this.description, this.schema);
    }

    @FunctionalInterface
    public interface Handler<I, O> {
        O execute(I input) throws Exception;
    }
}
