package com.iafenvoy.assistant.routing;

import com.iafenvoy.assistant.AssistantCore;
import com.mojang.serialization.Codec;
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
import java.util.List;
import java.util.Map;

@EventBusSubscriber(modid = AssistantCore.MOD_ID)
public final class RoutingGrammar extends SimpleJsonResourceReloadListener<RoutingGrammar.Entry> {
    private static final Identifier ID = Identifier.fromNamespaceAndPath(AssistantCore.MOD_ID, "routing_grammar");
    private static final ListenerKey<RoutingGrammar> KEY = ListenerKey.create(ID);
    private static final DirectiveGrammar EMPTY = new DirectiveGrammar(List.of());
    private Map<Key, DirectiveGrammar> grammars = Map.of();

    private RoutingGrammar() {
        super(Entry.CODEC, FileToIdConverter.json(ID.getNamespace() + "/" + ID.getPath()));
    }

    @SubscribeEvent
    public static void register(AddServerReloadListenersEvent event) {
        event.addRetainedListener(KEY, new RoutingGrammar());
    }

    public static DirectiveGrammar forScope(MinecraftServer server, Identifier scope, String language) {
        return server.getServerResources().managers().getListener(KEY).grammars.getOrDefault(new Key(scope, language), EMPTY);
    }

    @Override
    protected void apply(Map<Identifier, Entry> entries, @NonNull ResourceManager manager, @NonNull ProfilerFiller profiler) {
        Map<Key, List<DirectiveGrammar.Phrase>> phrases = new HashMap<>();
        entries.entrySet().stream().sorted(Map.Entry.comparingByKey()).map(Map.Entry::getValue).forEach(entry ->
                phrases.computeIfAbsent(new Key(entry.scope(), entry.language()), unused -> new ArrayList<>()).addAll(entry.phrases()));
        Map<Key, DirectiveGrammar> compiled = new HashMap<>();
        phrases.forEach((key, rules) -> compiled.put(key, new DirectiveGrammar(rules)));
        this.grammars = Map.copyOf(compiled);
    }

    private record Key(Identifier scope, String language) {
    }

    record Entry(Identifier scope, String language, List<DirectiveGrammar.Phrase> phrases) {
        private static final Codec<Entry> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Identifier.CODEC.fieldOf("scope").forGetter(Entry::scope),
                Codec.string(1, 16).fieldOf("language").forGetter(Entry::language),
                DirectiveGrammar.Phrase.CODEC.listOf(1, 256).fieldOf("phrases").forGetter(Entry::phrases)
        ).apply(instance, Entry::new));
    }
}
