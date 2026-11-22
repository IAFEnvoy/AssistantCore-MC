package com.iafenvoy.assistant.storage;

import com.iafenvoy.assistant.AgentResult;
import com.mojang.serialization.Dynamic;

import java.util.stream.Stream;

/**
 * Durable execution receipts inside an AgentStore<PlayerData> transaction; the host supplies the storage implementation.
 */
public interface AgentJournal<C> {
    EventTask<C> getTask(String key);

    void assertTaskCount();

    void putTask(String key, EventTask<C> task);

    void setTaskState(String key, State state);

    void completeTask(String key, AgentResult<C> result);

    void updateTaskUsage(String key, int models, int tools, int steps, int writes, int tokens);

    Stream<EventTask<C>> streamTasks();

    Step getOperation(String key);

    void assertOperationCount(String key);

    void putOperation(String key, Step step);

    void completeOperation(String key, Dynamic<?> result);

    boolean hasCompletedOperation(String taskId);
}
