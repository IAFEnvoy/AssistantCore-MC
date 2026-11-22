package com.iafenvoy.assistantcore.execution;

/**
 * Counts the work of one request. The caller owns policy, scheduling and persistence.
 */
public final class ExecutionBudget {
    private int models;
    private int tools;
    private int steps;
    private int writes;
    private int tokens;

    public record Usage(int models, int tools, int steps, int writes, int tokens) {
    }

    public void restore(Usage usage) {
        this.models = usage.models();
        this.tools = usage.tools();
        this.steps = usage.steps();
        this.writes = usage.writes();
        this.tokens = usage.tokens();
    }

    public Usage usage() {
        return new Usage(this.models, this.tools, this.steps, this.writes, this.tokens);
    }

    public boolean step(int maxSteps) {
        return ++this.steps <= maxSteps;
    }

    public boolean canCallModel(int maxCalls) {
        return this.models < maxCalls;
    }

    public boolean fitsModelInput(long inputBytes, int maxInputBytes, int outputTokens, int maxTotalTokens) {
        return inputBytes <= maxInputBytes && this.tokens + inputBytes + outputTokens <= maxTotalTokens;
    }

    public void reserveModel(int inputBytes, int outputTokens) {
        this.models++;
        this.tokens += inputBytes + outputTokens;
    }

    public void settleModel(int reportedTokens, int inputBytes, int outputTokens) {
        long difference = (long) reportedTokens - inputBytes - outputTokens;
        this.tokens = (int) Math.min(Integer.MAX_VALUE, this.tokens + Math.max(0L, difference));
    }

    public boolean tool(int maxSteps, int maxTools, boolean write, int maxWrites) {
        return this.step(maxSteps) && ++this.tools <= maxTools && (!write || ++this.writes <= maxWrites);
    }
}
