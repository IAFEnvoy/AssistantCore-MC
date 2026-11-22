package com.iafenvoy.assistant.routing;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.util.ExtraCodecs;
import net.minecraft.util.StringRepresentable;
import org.jspecify.annotations.NonNull;

import java.util.*;

/**
 * Lightweight lexical routing; old embedding thresholds are deliberately not reused.
 */
public record SemanticSettings(Mode mode, int maxCharacters, double minCoverage, Threshold defaultThreshold,
                               Set<String> languages, Map<String, Threshold> lexicalThresholds,
                               Map<String, Map<String, List<String>>> examples, int cacheEntries, int cacheSeconds,
                               int entriesPerPlayer, ModeScores modeScores) {
    public static final String ALGORITHM = "lexical-tfidf-v1";
    private static final Codec<Double> UNIT_INTERVAL;

    static {
        Codec<Double> codec = Codec.doubleRange(0, 1);
        UNIT_INTERVAL = codec.validate(value -> Double.isFinite(value) ? DataResult.success(value) : DataResult.error(() -> "Expected a finite value"));
    }

    private static final Codec<String> NON_BLANK = Codec.STRING.validate(value ->
            value.isBlank() ? DataResult.error(() -> "Expected non-blank text") : DataResult.success(value));

    private static Codec<String> matching(String pattern) {
        return Codec.STRING.validate(value -> value.matches(pattern) ? DataResult.success(value) : DataResult.error(() -> "Invalid value " + value));
    }

    private static final Codec<String> LANGUAGE = matching("[a-z]{2,3}");
    private static final Codec<String> MODULE_ID = matching("[a-z0-9_.-]+:[a-z0-9_./-]+");
    public static final Codec<SemanticSettings> CODEC = RecordCodecBuilder.create(i -> i.group(
            Mode.CODEC.lenientOptionalFieldOf("mode", Mode.ENFORCE).forGetter(SemanticSettings::mode),
            ExtraCodecs.POSITIVE_INT.lenientOptionalFieldOf("maxCharacters", 240).forGetter(SemanticSettings::maxCharacters),
            UNIT_INTERVAL.lenientOptionalFieldOf("minCoverage", 0.9).forGetter(SemanticSettings::minCoverage),
            Threshold.CODEC.lenientOptionalFieldOf("defaultThreshold", new Threshold(0.9, 0.15)).forGetter(SemanticSettings::defaultThreshold),
            LANGUAGE.listOf()
                    .xmap(values -> Collections.unmodifiableSet(new LinkedHashSet<>(values)), List::copyOf)
                    .lenientOptionalFieldOf("languages", Set.of("zh", "en")).forGetter(SemanticSettings::languages),
            Codec.unboundedMap(matching("[a-z0-9_.-]+:[a-z0-9_./-]+(?:@[a-z]{2,3})?"), Threshold.CODEC).lenientOptionalFieldOf("lexicalThresholds", Map.of()).forGetter(SemanticSettings::lexicalThresholds),
            Codec.unboundedMap(MODULE_ID, Codec.unboundedMap(LANGUAGE, NON_BLANK.listOf())).lenientOptionalFieldOf("examples", Map.of()).forGetter(SemanticSettings::examples),
            ExtraCodecs.POSITIVE_INT.lenientOptionalFieldOf("cacheEntries", 1024).forGetter(SemanticSettings::cacheEntries),
            ExtraCodecs.POSITIVE_INT.lenientOptionalFieldOf("cacheSeconds", 300).forGetter(SemanticSettings::cacheSeconds),
            ExtraCodecs.POSITIVE_INT.lenientOptionalFieldOf("entriesPerPlayer", 32).forGetter(SemanticSettings::entriesPerPlayer),
            ModeScores.CODEC.lenientOptionalFieldOf("modeScores", ModeScores.DEFAULT).forGetter(SemanticSettings::modeScores)
    ).apply(i, SemanticSettings::new));
    public static final SemanticSettings DEFAULT = new SemanticSettings(Mode.ENFORCE, 240, 0.9, new Threshold(0.9, 0.15), Set.of("zh", "en"), Map.of(), Map.of(), 1024, 300, 32, ModeScores.DEFAULT);

    public SemanticSettings {
        languages = Set.copyOf(languages);
        lexicalThresholds = Map.copyOf(lexicalThresholds);
        Map<String, Map<String, List<String>>> copied = new LinkedHashMap<>();
        examples.forEach((module, localized) -> {
            Map<String, List<String>> texts = new LinkedHashMap<>();
            localized.forEach((language, values) -> texts.put(language, List.copyOf(values)));
            copied.put(module, Map.copyOf(texts));
        });
        examples = Map.copyOf(copied);
    }

    /**
     * Similarity and module margin are evidence, not calibrated probabilities.
     */
    public record Threshold(double score, double margin) {
        public static final Codec<Threshold> CODEC = RecordCodecBuilder.create(i -> i.group(
                UNIT_INTERVAL.lenientOptionalFieldOf("score", 0.9).forGetter(Threshold::score),
                UNIT_INTERVAL.lenientOptionalFieldOf("margin", 0.15).forGetter(Threshold::margin)
        ).apply(i, Threshold::new));
    }

    public Threshold threshold(String moduleId, String language) {
        return this.lexicalThresholds.getOrDefault(moduleId + "@" + language, this.lexicalThresholds.getOrDefault(moduleId, this.defaultThreshold));
    }

    public Threshold threshold(String moduleId, String language, boolean freeChat) {
        Threshold base = this.threshold(moduleId, language);
        double offset = freeChat ? this.modeScores.freeChat() : this.modeScores.functions();
        return new Threshold(Math.clamp(base.score() + offset, 0, 1), base.margin());
    }

    public record ModeScores(double functions, double freeChat) {
        public static final ModeScores DEFAULT = new ModeScores(-0.1, 0.03);
        public static final Codec<ModeScores> CODEC = RecordCodecBuilder.create(i -> i.group(
                Codec.doubleRange(-1, 1).lenientOptionalFieldOf("functions", -0.1).forGetter(ModeScores::functions),
                Codec.doubleRange(-1, 1).lenientOptionalFieldOf("freeChat", 0.03).forGetter(ModeScores::freeChat)
        ).apply(i, ModeScores::new));
    }

    public enum Mode implements StringRepresentable {
        OFF, SHADOW, ENFORCE;
        public static final Codec<Mode> CODEC = StringRepresentable.fromValues(Mode::values);

        @Override
        public @NonNull String getSerializedName() {
            return this.name().toLowerCase(Locale.ROOT);
        }
    }
}
