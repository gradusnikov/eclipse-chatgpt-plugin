package com.github.gradusnikov.eclipse.assistai.mcp.results;

import java.util.List;

/**
 * The outcome of a Maven build run the way the IDE's Run As &gt; Maven build runs it:
 * through m2e's Maven launch configuration, in its own JVM, with the whole Maven log
 * going to a console.
 * <p>
 * The build used to run inside the IDE through the m2e embedder, and the only thing
 * that reached the console was a handful of lines a plug-in listener chose to print -
 * no [INFO] log, no compiler output, no test output, no reactor summary. Now the log is
 * Maven's own, and this response carries the parts of it a caller acts on: every
 * distinct {@code [ERROR]} line, and the tail of the log where Maven puts the verdict.
 *
 * @param status whether the build ran, and how it ended
 * @param projectName the Eclipse project the build was run for
 * @param pomDirectory the workspace path of the directory holding the pom that was
 *            built, or null when no build was attempted
 * @param launchName the saved launch configuration's name. The console holding the full
 *            log carries this name, so {@code getConsoleOutput(consoleName=launchName)}
 *            reads it; the configuration itself can be rerun from the IDE
 * @param mavenCommand the command line Maven was given, in {@code mvn} syntax, so a
 *            caller can see how its arguments were understood
 * @param exitCode Maven's exit code, or null when there is none to report - the build is
 *            still running, or never started
 * @param timedOut whether the wait ran out with the build still running. Distinct from
 *            an exit code that happens to be missing
 * @param durationMillis wall clock spent in this call, launch included
 * @param errorLines every distinct {@code [ERROR]} line Maven printed, in order, with
 *            the marker removed; cut at {@link #MAX_ERROR_LINES}
 * @param errorLinesTruncated whether {@code errorLines} is the whole list
 * @param output the last {@link OutputTail#MAX_LINES} lines of the log - the reactor
 *            summary and the BUILD SUCCESS or BUILD FAILURE verdict live there. The whole
 *            log is in the console named {@code launchName}
 */
public record MavenBuildResponse(
    BuildStatus status,
    String projectName,
    String pomDirectory,
    String launchName,
    String mavenCommand,
    Integer exitCode,
    boolean timedOut,
    long durationMillis,
    List<String> errorLines,
    boolean errorLinesTruncated,
    OutputTail output,
    List<Diagnostic> diagnostics,
    String summaryText
)
{
    /** A build that fails in fifty distinct ways has said enough by then. */
    public static final int MAX_ERROR_LINES = 50;

    /** How the build ended, or why it did not. */
    public enum BuildStatus
    {
        /** Maven exited with code 0. */
        SUCCESS,
        /** Maven exited with a non-zero code; see {@link #errorLines()} and {@link #output()}. */
        FAILURE,
        /** The build was still running when the wait ran out; the launch carries on. */
        RUNNING,
        /** The operation was cancelled and the build terminated with it. */
        CANCELLED,
        /** Nothing was launched - see diagnostics. */
        FAILED_TO_START
    }

    /**
     * The end of the log.
     *
     * @param text the last lines, joined with '\n'
     * @param totalLines how many lines the build wrote in all, so a caller knows how
     *            much lies before the tail
     * @param truncated whether lines before {@code text} were left out
     */
    public record OutputTail(
        String text,
        int totalLines,
        boolean truncated
    )
    {
        /** Lines kept from the end of the log. */
        public static final int MAX_LINES = 200;

        public static final OutputTail EMPTY = new OutputTail( "", 0, false );
    }

    /** Whether Maven ran to completion and said it succeeded. Not serialized. */
    public boolean succeeded()
    {
        return status == BuildStatus.SUCCESS;
    }

    /** Nothing was launched: a missing project, no pom, no Maven launcher installed. */
    public static MavenBuildResponse notStarted( String projectName, String pomDirectory, String mavenCommand,
            Diagnostic diagnostic, long durationMillis )
    {
        return new MavenBuildResponse( BuildStatus.FAILED_TO_START, projectName, pomDirectory, null, mavenCommand,
                null, false, durationMillis, List.of(), false, OutputTail.EMPTY, List.of( diagnostic ),
                diagnostic.message() );
    }

    /** The wait ran out with the build still going. */
    public static MavenBuildResponse running( String projectName, String pomDirectory, String launchName,
            String mavenCommand, long durationMillis, List<String> errorLines, boolean errorLinesTruncated,
            OutputTail output )
    {
        Diagnostic diagnostic = Diagnostic.retryable( DiagnosticCode.OPERATION_TIMED_OUT,
                "The build was still running after " + ( durationMillis / 1000 ) + "s; it carries on. Follow it with "
                        + "getConsoleOutput(consoleName=\"" + launchName + "\") or stop it with cancelOperation." );
        return new MavenBuildResponse( BuildStatus.RUNNING, projectName, pomDirectory, launchName, mavenCommand,
                null, true, durationMillis, errorLines, errorLinesTruncated, output, List.of( diagnostic ),
                diagnostic.message() );
    }

    /** The operation was cancelled; the launch was terminated with it. */
    public static MavenBuildResponse cancelled( String projectName, String pomDirectory, String launchName,
            String mavenCommand, long durationMillis, List<String> errorLines, boolean errorLinesTruncated,
            OutputTail output )
    {
        return new MavenBuildResponse( BuildStatus.CANCELLED, projectName, pomDirectory, launchName, mavenCommand,
                null, false, durationMillis, errorLines, errorLinesTruncated, output, Diagnostic.none(),
                "The build was cancelled after " + ( durationMillis / 1000 ) + "s." );
    }

    /**
     * Maven exited.
     *
     * @param exitCode what the JVM reported, or null when the debug plug-in recorded none
     * @param reportedSuccess whether the log carried BUILD SUCCESS - the tie-breaker when
     *            there is no exit code to go by
     */
    public static MavenBuildResponse finished( String projectName, String pomDirectory, String launchName,
            String mavenCommand, Integer exitCode, boolean reportedSuccess, long durationMillis,
            List<String> errorLines, boolean errorLinesTruncated, OutputTail output )
    {
        boolean success = exitCode != null ? exitCode == 0 : reportedSuccess;
        BuildStatus status = success ? BuildStatus.SUCCESS : BuildStatus.FAILURE;

        StringBuilder summary = new StringBuilder( success ? "BUILD SUCCESS" : "BUILD FAILURE" );
        if ( exitCode != null && !success )
        {
            summary.append( " (exit code " ).append( exitCode ).append( ')' );
        }
        summary.append( " after " ).append( durationMillis / 1000 ).append( "s: " ).append( mavenCommand );
        if ( !success && !errorLines.isEmpty() )
        {
            summary.append( ". First error: " ).append( errorLines.get( 0 ) );
        }
        return new MavenBuildResponse( status, projectName, pomDirectory, launchName, mavenCommand, exitCode, false,
                durationMillis, errorLines, errorLinesTruncated, output, Diagnostic.none(), summary.toString() );
    }
}
