package com.github.gradusnikov.eclipse.assistai.mcp.operations;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.NullProgressMonitor;

/**
 * A single execution of a tool declared with
 * {@code @Tool( longExecution = true )}.
 * <p>
 * The tool body itself stays synchronous. This object is what lets its execution
 * outlive the MCP call that started it: it holds the running future, the output
 * produced so far, a progress line, and the hooks needed to abort the underlying
 * work. Callers reach it by id through {@code getOperationStatus} and
 * {@code cancelOperation}.
 */
public class Operation
{
    private final String id;

    private final String toolName;

    private final String label;

    private final long startedNanos = System.nanoTime();

    private final long startedWallMillis = System.currentTimeMillis();

    private final OperationOutputBuffer output = new OperationOutputBuffer();

    private final List<Runnable> cancelHooks = new CopyOnWriteArrayList<>();

    private final List<Runnable> completionHooks = new CopyOnWriteArrayList<>();

    private final IProgressMonitor monitor = new OperationMonitor();

    private volatile CompletableFuture<Object> future;

    private volatile OperationState state = OperationState.RUNNING;

    private volatile String progress;

    private volatile String consoleHint;

    /**
     * Typed incremental results produced while the operation is running.
     * <p>
     * A {@code LinkedHashMap} from a caller-defined type key (e.g. {@code "summary"},
     * {@code "results"}) to a human-readable snapshot of that result type. Tools
     * update individual keys as work progresses; callers may request specific keys
     * or all keys via {@code getOperationStatus(includeResults=...)}. {@code null}
     * means no domain results have been published yet.
     * <p>
     * Access is guarded by the map's own monitor.
     */
    private final Map<String, String> intermediateResults = new LinkedHashMap<>();

    /**
     * Auto-backoff poll sequence index used when the caller omits {@code waitSeconds}.
     * Increments on each poll; capped at the end of the backoff table.
     */
    private final AtomicInteger pollCount = new AtomicInteger( 0 );

    /** Backoff wait seconds: 2, 3, 5, 10, 15, 15, 15, … */
    private static final int[] POLL_BACKOFF_SECONDS = { 2, 3, 5, 10, 15 };

    private volatile Object result;

    private volatile Throwable failure;

    private volatile long finishedNanos;

    private volatile boolean cancelRequested;

    private volatile Thread workerThread;

    Operation( String id, String toolName, String label )
    {
        this.id = id;
        this.toolName = toolName;
        this.label = label;
    }

    public String getId()
    {
        return id;
    }

    public String getToolName()
    {
        return toolName;
    }

    /** Human readable description of what this operation is working on, may be null. */
    public String getLabel()
    {
        return label;
    }

    public OperationState getState()
    {
        return state;
    }

    public boolean isTerminal()
    {
        return state.isTerminal();
    }

    public OperationOutputBuffer output()
    {
        return output;
    }

    /**
     * A progress monitor tied to this operation's cancellation. Long running code
     * must pass this instead of a {@link NullProgressMonitor}, which can never be
     * cancelled: that is the difference between an operation that can be aborted
     * and one that parks a thread forever. Task names reported through it become
     * this operation's progress line.
     */
    public IProgressMonitor monitor()
    {
        return monitor;
    }

    public String getProgress()
    {
        return progress;
    }

    public void setProgress( String progress )
    {
        this.progress = progress;
    }

    /** Name of an Eclipse console carrying this operation's output, if any. */
    public String getConsoleHint()
    {
        return consoleHint;
    }

    public void setConsoleHint( String consoleHint )
    {
        this.consoleHint = consoleHint;
    }

    /**
     * Returns an unmodifiable snapshot of all typed intermediate results published
     * so far, keyed by result type (e.g. {@code "summary"}, {@code "results"}).
     * Returns an empty map when no results have been published yet.
     */
    public Map<String, String> getIntermediateResults()
    {
        synchronized ( intermediateResults )
        {
            return Collections.unmodifiableMap( new LinkedHashMap<>( intermediateResults ) );
        }
    }

    /**
     * Returns the intermediate result for a specific type key, or {@code null} if
     * that key has not been published yet.
     */
    public String getIntermediateResult( String type )
    {
        synchronized ( intermediateResults )
        {
            return intermediateResults.get( type );
        }
    }

    /**
     * Publishes or updates a typed intermediate result snapshot.
     * <p>
     * Tools call this after each logical unit of work to surface structured
     * feedback while the operation is still running. Each call overwrites the
     * previous value for the given {@code type}.
     *
     * @param type  short label for the result type (e.g. {@code "summary"},
     *              {@code "results"})
     * @param value human-readable snapshot, or {@code null} to remove the key
     */
    public void setIntermediateResult( String type, String value )
    {
        synchronized ( intermediateResults )
        {
            if ( value == null )
            {
                intermediateResults.remove( type );
            }
            else
            {
                intermediateResults.put( type, value );
            }
        }
    }

    /**
     * Returns the wait time in seconds recommended for the next poll, based on an
     * auto-increasing backoff sequence (2 → 3 → 5 → 10 → 15, then capped at 15).
     * Each call advances the internal counter regardless of whether the operation
     * is still running, so callers should only invoke this once per poll cycle.
     */
    public int nextAutoPollWaitSeconds()
    {
        int idx = Math.min( pollCount.getAndIncrement(), POLL_BACKOFF_SECONDS.length - 1 );
        return POLL_BACKOFF_SECONDS[idx];
    }

    /**
     * Registers work to run when the operation is cancelled - terminating a launched
     * JVM, cancelling an Eclipse job. Interrupting the tool thread alone does not
     * stop a process that the tool merely started.
     */
    public void addCancelHook( Runnable hook )
    {
        cancelHooks.add( hook );
    }

    /**
     * Registers cleanup to run once the operation reaches a terminal state, however
     * it got there - detaching stream listeners, removing debug event listeners.
     */
    public void addCompletionHook( Runnable hook )
    {
        completionHooks.add( hook );
        if ( isTerminal() )
        {
            runQuietly( hook );
        }
    }

    /** Wall clock time this operation has been running, or ran for. */
    public double elapsedSeconds()
    {
        long end = finishedNanos > 0 ? finishedNanos : System.nanoTime();
        return ( end - startedNanos ) / 1_000_000_000.0;
    }

    /** Wall clock start, for callers that need to correlate with files on disk. */
    public long getStartedWallMillis()
    {
        return startedWallMillis;
    }

    public String getResultText()
    {
        return Optional.ofNullable( result ).map( Object::toString ).orElse( "" );
    }

    public Throwable getFailure()
    {
        return failure;
    }

    public boolean isCancelRequested()
    {
        return cancelRequested;
    }

    public CompletableFuture<Object> getFuture()
    {
        return future;
    }

    void setFuture( CompletableFuture<Object> future )
    {
        this.future = future;
        if ( cancelRequested )
        {
            // Cancelled before the future was attached.
            future.cancel( true );
        }
        future.whenComplete( ( value, error ) -> complete( value, error ) );
    }

    /**
     * Aborts the operation: runs the cancel hooks (which stop the real work) and
     * interrupts the tool thread.
     */
    public void cancel()
    {
        cancelRequested = true;
        cancelHooks.forEach( this::runQuietly );

        // CompletableFuture.cancel( true ) does NOT interrupt the running thread - its
        // contract says interrupts are not used - so a tool blocked in await() or sleep()
        // would carry on as a zombie. Interrupting the worker ourselves is what actually
        // stops it.
        Thread worker = workerThread;
        if ( worker != null )
        {
            worker.interrupt();
        }

        CompletableFuture<Object> current = future;
        if ( current != null )
        {
            current.cancel( true );
        }
    }

    /**
     * Binds the thread running the tool body, so cancellation can interrupt it.
     */
    public void attachWorkerThread( Thread thread )
    {
        this.workerThread = thread;
        if ( cancelRequested && thread != null )
        {
            thread.interrupt();
        }
    }

    private void complete( Object value, Throwable error )
    {
        finishedNanos = System.nanoTime();
        output.flush();
        if ( cancelRequested )
        {
            state = OperationState.CANCELLED;
        }
        else if ( error != null )
        {
            failure = error;
            state = OperationState.FAILED;
        }
        else
        {
            result = value;
            state = OperationState.COMPLETED;
        }
        completionHooks.forEach( this::runQuietly );
    }

    /**
     * Hooks are best effort cleanup and must never derail the operation's own
     * bookkeeping, so a failing one is recorded in the output rather than thrown.
     */
    private void runQuietly( Runnable hook )
    {
        try
        {
            hook.run();
        }
        catch ( Exception e )
        {
            output.append( "operation hook failed: " + e, true );
        }
    }

    private void setProgressIfPresent( String name )
    {
        if ( name != null && !name.isBlank() )
        {
            progress = name;
        }
    }

    /**
     * Reports cancellation to code that polls {@link IProgressMonitor#isCanceled()},
     * which is how JDT searches, refactorings and builds become interruptible.
     */
    private final class OperationMonitor extends NullProgressMonitor
    {
        @Override
        public boolean isCanceled()
        {
            return cancelRequested || Thread.currentThread().isInterrupted();
        }

        @Override
        public void setCanceled( boolean value )
        {
            if ( value )
            {
                cancelRequested = true;
            }
        }

        @Override
        public void beginTask( String name, int totalWork )
        {
            setProgressIfPresent( name );
        }

        @Override
        public void setTaskName( String name )
        {
            setProgressIfPresent( name );
        }

        @Override
        public void subTask( String name )
        {
            setProgressIfPresent( name );
        }
    }
}
