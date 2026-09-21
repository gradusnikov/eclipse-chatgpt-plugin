package com.github.gradusnikov.eclipse.assistai.mcp.results;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IFolder;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IProjectDescription;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.JavaCore;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import com.github.gradusnikov.eclipse.assistai.mcp.McpJson;
import com.github.gradusnikov.eclipse.assistai.mcp.McpOutputSchemas;
import com.github.gradusnikov.eclipse.assistai.mcp.results.TestRunResponse.CoverageResult;
import com.github.gradusnikov.eclipse.assistai.mcp.results.TestRunResponse.RunStatus;
import com.github.gradusnikov.eclipse.assistai.mcp.results.TestRunResponse.SourceLocation;
import com.github.gradusnikov.eclipse.assistai.mcp.results.TestRunResponse.TestStatus;
import com.github.gradusnikov.eclipse.assistai.mcp.results.TestRunResponse.TestSummary;
import com.github.gradusnikov.eclipse.assistai.mcp.services.UnitTestService;
import com.github.gradusnikov.eclipse.assistai.mcp.services.UnitTestService.TestResult;
import com.github.gradusnikov.eclipse.assistai.mcp.services.UnitTestService.TestRunResult;
import com.github.gradusnikov.eclipse.assistai.tools.StackTraces;

/**
 * The response the JUnit tools return, and the collection behind it.
 * <p>
 * Runs under the PDE harness because source-location resolution goes through JDT: the
 * point of the location is that it names a real file in a real project, which cannot be
 * checked without a workspace.
 */
@TestInstance( TestInstance.Lifecycle.PER_CLASS )
public class TestRunResponsePDETest
{
    private static final String TEST_PROJECT = "TestRunResponseFixtureProject";

    private final NullProgressMonitor monitor = new NullProgressMonitor();

    private IProject project;

    private IJavaProject javaProject;

    @BeforeAll
    public void setUp() throws Exception
    {
        project = ResourcesPlugin.getWorkspace().getRoot().getProject( TEST_PROJECT );
        if ( project.exists() )
        {
            project.delete( true, true, monitor );
        }

        IProjectDescription description = project.getWorkspace().newProjectDescription( TEST_PROJECT );
        description.setNatureIds( new String[] { JavaCore.NATURE_ID } );
        project.create( description, monitor );
        project.open( monitor );

        IFolder sourceFolder = project.getFolder( "src" );
        sourceFolder.create( IResource.NONE, true, monitor );
        IFolder packageFolder = sourceFolder.getFolder( "sample" );
        packageFolder.create( IResource.NONE, true, monitor );

        javaProject = JavaCore.create( project );
        javaProject.setRawClasspath(
                new org.eclipse.jdt.core.IClasspathEntry[] {
                        JavaCore.newSourceEntry( sourceFolder.getFullPath() ) },
                project.getFullPath().append( "bin" ), monitor );

        IFile file = packageFolder.getFile( "FixtureTest.java" );
        file.create( new ByteArrayInputStream(
                "package sample; public class FixtureTest { void check() {} }".getBytes( StandardCharsets.UTF_8 ) ),
                true, monitor );
        project.refreshLocal( IResource.DEPTH_INFINITE, monitor );
    }

    @AfterAll
    public void tearDown() throws Exception
    {
        if ( project != null && project.exists() )
        {
            project.delete( true, true, monitor );
        }
    }

    // ---- status ----------------------------------------------------------

    @Test
    public void terminalStatusComesFromTheCountsNotTheCaller()
    {
        assertEquals( RunStatus.COMPLETED,
                TestRunResponse.terminalStatus( new TestSummary( 7, 7, 0, 0, 0 ) ) );
        assertEquals( RunStatus.COMPLETED_WITH_FAILURES,
                TestRunResponse.terminalStatus( new TestSummary( 7, 6, 1, 0, 0 ) ) );
        // An error is not a failed assertion, but it is equally not a pass.
        assertEquals( RunStatus.COMPLETED_WITH_FAILURES,
                TestRunResponse.terminalStatus( new TestSummary( 7, 6, 0, 1, 0 ) ) );
        // Skipping everything is not failing.
        assertEquals( RunStatus.COMPLETED,
                TestRunResponse.terminalStatus( new TestSummary( 3, 0, 0, 0, 3 ) ) );
    }

    @Test
    public void aRunThatNeverStartedIsNotARunInWhichNothingFailed()
    {
        TestRunResponse response = TestRunResponse.notStarted( "P", List.of( "sample.FixtureTest" ),
                Diagnostic.fatal( DiagnosticCode.TEST_CLASS_NOT_FOUND, "Class not found." ), 12 );

        assertEquals( RunStatus.FAILED_TO_START, response.status() );
        assertFalse( response.hasFailures(), "no test failed, because no test ran" );
        assertTrue( response.hasDiagnostics(), "and the caller can see why" );
        assertEquals( DiagnosticCode.TEST_CLASS_NOT_FOUND, response.diagnostics().get( 0 ).code() );
        assertEquals( 0, response.summary().total() );
        assertEquals( List.of( "sample.FixtureTest" ), response.requestedClasses() );
    }

    @Test
    public void runningIsAStatusBecauseResultsArePublishedMidRun()
    {
        TestRunResult accumulator = new TestRunResult( "live" );
        accumulator.addTestResult( passing( "sample.FixtureTest", "a" ) );

        TestRunResponse snapshot = accumulator.snapshot( RunStatus.RUNNING, "P", List.of(), null,
                List.of(), 500 );

        assertEquals( RunStatus.RUNNING, snapshot.status() );
        assertEquals( 1, snapshot.summary().total() );
        assertTrue( snapshot.summaryText().contains( "so far" ), snapshot.summaryText() );
    }

    // ---- the accumulator -------------------------------------------------

    @Test
    public void theAccumulatorStaysMutableAndTheSnapshotDoesNot()
    {
        TestRunResult accumulator = new TestRunResult( "run" );
        accumulator.addTestResult( passing( "sample.FixtureTest", "a" ) );

        TestRunResponse first = accumulator.snapshot( RunStatus.RUNNING, "P", List.of(), null, List.of(), 1 );
        accumulator.addTestResult( failing( "sample.FixtureTest", "b", null ) );
        TestRunResponse second = accumulator.snapshot( RunStatus.RUNNING, "P", List.of(), null, List.of(), 2 );

        // The published snapshot must not change under the caller when the notifier
        // thread carries on writing - that is the whole reason snapshot() exists.
        assertEquals( 1, first.summary().total() );
        assertEquals( 0, first.failedTests().size() );
        assertEquals( 2, second.summary().total() );
        assertEquals( 1, second.failedTests().size() );
    }

    @Test
    public void aSnapshotTakenDuringConcurrentAccumulationIsInternallyConsistent() throws Exception
    {
        TestRunResult accumulator = new TestRunResult( "concurrent" );
        CountDownLatch started = new CountDownLatch( 1 );
        AtomicReference<Throwable> failure = new AtomicReference<>();

        Thread writer = new Thread( () -> {
            try
            {
                started.countDown();
                for ( int i = 0; i < 500; i++ )
                {
                    accumulator.addTestResult( i % 3 == 0
                            ? failing( "sample.FixtureTest", "t" + i, null )
                            : passing( "sample.FixtureTest", "t" + i ) );
                }
            }
            catch ( Throwable t )
            {
                failure.set( t );
            }
        } );
        writer.start();
        started.await( 5, TimeUnit.SECONDS );

        List<TestRunResponse> snapshots = new ArrayList<>();
        for ( int i = 0; i < 200; i++ )
        {
            snapshots.add( accumulator.snapshot( RunStatus.RUNNING, "P", List.of(), null, List.of(), 0 ) );
        }
        writer.join( 30_000 );
        assertNull( failure.get(), String.valueOf( failure.get() ) );

        for ( TestRunResponse snapshot : snapshots )
        {
            TestSummary counts = snapshot.summary();
            assertEquals( counts.total(),
                    counts.passed() + counts.failed() + counts.errors() + counts.skipped(),
                    "the counts in one snapshot must add up, whatever the writer did next" );
            assertEquals( counts.failed(), snapshot.failedTests().size(),
                    "the failure listing must match the count taken with it" );
        }
    }

    @Test
    public void passingTestsAreCountedNotListed()
    {
        TestRunResult accumulator = new TestRunResult( "run" );
        accumulator.addTestResult( passing( "sample.FixtureTest", "a" ) );
        accumulator.addTestResult( passing( "sample.FixtureTest", "b" ) );
        accumulator.addTestResult( failing( "sample.FixtureTest", "c", null ) );
        accumulator.addTestResult( skipped( "sample.FixtureTest", "d" ) );

        TestRunResponse response = accumulator.snapshot( RunStatus.COMPLETED_WITH_FAILURES, "P",
                List.of(), null, List.of(), 100 );

        assertEquals( 4, response.summary().total() );
        assertEquals( 2, response.summary().passed() );
        assertEquals( 1, response.failedTests().size() );
        assertEquals( 1, response.skippedTests().size() );
        // The two passing tests appear nowhere by name: they are the bulk of a large
        // run's payload and the least useful part of it.
        assertFalse( McpJson.toJson( response ).contains( "\"b\"" ), McpJson.toJson( response ) );
    }

    // ---- source location -------------------------------------------------

    @Test
    public void aFailureIsReportedAsProjectAndProjectRelativePath()
    {
        String trace = """
                java.lang.AssertionError: expected 5 but was 4
                \tat org.junit.Assert.fail(Assert.java:89)
                \tat sample.FixtureTest.check(FixtureTest.java:42)
                \tat java.base/java.lang.reflect.Method.invoke(Method.java:565)
                """;

        SourceLocation source = UnitTestService.resolveSourceLocation( javaProject, "sample.FixtureTest", trace );

        assertNotNull( source );
        assertEquals( TEST_PROJECT, source.projectName() );
        assertEquals( "src/sample/FixtureTest.java", source.filePath(),
                "a workspace path would have to be taken apart again before it could be opened" );
        assertEquals( Integer.valueOf( 42 ), source.line(),
                "the frame inside the test class, not the JUnit machinery above it" );
    }

    @Test
    public void aLineIsLeftNullRatherThanGuessedWhenNoFrameNamesTheTestClass()
    {
        String trace = """
                java.lang.AssertionError: boom
                \tat sample.Helper.assertThing(Helper.java:10)
                """;

        SourceLocation source = UnitTestService.resolveSourceLocation( javaProject, "sample.FixtureTest", trace );

        assertNotNull( source, "the file is still known even when the line is not" );
        assertEquals( "src/sample/FixtureTest.java", source.filePath() );
        assertNull( source.line(), "a guessed line sends the caller to code that is not the fault" );
    }

    @Test
    public void aClassOutsideTheProjectResolvesToNothingRatherThanAnInventedPath()
    {
        assertNull( UnitTestService.resolveSourceLocation( javaProject, "sample.NotHere",
                "\tat sample.NotHere.x(NotHere.java:1)" ) );
        assertNull( UnitTestService.resolveSourceLocation( javaProject, null, "" ) );
        assertNull( UnitTestService.resolveSourceLocation( null, "sample.FixtureTest", "" ) );
    }

    // ---- trace handling --------------------------------------------------

    @Test
    public void theTraceKeepsWorkspaceFramesAndCountsTheRest()
    {
        // Which frames are "own code" is decided by JDT: sample.FixtureTest is a compilation
        // unit in the fixture project, org.junit and java.lang are not.
        String trace = """
                org.opentest4j.AssertionFailedError: expected: <201> but was: <500>
                \tat org.junit.jupiter.api.AssertionFailureBuilder.build(AssertionFailureBuilder.java:151)
                \tat org.junit.jupiter.api.Assertions.assertEquals(Assertions.java:145)
                \tat sample.FixtureTest.check(FixtureTest.java:8)
                \tat java.base/java.lang.reflect.Method.invoke(Method.java:565)
                """;

        StackTraces.Abridged abridged = UnitTestService.abridgeTrace( javaProject, trace );

        assertEquals( "org.opentest4j.AssertionFailedError: expected: <201> but was: <500>", abridged.message() );
        assertEquals( """
                ... 2 frames in org.junit.jupiter.api omitted
                sample.FixtureTest.check(FixtureTest.java:8)
                ... 1 frame in java.lang.reflect omitted""", abridged.frames() );
        assertFalse( abridged.truncated() );
    }

    @Test
    public void aTypeOutsideTheWorkspaceIsNotOwnCodeEvenInTheSamePackage()
    {
        String trace = "java.lang.AssertionError: boom\n\tat sample.NotHere.x(NotHere.java:1)\n";

        assertEquals( "... 1 frame in sample omitted", UnitTestService.abridgeTrace( javaProject, trace ).frames() );
    }

    @Test
    public void noTraceIsNullNotAnEmptyString()
    {
        assertEquals( StackTraces.Abridged.NONE, UnitTestService.abridgeTrace( javaProject, null ) );
        assertEquals( StackTraces.Abridged.NONE, UnitTestService.abridgeTrace( javaProject, "   " ) );
    }

    // ---- coverage --------------------------------------------------------

    @Test
    public void coverageDistinguishesNotAskedForFromNotAvailable()
    {
        TestRunResult accumulator = new TestRunResult( "run" );
        accumulator.addTestResult( passing( "sample.FixtureTest", "a" ) );

        TestRunResponse without = accumulator.snapshot( RunStatus.COMPLETED, "P", List.of(), null,
                List.of(), 1 );
        assertNull( without.coverage(), "not asked for" );

        CoverageResult missing = CoverageResult.unavailable();
        assertTrue( missing.requested() );
        assertFalse( missing.available() );
        assertNull( missing.execFilePath() );
    }

    // ---- serialization ---------------------------------------------------

    @Test
    public void theResponseRoundTripsThroughTheWireMapper()
    {
        TestRunResult accumulator = new TestRunResult( "run" );
        accumulator.addTestResult( failing( "sample.FixtureTest", "check",
                new SourceLocation( TEST_PROJECT, "src/sample/FixtureTest.java", 42 ) ) );

        TestRunResponse response = accumulator.snapshot( RunStatus.COMPLETED_WITH_FAILURES, TEST_PROJECT,
                List.of( "sample.FixtureTest" ), CoverageResult.unavailable(),
                List.of( Diagnostic.fatal( DiagnosticCode.COVERAGE_UNAVAILABLE, "no coverage tooling" ) ),
                1260 );

        // No JavaTimeModule is registered on the mapper, so a field it cannot handle
        // would fail here rather than in front of a client.
        Map<String, Object> wire = McpJson.toMap( response );

        assertEquals( "COMPLETED_WITH_FAILURES", wire.get( "status" ) );
        assertEquals( TEST_PROJECT, wire.get( "projectName" ) );
        assertEquals( 1260, ( (Number) wire.get( "durationMillis" ) ).longValue() );

        @SuppressWarnings( "unchecked" )
        List<Map<String, Object>> failures = (List<Map<String, Object>>) wire.get( "failedTests" );
        assertEquals( 1, failures.size() );
        assertEquals( "FAILED", failures.get( 0 ).get( "status" ) );

        @SuppressWarnings( "unchecked" )
        Map<String, Object> source = (Map<String, Object>) failures.get( 0 ).get( "source" );
        assertEquals( "src/sample/FixtureTest.java", source.get( "filePath" ) );
        assertEquals( 42, ( (Number) source.get( "line" ) ).intValue() );
    }

    // ---- advertised schema -----------------------------------------------

    @SuppressWarnings( "unchecked" )
    private static Map<String, Object> properties( Class<?> type )
    {
        Map<String, Object> schema = McpOutputSchemas.forType( type );
        assertNotNull( schema, type.getSimpleName() + " must advertise a schema" );
        return (Map<String, Object>) schema.get( "properties" );
    }

    @SuppressWarnings( "unchecked" )
    private static Map<String, Object> itemsOf( Map<String, Object> properties, String field )
    {
        Map<String, Object> array = (Map<String, Object>) properties.get( field );
        assertEquals( "array", array.get( "type" ), field + " should be an array" );
        return (Map<String, Object>) array.get( "items" );
    }

    @SuppressWarnings( "unchecked" )
    private static Map<String, Object> propertiesOf( Map<String, Object> objectSchema )
    {
        return (Map<String, Object>) objectSchema.get( "properties" );
    }

    @SuppressWarnings( "unchecked" )
    @Test
    public void theSchemaAdvertisesRunningAlongsideTheTerminalStates()
    {
        List<String> statuses =
                (List<String>) ( (Map<String, Object>) properties( TestRunResponse.class ).get( "status" ) ).get( "enum" );

        assertTrue( statuses.contains( "RUNNING" ),
                "results are published mid-run, and a client is told that shape exists: " + statuses );
        assertTrue( statuses.contains( "COMPLETED" ), statuses.toString() );
        assertTrue( statuses.contains( "COMPLETED_WITH_FAILURES" ), statuses.toString() );
        assertTrue( statuses.contains( "FAILED_TO_START" ), statuses.toString() );
        assertTrue( statuses.contains( "TIMED_OUT" ), statuses.toString() );
        assertTrue( statuses.contains( "CANCELLED" ), statuses.toString() );
    }

    @SuppressWarnings( "unchecked" )
    @Test
    public void theSchemaAdvertisesWhatIsNeededToOpenAFailure()
    {
        Map<String, Object> failure = propertiesOf( itemsOf( properties( TestRunResponse.class ), "failedTests" ) );

        assertTrue( failure.containsKey( "className" ) );
        assertTrue( failure.containsKey( "methodName" ) );
        assertTrue( failure.containsKey( "failureTrace" ) );
        assertEquals( "boolean", ( (Map<String, Object>) failure.get( "traceTruncated" ) ).get( "type" ) );

        Map<String, Object> source = propertiesOf( (Map<String, Object>) failure.get( "source" ) );
        assertTrue( source.containsKey( "projectName" ) );
        assertTrue( source.containsKey( "filePath" ) );
        assertEquals( "integer", SchemaTypes.carriedBy( (Map<String, Object>) source.get( "line" ) ) );
    }

    @SuppressWarnings( "unchecked" )
    @Test
    public void theSchemaAdvertisesCountsAndDiagnosticCodes()
    {
        Map<String, Object> fields = properties( TestRunResponse.class );

        Map<String, Object> summary = propertiesOf( (Map<String, Object>) fields.get( "summary" ) );
        assertEquals( "integer", ( (Map<String, Object>) summary.get( "total" ) ).get( "type" ) );
        assertEquals( "integer", ( (Map<String, Object>) summary.get( "passed" ) ).get( "type" ) );
        assertEquals( "integer", ( (Map<String, Object>) summary.get( "failed" ) ).get( "type" ) );
        assertEquals( "integer", ( (Map<String, Object>) summary.get( "errors" ) ).get( "type" ) );
        assertEquals( "integer", ( (Map<String, Object>) summary.get( "skipped" ) ).get( "type" ) );

        Map<String, Object> diagnostic = propertiesOf( itemsOf( fields, "diagnostics" ) );
        List<String> codes = (List<String>) ( (Map<String, Object>) diagnostic.get( "code" ) ).get( "enum" );
        assertTrue( codes.contains( "PROJECT_NOT_FOUND" ), codes.toString() );
        assertTrue( codes.contains( "TEST_CLASS_NOT_FOUND" ), codes.toString() );
        assertTrue( codes.contains( "PDE_LAUNCH_TYPE_MISSING" ), codes.toString() );
        assertTrue( codes.contains( "TEST_RESULTS_NOT_REPORTED" ), codes.toString() );
        assertTrue( codes.contains( "COVERAGE_UNAVAILABLE" ), codes.toString() );
        assertEquals( "boolean", ( (Map<String, Object>) diagnostic.get( "retryable" ) ).get( "type" ) );
    }

    @Test
    public void testClassesAdvertiseWhichRunnerEachOneNeeds()
    {
        Map<String, Object> fields = properties( TestClassesResponse.class );

        Map<String, Object> plain = propertiesOf( itemsOf( fields, "plainTests" ) );
        assertTrue( plain.containsKey( "className" ) );
        assertTrue( plain.containsKey( "filePath" ) );
        assertTrue( plain.containsKey( "likelyRequiresPdeHarness" ) );
        assertTrue( fields.containsKey( "pdeTests" ) );
        assertTrue( fields.containsKey( "namingWarnings" ) );
    }

    @Test
    public void anEmptyProjectListingIsAResultNotAnError()
    {
        TestClassesResponse response = TestClassesResponse.of( "P", List.of(), List.of() );

        assertEquals( 0, response.totalClasses() );
        assertFalse( response.hasNamingWarnings() );
        assertTrue( response.summaryText().contains( "No test classes" ), response.summaryText() );
    }

    // ---- fixtures --------------------------------------------------------

    private static TestResult passing( String className, String name )
    {
        return new TestResult( className, name, TestStatus.PASSED, null, null, false, null, 0.01 );
    }

    private static TestResult failing( String className, String name, SourceLocation source )
    {
        return new TestResult( className, name, TestStatus.FAILED, "expected 5 but was 4",
                "java.lang.AssertionError: expected 5 but was 4", false, source, 0.13 );
    }

    private static TestResult skipped( String className, String name )
    {
        return new TestResult( className, name, TestStatus.SKIPPED, "disabled for now", null, false, null, 0.0 );
    }
}
