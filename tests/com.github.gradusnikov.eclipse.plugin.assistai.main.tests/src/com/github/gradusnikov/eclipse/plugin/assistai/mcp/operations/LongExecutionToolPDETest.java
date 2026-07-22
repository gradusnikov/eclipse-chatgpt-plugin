package com.github.gradusnikov.eclipse.plugin.assistai.mcp.operations;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.Test;

import com.github.gradusnikov.eclipse.assistai.mcp.ToolExecutor;
import com.github.gradusnikov.eclipse.assistai.mcp.annotations.McpServer;
import com.github.gradusnikov.eclipse.assistai.mcp.annotations.Tool;
import com.github.gradusnikov.eclipse.assistai.mcp.operations.Operation;
import com.github.gradusnikov.eclipse.assistai.mcp.operations.OperationContext;
import com.github.gradusnikov.eclipse.assistai.mcp.operations.OperationRegistry;
import com.github.gradusnikov.eclipse.assistai.mcp.operations.OperationState;

/**
 * The point of a long execution tool is that a slow call is no longer lost: when the
 * caller's inline wait runs out it gets a handle instead of an error, the work keeps
 * going, and the result is still there when it comes back. These tests pin that down.
 */
public class LongExecutionToolPDETest
{
    private final OperationRegistry registry = new OperationRegistry();

    @McpServer( name = "test-server" )
    public static class SlowServer
    {
        final CountDownLatch release = new CountDownLatch( 1 );

        final AtomicBoolean sawOperation = new AtomicBoolean();

        final AtomicBoolean interrupted = new AtomicBoolean();

        @Tool( name = "slowTool", description = "Blocks until released.", longExecution = true )
        public String slowTool()
        {
            sawOperation.set( OperationContext.current().isPresent() );
            OperationContext.current().ifPresent( op -> op.setProgress( "working" ) );
            try
            {
                release.await();
            }
            catch ( InterruptedException e )
            {
                interrupted.set( true );
                Thread.currentThread().interrupt();
                return "cancelled";
            }
            return "slow result";
        }

        @Tool( name = "fastTool", description = "Returns at once." )
        public String fastTool()
        {
            return "fast result";
        }
    }

    @McpServer( name = "failing-server" )
    public static class FailingServer
    {
        @Tool( name = "boom", description = "Always fails.", longExecution = true )
        public String boom()
        {
            throw new IllegalStateException( "kaboom" );
        }
    }

    private Operation start( SlowServer server, String toolName )
    {
        ToolExecutor executor = new ToolExecutor( server );
        Operation operation = registry.register( toolName, "label" );
        CompletableFuture<Object> future = executor.call( toolName, Map.of(), operation );
        registry.attachFuture( operation, future );
        return operation;
    }

    @Test
    public void handsBackAnOperationIdWhenTheInlineWaitElapses() throws Exception
    {
        SlowServer server = new SlowServer();
        Operation operation = start( server, "slowTool" );

        String handOff = (String) registry.awaitOrHandOff( operation, 1 );

        // The old behaviour was an error and a lost run. Now the caller gets a handle.
        assertTrue( handOff.contains( "operationId: " + operation.getId() ), handOff );
        assertTrue( handOff.contains( "Still running" ), handOff );
        assertTrue( handOff.contains( "getOperationStatus" ), handOff );
        assertFalse( handOff.contains( "Error" ), handOff );

        // ...and the work really is still going, not abandoned.
        assertEquals( OperationState.RUNNING, operation.getState() );

        server.release.countDown();
        operation.getFuture().get( 10, TimeUnit.SECONDS );

        String status = registry.getOperationStatus( operation.getId(), 0, 0, 5 );
        assertTrue( status.contains( "COMPLETED" ), status );
        assertTrue( status.contains( "slow result" ), status );
    }

    @Test
    public void returnsTheResultInlineWhenItFinishesInTime() throws Exception
    {
        SlowServer server = new SlowServer();
        server.release.countDown();
        Operation operation = start( server, "slowTool" );

        // A fast enough run still answers in a single call - no extra round trip.
        assertEquals( "slow result", registry.awaitOrHandOff( operation, 10 ) );
    }

    @Test
    public void anInlineWaitOfZeroHandsBackTheIdImmediately()
    {
        SlowServer server = new SlowServer();
        Operation operation = start( server, "slowTool" );

        String handOff = (String) registry.awaitOrHandOff( operation, 0 );
        assertTrue( handOff.contains( "operationId: " + operation.getId() ), handOff );

        server.release.countDown();
    }

    @Test
    public void bindsTheOperationToTheToolThread() throws Exception
    {
        SlowServer server = new SlowServer();
        Operation operation = start( server, "slowTool" );

        // The service must be able to reach its operation without being passed it.
        registry.awaitOrHandOff( operation, 1 );
        assertTrue( server.sawOperation.get() );
        assertEquals( "working", operation.getProgress() );

        server.release.countDown();
        operation.getFuture().get( 10, TimeUnit.SECONDS );
    }

    @Test
    public void cancelStopsTheWorkAndReportsIt() throws Exception
    {
        SlowServer server = new SlowServer();
        Operation operation = start( server, "slowTool" );
        registry.awaitOrHandOff( operation, 1 );

        AtomicBoolean hookRan = new AtomicBoolean();
        operation.addCancelHook( () -> hookRan.set( true ) );

        String cancelled = registry.cancelOperation( operation.getId() );
        assertTrue( cancelled.contains( operation.getId() ), cancelled );

        // The hook is what stops the real work (a launched JVM, a job); interrupting the
        // tool thread alone would not.
        assertTrue( hookRan.get() );
        waitFor( () -> operation.getState() == OperationState.CANCELLED );
        assertTrue( server.interrupted.get() );

        String status = registry.getOperationStatus( operation.getId(), 0, 0, 0 );
        assertTrue( status.contains( "CANCELLED" ), status );
    }

    @Test
    public void reportsAnUnknownOperationInsteadOfFailing()
    {
        String status = registry.getOperationStatus( "op-does-not-exist", 0, 0, 0 );
        assertTrue( status.contains( "no operation with id" ), status );
        assertTrue( status.contains( "listOperations" ), status );
    }

    @Test
    public void listsOperations()
    {
        SlowServer server = new SlowServer();
        Operation operation = start( server, "slowTool" );

        String listed = registry.listOperations();
        assertTrue( listed.contains( operation.getId() ), listed );
        assertTrue( listed.contains( "slowTool" ), listed );

        server.release.countDown();
    }

    @Test
    public void aFailingToolIsReportedAsFailedRatherThanLost() throws Exception
    {
        ToolExecutor executor = new ToolExecutor( new FailingServer() );
        Operation operation = registry.register( "boom", "label" );
        registry.attachFuture( operation, executor.call( "boom", Map.of(), operation ) );

        String result = (String) registry.awaitOrHandOff( operation, 10 );
        assertTrue( result.contains( "kaboom" ), result );
        waitFor( () -> operation.getState() == OperationState.FAILED );
    }

    private void waitFor( java.util.function.BooleanSupplier condition ) throws InterruptedException
    {
        long deadline = System.currentTimeMillis() + 10_000;
        while ( System.currentTimeMillis() < deadline )
        {
            if ( condition.getAsBoolean() )
            {
                return;
            }
            Thread.sleep( 50 );
        }
        throw new AssertionError( "Condition not met within 10s" );
    }
}
