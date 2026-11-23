package com.iafenvoy.assistant.routing;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.resources.Identifier;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Declares which combinations a task can fulfill and how to serialize its arguments. */
public record DirectiveContract(Identifier operation, Map<String, Field> fields, List<String> arguments) {
    public static final Codec<DirectiveContract> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Identifier.CODEC.fieldOf("operation").forGetter(DirectiveContract::operation),
            Codec.unboundedMap(Codec.string(1, 64), Field.CODEC).optionalFieldOf("fields", Map.of()).forGetter(DirectiveContract::fields),
            Codec.string(1, 128).listOf(0, 16).optionalFieldOf("arguments", List.of()).forGetter(DirectiveContract::arguments)
    ).apply(instance, DirectiveContract::new));

    public DirectiveContract {
        fields = Map.copyOf(fields);
        arguments = List.copyOf(arguments);
    }

    public boolean accepts(Map<String, String> input) {
        if (!this.operation.toString().equals(input.get("operation"))) return false;
        for (Map.Entry<String, String> entry : input.entrySet()) {
            if (entry.getKey().equals("operation")) continue;
            Field field = this.fields.get(entry.getKey());
            if (field == null || field.fixed().filter(value -> !value.equals(entry.getValue())).isPresent()) return false;
        }
        return true;
    }

    public Optional<String> bind(Map<String, String> input) {
        if (!this.accepts(input)) return Optional.empty();
        for (Map.Entry<String, Field> entry : this.fields.entrySet())
            if (entry.getValue().required() && !input.containsKey(entry.getKey())) return Optional.empty();

        StringBuilder args = new StringBuilder();
        for (String argument : this.arguments) {
            String value = argument;
            if (argument.startsWith("$")) {
                String key = argument.substring(1);
                Field field = this.fields.get(key);
                if (field == null) return Optional.empty();
                value = input.getOrDefault(key, field.fallback().orElse(""));
                if (value.isEmpty()) return Optional.empty();
            }
            if (!args.isEmpty()) args.append(' ');
            args.append(value);
        }
        return Optional.of(args.toString());
    }

    public record Field(boolean required, Optional<String> fixed, Optional<String> fallback) {
        private static final Codec<Field> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Codec.BOOL.optionalFieldOf("required", false).forGetter(Field::required),
                Codec.string(1, 128).optionalFieldOf("value").forGetter(Field::fixed),
                Codec.string(1, 128).optionalFieldOf("default").forGetter(Field::fallback)
        ).apply(instance, Field::new));
    }
}
