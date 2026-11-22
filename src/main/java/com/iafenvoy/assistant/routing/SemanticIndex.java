package com.iafenvoy.assistant.routing;

import com.iafenvoy.assistant.util.CodecIO;
import com.mojang.serialization.Codec;
import net.minecraft.resources.Identifier;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.Predicate;

/**
 * Builds routing examples and filters shared lexical rankings to available workflows.
 */
final class SemanticIndex {
    private final LexicalIndex<RoutingProfile.Bound> index;

    SemanticIndex(Collection<RoutingProfile.Bound> profiles, SemanticSettings settings, InputStream lexicon) throws IOException {
        List<LexicalIndex.Example<RoutingProfile.Bound>> documents = new ArrayList<>();
        for (RoutingProfile.Bound profile : profiles) {
            Map<String, List<String>> texts = new LinkedHashMap<>(profile.examples());
            settings.examples().getOrDefault(profile.moduleId().toString(), Map.of()).forEach((language, extra) -> {
                List<String> combined = new ArrayList<>(texts.getOrDefault(language, List.of()));
                combined.addAll(extra);
                texts.put(language, combined);
            });
            for (Map.Entry<String, List<String>> language : texts.entrySet())
                if (settings.languages().contains(language.getKey()))
                    for (String text : new LinkedHashSet<>(language.getValue()))
                        documents.add(new LexicalIndex.Example<>(profile, language.getKey(), text));
        }
        this.index = new LexicalIndex<>(documents, readLexicon(lexicon), true);
    }

    private static Map<String, Map<String, List<String>>> readLexicon(InputStream stream) throws IOException {
        if (stream == null) throw new IOException("Missing routing lexicon");
        byte[] bytes = stream.readNBytes(131073);
        if (bytes.length > 131072) throw new IOException("Routing lexicon exceeds byte limit");
        Codec<Map<String, Map<String, List<String>>>> codec = Codec.unboundedMap(Codec.STRING,
                Codec.unboundedMap(Codec.STRING, Codec.STRING.listOf()));
        return CodecIO.parse(codec, new String(bytes, StandardCharsets.UTF_8));
    }

    int size() {
        return this.index.size();
    }

    LexicalIndex.Ranking<RoutingProfile.Bound> search(String text, String language, Predicate<Identifier> available) {
        LexicalIndex.Ranking<RoutingProfile.Bound> ranking = this.index.search(text, language);
        return new LexicalIndex.Ranking<>(ranking.matches().stream()
                .filter(match -> available.test(match.value().moduleId()))
                .sorted(Comparator.comparingDouble(LexicalIndex.Match<RoutingProfile.Bound>::score).reversed().thenComparing(match -> match.value().moduleId().toString()))
                .limit(5).toList(), ranking.hasTopic(), ranking.writeRequest());
    }
}
