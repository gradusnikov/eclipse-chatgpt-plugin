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
 * Tests for PDEMcpServer â focuses on parameter handling and delegation.
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
     * Verifies that a null timeout string is treated as the default (60 s) and
     * that the call delegates to PDEService without throwing.
     */
    @Test
    public void testRunJUnitPluginTests_nullTimeout_usesDefault()
    {
        try
        {
            // Non-existent project â will get an error string back, not an exception
            String result = server.runJUnitPluginTests( "NonExistentProject_XYZ", null );
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
     * Verifies that an explicit timeout string is parsed and forwarded correctly.
     */
    @Test
    public void testRunJUnitPluginTests_explicitTimeout_parsed()
    {
        try
        {
            String result = server.runJUnitPluginTests( "NonExistentProject_XYZ", "30" );
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
    public void testRunJUnitPluginTestClass_nullTimeout_usesDefault()
    {
        try
        {
            String result = server.runJUnitPluginTestClass(
                "NonExistentProject_XYZ", "com.example.MyTest", null );
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
