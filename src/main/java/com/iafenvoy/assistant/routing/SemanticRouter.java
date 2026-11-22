package com.iafenvoy.assistant.routing;

import com.iafenvoy.assistant.action.AgentAction;
import com.iafenvoy.assistant.chat.ConversationMode;
import com.mojang.logging.LogUtils;
import net.minecraft.resources.Identifier;
import org.slf4j.Logger;

import java.io.InputStream;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Supplier;

/**
 * A lightweight optional accelerator; uncertain requests keep the single-call LLM fallback.
 */
public final class SemanticRouter implements AutoCloseable {
    private static final Logger LOGGER = LogUtils.getLogger();

    public record Attempt(RouteDecision decision, List<ModelRoute.Candidate> hints, String reason) {
        public static Attempt fallback(String reason) {
            return new Attempt(null, List.of(), reason);
        }
    }

    private final RouteCache<UUID> cache;
    private final Supplier<SemanticSettings> settings;
    private final Supplier<InputStream> lexicon;
    private final Map<String, LongAdder> counters = new ConcurrentHashMap<>();
    private final AtomicBoolean started = new AtomicBoolean(), closed = new AtomicBoolean();
    private volatile String state = "NOT_STARTED";
    private volatile long lastMicros;
    private volatile RoutingDiagnostics.Match lastMatch;
    private final AtomicLong totalMicros = new AtomicLong(), maxMicros = new AtomicLong();
    private volatile SemanticIndex index;
    private Map<String, RoutingProfile.Bound> profiles = Map.of();

    public SemanticRouter(Supplier<SemanticSettings> settings, Supplier<InputStream> lexicon) {
        this.settings = settings;
        this.lexicon = lexicon;
        this.cache = new RouteCache<>(() -> {
            SemanticSettings config = this.settings.get();
            return new RouteCache.Limits(config.cacheEntries(), config.entriesPerPlayer(), config.cacheSeconds());
        });
    }

    public synchronized void start(Collection<RoutingProfile.Bound> registrations) {
        if (!this.started.compareAndSet(false, true)) throw new IllegalStateException("Routing already started");
        if (this.closed.get()) return;
        if (this.settings.get().mode() == SemanticSettings.Mode.OFF) {
            this.state = "OFF";
            return;
        }
        this.state = "LOADING";
        try (InputStream stream = this.lexicon.get()) {
            Map<String, RoutingProfile.Bound> registered = new LinkedHashMap<>();
            registrations.forEach(profile -> registered.put(profile.moduleId().toString(), profile));
            this.profiles = Map.copyOf(registered);
            this.index = new SemanticIndex(this.profiles.values(), this.settings.get(), stream);
            this.state = "READY";
            LOGGER.info("Lexical routing ready: {} examples, mode {}", this.index.size(), this.settings.get().mode());
        } catch (Exception error) {
            this.state = "UNAVAILABLE";
            this.index = null;
            LOGGER.warn("Lexical routing unavailable; retaining LLM fallback", error);
        }
    }

    public Attempt match(Context context, String raw) throws Exception {
        long start = System.nanoTime();
        try {
            Attempt attempt = this.attempt(context, raw);
            this.counters.computeIfAbsent(attempt.reason(), ignored -> new LongAdder()).increment();
            return attempt;
        } finally {
            long micros = TimeUnit.NANOSECONDS.toMicros(System.nanoTime() - start);
            this.lastMicros = micros;
            this.totalMicros.addAndGet(micros);
            this.maxMicros.accumulateAndGet(micros, Math::max);
        }
    }

    private Attempt attempt(Context context, String raw) throws Exception {
        context.ensureMemoryCurrent();
        if (this.settings.get().mode() == SemanticSettings.Mode.OFF) return Attempt.fallback("OFF");
        SemanticIndex current = this.index;
        if (current == null || this.closed.get()) return Attempt.fallback("NOT_READY");
        String language = context.language().split("_", 2)[0], text = LexicalIndex.normalize(raw);
        String rejected = this.guard(text, language);
        if (rejected != null) return Attempt.fallback(rejected);
        RouteCache.Key<UUID> key = this.key(context, language, text);
        String cached = key == null ? null : this.cache.get(key);
        if (cached != null && this.settings.get().mode() == SemanticSettings.Mode.ENFORCE) {
            RoutingProfile.Bound profile = this.profiles.get(cached);
            AgentAction input = profile == null || !profile.direct() || !context.available(profile.moduleId()) ? null : profile.arguments().parse(text);
            if (input != null)
                return new Attempt(new RouteDecision(input, RouteSource.ROUTE_CACHE), List.of(), "CACHE_HIT");
        }
        LexicalIndex.Ranking<RoutingProfile.Bound> ranking = current.search(text, language, context::available);
        context.ensureMemoryCurrent();
        LexicalIndex.Match<RoutingProfile.Bound> best = ranking.first();
        if (best == null) return Attempt.fallback("NO_CANDIDATE");
        List<ModelRoute.Candidate> hints = ranking.matches().stream().limit(3)
                .map(match -> new ModelRoute.Candidate(match.value().moduleId().toString(), match.score())).toList();
        SemanticSettings.Threshold threshold = this.settings.get().threshold(best.value().moduleId().toString(), language, context.freeChatAllowed());
        IntentRouter.Decision<AgentAction> local = IntentRouter.decide(ranking, text,
                new IntentRouter.Threshold(threshold.score(), threshold.margin(), this.settings.get().minCoverage()),
                false, RoutingProfile.Bound::direct, (profile, request) -> profile.arguments().parse(request));
        AgentAction input = local.action();
        String reason = local.reason().name();
        this.lastMatch = new RoutingDiagnostics.Match(best.value().moduleId().toString(), language, best.score(), ranking.margin(), best.coverage(), reason);
        if (this.settings.get().mode() == SemanticSettings.Mode.SHADOW) {
            // Shadow mode records local decisions without changing routing or supplying candidate hints.
            this.counters.computeIfAbsent("SHADOW_" + reason, ignored -> new LongAdder()).increment();
            return Attempt.fallback("SHADOW");
        }
        return new Attempt(input == null ? null : new RouteDecision(input, RouteSource.SEMANTIC), hints, reason);
    }

    /**
     * Remember only a validated successful, independently parsed read intent, never model parameters.
     */
    public void remember(Context context, RouteDecision decision, String raw) throws Exception {
        if (this.settings.get().mode() != SemanticSettings.Mode.ENFORCE || this.closed.get() || this.index == null || decision.source() != RouteSource.LLM)
            return;
        RoutingProfile.Bound profile = this.profiles.get(decision.moduleId());
        if (profile == null || !profile.direct() || !context.available(profile.moduleId())) return;
        String language = context.language().split("_", 2)[0], text = LexicalIndex.normalize(raw);
        if (this.guard(text, language) != null) return;
        AgentAction parsed = profile.arguments().parse(text);
        if (parsed == null || !parsed.equals(decision.input())) return;
        // Unknown or unmatched topic content must not be turned into an exact intent shortcut.
        LexicalIndex.Ranking<RoutingProfile.Bound> ranking = this.index.search(text, language, context::available);
        if (ranking.writeRequest() || !ranking.hasTopic() || ranking.matches().stream().noneMatch(match -> match.value().moduleId().toString().equals(decision.moduleId())
                && match.topicsCovered() && match.coverage() + 1e-9 >= this.settings.get().minCoverage()))
            return;
        RouteCache.Key<UUID> key = this.key(context, language, text);
        if (key != null) context.withHistory(() -> {
            this.cache.put(key, decision.moduleId());
            return null;
        });
    }

    private RouteCache.Key<UUID> key(Context context, String language, String text) throws Exception {
        RouteCache.Scope<UUID> scope = context.withHistory(() -> this.cache.scope(context.player(), context.incarnation(), context.memoryEpoch()));
        // A reloaded prompt must not reuse or be repopulated by decisions from the previous revision.
        // Mode and routing configuration changes must not reuse a decision made with another threshold.
        return scope == null ? null : this.cache.key(scope, language, context.promptRevision() + "\n"
                + context.mode() + ":" + this.settings.get().hashCode() + "\n" + text);
    }

    private String guard(String text, String language) {
        SemanticSettings config = this.settings.get();
        IntentRouter.Reason reason = IntentRouter.guard(text, language, config.languages(), config.maxCharacters());
        return reason == null ? null : reason.name();
    }

    public void clear(UUID player) {
        this.cache.clear(player);
    }

    public RoutingDiagnostics diagnostics() {
        Map<String, Long> counts = new LinkedHashMap<>();
        this.counters.forEach((key, count) -> counts.put(key, count.sum()));
        return new RoutingDiagnostics(SemanticSettings.ALGORITHM, this.settings.get().mode().name(), this.state,
                this.index == null ? 0 : this.index.size(), this.cache.size(), Map.copyOf(counts),
                Optional.ofNullable(this.lastMatch), this.lastMicros, this.totalMicros.get(), this.maxMicros.get());
    }

    public interface Context {
        UUID player();

        long incarnation();

        long memoryEpoch();

        String language();

        String promptRevision();

        ConversationMode mode();

        boolean freeChatAllowed();

        boolean available(Identifier id);

        void ensureMemoryCurrent();

        /**
         * Run under the history transaction after checking current authority; return null when history is disabled.
         */
        <T> T withHistory(Supplier<T> work);
    }

    @Override
    public synchronized void close() {
        if (!this.closed.compareAndSet(false, true)) return;
        this.index = null;
        this.profiles = Map.of();
        this.state = "CLOSED";
        this.cache.close();
    }
}
