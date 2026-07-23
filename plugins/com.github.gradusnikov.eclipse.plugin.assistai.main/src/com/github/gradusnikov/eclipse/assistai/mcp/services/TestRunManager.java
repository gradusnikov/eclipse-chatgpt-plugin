package com.github.gradusnikov.eclipse.assistai.mcp.services;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

import org.eclipse.core.runtime.ILog;
import org.eclipse.debug.core.DebugException;
import org.eclipse.debug.core.ILaunch;
import org.eclipse.debug.core.model.IProcess;
import org.eclipse.debug.core.model.IStreamMonitor;
import org.eclipse.debug.core.model.IStreamsProxy;
import org.eclipse.e4.core.di.annotations.Creatable;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;

/**
 * Registry for asynchronous test run sessions.
 * Manages session lifecycle, console capture, and auto-eviction of old results.
 */
@Creatable
@Singleton
public class TestRunManager
{
    private static final int MAX_COMPLETED_SESSIONS = 20;

    @Inject
    private ILog logger;

    private final ConcurrentHashMap<String, TestRunSession> sessions = new ConcurrentHashMap<>();

    /**
     * Registers a new session and returns it.
     */
    public TestRunSession createSession( String description, boolean withCoverage, String projectName )
    {
        evictOldSessions();
        TestRunSession session = new TestRunSession( description, withCoverage, projectName );
        sessions.put( session.getRunId(), session );
        return session;
    }

    /**
     * Retrieves a session by run ID.
     * @return the session, or null if not found
     */
    public TestRunSession getSession( String runId )
    {
        return sessions.get( runId );
    }

    /**
     * Attaches console output listeners to the launch's processes.
     * Should be called after the launch is started.
     */
    public void attachConsoleCapture( TestRunSession session )
    {
        ILaunch launch = session.getLaunch();
        if ( launch == null )
        {
            return;
        }

        for ( IProcess process : launch.getProcesses() )
        {
            IStreamsProxy streamsProxy = process.getStreamsProxy();
            if ( streamsProxy != null )
            {
                attachMonitor( session, streamsProxy.getOutputStreamMonitor() );
                attachMonitor( session, streamsProxy.getErrorStreamMonitor() );
            }
        }
    }

    private void attachMonitor( TestRunSession session, IStreamMonitor monitor )
    {
        if ( monitor == null )
        {
            return;
        }

        // Capture any already-buffered content
        String existing = monitor.getContents();
        if ( existing != null && !existing.isEmpty() )
        {
            for ( String line : existing.split( "\\R" ) )
            {
                session.appendConsoleLine( line );
            }
        }

        // Listen for new output
        monitor.addListener( ( text, m ) -> {
            if ( text != null && !text.isEmpty() )
            {
                for ( String line : text.split( "\\R" ) )
                {
                    if ( !line.isEmpty() )
                    {
                        session.appendConsoleLine( line );
                    }
                }
            }
        } );
    }

    /**
     * Stops a running test by terminating the launch.
     * @return true if successfully cancelled, false if already finished or not found
     */
    public boolean stopTestRun( String runId )
    {
        TestRunSession session = sessions.get( runId );
        if ( session == null )
        {
            return false;
        }
        if ( session.getState() != TestRunSession.State.RUNNING )
        {
            return false;
        }

        ILaunch launch = session.getLaunch();
        if ( launch != null && !launch.isTerminated() )
        {
            try
            {
                launch.terminate();
            }
            catch ( DebugException e )
            {
                logger.error( "Error terminating test run " + runId, e );
                return false;
            }
        }
        session.setState( TestRunSession.State.CANCELLED );
        return true;
    }

    /**
     * Formats a status response for the given session.
     */
    public String formatStatus( TestRunSession session )
    {
        StringBuilder sb = new StringBuilder();
        sb.append( "Run ID: " ).append( session.getRunId() ).append( "\n" );
        sb.append( "Status: " ).append( session.getState() ).append( "\n" );
        sb.append( "Description: " ).append( session.getDescription() ).append( "\n" );
        sb.append( "Elapsed: " ).append( session.getElapsedFormatted() ).append( "\n" );

        var result = session.getTestRunResult();
        if ( result != null )
        {
            sb.append( "Progress: " ).append( result.getTotalCount() ).append( " tests run" );
            sb.append( ", " ).append( result.getPassedCount() ).append( " passed" );
            if ( result.getFailedCount() > 0 )
            {
                sb.append( ", " ).append( result.getFailedCount() ).append( " failed" );
            }
            if ( result.getErrorCount() > 0 )
            {
                sb.append( ", " ).append( result.getErrorCount() ).append( " errors" );
            }
            if ( result.getSkippedCount() > 0 )
            {
                sb.append( ", " ).append( result.getSkippedCount() ).append( " skipped" );
            }
            sb.append( "\n" );
            sb.append( "Total test time: " ).append( String.format( "%.2f", result.getTotalTime() ) ).append( "s\n" );
        }

        if ( session.getState() == TestRunSession.State.RUNNING )
        {
            sb.append( "NOTE: Run is still in progress. Keep polling until Status: COMPLETED.\n" );
        }

        if ( session.getErrorMessage() != null )
        {
            sb.append( "Error: " ).append( session.getErrorMessage() ).append( "\n" );
        }

        return sb.toString();
    }

    /**
     * Formats the full results (per-test details).
     */
    public String formatResults( TestRunSession session )
    {
        var result = session.getTestRunResult();
        if ( result == null )
        {
            if ( session.getState() == TestRunSession.State.FAILED )
            {
                String err = session.getErrorMessage();
                return "Error: " + ( err != null ? err : "Launch failed with no test results" ) + "\n";
            }
            return "No test results available" + (session.getState() == TestRunSession.State.RUNNING ? " yet" : "") + ".\n";
        }
        if ( session.getState() == TestRunSession.State.RUNNING )
        {
            return "[PARTIAL RESULTS - run is still in progress]\n"
                + result.toString();
        }
        return result.toString();
    }

    /**
     * Formats console output with offset/limit.
     */
    public String formatConsole( TestRunSession session, int offset, int limit )
    {
        List<String> lines = session.getConsoleLines( offset, limit );
        if ( lines.isEmpty() )
        {
            return "No console output available (offset=" + offset + ", total lines=" + session.getConsoleLineCount() + ").\n";
        }

        StringBuilder sb = new StringBuilder();
        sb.append( "Console output (lines " ).append( offset + 1 ).append( "-" )
          .append( offset + lines.size() ).append( " of " ).append( session.getConsoleLineCount() ).append( "):\n" );
        for ( String line : lines )
        {
            sb.append( line ).append( "\n" );
        }
        return sb.toString();
    }

    /**
     * Terminates any currently running sessions for the given project and waits
     * for their processes to fully exit. This prevents workspace-lock conflicts
     * when a new PDE test run is started before the previous Eclipse instance has
     * shut down.
     *
     * @param projectName the project whose running sessions should be terminated
     * @param timeoutMs   maximum time to wait per process (milliseconds)
     */
    public void terminateRunningSessionsForProject( String projectName, long timeoutMs )
    {
        for ( TestRunSession session : sessions.values() )
        {
            if ( !projectName.equals( session.getProjectName() ) )
            {
                continue;
            }
            if ( session.getState() != TestRunSession.State.RUNNING )
            {
                continue;
            }
            ILaunch launch = session.getLaunch();
            if ( launch == null || launch.isTerminated() )
            {
                continue;
            }
            try
            {
                launch.terminate();
                logger.log( org.eclipse.core.runtime.Status.info(
                    "Terminated previous PDE test run for project: " + projectName ) );
            }
            catch ( DebugException e )
            {
                logger.error( "Error terminating previous PDE test run for project: " + projectName, e );
                continue;
            }
            // Wait for all processes to actually exit so the workspace lock is released
            long deadline = System.currentTimeMillis() + timeoutMs;
            for ( IProcess process : launch.getProcesses() )
            {
                while ( !process.isTerminated() && System.currentTimeMillis() < deadline )
                {
                    try
                    {
                        Thread.sleep( 100 );
                    }
                    catch ( InterruptedException ie )
                    {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
            session.setState( TestRunSession.State.CANCELLED );
        }
    }

    /**
     * Evicts the oldest completed sessions if we exceed the max.
     */
    private void evictOldSessions()
    {
        List<TestRunSession> completedSessions = new ArrayList<>();
        for ( TestRunSession session : sessions.values() )
        {
            if ( session.getState() != TestRunSession.State.RUNNING )
            {
                completedSessions.add( session );
            }
        }

        if ( completedSessions.size() >= MAX_COMPLETED_SESSIONS )
        {
            completedSessions.sort( Comparator.comparingLong( TestRunSession::getStartTime ) );
            int toRemove = completedSessions.size() - MAX_COMPLETED_SESSIONS + 1;
            for ( int i = 0; i < toRemove; i++ )
            {
                sessions.remove( completedSessions.get( i ).getRunId() );
            }
        }
    }
}
