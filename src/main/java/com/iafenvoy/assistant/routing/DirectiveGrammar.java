package com.iafenvoy.assistant.routing;

import com.iafenvoy.assistant.text.TextAnnotations;
import com.iafenvoy.assistant.text.TextAnnotator;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.resources.Identifier;

import java.util.*;
import java.util.function.BiFunction;

/** Parses complete commands by composing literal phrases and host-resolved fields. */
public final class DirectiveGrammar {
    private static final int MAX_STEPS = 24000;
    private static final int MAX_FIELD_LENGTH = 80;
    private static final int MAX_FIELD_VALUES = 16;
    private final List<Production> productions;

    public DirectiveGrammar(List<Phrase> phrases) {
        this(phrases.stream().distinct().map(Production::new).toList());
    }

    private DirectiveGrammar(Collection<Production> productions) {
        this.productions = List.copyOf(productions);
    }

    /** Makes an overlay for the operations actually enabled by the host. */
    public DirectiveGrammar withOperationWords(Map<Identifier, List<String>> operations) {
        if (operations.isEmpty()) return this;
        List<Production> rules = new ArrayList<>(this.productions);
        operations.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> entry.getValue().stream()
                .map(TextAnnotator::normalize).filter(text -> !text.isEmpty()).distinct()
                .map(text -> new Production(List.of(new Part(text, "")), Map.of("operation", entry.getKey().toString()), 1))
                .forEach(rules::add));
        return new DirectiveGrammar(rules);
    }

    public Parse parse(String input, BiFunction<String, String, List<String>> resolve) {
        if (input.length() > TextAnnotations.MAX_TEXT_LENGTH || this.productions.isEmpty()) return Parse.NO_MATCH;
        String text = TextAnnotator.normalize(input).replaceFirst("[.!?。]+$", "").stripTrailing();
        if (text.isEmpty()) return Parse.NO_MATCH;
        Search search = new Search(text, resolve);
        search.scan(this.productions);
        if (search.results.isEmpty()) return search.exhausted() ? new Parse(ParseStatus.LIMIT, Map.of()) : Parse.NO_MATCH;
        if (search.exhausted()) return new Parse(ParseStatus.LIMIT, Map.of());
        if (search.results.size() > 1) return new Parse(ParseStatus.AMBIGUOUS, Map.of());
        return new Parse(ParseStatus.MATCH, search.results.iterator().next());
    }

    public enum ParseStatus {
        NO_MATCH, MATCH, AMBIGUOUS, LIMIT
    }

    public record Parse(ParseStatus status, Map<String, String> fields) {
        private static final Parse NO_MATCH = new Parse(ParseStatus.NO_MATCH, Map.of());
    }

    public record Phrase(List<String> pattern, Map<String, String> set, int phase) {
        public static final Codec<Phrase> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Codec.string(1, 96).listOf(1, 16).fieldOf("pattern").forGetter(Phrase::pattern),
                Codec.unboundedMap(Codec.string(1, 64), Codec.string(1, 128)).optionalFieldOf("set", Map.of()).forGetter(Phrase::set),
                Codec.intRange(0, 16).optionalFieldOf("phase", 1).forGetter(Phrase::phase)
        ).apply(instance, Phrase::new));

        public Phrase {
            pattern = List.copyOf(pattern);
            set = Map.copyOf(set);
        }
    }

    private record Part(String value, String type) {
        private static Part compile(String raw) {
            if (raw.startsWith("<") && raw.endsWith(">") && raw.length() > 2) {
                String name = raw.substring(1, raw.length() - 1);
                int separator = name.indexOf('@');
                return separator < 0 ? new Part(name, name) : new Part(name.substring(0, separator), name.substring(separator + 1));
            }
            return new Part(TextAnnotator.normalize(raw), "");
        }

        private boolean field() {
            return !this.type.isEmpty();
        }
    }

    private record Production(List<Part> parts, Map<String, String> set, int phase) {
        private Production(Phrase phrase) {
            this(phrase.pattern().stream().map(Part::compile).toList(), phrase.set(), phrase.phase());
        }
    }

    private record State(int position, int phase, Map<String, String> fields) {
    }

    private record SlotStart(String field, int position) {
    }

    private record FieldMatch(int end, String value) {
    }

    private static final class Search {
        private final String text;
        private final BiFunction<String, String, List<String>> resolve;
        private final Map<SlotStart, List<FieldMatch>> slots = new HashMap<>();
        private final Set<State> visited = new HashSet<>();
        private final ArrayDeque<State> pending = new ArrayDeque<>();
        private final Set<Map<String, String>> results = new HashSet<>();
        private int steps;

        private Search(String text, BiFunction<String, String, List<String>> resolve) {
            this.text = text;
            this.resolve = resolve;
        }

        private void scan(List<Production> productions) {
            this.enqueue(0, 0, Map.of());
            while (!this.pending.isEmpty() && !this.exhausted() && this.results.size() < 2) {
                State state = this.pending.removeFirst();
                if (state.position() == this.text.length()) {
                    if (state.fields().containsKey("operation")) this.results.add(state.fields());
                    continue;
                }
                for (Production production : productions) {
                    if (this.exhausted()) break;
                    if (production.phase() >= state.phase() && Collections.disjoint(state.fields().keySet(), production.set().keySet()))
                        this.match(production, 0, state.position(), state.fields());
                }
            }
        }

        private void match(Production production, int partIndex, int from, Map<String, String> fields) {
            if (++this.steps > MAX_STEPS) return;
            if (partIndex == production.parts().size()) {
                if (Collections.disjoint(fields.keySet(), production.set().keySet())) {
                    Map<String, String> complete = new HashMap<>(fields);
                    complete.putAll(production.set());
                    this.enqueue(from, production.phase(), Map.copyOf(complete));
                }
                return;
            }
            int start = this.skipSpaces(from);
            if (start == this.text.length()) return;
            Part part = production.parts().get(partIndex);
            if (part.field()) {
                if (fields.containsKey(part.value()) || production.set().containsKey(part.value())) return;
                for (FieldMatch field : this.slots.computeIfAbsent(new SlotStart(part.type(), start), this::findField)) {
                    if (this.exhausted()) break;
                    Map<String, String> updated = new HashMap<>(fields);
                    updated.put(part.value(), field.value());
                    this.match(production, partIndex + 1, field.end(), updated);
                }
            } else if (!part.value().isEmpty() && this.text.startsWith(part.value(), start)) {
                int end = start + part.value().length();
                if (boundaries(this.text, start, end)) this.match(production, partIndex + 1, end, fields);
            }
        }

        private List<FieldMatch> findField(SlotStart slot) {
            List<FieldMatch> matches = new ArrayList<>();
            int start = slot.position();
            for (int end = start; end < this.text.length() && end - start < MAX_FIELD_LENGTH; ) {
                end += Character.charCount(this.text.codePointAt(end));
                if (++this.steps > MAX_STEPS) break;
                if (this.text.charAt(end - 1) == ' ' || !boundaries(this.text, start, end)) continue;
                String value = this.text.substring(start, end);
                this.resolve.apply(slot.field(), value).stream()
                        .filter(candidate -> !candidate.isBlank() && candidate.length() <= 128)
                        .distinct().limit(MAX_FIELD_VALUES + 1L)
                        .forEach(candidate -> matches.add(new FieldMatch(start + value.length(), candidate)));
            }
            return matches;
        }

        private void enqueue(int position, int phase, Map<String, String> fields) {
            State state = new State(this.skipSpaces(position), phase, fields);
            if (this.visited.add(state)) this.pending.addLast(state);
        }

        private int skipSpaces(int position) {
            while (position < this.text.length() && this.text.charAt(position) == ' ') position++;
            return position;
        }

        private boolean exhausted() {
            return this.steps > MAX_STEPS;
        }
    }

    private static boolean boundaries(String text, int start, int end) {
        return (start == 0 || !word(text.codePointBefore(start)) || !word(text.codePointAt(start)))
                && (end == text.length() || !word(text.codePointBefore(end)) || !word(text.codePointAt(end)));
    }

    private static boolean word(int point) {
        return point == '_' || point == ':' || point == '-' || point == '/' || point == '.'
                || Character.isLetterOrDigit(point) && Character.UnicodeScript.of(point) != Character.UnicodeScript.HAN;
    }
}
