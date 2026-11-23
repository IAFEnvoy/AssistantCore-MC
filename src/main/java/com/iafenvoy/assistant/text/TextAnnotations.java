package com.iafenvoy.assistant.text;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.Identifier;

import java.util.List;
import java.util.Optional;

/** References and quantities use UTF-16 offsets in the accompanying, unmodified text. */
public record TextAnnotations(List<Reference> references, List<Quantity> quantities) {
    public static final int MAX_TEXT_LENGTH = 256;
    static final int MAX_MATCHES = 64;
    static final int MAX_CANDIDATES = 16;
    static final int MAX_TRANSLATION_KEY_LENGTH = 256;
    public static final TextAnnotations EMPTY = new TextAnnotations(List.of(), List.of());
    public static final StreamCodec<ByteBuf, TextAnnotations> STREAM_CODEC = StreamCodec.composite(
            Reference.STREAM_CODEC.apply(ByteBufCodecs.list(MAX_MATCHES)), TextAnnotations::references,
            Quantity.STREAM_CODEC.apply(ByteBufCodecs.list(MAX_MATCHES)), TextAnnotations::quantities,
            TextAnnotations::new);

    public TextAnnotations {
        references = List.copyOf(references);
        quantities = List.copyOf(quantities);
    }

    public boolean validFor(String text) {
        return text.length() <= MAX_TEXT_LENGTH
                && this.references.stream().allMatch(ref -> ref.span().validFor(text) && !ref.candidates().isEmpty()
                        && ref.candidates().stream().allMatch(candidate ->
                                candidate.translationKey().map(key -> key.length() <= MAX_TRANSLATION_KEY_LENGTH).orElse(true)))
                && this.quantities.stream().allMatch(quantity -> quantity.span().validFor(text) && quantity.value() > 0);
    }

    public TextAnnotations slice(int start, int end) {
        return new TextAnnotations(
                this.references.stream().filter(ref -> ref.span().within(start, end))
                        .map(ref -> new Reference(ref.span().offset(-start), ref.candidates())).toList(),
                this.quantities.stream().filter(quantity -> quantity.span().within(start, end))
                        .map(quantity -> new Quantity(quantity.span().offset(-start), quantity.value())).toList());
    }

    public record Span(int start, int end) {
        private static final StreamCodec<ByteBuf, Span> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, Span::start, ByteBufCodecs.VAR_INT, Span::end, Span::new);

        public String text(String text) {
            return text.substring(this.start, this.end);
        }

        boolean within(int start, int end) {
            return this.start >= start && this.start < this.end && this.end <= end;
        }

        boolean validFor(String text) {
            return this.within(0, text.length()) && !Character.isLowSurrogate(text.charAt(this.start))
                    && (this.end == text.length() || !Character.isLowSurrogate(text.charAt(this.end)));
        }

        Span offset(int delta) {
            return new Span(this.start + delta, this.end + delta);
        }
    }

    public record Candidate(Identifier registry, Identifier id, Optional<String> translationKey) {
        private static final StreamCodec<ByteBuf, Candidate> STREAM_CODEC = StreamCodec.composite(
                Identifier.STREAM_CODEC, Candidate::registry,
                Identifier.STREAM_CODEC, Candidate::id,
                ByteBufCodecs.optional(ByteBufCodecs.stringUtf8(MAX_TRANSLATION_KEY_LENGTH)), Candidate::translationKey,
                Candidate::new);
    }

    public record Reference(Span span, List<Candidate> candidates) {
        private static final StreamCodec<ByteBuf, Reference> STREAM_CODEC = StreamCodec.composite(
                Span.STREAM_CODEC, Reference::span,
                Candidate.STREAM_CODEC.apply(ByteBufCodecs.list(MAX_CANDIDATES)), Reference::candidates,
                Reference::new);

        public Reference {
            candidates = List.copyOf(candidates);
        }
    }

    public record Quantity(Span span, int value) {
        private static final StreamCodec<ByteBuf, Quantity> STREAM_CODEC = StreamCodec.composite(
                Span.STREAM_CODEC, Quantity::span, ByteBufCodecs.VAR_INT, Quantity::value, Quantity::new);
    }
}
