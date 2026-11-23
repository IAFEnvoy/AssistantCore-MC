package com.iafenvoy.assistant.routing;

import com.iafenvoy.assistant.AssistantException;
import com.iafenvoy.assistant.AssistantException.Reason;
import com.iafenvoy.assistant.action.AgentAction;
import com.iafenvoy.assistant.chat.ConversationStrategy;
import com.iafenvoy.assistant.module.ModuleRegistry;
import com.mojang.serialization.Codec;
import net.minecraft.resources.Identifier;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * Selects an entry point only; registered modules own syntax, examples and workflow execution.
 */
public final class HybridRouter<C extends HybridRouter.Context, R> implements AutoCloseable {
    private final ModuleRegistry<C, R> modules;
    private final SemanticRouter semantic;
    private final AssistedRoute<C> model;
    private final Predicate<RuntimeException> localFallback;

    public HybridRouter(ModuleRegistry<C, R> modules, SemanticRouter semantic,
                        AssistedRoute<C> model, Predicate<RuntimeException> localFallback) {
        this.modules = modules;
        this.semantic = semantic;
        this.model = model;
        this.localFallback = localFallback;
    }

    public void start() {
        this.modules.freeze();
        this.semantic.start(this.modules.profiles());
    }

    public RouteDecision route(C context, AgentAction input, String text) throws Exception {
        // Card actions retain their own authority; only plain text follows the selected conversation strategy.
        if (input != null) return this.direct(context, input, RouteSource.STRUCTURED);
        if (text.isBlank()) throw new AssistantException(Reason.INVALID_INPUT);
        return context.mode().route(new Routes(context, text));
    }

    private final class Routes implements ConversationStrategy.Routes {
        private final C context;
        private final String text;
        private List<ModelRoute.Candidate> hints = List.of();

        private Routes(C context, String text) {
            this.context = context;
            this.text = text;
        }

        @Override
        public RouteDecision local() {
            AgentAction command = HybridRouter.this.modules.commands().parse(this.text, this.context::available);
            if (command != null) return HybridRouter.this.direct(this.context, command, RouteSource.COMMAND);
            AgentAction explicit = HybridRouter.this.modules.explicit(this.text, this.context::available);
            if (explicit != null) return HybridRouter.this.direct(this.context, explicit, RouteSource.COMMAND);
            SemanticRouter.Attempt attempt;
            try {
                attempt = HybridRouter.this.semantic.match(this.context, this.text);
            } catch (Exception | LinkageError | AssertionError error) {
                this.context.ensureMemoryCurrent();
                attempt = SemanticRouter.Attempt.fallback("SEMANTIC_ERROR");
            }
            if (attempt.decision() != null && this.context.available(Identifier.tryParse(attempt.decision().moduleId()))
                    && HybridRouter.this.modules.semanticSelectable(Identifier.tryParse(attempt.decision().moduleId())))
                return attempt.decision();
            this.hints = attempt.hints().stream()
                    .filter(candidate -> HybridRouter.this.modules.modelSelectable(Identifier.tryParse(candidate.moduleId()))
                            && this.context.available(Identifier.tryParse(candidate.moduleId()))).toList();
            return this.fallback();
        }

        @Override
        public RouteDecision assisted() {
            if (!this.context.externalServicesAllowed()) return this.fallback();
            // The decoder accepts exactly the advertised catalogue, including confirmation-only exclusions.
            try {
                ModelRoute route = HybridRouter.this.model.route(this.context, this.text, this.hints,
                        HybridRouter.this.modules.capabilities(this.context::available),
                        HybridRouter.this.modules.modelCodec(this.context::available));
                return new RouteDecision(route.call(), RouteSource.LLM,
                        route.call() == null ? HybridRouter.this.modules.suggestions(this.text, this.hints, this.context::available) : List.of());
            } catch (RuntimeException error) {
                // Missing credentials or opt-out only disable model fallback, not registered local capabilities.
                if (!HybridRouter.this.localFallback.test(error)) throw error;
                this.context.ensureMemoryCurrent();
                return this.fallback();
            }
        }

        private RouteDecision fallback() {
            return new RouteDecision(null, RouteSource.LOCAL_FALLBACK, HybridRouter.this.modules.suggestions(this.text, this.hints, this.context::available));
        }

        @Override
        public RouteDecision chat() {
            return HybridRouter.this.direct(this.context, this.context.chatAction(this.text), RouteSource.FREE_CHAT);
        }
    }

    private RouteDecision direct(C context, AgentAction input, RouteSource source) {
        if (!context.available(input.id())) throw new AssistantException(Reason.CAPABILITY_UNAVAILABLE);
        return new RouteDecision(input, source);
    }

    public void remember(C context, RouteDecision decision, String text) {
        if (!this.modules.semanticSelectable(Identifier.tryParse(decision.moduleId()))) return;
        try {
            this.semantic.remember(context, decision, text);
        } catch (Exception ignored) { /* An optional cache must not change a completed read result. */ }
    }

    public interface Context extends SemanticRouter.Context {
        boolean externalServicesAllowed();

        AgentAction chatAction(String text);
    }

    @FunctionalInterface
    public interface AssistedRoute<C> {
        ModelRoute route(C context, String text, List<ModelRoute.Candidate> hints,
                         Map<String, ModelRoute.Capability> capabilities, Codec<ModelRoute> codec);
    }

    public void clear(UUID player) {
        this.semantic.clear(player);
    }

    public RoutingDiagnostics diagnostics() {
        return this.semantic.diagnostics();
    }

    @Override
    public void close() {
        this.semantic.close();
    }
}
