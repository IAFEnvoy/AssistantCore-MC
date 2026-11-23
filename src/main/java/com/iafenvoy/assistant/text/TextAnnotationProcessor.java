package com.iafenvoy.assistant.text;

import net.minecraft.resources.Identifier;

/**
 * Adds references or quantities to client text. Register an instance on the mod event bus and
 * put client-only lookups inside the callback. Spans must use UTF-16 offsets in the input text.
 */
@FunctionalInterface
public interface TextAnnotationProcessor {
    TextAnnotations annotate(Input input);

    default boolean supports(Identifier scope) {
        return true;
    }

    record Input(Identifier scope, String text, String locale) {
    }
}
