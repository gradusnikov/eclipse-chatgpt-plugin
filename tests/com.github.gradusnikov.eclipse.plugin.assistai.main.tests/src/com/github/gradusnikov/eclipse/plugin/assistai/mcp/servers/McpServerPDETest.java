package com.github.gradusnikov.eclipse.plugin.assistai.mcp.servers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.jobs.IJobChangeEvent;
import org.eclipse.core.runtime.jobs.JobChangeAdapter;
import org.eclipse.pde.core.target.ITargetDefinition;
import org.eclipse.pde.core.target.ITargetHandle;
import org.eclipse.pde.core.target.ITargetPlatformService;
import org.eclipse.pde.core.target.LoadTargetDefinitionJob;
import org.osgi.framework.BundleContext;
import org.osgi.framework.FrameworkUtil;
import org.osgi.framework.ServiceReference;

import org.eclipse.core.runtime.ILog;
import org.eclipse.core.runtime.ILogListener;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.e4.core.contexts.ContextInjectionFactory;
import org.eclipse.e4.core.contexts.EclipseContextFactory;
import org.eclipse.e4.core.contexts.IEclipseContext;
import org.eclipse.e4.ui.di.UISynchronize;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.osgi.framework.Bundle;

import com.github.gradusnikov.eclipse.assistai.mcp.McpJson;
import com.github.gradusnikov.eclipse.assistai.mcp.McpOutputSchemas;
import com.github.gradusnikov.eclipse.assistai.mcp.StructuredToolResult;
import com.github.gradusnikov.eclipse.assistai.mcp.results.ActiveTargetResponse;
import com.github.gradusnikov.eclipse.assistai.mcp.results.ActiveTargetResponse.TargetStatus;
import com.github.gradusnikov.eclipse.assistai.mcp.results.Diagnostic;
import com.github.gradusnikov.eclipse.assistai.mcp.results.DiagnosticCode;
import com.github.gradusnikov.eclipse.assistai.mcp.results.TestRunResponse;
import com.github.gradusnikov.eclipse.assistai.mcp.results.TestRunResponse.RunStatus;
import com.github.gradusnikov.eclipse.assistai.mcp.servers.PDEMcpServer;

/**
 * Tests for PDEMcpServer - focuses on parameter handling and delegation to PDEService.
 * Tests that require a live PDE runtime are skipped via {@code assumeTrue}.
 */
public class McpServerPDETest
{
    private PDEMcpServer server;

    @BeforeEach
    public void setUp()
    {
        IEclipseContext context = EclipseContextFactory.create();

        context.set( ILog.class, new ILog()
        {
            @Override
            public void removeLogListener( ILogListener listener ) {}

            @Override
            public void log( IStatus status )
            {
                System.out.println( status.getMessage() );
                if ( status.getException() != null )
                {
                    status.getException().printStackTrace();
                }
            }

            @Override
            public Bundle getBundle() { return null; }

            @Override
            public void addLogListener( ILogListener listener ) {}
        } );

        context.set( UISynchronize.class, new UISynchronize()
        {
            @Override
            public void syncExec( Runnable runnable ) { runnable.run(); }

            @Override
            public void asyncExec( Runnable runnable ) { runnable.run(); }

            @Override
            protected boolean isUIThread( Thread thread ) { return true; }

            @Override
            protected void showBusyWhile( Runnable runnable ) { runnable.run(); }

            @Override
            protected boolean dispatchEvents() { return false; }
        } );

        server = ContextInjectionFactory.make( PDEMcpServer.class, context );
    }

    @Test
    public void testGetActiveTarget_describesTheTargetInForce()
    {
        ActiveTargetResponse response;
        try
        {
            response = server.getActiveTarget();
        }
        catch ( Exception e )
        {
            assumeTrue( false, "Skipping: PDE runtime not available (" + e.getMessage() + ")" );
            return;
        }

        assertNotNull( response, "getActiveTarget should not return null" );
        assertNotEquals( TargetStatus.FAILED, response.status(),
            () -> String.valueOf( response.diagnostics() ) );
        assertActiveTargetIsSelfConsistent( response );
    }

    @Test
    public void testReloadTarget_noExplicitTargetIsNotAFailure()
    {
        ActiveTargetResponse response;
        try
        {
            response = server.reloadTarget();
        }
        catch ( Exception e )
        {
            assumeTrue( false, "Skipping: PDE runtime not available (" + e.getMessage() + ")" );
            return;
        }

        assertNotNull( response, "reloadTarget should not return null" );
        // The point of the conversion: reload used to call "no target file is set" an
        // error while getActiveTarget called the identical state normal. Whatever this
        // workspace is, the two now agree on how to describe it.
        assertEquals( server.getActiveTarget().explicitTarget(), response.explicitTarget() );
        assertActiveTargetIsSelfConsistent( response );
        if ( response.status() == TargetStatus.ACTIVE )
        {
            // A reload that completed has resolved the target. Reading a fresh definition
            // off the handle reported it unresolved anyway, which made every successful
            // reload look like a failed one.
            assertTrue( response.resolved(), "a completed reload must report the target as resolved" );
            assertNotNull( response.bundleCount(), "a resolved target has a bundle count" );
        }
    }

    /**
     * The real case: a .target file in the workspace, loaded, read back, and reloaded.
     * All three must describe it as resolved, because it is - the load job resolved it.
     * Reading a fresh definition off the handle instead said it was not, so a reload
     * that succeeded was indistinguishable from one that never happened.
     */
    @Test
    public void aLoadedWorkspaceTargetFileIsReportedAsResolved() throws Exception
    {
        ITargetPlatformService targets = targetPlatformService();
        assumeTrue( targets != null, "Skipping: PDE target platform service not available" );
        ITargetHandle previous = targets.getWorkspaceTargetHandle();

        IProject project = ResourcesPlugin.getWorkspace().getRoot()
            .getProject( "target-reporting-" + System.nanoTime() );
        project.create( null );
        project.open( null );
        try
        {
            // The running platform's own contents, written to a workspace .target file so
            // the handle is a workspace-file one - the shape every real project has.
            IFile file = project.getFile( "running-platform.target" );
            ITargetDefinition definition = targets.getTarget( file ).getTargetDefinition();
            targets.copyTargetDefinition( targets.newDefaultTarget(), definition );
            targets.saveTargetDefinition( definition );

            ActiveTargetResponse loaded = server.setActiveTarget( file.getFullPath().toString() );
            assertEquals( TargetStatus.ACTIVE, loaded.status(), () -> String.valueOf( loaded.diagnostics() ) );
            assertTrue( loaded.resolved(), "a target the load job just resolved must be reported as resolved" );
            assertNotNull( loaded.bundleCount() );
            assertTrue( loaded.bundleCount() > 0, "the running platform resolves to more than nothing" );

            ActiveTargetResponse read = server.getActiveTarget();
            assertTrue( read.resolved(), "reading the active target must see the same resolved definition" );
            assertEquals( loaded.bundleCount(), read.bundleCount() );

            ActiveTargetResponse reloaded = server.reloadTarget();
            assertEquals( TargetStatus.ACTIVE, reloaded.status(), () -> String.valueOf( reloaded.diagnostics() ) );
            assertTrue( reloaded.resolved(), "a completed reload must report the target as resolved" );
            assertEquals( loaded.bundleCount(), reloaded.bundleCount() );
        }
        finally
        {
            restoreTarget( previous );
            project.delete( true, true, null );
        }
    }

    private static ITargetPlatformService targetPlatformService()
    {
        BundleContext context = FrameworkUtil.getBundle( McpServerPDETest.class ).getBundleContext();
        ServiceReference<ITargetPlatformService> reference =
            context.getServiceReference( ITargetPlatformService.class );
        return reference == null ? null : context.getService( reference );
    }

    /** Puts back whatever target the harness workspace had, so later tests build against it. */
    private static void restoreTarget( ITargetHandle previous ) throws Exception
    {
        ITargetDefinition definition = previous == null ? null : previous.getTargetDefinition();
        CountDownLatch done = new CountDownLatch( 1 );
        LoadTargetDefinitionJob.load( definition, new JobChangeAdapter()
        {
            @Override
            public void done( IJobChangeEvent event )
            {
                done.countDown();
            }
        } );
        assertTrue( done.await( 2, TimeUnit.MINUTES ), "restoring the previous target platform timed out" );
    }

    /**
     * A count of zero would say the target resolved to nothing, which is a far worse
     * situation than one that has not been resolved yet - so an unresolved target has no
     * count at all.
     */
    private static void assertActiveTargetIsSelfConsistent( ActiveTargetResponse response )
    {
        if ( !response.resolved() )
        {
            assertNull( response.bundleCount(),
                "bundleCount must be absent rather than 0 when the target is not resolved" );
        }
        if ( !response.explicitTarget() )
        {
            assertNull( response.name(), "the running platform is not a named target" );
            assertNull( response.memento() );
        }
    }

    @Test
    public void anActiveTargetSendsExactlyTheFieldsItAdvertises()
    {
        // The schema comes from the record components and the payload from Jackson; a
        // derived accessor - isUsable() here - would otherwise be serialized as a field
        // the schema never mentioned.
        @SuppressWarnings( "unchecked" )
        Map<String, Object> advertised = (Map<String, Object>) McpOutputSchemas
            .forType( ActiveTargetResponse.class ).get( "properties" );

        assertEquals( advertised.keySet(),
            McpJson.toMap( ActiveTargetResponse.active( "a-target", "memento", true, true, 412 ) ).keySet() );
        assertEquals( advertised.keySet(),
            McpJson.toMap( ActiveTargetResponse.runningPlatform() ).keySet(),
            "every state of the response has to have the same shape" );
    }

    @Test
    public void aFailedLoadStillSaysWhatIsInForce()
    {
        ActiveTargetResponse loaded = ActiveTargetResponse.active( "a-target", "memento", true, true, 412 );
        ActiveTargetResponse failed = loaded.withFailure(
            Diagnostic.retryable( DiagnosticCode.OPERATION_TIMED_OUT, "took too long" ) );

        assertEquals( TargetStatus.FAILED, failed.status() );
        assertEquals( List.of( DiagnosticCode.OPERATION_TIMED_OUT ),
            failed.diagnostics().stream().map( Diagnostic::code ).toList() );
        assertEquals( "a-target", failed.name(), "the target that is still active is the caller's next question" );
        assertEquals( Integer.valueOf( 412 ), failed.bundleCount() );
    }

    @Test
    public void anUnresolvedTargetHasNoBundleCount()
    {
        assertNull( ActiveTargetResponse.active( "a-target", "memento", true, false, 0 ).bundleCount() );
        assertNull( ActiveTargetResponse.runningPlatform().bundleCount() );
    }

    // -----------------------------------------------------------------------
    // runJUnitPluginTests — unified entry point
    // params: projectName, className, methodName, packageName, timeout,
    //         withCoverage, includeAllPlugins, additionalBundles, launcherName
    // -----------------------------------------------------------------------

    @Test
    public void testStartJUnitPluginTestRun_allTests_nullTimeout_usesDefault()
    {
        try
        {
            // no className/packageName → runs all tests in project
            assertProjectNotFound( server.runJUnitPluginTests(
                "NonExistentProject_XYZ", null, null, null, null, null, null, null, null ) );
        }
        catch ( IllegalStateException e )
        {
            assumeTrue( false, "Skipping: workspace not available (" + e.getMessage() + ")" );
        }
    }

    @Test
    public void testStartJUnitPluginTestRun_allTests_explicitTimeout_parsed()
    {
        try
        {
            assertProjectNotFound( server.runJUnitPluginTests(
                "NonExistentProject_XYZ", null, null, null, "30", null, null, null, null ) );
        }
        catch ( IllegalStateException e )
        {
            assumeTrue( false, "Skipping: workspace not available (" + e.getMessage() + ")" );
        }
    }

    @Test
    public void testStartJUnitPluginTestRun_includeAllPluginsTrue()
    {
        try
        {
            assertProjectNotFound( server.runJUnitPluginTests(
                "NonExistentProject_XYZ", null, null, null, "10", null, "true", null, null ) );
        }
        catch ( IllegalStateException e )
        {
            assumeTrue( false, "Skipping: workspace not available (" + e.getMessage() + ")" );
        }
    }

    @Test
    public void testStartJUnitPluginTestRun_includeAllPluginsFalse()
    {
        try
        {
            assertProjectNotFound( server.runJUnitPluginTests(
                "NonExistentProject_XYZ", null, null, null, "10", null, "false", null, null ) );
        }
        catch ( IllegalStateException e )
        {
            assumeTrue( false, "Skipping: workspace not available (" + e.getMessage() + ")" );
        }
    }

    @Test
    public void testStartJUnitPluginTestRun_withAdditionalBundles()
    {
        try
        {
            assertProjectNotFound( server.runJUnitPluginTests(
                "NonExistentProject_XYZ", null, null, null, "10", null, "false",
                "org.eclipse.core.runtime,org.eclipse.ui", null ) );
        }
        catch ( IllegalStateException e )
        {
            assumeTrue( false, "Skipping: workspace not available (" + e.getMessage() + ")" );
        }
    }

    @Test
    public void testStartJUnitPluginTestRun_singleClass_nullTimeout()
    {
        try
        {
            assertProjectNotFound( server.runJUnitPluginTests(
                "NonExistentProject_XYZ", "com.example.MyTest", null, null, null, null, null, null, null ) );
        }
        catch ( IllegalStateException e )
        {
            assumeTrue( false, "Skipping: workspace not available (" + e.getMessage() + ")" );
        }
    }

    @Test
    public void testStartJUnitPluginTestRun_singleClass_includeAllPluginsTrue()
    {
        try
        {
            assertProjectNotFound( server.runJUnitPluginTests(
                "NonExistentProject_XYZ", "com.example.MyTest", null, null, "10", null, "true", null, null ) );
        }
        catch ( IllegalStateException e )
        {
            assumeTrue( false, "Skipping: workspace not available (" + e.getMessage() + ")" );
        }
    }

    @Test
    public void testStartJUnitPluginTestRun_singleClass_withAdditionalBundles()
    {
        try
        {
            assertProjectNotFound( server.runJUnitPluginTests(
                "NonExistentProject_XYZ", "com.example.MyTest", null, null, "10", null, "false",
                "org.eclipse.core.runtime, org.eclipse.ui", null ) );
        }
        catch ( IllegalStateException e )
        {
            assumeTrue( false, "Skipping: workspace not available (" + e.getMessage() + ")" );
        }
    }

    @Test
    public void testStartJUnitPluginTestRun_multipleClasses_emptySelection_isRejected()
    {
        // comma-only className → parseCommaSeparated returns empty list → rejected by PDEService
        assertThrows( IllegalArgumentException.class,
            () -> server.runJUnitPluginTests(
                "SomeProject", " , ", null, null, null, null, null, null, null ) );
    }

    @Test
    public void testStartJUnitPluginTestRun_multipleClasses_areAccepted()
    {
        assertProjectNotFound( server.runJUnitPluginTests(
            "NonExistentProject_XYZ",
            " com.example.FirstPDETest, com.example.SecondPDETest ",
            null, null, "10", null, "false", "org.eclipse.ui, org.eclipse.core.runtime", null ) );
    }

    @Test
    public void testStartJUnitPluginTestRun_packageScope()
    {
        try
        {
            assertProjectNotFound( server.runJUnitPluginTests(
                "NonExistentProject_XYZ", null, null, "com.example.tests", "10", null, null, null, null ) );
        }
        catch ( IllegalStateException e )
        {
            assumeTrue( false, "Skipping: workspace not available (" + e.getMessage() + ")" );
        }
    }

    // -----------------------------------------------------------------------
    // methodName routing
    // -----------------------------------------------------------------------

    @Test
    public void testStartJUnitPluginTestRun_singleMethod_routesToRunJUnitPluginTestMethod()
    {
        // className + methodName → delegates to pdeService.runJUnitPluginTestMethod
        // Non-existent project produces a PROJECT_NOT_FOUND diagnostic.
        try
        {
            assertProjectNotFound( server.runJUnitPluginTests(
                "NonExistentProject_XYZ", "com.example.MyTest", "testSomething",
                null, "10", null, null, null, null ) );
        }
        catch ( IllegalStateException e )
        {
            assumeTrue( false, "Skipping: workspace not available (" + e.getMessage() + ")" );
        }
    }

    @Test
    public void testStartJUnitPluginTestRun_methodName_withMultipleClasses_isRejected()
    {
        // methodName combined with comma-separated classNames must return a VALIDATION_ERROR,
        // not throw, since the check happens inside the routing block.
        TestRunResponse response = server.runJUnitPluginTests(
            "NonExistentProject_XYZ",
            "com.example.FirstTest, com.example.SecondTest",
            "testSomething",
            null, "10", null, null, null, null );
        assertNotNull( response );
        assertEquals( RunStatus.FAILED_TO_START, response.status(), response.summaryText() );
        assertEquals( List.of( DiagnosticCode.VALIDATION_ERROR ),
            response.diagnostics().stream().map( Diagnostic::code ).toList() );
    }

    @Test
    public void testStartJUnitPluginTestRun_methodName_withoutClassName_isIgnored()
    {
        // methodName without className falls through to the package/all-tests branch.
        // The package name wins and produces an error about the non-existent project.
        try
        {
            assertProjectNotFound( server.runJUnitPluginTests(
                "NonExistentProject_XYZ", null, "testSomething", "com.example.tests",
                "10", null, null, null, null ) );
        }
        catch ( IllegalStateException e )
        {
            assumeTrue( false, "Skipping: workspace not available (" + e.getMessage() + ")" );
        }
    }

    /**
     * Whatever scope was asked for, naming a project that is not in the workspace is
     * reported as a diagnostic code on a structured result - not as a sentence starting
     * with "Error", which a caller had to match on to notice.
     */
    private static void assertProjectNotFound( TestRunResponse response )
    {
        assertNotNull( response );
        assertEquals( RunStatus.FAILED_TO_START, response.status(), response.summaryText() );
        assertEquals( List.of( DiagnosticCode.PROJECT_NOT_FOUND ),
            response.diagnostics().stream().map( Diagnostic::code ).toList() );
    }
}
