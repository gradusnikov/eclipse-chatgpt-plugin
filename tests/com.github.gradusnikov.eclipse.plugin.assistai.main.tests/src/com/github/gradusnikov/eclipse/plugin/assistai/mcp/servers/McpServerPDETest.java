package com.github.gradusnikov.eclipse.plugin.assistai.mcp.servers;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

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
    public void testGetActiveTarget_returnsNonNull()
    {
        try
        {
            String result = server.getActiveTarget();
            assertNotNull( result, "getActiveTarget should not return null" );
        }
        catch ( Exception e )
        {
            assumeTrue( false, "Skipping: PDE runtime not available (" + e.getMessage() + ")" );
        }
    }

    @Test
    public void testReloadTarget_returnsNonNull()
    {
        try
        {
            String result = server.reloadTarget();
            assertNotNull( result, "reloadTarget should not return null" );
        }
        catch ( Exception e )
        {
            assumeTrue( false, "Skipping: PDE runtime not available (" + e.getMessage() + ")" );
        }
    }

    // -----------------------------------------------------------------------
    // runJUnitPluginTests — unified entry point
    // -----------------------------------------------------------------------

    @Test
    public void testStartJUnitPluginTestRun_allTests_nullTimeout_usesDefault()
    {
        try
        {
            // no className/packageName → runs all tests in project
            String result = server.runJUnitPluginTests(
                "NonExistentProject_XYZ", null, null, null, null, null, null, null );
            assertNotNull( result );
            assertTrue( result.startsWith( "Error" ),
                "Expected error for non-existent project, got: " + result );
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
            String result = server.runJUnitPluginTests(
                "NonExistentProject_XYZ", null, null, "30", null, null, null, null );
            assertNotNull( result );
            assertTrue( result.startsWith( "Error" ),
                "Expected error for non-existent project, got: " + result );
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
            String result = server.runJUnitPluginTests(
                "NonExistentProject_XYZ", null, null, "10", null, "true", null, null );
            assertNotNull( result );
            assertTrue( result.startsWith( "Error" ),
                "Expected error for non-existent project, got: " + result );
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
            String result = server.runJUnitPluginTests(
                "NonExistentProject_XYZ", null, null, "10", null, "false", null, null );
            assertNotNull( result );
            assertTrue( result.startsWith( "Error" ),
                "Expected error for non-existent project, got: " + result );
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
            String result = server.runJUnitPluginTests(
                "NonExistentProject_XYZ", null, null, "10", null, "false",
                "org.eclipse.core.runtime,org.eclipse.ui", null );
            assertNotNull( result );
            assertTrue( result.startsWith( "Error" ),
                "Expected error for non-existent project, got: " + result );
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
            String result = server.runJUnitPluginTests(
                "NonExistentProject_XYZ", "com.example.MyTest", null, null, null, null, null, null );
            assertNotNull( result );
            assertTrue( result.startsWith( "Error" ),
                "Expected error for non-existent project, got: " + result );
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
            String result = server.runJUnitPluginTests(
                "NonExistentProject_XYZ", "com.example.MyTest", null, "10", null, "true", null, null );
            assertNotNull( result );
            assertTrue( result.startsWith( "Error" ),
                "Expected error for non-existent project, got: " + result );
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
            String result = server.runJUnitPluginTests(
                "NonExistentProject_XYZ", "com.example.MyTest", null, "10", null, "false",
                "org.eclipse.core.runtime, org.eclipse.ui", null );
            assertNotNull( result );
            assertTrue( result.startsWith( "Error" ),
                "Expected error for non-existent project, got: " + result );
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
                "SomeProject", " , ", null, null, null, null, null, null ) );
    }

    @Test
    public void testStartJUnitPluginTestRun_multipleClasses_areAccepted()
    {
        String result = server.runJUnitPluginTests(
            "NonExistentProject_XYZ",
            " com.example.FirstPDETest, com.example.SecondPDETest ",
            null, "10", null, "false", "org.eclipse.ui, org.eclipse.core.runtime", null );

        assertTrue( result.startsWith( "Error" ), result );
    }

    @Test
    public void testStartJUnitPluginTestRun_packageScope()
    {
        try
        {
            String result = server.runJUnitPluginTests(
                "NonExistentProject_XYZ", null, "com.example.tests", "10", null, null, null, null );
            assertNotNull( result );
            assertTrue( result.startsWith( "Error" ),
                "Expected error for non-existent project, got: " + result );
        }
        catch ( IllegalStateException e )
        {
            assumeTrue( false, "Skipping: workspace not available (" + e.getMessage() + ")" );
        }
    }
}
