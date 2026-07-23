package com.github.gradusnikov.eclipse.assistai.mcp.services;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import org.eclipse.debug.core.ILaunch;

import com.github.gradusnikov.eclipse.assistai.mcp.services.UnitTestService.TestResult;
import com.github.gradusnikov.eclipse.assistai.mcp.services.UnitTestService.TestRunResult;

/**
 * Represents an asynchronous test run session.
 * Holds incremental results, console output, and state.
 */
public class TestRunSession
{
    public enum State
    {
        RUNNING, COMPLETED, FAILED, CANCELLED
    }

    private final String runId;
    private final String description;
    private final long startTime;
    private volatile State state;
    private volatile ILaunch launch;
    private volatile TestRunResult testRunResult;
    private volatile String coverageInfo;
    private volatile String errorMessage;

    // Console output lines captured from the test JVM
    private final CopyOnWriteArrayList<String> consoleLines = new CopyOnWriteArrayList<>();

    // Coverage-related fields
    private final boolean withCoverage;
    private final String projectName;

    // Tracks how many times this session has been polled, for auto-backoff
    private final AtomicInteger pollCount = new AtomicInteger( 0 );

    public TestRunSession( String description, boolean withCoverage, String projectName )
    {
        this.runId = UUID.randomUUID().toString();
        this.description = description;
        this.startTime = System.currentTimeMillis();
        this.state = State.RUNNING;
        this.withCoverage = withCoverage;
        this.projectName = projectName;
    }

    public String getRunId()
    {
        return runId;
    }

    public String getDescription()
    {
        return description;
    }

    public long getStartTime()
    {
        return startTime;
    }

    public State getState()
    {
        return state;
    }

    public void setState( State state )
    {
        this.state = state;
    }

    public ILaunch getLaunch()
    {
        return launch;
    }

    public void setLaunch( ILaunch launch )
    {
        this.launch = launch;
    }

    public TestRunResult getTestRunResult()
    {
        return testRunResult;
    }

    public void setTestRunResult( TestRunResult testRunResult )
    {
        this.testRunResult = testRunResult;
    }

    public String getCoverageInfo()
    {
        return coverageInfo;
    }

    public void setCoverageInfo( String coverageInfo )
    {
        this.coverageInfo = coverageInfo;
    }

    public String getErrorMessage()
    {
        return errorMessage;
    }

    public void setErrorMessage( String errorMessage )
    {
        this.errorMessage = errorMessage;
    }

    public boolean isWithCoverage()
    {
        return withCoverage;
    }

    public String getProjectName()
    {
        return projectName;
    }

    public void appendConsoleLine( String line )
    {
        consoleLines.add( line );
    }

    public List<String> getConsoleLines()
    {
        return Collections.unmodifiableList( consoleLines );
    }

    public int getConsoleLineCount()
    {
        return consoleLines.size();
    }

    /**
     * Returns a subset of console lines using offset and limit.
     * @param offset 0-based line offset
     * @param limit maximum number of lines to return
     */
    public List<String> getConsoleLines( int offset, int limit )
    {
        int size = consoleLines.size();
        if ( offset >= size )
        {
            return Collections.emptyList();
        }
        int end = Math.min( offset + limit, size );
        return new ArrayList<>( consoleLines.subList( offset, end ) );
    }

    /**
     * Returns the number of seconds the caller should wait before the next poll,
     * based on an auto-increasing backoff schedule. The count is incremented on
     * every call, regardless of whether an explicit wait was used.
     * Schedule: 2, 3, 5, 5, 10, 15, 15, 15, ... (capped at 15s)
     */
    public int nextAutoPollWaitSeconds()
    {
        int[] schedule = { 2, 3, 5, 5, 10, 15 };
        int idx = pollCount.getAndIncrement();
        return schedule[Math.min( idx, schedule.length - 1 )];
    }

    public long getElapsedMillis()
    {
        return System.currentTimeMillis() - startTime;
    }

    public String getElapsedFormatted()
    {
        long elapsed = getElapsedMillis();
        long seconds = elapsed / 1000;
        if ( seconds < 60 )
        {
            return seconds + "s";
        }
        return ( seconds / 60 ) + "m " + ( seconds % 60 ) + "s";
    }
}
