package com.iafenvoy.assistant.text;

import com.iafenvoy.assistant.registry.AssistantResourceKeys;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.Identifier;

import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Stream;

/** Annotations use UTF-16 offsets in the accompanying, unmodified text. */
public record TextAnnotations(List<Annotation> values) {
    public static final int MAX_TEXT_LENGTH = 256;
    static final int MAX_MATCHES = 64;
    static final int MAX_CANDIDATES = 16;
    static final int MAX_TRANSLATION_KEY_LENGTH = 256;
    static final int MAX_ANNOTATIONS = MAX_MATCHES * 3;
    public static final TextAnnotations EMPTY = new TextAnnotations(List.of());
    public static final StreamCodec<RegistryFriendlyByteBuf, TextAnnotations> STREAM_CODEC =
            ByteBufCodecs.registry(AssistantResourceKeys.ANNOTATION_TYPE)
                    .<Annotation>dispatch(Annotation::streamCodec, Function.identity())
                    .apply(ByteBufCodecs.list(MAX_ANNOTATIONS)).map(TextAnnotations::new, TextAnnotations::values);

    public TextAnnotations {
        values = List.copyOf(values);
    }

    public TextAnnotations(List<Reference> references, List<Quantity> quantities) {
        this(Stream.<Annotation>concat(references.stream(), quantities.stream()).toList());
    }

    public <T extends Annotation> List<T> ofType(Class<T> type) {
        return this.values.stream().filter(type::isInstance).map(type::cast).toList();
    }

    public List<Reference> references() {
        return this.ofType(Reference.class);
    }

    public List<Quantity> quantities() {
        return this.ofType(Quantity.class);
    }

    public boolean validFor(String text) {
        return text.length() <= MAX_TEXT_LENGTH
                && this.values.size() <= MAX_ANNOTATIONS
                && this.values.stream().allMatch(value -> value.span().validFor(text) && value.validPayload());
    }

    public TextAnnotations slice(int start, int end) {
        return new TextAnnotations(this.values.stream().filter(value -> value.span().within(start, end))
                .map(value -> value.withSpan(value.span().offset(-start))).toList());
    }

    public interface Annotation {
        Span span();

        Annotation withSpan(Span span);

        StreamCodec<? super RegistryFriendlyByteBuf, ? extends Annotation> streamCodec();

        default boolean validPayload() {
            return true;
        }
    }

    public record Span(int start, int end) {
        public static final StreamCodec<ByteBuf, Span> STREAM_CODEC = StreamCodec.composite(
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

    public record Candidate(Identifier registry, Identifier id, boolean tag, Optional<String> translationKey) {
        private static final StreamCodec<ByteBuf, Candidate> STREAM_CODEC = StreamCodec.composite(
                Identifier.STREAM_CODEC, Candidate::registry,
                Identifier.STREAM_CODEC, Candidate::id,
                ByteBufCodecs.BOOL, Candidate::tag,
                ByteBufCodecs.optional(ByteBufCodecs.stringUtf8(MAX_TRANSLATION_KEY_LENGTH)), Candidate::translationKey,
                Candidate::new);

        public Candidate(Identifier registry, Identifier id, Optional<String> translationKey) {
            this(registry, id, false, translationKey);
        }

        CandidateKey key() {
            return new CandidateKey(this.registry, this.id, this.tag);
        }
    }

    record CandidateKey(Identifier registry, Identifier id, boolean tag) {
    }

    public record Reference(Span span, List<Candidate> candidates) implements Annotation {
        public static final StreamCodec<ByteBuf, Reference> STREAM_CODEC = StreamCodec.composite(
                Span.STREAM_CODEC, Reference::span,
                Candidate.STREAM_CODEC.apply(ByteBufCodecs.list(MAX_CANDIDATES)), Reference::candidates,
                Reference::new);

        public Reference {
            candidates = List.copyOf(candidates);
        }

        @Override
        public Reference withSpan(Span span) {
            return new Reference(span, this.candidates);
        }

        @Override
        public StreamCodec<ByteBuf, Reference> streamCodec() {
            return STREAM_CODEC;
        }

        @Override
        public boolean validPayload() {
            return !this.candidates.isEmpty() && this.candidates.size() <= MAX_CANDIDATES
                    && this.candidates.stream().allMatch(candidate ->
                    candidate.translationKey().map(key -> key.length() <= MAX_TRANSLATION_KEY_LENGTH).orElse(true));
        }
    }

    public record Quantity(Span span, int value) implements Annotation {
        public static final StreamCodec<ByteBuf, Quantity> STREAM_CODEC = StreamCodec.composite(
                Span.STREAM_CODEC, Quantity::span, ByteBufCodecs.VAR_INT, Quantity::value, Quantity::new);

        @Override
        public Quantity withSpan(Span span) {
            return new Quantity(span, this.value);
        }

        @Override
        public StreamCodec<ByteBuf, Quantity> streamCodec() {
            return STREAM_CODEC;
        }

        @Override
        public boolean validPayload() {
            return this.value > 0;
        }
    }
}
