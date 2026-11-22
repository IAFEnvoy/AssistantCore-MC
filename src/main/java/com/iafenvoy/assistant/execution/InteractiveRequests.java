package com.iafenvoy.assistant.execution;

import com.iafenvoy.assistant.AssistantException;
import com.iafenvoy.assistant.AssistantException.Reason;
import com.iafenvoy.assistant.action.AgentAction;
import com.iafenvoy.assistant.util.CodecIO;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Serializes interactive work per player and exposes cancellation to the host's storage and execution callbacks.
 */
public final class InteractiveRequests implements AutoCloseable {
    private final TaskExecutor tasks;
    private final long queueTimeoutMillis;
    private final Map<UUID, Request> active = new ConcurrentHashMap<>();
    private final AtomicBoolean closed = new AtomicBoolean();

    public InteractiveRequests(String name, int workers, int capacity, long queueTimeoutMillis) {
        this.tasks = new TaskExecutor(name, workers, capacity);
        this.queueTimeoutMillis = queueTimeoutMillis;
    }

    public <R> CompletableFuture<R> submit(UUID player, Work<R> work) {
        if (this.closed.get()) return CompletableFuture.failedFuture(new AssistantException(Reason.CANCELLED));
        Request request = new Request(System.currentTimeMillis());
        if (this.active.putIfAbsent(player, request) != null)
            return CompletableFuture.failedFuture(new AssistantException(Reason.BUSY));
        return this.tasks.submit(() -> {
            if (System.currentTimeMillis() - request.received() > this.queueTimeoutMillis)
                throw new AssistantException(Reason.BUSY);
            request.check();
            return work.run(request);
        }).whenComplete((_, _) -> this.active.remove(player, request));
    }

    public void cancel(UUID player) {
        Request request = this.active.get(player);
        if (request != null) request.cancel();
    }

    @Override
    public void close() {
        if (!this.closed.compareAndSet(false, true)) return;
        this.active.values().forEach(Request::cancel);
        this.tasks.close();
    }

    public static String fingerprint(String requestId, AgentAction action, String text) {
        UUID.fromString(requestId);
        String encoded = action == null ? "{}" : CodecIO.write(AgentAction.CODEC, action);
        if (text == null || text.codePointCount(0, text.length()) > 2000 || text.getBytes(StandardCharsets.UTF_8).length > 8192
                || encoded.length() > 4096)
            throw new AssistantException(Reason.INVALID_INPUT);
        String id = action == null ? "" : action.id().toString();
        try {
            byte[] bytes = (id + "\n" + encoded + "\n" + text).getBytes(StandardCharsets.UTF_8);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException(error);
        }
    }

    public static <R> R replay(String id, String fingerprint, Map<String, String> inputFingerprints, Map<String, R> results) {
        if (inputFingerprints.containsKey(id) && !fingerprint.equals(inputFingerprints.get(id)))
            throw new AssistantException(Reason.INVALID_INPUT);
        return results.get(id);
    }

    @FunctionalInterface
    public interface Work<R> {
        R run(Request request);
    }

    public static final class Request {
        private final long received;
        private volatile boolean cancelled;
        private Runnable cancelExecution;

        private Request(long received) {
            this.received = received;
        }

        public long received() {
            return this.received;
        }

        public boolean cancelled() {
            return this.cancelled;
        }

        public void check() {
            if (this.cancelled()) throw new AssistantException(Reason.CANCELLED);
        }

        public synchronized void onCancel(Runnable cancelExecution) {
            this.cancelExecution = cancelExecution;
            if (this.cancelled) cancelExecution.run();
        }

        private synchronized void cancel() {
            this.cancelled = true;
            if (this.cancelExecution != null) this.cancelExecution.run();
        }
    }
}
