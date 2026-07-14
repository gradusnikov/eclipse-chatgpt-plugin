package com.github.gradusnikov.eclipse.assistai.mcp.operations;

import java.util.Optional;
import java.util.function.Supplier;

/**
 * Gives the code running inside a long execution tool access to its own
 * {@link Operation}, without having to thread it through every service signature.
 * <p>
 * The framework binds the operation to the thread that runs the tool body, so a
 * service can publish progress, attach output, or register a cancel hook simply
 * by asking for {@link #current()}. When the same service is called from
 * somewhere other than an MCP tool - a unit test, an agent - there is no
 * operation bound and the service behaves exactly as it always did.
 */
public final class OperationContext
{
    private static final ThreadLocal<Operation> CURRENT = new ThreadLocal<>();

    private OperationContext()
    {
    }

    /** The operation running on this thread, if the caller was invoked as one. */
    public static Optional<Operation> current()
    {
        return Optional.ofNullable( CURRENT.get() );
    }

    /**
     * Runs a tool body with the operation bound to the calling thread. Used by the
     * framework when dispatching a long execution tool; nothing else should bind an
     * operation.
     */
    public static <T> T callWith( Operation operation, Supplier<T> body )
    {
        CURRENT.set( operation );
        try
        {
            return body.get();
        }
        finally
        {
            CURRENT.remove();
        }
    }
}
