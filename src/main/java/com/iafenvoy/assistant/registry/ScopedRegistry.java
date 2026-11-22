package com.iafenvoy.assistant.registry;

import net.minecraft.resources.Identifier;

import java.util.*;
import java.util.function.BiPredicate;
import java.util.function.Predicate;

/**
 * Runtime registrations for one assistant instance. Scoped exports are usable by other
 * workflows while their owner is enabled.
 */
public final class ScopedRegistry<K, V> {
    private final Map<K, Set<ModuleScope>> registrations = new LinkedHashMap<>();
    private final Map<ModuleScope, Map<K, V>> values = new LinkedHashMap<>();
    private final Map<K, Set<ModuleScope>> exports = new LinkedHashMap<>();
    private final BiPredicate<V, V> compatible;
    private boolean frozen;

    public ScopedRegistry(BiPredicate<V, V> compatible) {
        this.compatible = Objects.requireNonNull(compatible);
    }

    public void register(ModuleScope scope, Map<K, V> entries) {
        if (this.frozen) throw new IllegalStateException("Assistant registration has finished");
        Objects.requireNonNull(scope);
        Map<K, V> current = this.values.getOrDefault(scope, Map.of());
        entries.forEach((id, value) -> {
            Objects.requireNonNull(id);
            Objects.requireNonNull(value);
            V previous = this.canonical(id);
            if (previous != null && previous != value
                    && (scope == ModuleScope.Global.INSTANCE || !this.compatible.test(previous, value)))
                throw new IllegalArgumentException("Conflicting registration " + id);
            V existing = current.get(id);
            if (existing != null && existing != value)
                throw new IllegalArgumentException("Conflicting registration " + id + " for " + scope);
        });
        this.values.computeIfAbsent(scope, ignored -> new LinkedHashMap<>()).putAll(entries);
        entries.keySet().forEach(id -> this.registrations.computeIfAbsent(id, ignored -> new LinkedHashSet<>()).add(scope));
    }

    public void registerExported(ModuleScope scope, Map<K, V> entries) {
        this.register(scope, entries);
        if (scope != ModuleScope.Global.INSTANCE)
            entries.keySet().forEach(id -> this.exports.computeIfAbsent(id, ignored -> new LinkedHashSet<>()).add(scope));
    }

    public void freeze() {
        this.frozen = true;
    }

    public V canonical(K id) {
        Set<ModuleScope> scopes = this.registrations.get(id);
        return scopes == null ? null : this.get(scopes.iterator().next(), id);
    }

    public V get(ModuleScope scope, K id) {
        return this.values.getOrDefault(scope, Map.of()).get(id);
    }

    public boolean available(K id, Predicate<Identifier> enabled) {
        if (this.get(ModuleScope.Global.INSTANCE, id) != null) return true;
        Set<ModuleScope> owners = this.exports.get(id);
        if (owners != null) return owners.stream().anyMatch(scope -> scope.available(enabled));
        return this.first(id, enabled) != null;
    }

    public boolean hasScopes(K id, Predicate<ModuleScope> predicate) {
        return this.registrations.getOrDefault(id, Set.of()).stream().anyMatch(predicate);
    }

    public Map.Entry<ModuleScope, V> first(K id, Predicate<Identifier> enabled) {
        for (ModuleScope scope : this.registrations.getOrDefault(id, Set.of()))
            if (scope.available(enabled)) return Map.entry(scope, this.get(scope, id));
        return null;
    }

    public Map.Entry<ModuleScope, V> resolve(K id, ModuleScope scope, Predicate<Identifier> enabled) {
        if (scope != ModuleScope.Global.INSTANCE) {
            V own = this.get(scope, id);
            if (own != null && scope.available(enabled)) return Map.entry(scope, own);
        }
        for (ModuleScope owner : this.exports.getOrDefault(id, Set.of())) {
            if (owner.available(enabled)) return Map.entry(owner, this.get(owner, id));
        }
        V global = this.get(ModuleScope.Global.INSTANCE, id);
        return global == null ? null : Map.entry(ModuleScope.Global.INSTANCE, global);
    }

    public Map<K, Map.Entry<ModuleScope, V>> advertised(Predicate<Identifier> enabled, Predicate<V> select,
                                                        Comparator<K> order) {
        Map<K, Map.Entry<ModuleScope, V>> result = new LinkedHashMap<>();
        this.values.forEach((scope, entries) -> {
            if (scope != ModuleScope.Global.INSTANCE && scope.available(enabled))
                advertise(scope, entries, select, order, result);
        });
        advertise(ModuleScope.Global.INSTANCE, this.values.getOrDefault(ModuleScope.Global.INSTANCE, Map.of()), select, order, result);
        return Collections.unmodifiableMap(result);
    }

    private static <K, V> void advertise(ModuleScope scope, Map<K, V> entries, Predicate<V> select,
                                         Comparator<K> order, Map<K, Map.Entry<ModuleScope, V>> result) {
        entries.entrySet().stream().filter(entry -> select.test(entry.getValue()))
                .sorted(Map.Entry.comparingByKey(order))
                .forEach(entry -> result.putIfAbsent(entry.getKey(), Map.entry(scope, entry.getValue())));
    }
}
