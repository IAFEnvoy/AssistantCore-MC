package com.iafenvoy.assistant.text;

import com.iafenvoy.assistant.registry.AssistantRegistries;
import com.iafenvoy.assistant.text.TextAnnotations.Annotation;
import com.iafenvoy.assistant.text.TextAnnotations.Candidate;
import com.iafenvoy.assistant.text.TextAnnotations.CandidateKey;
import com.iafenvoy.assistant.text.TextAnnotations.Quantity;
import com.iafenvoy.assistant.text.TextAnnotations.Reference;
import com.iafenvoy.assistant.text.TextAnnotations.Span;
import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Combines the annotations from all registered processors for the current client input. */
public final class TextAnnotationProcessors {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Comparator<Span> SPAN_ORDER = Comparator.comparingInt(Span::start).thenComparingInt(Span::end);

    private TextAnnotationProcessors() {
    }

    public static TextAnnotations annotate(TextAnnotationProcessor.Input input) {
        if (input.text().length() > TextAnnotations.MAX_TEXT_LENGTH) return TextAnnotations.EMPTY;
        Map<Span, Map<CandidateKey, Candidate>> references = new LinkedHashMap<>();
        Map<Span, Set<Integer>> quantities = new LinkedHashMap<>();
        Set<Annotation> extra = new LinkedHashSet<>();
        AssistantRegistries.ANNOTATION_PROCESSOR.listElements()
                .sorted(Comparator.comparing(entry -> entry.key().identifier())).forEach(entry -> {
                    try {
                        TextAnnotationProcessor processor = entry.value();
                        if (!processor.supports(input.scope())) return;
                        TextAnnotations annotations = processor.annotate(input);
                        if (!annotations.validFor(input.text())) {
                            LOGGER.warn("Text annotation processor {} returned invalid annotations", entry.key().identifier());
                            return;
                        }
                        for (Annotation annotation : annotations.values()) {
                            switch (annotation) {
                                case Reference reference -> {
                                    Map<CandidateKey, Candidate> candidates = references.computeIfAbsent(reference.span(), unused -> new LinkedHashMap<>());
                                    for (Candidate candidate : reference.candidates())
                                        candidates.merge(candidate.key(), candidate,
                                                (first, second) -> first.translationKey().isPresent() ? first : second);
                                }
                                case Quantity quantity ->
                                        quantities.computeIfAbsent(quantity.span(), unused -> new LinkedHashSet<>()).add(quantity.value());
                                default -> {
                                    if (AssistantRegistries.ANNOTATION_TYPE.getKey(annotation.streamCodec()) != null)
                                        extra.add(annotation);
                                }
                            }
                        }
                    } catch (RuntimeException error) {
                        LOGGER.warn("Text annotation processor {} failed", entry.key().identifier(), error);
                    }
                });
        List<Reference> names = references.entrySet().stream()
                .filter(entry -> entry.getValue().size() <= TextAnnotations.MAX_CANDIDATES)
                .sorted(Map.Entry.comparingByKey(SPAN_ORDER)).limit(TextAnnotations.MAX_MATCHES)
                .map(entry -> new Reference(entry.getKey(), List.copyOf(entry.getValue().values()))).toList();
        List<Quantity> amounts = quantities.entrySet().stream().filter(entry -> entry.getValue().size() == 1)
                .sorted(Map.Entry.comparingByKey(SPAN_ORDER)).limit(TextAnnotations.MAX_MATCHES)
                .map(entry -> new Quantity(entry.getKey(), entry.getValue().iterator().next())).toList();
        List<Annotation> values = new ArrayList<>(names);
        values.addAll(amounts);
        extra.stream().sorted(Comparator.comparing(Annotation::span, SPAN_ORDER))
                .limit(TextAnnotations.MAX_ANNOTATIONS - values.size()).forEach(values::add);
        return new TextAnnotations(values);
    }
}
