package com.iafenvoy.assistant.text;

import com.iafenvoy.assistant.text.TextAnnotations.Candidate;
import com.iafenvoy.assistant.text.TextAnnotations.Quantity;
import com.iafenvoy.assistant.text.TextAnnotations.Reference;
import com.iafenvoy.assistant.text.TextAnnotations.Span;
import net.minecraft.resources.Identifier;

import java.text.Normalizer;
import java.util.*;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class TextAnnotator {
    private static final Pattern GRAPHEME = Pattern.compile("\\X");
    private static final Pattern SPACE = Pattern.compile("\\s+", Pattern.UNICODE_CHARACTER_CLASS);
    private final Node names = new Node();

    public TextAnnotator(Iterable<Name> names) {
        for (Name name : names) {
            Node node = this.names;
            String text = normalize(name.text());
            if (text.isEmpty() || text.length() > TextAnnotations.MAX_TEXT_LENGTH) continue;
            for (int i = 0; i < text.length(); i++)
                node = node.children.computeIfAbsent(text.charAt(i), ignored -> new Node());
            Candidate candidate = name.candidate();
            node.candidates.merge(new RegistryEntry(candidate.registry(), candidate.id()), candidate,
                    (first, second) -> first.translationKey().isPresent() ? first : second);
        }
    }

    public static String normalize(String text) {
        return SPACE.matcher(Normalizer.normalize(text, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT)).replaceAll(" ").strip();
    }

    public TextAnnotations annotate(String text, Function<String, List<Quantity>> quantities) {
        if (text.length() > TextAnnotations.MAX_TEXT_LENGTH) return TextAnnotations.EMPTY;
        MappedText mapped = MappedText.of(text);
        String normalized = mapped.text();
        List<Reference> references = new ArrayList<>();
        for (int start = 0; start < normalized.length() && references.size() < TextAnnotations.MAX_MATCHES; ) {
            Node node = this.names;
            Node longest = null;
            int matchEnd = start;
            for (int end = start; end < normalized.length(); end++) {
                node = node.children.get(normalized.charAt(end));
                if (node == null) break;
                if (!node.candidates.isEmpty() && mapped.hasBoundaries(start, end + 1)
                        && wordBoundaries(normalized, start, end + 1)) {
                    longest = node;
                    matchEnd = end + 1;
                }
            }
            if (longest == null) {
                start += Character.charCount(normalized.codePointAt(start));
            } else {
                if (longest.candidates.size() <= TextAnnotations.MAX_CANDIDATES)
                    references.add(new Reference(mapped.span(start, matchEnd), List.copyOf(longest.candidates.values())));
                start = matchEnd;
            }
        }
        List<Quantity> amounts = quantities.apply(normalized).stream()
                .filter(amount -> amount.value() > 0 && amount.span().within(0, normalized.length()))
                .filter(amount -> mapped.hasBoundaries(amount.span().start(), amount.span().end()))
                .map(amount -> new Quantity(mapped.span(amount.span().start(), amount.span().end()), amount.value()))
                .filter(amount -> references.stream().noneMatch(ref -> ref.span().start() < amount.span().end()
                        && amount.span().start() < ref.span().end()))
                .limit(TextAnnotations.MAX_MATCHES).toList();
        return new TextAnnotations(references, amounts);
    }

    private static boolean wordBoundaries(String text, int start, int end) {
        return (start == 0 || !joinsWord(text.codePointBefore(start), text.codePointAt(start)))
                && (end == text.length() || !joinsWord(text.codePointBefore(end), text.codePointAt(end)));
    }

    private static boolean joinsWord(int left, int right) {
        return wordCharacter(left) && wordCharacter(right);
    }

    private static boolean wordCharacter(int point) {
        if (point == '_' || point == ':') return true;
        if (!Character.isLetterOrDigit(point)) return false;
        return switch (Character.UnicodeScript.of(point)) {
            case HAN, HIRAGANA, KATAKANA, THAI, LAO, KHMER, MYANMAR -> false;
            default -> true;
        };
    }

    public record Name(String text, Candidate candidate) {
    }

    private record RegistryEntry(Identifier registry, Identifier id) {
    }

    private static final class Node {
        private final Map<Character, Node> children = new HashMap<>();
        private final Map<RegistryEntry, Candidate> candidates = new LinkedHashMap<>();
    }

    private record MappedText(String text, List<Span> offsets) {
        private static MappedText of(String text) {
            StringBuilder normalized = new StringBuilder();
            List<Span> offsets = new ArrayList<>();
            Matcher graphemes = GRAPHEME.matcher(text);
            while (graphemes.find()) {
                String cluster = SPACE.matcher(Normalizer.normalize(graphemes.group(), Normalizer.Form.NFKC))
                        .replaceAll(" ");
                Span span = new Span(graphemes.start(), graphemes.end());
                for (int i = 0; i < cluster.length(); i++) {
                    char character = cluster.charAt(i);
                    if (character == ' ' && normalized.isEmpty()) continue;
                    if (character == ' ' && normalized.charAt(normalized.length() - 1) == ' ') {
                        Span previous = offsets.getLast();
                        offsets.set(offsets.size() - 1, new Span(previous.start(), span.end()));
                    } else {
                        normalized.append(character);
                        offsets.add(span);
                    }
                }
            }
            if (!normalized.isEmpty() && normalized.charAt(normalized.length() - 1) == ' ') {
                normalized.setLength(normalized.length() - 1);
                offsets.removeLast();
            }
            // Case conversion can expand a code point. Map those extra units to the same original span.
            String folded = normalized.toString().toLowerCase(Locale.ROOT);
            if (folded.length() == offsets.size()) return new MappedText(folded, offsets);
            List<Span> foldedOffsets = new ArrayList<>();
            for (int i = 0; i < normalized.length(); ) {
                int length = Character.charCount(normalized.codePointAt(i));
                int foldedLength = normalized.substring(i, i + length).toLowerCase(Locale.ROOT).length();
                for (int j = 0; j < foldedLength; j++) foldedOffsets.add(offsets.get(i));
                i += length;
            }
            return new MappedText(folded, foldedOffsets);
        }

        private boolean hasBoundaries(int start, int end) {
            return (start == 0 || !this.offsets.get(start - 1).equals(this.offsets.get(start)))
                    && (end == this.offsets.size() || !this.offsets.get(end - 1).equals(this.offsets.get(end)));
        }

        private Span span(int start, int end) {
            return new Span(this.offsets.get(start).start(), this.offsets.get(end - 1).end());
        }
    }
}
