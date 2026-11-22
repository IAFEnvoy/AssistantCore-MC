package com.iafenvoy.assistant.chat;

import com.iafenvoy.assistant.routing.RouteDecision;

/**
 * Strategies select the execution path; shared stages own routing, authorization and fallback data.
 */
public interface ConversationStrategy {
    RouteDecision route(Routes routes) throws Exception;

    boolean usesAi();

    boolean chats();

    interface Routes {
        RouteDecision local();

        RouteDecision assisted() throws Exception;

        RouteDecision chat();
    }
}
