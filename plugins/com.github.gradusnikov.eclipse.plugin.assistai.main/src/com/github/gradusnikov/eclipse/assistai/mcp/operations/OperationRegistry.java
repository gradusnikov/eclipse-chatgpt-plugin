package com.github.gradusnikov.eclipse.assistai.mcp.operations;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;

import org.eclipse.e4.core.di.annotations.Creatable;

import jakarta.inject.Singleton;

/**
 * Tracks the long execution tools that are in flight, and the last few that
 * have finished.
 * <p>
 * A tool marked {@code @Tool( longExecution = true )} can easily outlive the
 * MCP client's tool call timeout. Rather than letting the client abandon the
 * work - which leaves it running invisibly and throws its result away - the
 * framework registers it here, hands the caller an operation id, and keeps the
 * result until the caller comes back for it.
 */
@Creatable
@Singleton
public class OperationRegistry
{
    /**
     * Finished operations kept for collection before the oldest is discarded.
     */
    static final int                     MAX_RETAINED = 10;

    private final AtomicLong             sequence     = new AtomicLong();

    private final Map<String, Operation> operations   = new LinkedHashMap<>();

    /**
     * Creates an operation for a tool call that is about to start. The future
     * is attached separately, because the operation has to exist before the
     * work does: the tool body needs to find it through
     * {@link OperationContext}.
     */
    public synchronized Operation register( String toolName, String label )
    {
        evictFinished();
        Operation operation = new Operation( "op-" + sequence.incrementAndGet(), toolName, label );
        operations.put( operation.getId(), operation );
        return operation;
    }

    public void attachFuture( Operation operation, CompletableFuture<Object> future )
    {
        operation.setFuture( future );
    }

    public synchronized Optional<Operation> find( String operationId )
    {
        return Optional.ofNullable( operations.get( operationId ) );
    }

    private synchronized List<Operation> snapshot()
    {
        return new ArrayList<>( operations.values() );
    }

    /**
     * Waits for a running operation, returning its result if it lands in time.
     *
     * @return the tool's own result when it finished within the wait, otherwise
     *         a hand off message naming the operation id
     */
    public Object awaitOrHandOff( Operation operation, int waitSeconds )
    {
        CompletableFuture<Object> future = operation.getFuture();
        if ( future == null )
        {
            return formatHandOff( operation );
        }
        if ( waitSeconds <= 0 )
        {
            return formatHandOff( operation );
        }
        try
        {
            return future.get( waitSeconds, TimeUnit.SECONDS );
        }
        catch ( TimeoutException e )
        {
            return formatHandOff( operation );
        }
        catch ( CancellationException e )
        {
            return formatStatus( operation, 0, 0 );
        }
        catch ( ExecutionException e )
        {
            // Reflection and CompletableFuture bury the real failure under
            // wrappers, so
            // reporting e.getCause() directly yields
            // "InvocationTargetException" instead of
            // what actually went wrong.
            return "Error: " + rootMessage( e );
        }
        catch ( InterruptedException e )
        {
            Thread.currentThread().interrupt();
            return formatHandOff( operation );
        }
    }

    /**
     * The message a caller gets when the tool is still running: it names the
     * handle needed to get back to the work, which is the whole point of the
     * mechanism.
     */
    public String formatHandOff( Operation operation )
    {
        StringBuilder sb = new StringBuilder();
        sb.append( "Still running after " ).append( seconds( operation.elapsedSeconds() ) ).append( "." ).append( "\n" );
        sb.append( "operationId: " ).append( operation.getId() ).append( "\n" );
        sb.append( "tool: " ).append( operation.getToolName() );
        if ( operation.getLabel() != null && !operation.getLabel().isBlank() )
        {
            sb.append( "    target: " ).append( operation.getLabel() );
        }
        sb.append( "\n" );
        if ( operation.getProgress() != null )
        {
            sb.append( "progress: " ).append( operation.getProgress() ).append( "\n" );
        }
        sb.append( "\nThe work was NOT cancelled and its result is being kept.\n" );
        sb.append( "Poll:   getOperationStatus(operationId=\"" ).append( operation.getId() ).append( "\")\n" );
        sb.append( "Output: getOperationStatus(operationId=\"" ).append( operation.getId() ).append( "\", outputLimit=\"100\", outputOffset=\"-100\")\n" );
        sb.append( "Abort:  cancelOperation(operationId=\"" ).append( operation.getId() ).append( "\")" );
        return sb.toString();
    }

    /**
     * Status of one operation, optionally with a page of its output.
     *
     * @param waitSeconds
     *            block up to this long for a running operation to finish before
     *            answering
     */
    public String getOperationStatus( String operationId, int outputOffset, int outputLimit, int waitSeconds )
    {
        Optional<Operation> found = find( operationId );
        if ( found.isEmpty() )
        {
            return "Error: no operation with id '" + operationId + "'. Use listOperations to see the known ones.";
        }
        Operation operation = found.get();

        if ( waitSeconds > 0 && !operation.isTerminal() && operation.getFuture() != null )
        {
            try
            {
                operation.getFuture().get( waitSeconds, TimeUnit.SECONDS );
            }
            catch ( TimeoutException | CancellationException e )
            {
                // fall through and report the current state
            }
            catch ( ExecutionException e )
            {
                // the failure is recorded on the operation itself
            }
            catch ( InterruptedException e )
            {
                Thread.currentThread().interrupt();
            }
        }
        return formatStatus( operation, outputOffset, outputLimit );
    }

    public String formatStatus( Operation operation, int outputOffset, int outputLimit )
    {
        StringBuilder sb = new StringBuilder();
        sb.append( "Operation " ).append( operation.getId() ).append( " [" ).append( operation.getToolName() ).append( "]" );
        if ( operation.getLabel() != null && !operation.getLabel().isBlank() )
        {
            sb.append( "  " ).append( operation.getLabel() );
        }
        sb.append( "\n" );
        sb.append( "State: " ).append( operation.getState() );
        sb.append( "    Elapsed: " ).append( seconds( operation.elapsedSeconds() ) ).append( "\n" );
        if ( !operation.isTerminal() && operation.getProgress() != null )
        {
            sb.append( "Progress: " ).append( operation.getProgress() ).append( "\n" );
        }

        switch ( operation.getState() )
        {
            case COMPLETED -> sb.append( "\n" ).append( operation.getResultText() ).append( "\n" );
            case FAILED -> sb.append( "\nFailed: " ).append( rootMessage( operation.getFailure() ) ).append( "\n" );
            case CANCELLED -> sb.append( "\nCancelled.\n" );
            case RUNNING -> sb.append( "\nStill running. Poll again, or cancelOperation(operationId=\"" ).append( operation.getId() ).append( "\").\n" );
        }

        appendOutput( sb, operation, outputOffset, outputLimit );
        return sb.toString();
    }

    private void appendOutput( StringBuilder sb, Operation operation, int outputOffset, int outputLimit )
    {
        if ( outputLimit < 1 )
        {
            int total = operation.output().totalLines();
            if ( total > 0 )
            {
                sb.append( "\nOutput: " ).append( total )
                        .append( " lines captured. Ask for them with outputLimit (and outputOffset=\"-100\" for the tail).\n" );
            }
            else if ( operation.getConsoleHint() != null )
            {
                sb.append( "\nOutput: see getConsoleOutput(consoleName=\"" ).append( operation.getConsoleHint() ).append( "\").\n" );
            }
            return;
        }

        OperationOutputBuffer.Page page = operation.output().page( outputOffset, outputLimit );
        if ( page.totalLines() == 0 )
        {
            sb.append( "\nOutput: none captured yet.\n" );
            return;
        }
        sb.append( "\nOutput (lines " ).append( page.firstIndex() ).append( "-" ).append( Math.max( page.firstIndex(), page.nextOffset() - 1 ) )
                .append( " of " ).append( page.totalLines() ).append( ")" );
        if ( page.droppedLines() > 0 )
        {
            sb.append( "; " ).append( page.droppedLines() ).append( " earlier lines dropped" );
        }
        sb.append( ":\n" );
        for ( String line : page.lines() )
        {
            sb.append( line ).append( "\n" );
        }
        if ( page.nextOffset() < page.totalLines() )
        {
            sb.append( "nextOffset: " ).append( page.nextOffset() ).append( "\n" );
        }
    }

    public String listOperations()
    {
        List<Operation> all = snapshot();
        if ( all.isEmpty() )
        {
            return "No operations have been started.";
        }
        StringBuilder sb = new StringBuilder();
        sb.append( "Operations (" ).append( all.size() ).append( "):\n" );
        for ( Operation operation : all )
        {
            sb.append( "  " ).append( operation.getId() ).append( "  " ).append( operation.getState() ).append( "  " )
                    .append( seconds( operation.elapsedSeconds() ) ).append( "  " ).append( operation.getToolName() );
            if ( operation.getLabel() != null && !operation.getLabel().isBlank() )
            {
                sb.append( "  " ).append( operation.getLabel() );
            }
            sb.append( "\n" );
        }
        return sb.toString();
    }

    public String cancelOperation( String operationId )
    {
        Optional<Operation> found = find( operationId );
        if ( found.isEmpty() )
        {
            return "Error: no operation with id '" + operationId + "'. Use listOperations to see the known ones.";
        }
        Operation operation = found.get();
        if ( operation.isTerminal() )
        {
            return "Operation " + operationId + " already finished (" + operation.getState() + ").";
        }
        operation.cancel();
        return "Cancellation requested for " + operationId + " (" + operation.getToolName() + ").\n" + "Poll getOperationStatus(operationId=\"" + operationId
                + "\") to confirm it stopped.";
    }

    /**
     * Discards the oldest finished operations. Running ones are never evicted -
     * a caller must always be able to find the work it started, however long it
     * takes.
     */
    private void evictFinished()
    {
        while ( operations.size() >= MAX_RETAINED )
        {
            Optional<String> oldest = operations.entrySet().stream().filter( entry -> entry.getValue().isTerminal() ).map( Map.Entry::getKey ).findFirst();
            if ( oldest.isEmpty() )
            {
                return;
            }
            Operation removed = operations.remove( oldest.get() );
            if ( removed != null )
            {
                removed.output().clear();
            }
        }
    }

    private static String rootMessage( Throwable throwable )
    {
        if ( throwable == null )
        {
            return "unknown error";
        }
        Throwable cause = throwable;
        while ( cause.getCause() != null && cause.getCause() != cause )
        {
            cause = cause.getCause();
        }
        Throwable root = cause;
        return Optional.ofNullable( root.getMessage() ).orElseGet( () -> root.getClass().getSimpleName() );
    }

    private static String seconds( double value )
    {
        return String.format( "%.1fs", value );
    }
}
