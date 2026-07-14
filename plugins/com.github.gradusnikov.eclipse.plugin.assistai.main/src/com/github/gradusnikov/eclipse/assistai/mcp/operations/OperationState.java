package com.github.gradusnikov.eclipse.assistai.mcp.operations;

/**
 * Lifecycle of an {@link Operation}.
 */
public enum OperationState
{
    RUNNING,
    COMPLETED,
    FAILED,
    CANCELLED;

    public boolean isTerminal()
    {
        return this != RUNNING;
    }
}
