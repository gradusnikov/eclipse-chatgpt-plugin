package com.github.gradusnikov.eclipse.assistai.mcp.results;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.eclipse.core.runtime.ILog;
import org.eclipse.e4.core.contexts.ContextInjectionFactory;
import org.eclipse.e4.core.contexts.EclipseContextFactory;
import org.eclipse.e4.core.contexts.IEclipseContext;
import org.eclipse.e4.ui.di.UISynchronize;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.osgi.framework.BundleContext;
import org.osgi.framework.FrameworkUtil;
import org.osgi.util.tracker.ServiceTracker;

import com.github.gradusnikov.eclipse.assistai.mcp.McpOutputSchemas;
import com.github.gradusnikov.eclipse.assistai.mcp.services.JavaLaunchService;

/**
 * The response records the eclipse-runner listing tools advertise.
 * <p>
 * Two things are checked. That the generated schema names the fields a client is told
 * to branch on - above all the project and project-relative path that make a stack
 * frame or a breakpoint openable with the reading tools. And that the states a debugger
 * spends most of its life in - nothing launched, no breakpoints, no matching session,
 * a session that is running rather than suspended - are expressed in fields rather than
 * in a sentence the caller has to read.
 * <p>
 * Nothing here starts a debug session. Whether a JVM is suspended is a property of the
 * machine the tests happen to run on, so the record construction and the schema are
 * what is asserted, never a live stack.
 */
@TestInstance( TestInstance.Lifecycle.PER_CLASS )
public class RunnerResponsesPDETest
{
    private JavaLaunchService service;

    @BeforeAll
    public void setUp()
    {
        BundleContext bundleContext = FrameworkUtil.getBundle( RunnerResponsesPDETest.class ).getBundleContext();

        ServiceTracker<ILog, ILog> logTracker = new ServiceTracker<>( bundleContext, ILog.class, null );
        logTracker.open();
        ILog log = logTracker.getService();

        IEclipseContext context = EclipseContextFactory.getServiceContext( bundleContext );
        context.set( UISynchronize.class, new UISynchronize()
        {
            @Override public void syncExec( Runnable runnable ) { runnable.run(); }
            @Override public void asyncExec( Runnable runnable ) { runnable.run(); }
            @Override protected boolean isUIThread( Thread thread ) { return false; }
            @Override protected void showBusyWhile( Runnable runnable ) { runnable.run(); }
            @Override protected boolean dispatchEvents() { return false; }
        } );
        if ( log != null )
        {
            context.set( ILog.class, log );
        }

        service = ContextInjectionFactory.make( JavaLaunchService.class, context );
    }

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
        assertNotNull( array, field + " should be advertised" );
        assertEquals( "array", array.get( "type" ), field + " should be an array" );
        return SchemaTypes.nonNull( array.get( "items" ) );
    }

    @SuppressWarnings( "unchecked" )
    private static Map<String, Object> propertiesOf( Map<String, Object> objectSchema )
    {
        return SchemaTypes.propertiesOf( objectSchema );
    }

    @SuppressWarnings( "unchecked" )
    private static String jsonType( Map<String, Object> properties, String field )
    {
        Map<String, Object> schema = (Map<String, Object>) properties.get( field );
        assertNotNull( schema, field + " should be advertised, saw " + properties.keySet() );
        return SchemaTypes.carriedBy( schema );
    }

    // ---- active launches -------------------------------------------------

    @Test
    public void activeLaunchesAdvertiseWhatIdentifiesARunningProgram()
    {
        Map<String, Object> launch = propertiesOf( itemsOf( properties( ActiveLaunchesResponse.class ), "launches" ) );

        assertTrue( launch.containsKey( "name" ), launch.keySet().toString() );
        assertTrue( launch.containsKey( "mode" ) );
        assertTrue( launch.containsKey( "mainType" ) );
        assertTrue( launch.containsKey( "projectName" ) );
        assertEquals( "boolean", jsonType( launch, "terminated" ) );
        assertEquals( "integer", jsonType( launch, "pid" ),
                "the pid is what a caller reaches for and must not arrive as text" );
    }

    @Test
    public void activeLaunchesAdvertisePerProcessState()
    {
        Map<String, Object> launch = propertiesOf( itemsOf( properties( ActiveLaunchesResponse.class ), "launches" ) );
        Map<String, Object> process = propertiesOf( itemsOf( launch, "processes" ) );

        assertTrue( process.containsKey( "label" ) );
        assertEquals( "boolean", jsonType( process, "terminated" ),
                "a live launch whose process has exited is the state a caller must notice" );
        assertEquals( "integer", jsonType( process, "pid" ) );
    }

    @Test
    public void nothingRunningIsAnEmptyListAndNotAMessage()
    {
        ActiveLaunchesResponse response = ActiveLaunchesResponse.of( List.of() );

        assertEquals( 0, response.totalLaunches() );
        assertTrue( response.launches().isEmpty() );
        assertNotNull( response.summaryText() );
    }

    @Test
    public void activeLaunchesCountWhatTheyList()
    {
        ActiveLaunchesResponse response = ActiveLaunchesResponse.of( List.of(
                new ActiveLaunchesResponse.ActiveLaunch( "Run Main", "run", false, "com.example.Main", "P",
                        4711L, List.of( new ActiveLaunchesResponse.LaunchProcess( "java", false, 4711L ) ) ),
                new ActiveLaunchesResponse.ActiveLaunch( "Debug Main", "debug", false, "com.example.Main", "P",
                        null, List.of() ) ) );

        assertEquals( 2, response.totalLaunches() );
        assertEquals( "run", response.launches().get( 0 ).mode() );
        assertEquals( 4711L, response.launches().get( 0 ).pid() );
        assertNull( response.launches().get( 1 ).pid(),
                "a launch whose process id was never recorded says so rather than inventing one" );
    }

    // ---- launch configurations -------------------------------------------

    @Test
    public void launchConfigurationsAdvertiseTheNameTheRunnersTake()
    {
        Map<String, Object> configuration =
                propertiesOf( itemsOf( properties( LaunchConfigurationsResponse.class ), "configurations" ) );

        // The name is what launchConfiguration, runJUnitTests and runJUnitPluginTests
        // are given; the type id is what typeFilter matches.
        assertTrue( configuration.containsKey( "name" ), configuration.keySet().toString() );
        assertTrue( configuration.containsKey( "typeId" ) );
        assertTrue( configuration.containsKey( "typeName" ) );
        assertTrue( configuration.containsKey( "projectName" ) );
        assertTrue( configuration.containsKey( "mainClass" ) );
    }

    @Test
    public void anEmptyConfigurationListingEchoesTheFilterThatProducedIt()
    {
        LaunchConfigurationsResponse response = LaunchConfigurationsResponse.of( "junit-plugin", List.of() );

        assertEquals( "junit-plugin", response.typeFilter(),
                "an empty result from a filter is not the same fact as an empty workspace" );
        assertEquals( 0, response.totalConfigurations() );
        assertTrue( response.configurations().isEmpty() );
    }

    @Test
    public void launchConfigurationsCountWhatTheyList()
    {
        LaunchConfigurationsResponse response = LaunchConfigurationsResponse.of( null, List.of(
                new LaunchConfigurationsResponse.LaunchConfigurationInfo( "A",
                        "org.eclipse.jdt.junit.launchconfig", "JUnit", "P", "com.example.ATest" ),
                new LaunchConfigurationsResponse.LaunchConfigurationInfo( "B",
                        "org.eclipse.jdt.launching.localJavaApplication", "Java Application", "P",
                        "com.example.Main" ) ) );

        assertEquals( 2, response.totalConfigurations() );
        assertEquals( 2, response.configurations().size() );
    }

    // ---- breakpoints -----------------------------------------------------

    @Test
    public void breakpointsAdvertiseWhereToOpenThem()
    {
        Map<String, Object> breakpoint =
                propertiesOf( itemsOf( properties( BreakpointsResponse.class ), "breakpoints" ) );

        // A breakpoint the caller cannot open is barely worth listing; the reading
        // tools address files by project and project-relative path.
        assertTrue( breakpoint.containsKey( "projectName" ), breakpoint.keySet().toString() );
        assertTrue( breakpoint.containsKey( "filePath" ) );
        assertTrue( breakpoint.containsKey( "typeName" ) );
        assertEquals( "integer", jsonType( breakpoint, "lineNumber" ) );
    }

    @Test
    public void breakpointsAdvertiseWhatMakesThemFire()
    {
        Map<String, Object> breakpoint =
                propertiesOf( itemsOf( properties( BreakpointsResponse.class ), "breakpoints" ) );

        assertEquals( "boolean", jsonType( breakpoint, "enabled" ) );
        assertTrue( breakpoint.containsKey( "condition" ) );
        assertEquals( "integer", jsonType( breakpoint, "hitCount" ) );
    }

    @Test
    public void noBreakpointsIsAnEmptyListAndNotAMessage()
    {
        BreakpointsResponse response = BreakpointsResponse.of( List.of() );

        assertEquals( 0, response.totalBreakpoints() );
        assertEquals( 0, response.enabledCount() );
        assertTrue( response.breakpoints().isEmpty() );
    }

    @Test
    public void breakpointsCountEnabledSeparatelyFromTheTotal()
    {
        BreakpointsResponse response = BreakpointsResponse.of( List.of(
                new BreakpointsResponse.BreakpointInfo( "P", "src/A.java", "com.example.A", 10,
                        true, null, 0, "org.eclipse.jdt.debug" ),
                new BreakpointsResponse.BreakpointInfo( "P", "src/A.java", "com.example.A", 20,
                        false, "i > 100", 0, "org.eclipse.jdt.debug" ),
                new BreakpointsResponse.BreakpointInfo( "P", "src/B.java", "com.example.B", 5,
                        true, null, 3, "org.eclipse.jdt.debug" ) ) );

        // "Three breakpoints, one of them off" is the thing a caller acts on, and it
        // must survive without counting bracketed words in a listing.
        assertEquals( 3, response.totalBreakpoints() );
        assertEquals( 2, response.enabledCount() );
        assertEquals( "i > 100", response.breakpoints().get( 1 ).condition() );
        assertEquals( 3, response.breakpoints().get( 2 ).hitCount() );
    }

    // ---- stack trace -----------------------------------------------------

    @Test
    public void stackTraceAdvertisesSessionStateAsFields()
    {
        Map<String, Object> fields = properties( StackTraceResponse.class );

        // Three outcomes used to be three sentences: no session, a running session, a
        // real stack. Two booleans separate them now.
        assertEquals( "boolean", jsonType( fields, "sessionFound" ) );
        assertEquals( "boolean", jsonType( fields, "anyThreadSuspended" ) );
        assertEquals( "integer", jsonType( fields, "totalThreads" ) );
    }

    @Test
    public void stackTraceAdvertisesThreadNameAndSuspendedState()
    {
        Map<String, Object> thread = propertiesOf( itemsOf( properties( StackTraceResponse.class ), "threads" ) );

        assertTrue( thread.containsKey( "name" ) );
        assertEquals( "boolean", jsonType( thread, "suspended" ) );
        assertEquals( "integer", jsonType( thread, "totalFrames" ) );
    }

    @Test
    public void stackFramesAdvertiseWhereToOpenThem()
    {
        Map<String, Object> thread = propertiesOf( itemsOf( properties( StackTraceResponse.class ), "threads" ) );
        Map<String, Object> frame = propertiesOf( itemsOf( thread, "frames" ) );

        // A frame is only useful if the caller can open it, which needs the project and
        // the project-relative path, not the debug model's own label.
        assertTrue( frame.containsKey( "projectName" ), frame.keySet().toString() );
        assertTrue( frame.containsKey( "filePath" ) );
        assertEquals( "integer", jsonType( frame, "lineNumber" ) );
        assertTrue( frame.containsKey( "declaringType" ) );
        assertTrue( frame.containsKey( "methodName" ) );
        assertEquals( "boolean", jsonType( frame, "nativeMethod" ) );
        assertEquals( "boolean", jsonType( frame, "synthetic" ) );
    }

    @Test
    public void stackFramesAdvertiseTheTopFrameVariables()
    {
        Map<String, Object> thread = propertiesOf( itemsOf( properties( StackTraceResponse.class ), "threads" ) );
        Map<String, Object> variable = propertiesOf( itemsOf( propertiesOf( itemsOf( thread, "frames" ) ), "variables" ) );

        assertTrue( variable.containsKey( "name" ) );
        assertTrue( variable.containsKey( "typeName" ) );
        assertTrue( variable.containsKey( "value" ) );
    }

    @Test
    public void noMatchingDebugSessionIsAStateAndNotAnError()
    {
        StackTraceResponse response = StackTraceResponse.notFound( "NoSuchApp" );

        assertEquals( "NoSuchApp", response.nameOrClass() );
        assertFalse( response.sessionFound() );
        assertFalse( response.anyThreadSuspended() );
        assertEquals( 0, response.totalThreads() );
        assertTrue( response.threads().isEmpty() );
    }

    @Test
    public void aRunningSessionIsFoundButNotSuspended()
    {
        StackTraceResponse response = StackTraceResponse.of( "Main", "Run Main", "com.example.Main",
                List.of( new StackTraceResponse.ThreadTrace( "main", false, 0, List.of() ) ) );

        // Found-but-running and not-found-at-all are different answers, and the caller
        // must not have to tell them apart by reading prose.
        assertTrue( response.sessionFound() );
        assertFalse( response.anyThreadSuspended() );
        assertEquals( 1, response.totalThreads() );
        assertTrue( response.threads().get( 0 ).frames().isEmpty() );
    }

    @Test
    public void aSuspendedSessionCarriesItsFrames()
    {
        StackTraceResponse.Frame top = new StackTraceResponse.Frame( 0, "com.example.Main", "run",
                "P", "src/com/example/Main.java", 42, false, false,
                List.of( new StackTraceResponse.Variable( "i", "int", "7" ) ) );
        StackTraceResponse.Frame caller = new StackTraceResponse.Frame( 1, "com.example.Main", "main",
                "P", "src/com/example/Main.java", 12, false, false, List.of() );

        StackTraceResponse response = StackTraceResponse.of( "Main", "Debug Main", "com.example.Main",
                List.of( new StackTraceResponse.ThreadTrace( "main", true, 2, List.of( top, caller ) ),
                         new StackTraceResponse.ThreadTrace( "Finalizer", false, 0, List.of() ) ) );

        assertTrue( response.anyThreadSuspended() );
        assertEquals( 2, response.totalThreads() );

        StackTraceResponse.Frame reported = response.threads().get( 0 ).frames().get( 0 );
        assertEquals( "P", reported.projectName() );
        assertEquals( "src/com/example/Main.java", reported.filePath() );
        assertFalse( reported.filePath().startsWith( "/" ),
                "a workspace-absolute path is not what the reading tools take" );
        assertEquals( 42, reported.lineNumber() );
        assertEquals( 1, reported.variables().size() );
        assertTrue( response.threads().get( 0 ).frames().get( 1 ).variables().isEmpty(),
                "variables cost a round trip to the VM, so only the top frame carries them" );
    }

    // ---- against the live workspace --------------------------------------

    @Test
    public void listActiveLaunchesAgreesWithItsOwnCount()
    {
        // Whether anything is running depends on the machine, so only the invariants
        // are asserted: the count matches the listing and the record is well formed.
        ActiveLaunchesResponse response = service.listActiveLaunches();

        assertNotNull( response );
        assertEquals( response.launches().size(), response.totalLaunches() );
        for ( ActiveLaunchesResponse.ActiveLaunch launch : response.launches() )
        {
            assertNotNull( launch.name() );
            assertNotNull( launch.processes() );
        }
    }

    @Test
    public void listBreakpointsNeverReportsAWorkspaceAbsolutePath()
    {
        BreakpointsResponse response = service.listBreakpoints();

        assertNotNull( response );
        assertEquals( response.breakpoints().size(), response.totalBreakpoints() );
        for ( BreakpointsResponse.BreakpointInfo breakpoint : response.breakpoints() )
        {
            if ( breakpoint.filePath() != null )
            {
                assertFalse( breakpoint.filePath().startsWith( "/" ),
                        "filePath must be relative to projectName, saw " + breakpoint.filePath() );
                assertNotNull( breakpoint.projectName(),
                        "a path without a project cannot be opened" );
            }
        }
    }

    @Test
    public void findLaunchConfigurationsAgreesWithItsOwnCount()
    {
        LaunchConfigurationsResponse response = service.listLaunchConfigurations( null );

        assertNotNull( response );
        assertEquals( response.configurations().size(), response.totalConfigurations() );
        for ( LaunchConfigurationsResponse.LaunchConfigurationInfo configuration : response.configurations() )
        {
            assertNotNull( configuration.name() );
            assertNotNull( configuration.typeId() );
        }
    }

    @Test
    public void anUnknownTypeFilterMatchesNothingRatherThanFailing()
    {
        LaunchConfigurationsResponse response =
                service.listLaunchConfigurations( "completely-unknown-type-xyz-12345" );

        assertEquals( 0, response.totalConfigurations() );
        assertTrue( response.configurations().isEmpty() );
        assertEquals( "completely-unknown-type-xyz-12345", response.typeFilter() );
    }

    @Test
    public void getStackTraceOnAnUnknownSessionReportsNotFound()
    {
        StackTraceResponse response = service.getStackTrace( "no-such-launch-xyz-12345" );

        assertFalse( response.sessionFound() );
        assertFalse( response.anyThreadSuspended() );
        assertTrue( response.threads().isEmpty() );
    }
}
