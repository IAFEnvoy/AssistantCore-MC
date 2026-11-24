package com.iafenvoy.assistant.client;

import com.iafenvoy.assistant.text.TextAnnotations.Candidate;
import com.iafenvoy.assistant.text.TextAnnotator;
import net.minecraft.core.Registry;
import net.minecraft.locale.Language;
import net.neoforged.neoforge.common.Tags;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Predicate;

/** Builds a client-language name source for a registry selected by the caller. */
public final class RegistryTextNames {
    private RegistryTextNames() {
    }

    public static <T> List<TextAnnotator.Name> collect(Registry<T> registry, Language language,
                                                      Predicate<T> include, Function<T, String> translationKey) {
        return registry.stream().filter(include).map(value -> {
            String key = translationKey.apply(value);
            return language.has(key) ? new TextAnnotator.Name(language.getOrDefault(key),
                    new Candidate(registry.key().identifier(), registry.getKey(value), Optional.of(key))) : null;
        }).filter(Objects::nonNull).toList();
    }

    public static <T> List<TextAnnotator.Name> collectTags(Registry<T> registry, Language language) {
        return registry.getTags().filter(set -> set.size() > 0).map(set -> {
            var tag = set.key();
            String key = Tags.getTagTranslationKey(tag);
            return language.has(key) ? new TextAnnotator.Name(language.getOrDefault(key),
                    new Candidate(registry.key().identifier(), tag.location(), true, Optional.of(key))) : null;
        }).filter(Objects::nonNull).toList();
    }
}
