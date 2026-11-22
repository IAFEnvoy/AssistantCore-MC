package com.iafenvoy.assistant.routing;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Bounded caller-scoped exact intent cache. Never stores text, arguments or business results.
 */
public final class RouteCache<P> implements AutoCloseable {
    public record Limits(int entries, int entriesPerOwner, int ttlSeconds) {
        public Limits {
            if (entries <= 0 || entriesPerOwner <= 0 || ttlSeconds <= 0)
                throw new IllegalArgumentException("Route cache limits must be positive");
        }
    }

    public record Scope<P>(P owner, long incarnation, long epoch) {
    }

    public record Key<P>(Scope<P> scope, String language, String digest) {
    }

    private record Entry(String moduleId, long expiresNanos) {
    }

    private final LinkedHashMap<P, Scope<P>> scopes = new LinkedHashMap<>(16, 0.75f, true);
    private final LinkedHashMap<Key<P>, Entry> entries = new LinkedHashMap<>(16, 0.75f, true);
    private final Supplier<Limits> limits;
    private boolean closed;

    public RouteCache(Supplier<Limits> limits) {
        this.limits = Objects.requireNonNull(limits);
    }

    public synchronized Scope<P> scope(P player, long incarnation, long epoch) {
        // Revoke and memory changes replace the scope; late results cannot repopulate the old cache.
        Scope<P> scope = this.scopes.get(player);
        if (this.closed) return new Scope<>(player, incarnation, epoch);
        if (scope == null || scope.incarnation != incarnation || scope.epoch != epoch) {
            this.clear(player);
            while (this.scopes.size() >= this.limits.get().entries())
                this.clear(this.scopes.firstEntry().getKey());
            scope = new Scope<>(player, incarnation, epoch);
            this.scopes.put(player, scope);
        }
        return scope;
    }

    public Key<P> key(Scope<P> scope, String language, String text) throws NoSuchAlgorithmException {
        return new Key<>(scope, language, HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(text.getBytes(StandardCharsets.UTF_8))));
    }

    public synchronized String get(Key<P> key) {
        if (this.closed || this.scopes.get(key.scope().owner) != key.scope()) return null;
        Entry entry = this.entries.get(key);
        if (entry != null && System.nanoTime() - entry.expiresNanos() >= 0) {
            this.entries.remove(key);
            return null;
        }
        return entry == null ? null : entry.moduleId();
    }

    public synchronized void put(Key<P> key, String moduleId) {
        if (this.closed || this.scopes.get(key.scope().owner) != key.scope()) return;
        long now = System.nanoTime();
        this.entries.values().removeIf(entry -> now - entry.expiresNanos() >= 0);
        Limits limits = this.limits.get();
        List<Key<P>> owned = this.entries.keySet().stream().filter(value -> value.scope() == key.scope() && !value.equals(key)).toList();
        for (int index = 0; index <= owned.size() - limits.entriesPerOwner(); index++)
            this.entries.remove(owned.get(index));
        this.entries.put(key, new Entry(moduleId, now + limits.ttlSeconds() * 1_000_000_000L));
        while (this.entries.size() > limits.entries()) this.entries.pollFirstEntry();
    }

    public synchronized void clear(P player) {
        this.scopes.remove(player);
        this.entries.keySet().removeIf(key -> key.scope().owner.equals(player));
    }

    public synchronized int size() {
        long now = System.nanoTime();
        this.entries.values().removeIf(entry -> now - entry.expiresNanos() >= 0);
        return this.entries.size();
    }

    @Override
    public synchronized void close() {
        this.closed = true;
        this.scopes.clear();
        this.entries.clear();
    }
}
