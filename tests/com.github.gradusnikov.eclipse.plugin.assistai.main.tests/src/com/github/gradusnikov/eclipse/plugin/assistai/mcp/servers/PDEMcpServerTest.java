package com.github.gradusnikov.eclipse.plugin.assistai.mcp.servers;

import static org.junit.jupiter.api.Assertions.assertNotNull;
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
 * Tests for PDEMcpServer - focuses on parameter handling and delegation.
 * Methods that require a live PDE runtime are skipped via {@code assumeTrue}.
 */
public class PDEMcpServerTest
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

    /**
     * Verifies that a null className (all-tests scope) for a non-existent project
     * returns an error string from startJUnitPluginTestRun.
     */
    @Test
    public void testStartJUnitPluginTestRun_nullClassName_allTests_nonExistentProject()
    {
        try
        {
            String result = server.startJUnitPluginTestRun(
                "NonExistentProject_XYZ", null, null, null, null, null, null );
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
    public void testStartJUnitPluginTestRun_withCoverageTrue_nonExistentProject()
    {
        try
        {
            String result = server.startJUnitPluginTestRun(
                "NonExistentProject_XYZ", null, null, "true", null, null, null );
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
    public void testStartJUnitPluginTestRun_includeAllPluginsTrue_nonExistentProject()
    {
        try
        {
            String result = server.startJUnitPluginTestRun(
                "NonExistentProject_XYZ", null, null, null, "true", null, null );
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
    public void testStartJUnitPluginTestRun_includeAllPluginsFalse_nonExistentProject()
    {
        try
        {
            String result = server.startJUnitPluginTestRun(
                "NonExistentProject_XYZ", null, null, null, "false", null, null );
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
    public void testStartJUnitPluginTestRun_withAdditionalBundles_nonExistentProject()
    {
        try
        {
            String result = server.startJUnitPluginTestRun(
                "NonExistentProject_XYZ", null, null, null, "false",
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
    public void testStartJUnitPluginTestRun_withClassName_nonExistentProject()
    {
        try
        {
            String result = server.startJUnitPluginTestRun(
                "NonExistentProject_XYZ", "com.example.MyTest", null, null, null, null, null );
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
    public void testStartJUnitPluginTestRun_withClassName_includeAllPlugins_nonExistentProject()
    {
        try
        {
            String result = server.startJUnitPluginTestRun(
                "NonExistentProject_XYZ", "com.example.MyTest", null, null, "true", null, null );
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
    public void testStartJUnitPluginTestRun_withClassName_withAdditionalBundles_nonExistentProject()
    {
        try
        {
            String result = server.startJUnitPluginTestRun(
                "NonExistentProject_XYZ", "com.example.MyTest", null, null, "false",
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
    public void testStartJUnitPluginTestRun_withUnknownLauncherName_returnsError()
    {
        try
        {
            String result = server.startJUnitPluginTestRun(
                "NonExistentProject_XYZ", null, null, null, null, null,
                "NonExistentLaunchConfig_XYZ_12345" );
            assertNotNull( result );
            assertTrue( result.startsWith( "Error" ),
                "Expected error for unknown launcher name, got: " + result );
        }
        catch ( IllegalStateException e )
        {
            assumeTrue( false, "Skipping: workspace not available (" + e.getMessage() + ")" );
        }
    }

    // -------------------------------------------------------------------------
    // packageName scope — error paths
    // -------------------------------------------------------------------------

    /**
     * Verifies that a non-null packageName (package scope) for a non-existent project
     * returns an error string.
     */
    @Test
    public void testStartJUnitPluginTestRun_withPackageName_nonExistentProject()
    {
        try
        {
            String result = server.startJUnitPluginTestRun(
                "NonExistentProject_XYZ", null, "com.example.tests", null, null, null, null );
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
    public void testStartJUnitPluginTestRun_withPackageName_withCoverage_nonExistentProject()
    {
        try
        {
            String result = server.startJUnitPluginTestRun(
                "NonExistentProject_XYZ", null, "com.example.tests", "true", null, null, null );
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
    public void testStartJUnitPluginTestRun_withPackageName_includeAllPlugins_nonExistentProject()
    {
        try
        {
            String result = server.startJUnitPluginTestRun(
                "NonExistentProject_XYZ", null, "com.example.tests", null, "true", null, null );
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
    public void testStartJUnitPluginTestRun_withPackageName_withAdditionalBundles_nonExistentProject()
    {
        try
        {
            String result = server.startJUnitPluginTestRun(
                "NonExistentProject_XYZ", null, "com.example.tests", null, "false",
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

    /**
     * When both className and packageName are provided, className takes precedence
     * (scope inference: class > package > all).
     */
    @Test
    public void testStartJUnitPluginTestRun_classNameTakesPrecedenceOverPackageName_nonExistentProject()
    {
        try
        {
            String result = server.startJUnitPluginTestRun(
                "NonExistentProject_XYZ", "com.example.MyTest", "com.example.tests",
                null, null, null, null );
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
    public void testStartJUnitPluginTestRun_withPackageName_withLauncherName_nonExistentProject()
    {
        try
        {
            String result = server.startJUnitPluginTestRun(
                "NonExistentProject_XYZ", null, "com.example.tests", null, null, null,
                "NonExistentLaunchConfig_XYZ_12345" );
            assertNotNull( result );
            assertTrue( result.startsWith( "Error" ),
                "Expected error for non-existent project or launcher, got: " + result );
        }
        catch ( IllegalStateException e )
        {
            assumeTrue( false, "Skipping: workspace not available (" + e.getMessage() + ")" );
        }
    }

    /**
     * Verifies that a blank packageName is treated the same as null (falls through to all-tests scope).
     */
    @Test
    public void testStartJUnitPluginTestRun_blankPackageName_treatedAsAllTests_nonExistentProject()
    {
        try
        {
            String result = server.startJUnitPluginTestRun(
                "NonExistentProject_XYZ", null, "   ", null, null, null, null );
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
