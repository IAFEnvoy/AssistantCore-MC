package com.iafenvoy.assistant.routing;

import com.iafenvoy.assistant.AssistantCore;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.resources.FileToIdConverter;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.AddServerReloadListenersEvent;
import net.neoforged.neoforge.resource.ListenerKey;
import org.jspecify.annotations.NonNull;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Reloadable datapack synonyms and concept weights, scoped to a host's routing entry point. */
@EventBusSubscriber(modid = AssistantCore.MOD_ID)
public final class RoutingLexicon extends SimpleJsonResourceReloadListener<RoutingLexicon.Entry> {
    private static final String DIRECTORY = AssistantCore.MOD_ID + "/routing_lexicon";
    private static final ListenerKey<RoutingLexicon> KEY =
            ListenerKey.create(Identifier.fromNamespaceAndPath(AssistantCore.MOD_ID, "routing_lexicon"));
    private Map<Identifier, Vocabulary> scopes = Map.of();

    private RoutingLexicon() {
        super(Entry.CODEC, FileToIdConverter.json(DIRECTORY));
    }

    @SubscribeEvent
    public static void register(AddServerReloadListenersEvent event) {
        event.addRetainedListener(KEY, new RoutingLexicon());
    }

    public static Vocabulary forScope(MinecraftServer server, Identifier scope) {
        return server.getServerResources().managers().getListener(KEY).scopes.getOrDefault(scope, Vocabulary.EMPTY);
    }

    @Override
    protected void apply(Map<Identifier, Entry> entries, @NonNull ResourceManager manager, @NonNull ProfilerFiller profiler) {
        Map<Identifier, Map<String, LanguageWords>> scopes = new HashMap<>();
        entries.entrySet().stream().sorted(Map.Entry.comparingByKey()).map(Map.Entry::getValue).forEach(entry ->
                scopes.computeIfAbsent(entry.scope(), unused -> new HashMap<>())
                        .computeIfAbsent(entry.language(), unused -> new LanguageWords()).add(entry));
        Map<Identifier, Vocabulary> vocabularies = new HashMap<>();
        scopes.forEach((scope, languages) -> {
            Map<String, Map<String, List<String>>> concepts = new HashMap<>();
            Map<String, Map<String, Double>> weights = new HashMap<>();
            languages.forEach((language, words) -> {
                concepts.put(language, words.concepts());
                weights.put(language, Map.copyOf(words.weights));
            });
            vocabularies.put(scope, new Vocabulary(Map.copyOf(concepts), Map.copyOf(weights)));
        });
        this.scopes = Map.copyOf(vocabularies);
    }

    public record Vocabulary(Map<String, Map<String, List<String>>> concepts, Map<String, Map<String, Double>> weights) {
        private static final Vocabulary EMPTY = new Vocabulary(Map.of(), Map.of());
    }

    record Entry(Identifier scope, String language, Map<String, List<String>> concepts, Map<String, Double> weights) {
        private static final Codec<String> ALIAS = Codec.string(1, 80).validate(alias -> LexicalIndex.normalize(alias).isEmpty()
                ? DataResult.error(() -> "Routing alias is blank") : DataResult.success(alias));
        private static final Codec<String> CONCEPT = Codec.string(1, 64).validate(concept -> concept.matches("[a-z0-9_.-]+")
                ? DataResult.success(concept) : DataResult.error(() -> "Invalid routing concept " + concept));
        private static final Codec<Entry> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Identifier.CODEC.fieldOf("scope").forGetter(Entry::scope),
                Codec.string(1, 16).fieldOf("language").forGetter(Entry::language),
                Codec.unboundedMap(CONCEPT, ALIAS.listOf(1, 128)).optionalFieldOf("concepts", Map.of()).forGetter(Entry::concepts),
                Codec.unboundedMap(CONCEPT, Codec.doubleRange(0.1, 5.0)).optionalFieldOf("weights", Map.of()).forGetter(Entry::weights)
        ).apply(instance, Entry::new));
    }

    private static final class LanguageWords {
        private final Map<String, String> aliases = new LinkedHashMap<>();
        private final Map<String, Double> weights = new HashMap<>();

        private void add(Entry entry) {
            entry.concepts().entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(concept ->
                    concept.getValue().forEach(alias -> this.aliases.put(LexicalIndex.normalize(alias), concept.getKey())));
            this.weights.putAll(entry.weights());
        }

        private Map<String, List<String>> concepts() {
            Map<String, List<String>> concepts = new HashMap<>();
            this.aliases.forEach((alias, concept) -> concepts.computeIfAbsent(concept, unused -> new ArrayList<>()).add(alias));
            concepts.replaceAll((unused, aliases) -> List.copyOf(aliases));
            return Map.copyOf(concepts);
        }
    }
}
