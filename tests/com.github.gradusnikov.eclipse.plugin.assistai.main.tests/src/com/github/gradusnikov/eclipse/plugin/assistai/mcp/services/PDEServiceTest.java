package com.github.gradusnikov.eclipse.plugin.assistai.mcp.services;

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

import com.github.gradusnikov.eclipse.assistai.mcp.services.PDEService;

public class PDEServiceTest
{
    private PDEService service;

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

        service = ContextInjectionFactory.make( PDEService.class, context );
    }

    // -------------------------------------------------------------------------
    // getActiveTarget
    // -------------------------------------------------------------------------

    @Test
    public void testGetActiveTarget_returnsSomething()
    {
        try
        {
            String result = service.getActiveTarget();
            assertTrue( result != null && !result.isEmpty(),
                "getActiveTarget should return a non-empty string" );
        }
        catch ( Exception e )
        {
            // Outside a PDE runtime, platform services may be absent â skip
            assumeTrue( false, "Skipping: PDE service not available (" + e.getMessage() + ")" );
        }
    }

    // -------------------------------------------------------------------------
    // setActiveTarget â input validation
    // -------------------------------------------------------------------------

    @Test
    public void testSetActiveTarget_nullPath_throwsNPE()
    {
        assertThrows( NullPointerException.class,
            () -> service.setActiveTarget( null ) );
    }

    @Test
    public void testSetActiveTarget_nonExistentFile_returnsError()
    {
        try
        {
            String result = service.setActiveTarget( "/DoesNotExist/missing.target" );
            assertTrue( result.startsWith( "Error" ),
                "Expected an error message for a missing target file, got: " + result );
        }
        catch ( IllegalStateException e )
        {
            // ITargetPlatformService not available outside PDE runtime â acceptable
            assumeTrue( false, "Skipping: PDE service not available (" + e.getMessage() + ")" );
        }
    }

    // -------------------------------------------------------------------------
    // reloadTarget â no explicit target set
    // -------------------------------------------------------------------------

    @Test
    public void testReloadTarget_whenNoPlatformService_returnsErrorOrSkips()
    {
        try
        {
            String result = service.reloadTarget();
            // With no target set the method returns an error string; with one set it
            // would try to reload â either outcome is a non-empty string.
            assertTrue( result != null && !result.isEmpty(),
                "reloadTarget should return a non-empty string" );
        }
        catch ( IllegalStateException e )
        {
            assumeTrue( false, "Skipping: PDE service not available (" + e.getMessage() + ")" );
        }
    }

    // -------------------------------------------------------------------------
    // runJUnitPluginTests â input validation
    // -------------------------------------------------------------------------

    @Test
    public void testRunJUnitPluginTests_nullProjectName_throwsNPE()
    {
        assertThrows( NullPointerException.class,
            () -> service.runJUnitPluginTests( null, 60 ) );
    }

    @Test
    public void testRunJUnitPluginTests_emptyProjectName_throwsIllegalArgument()
    {
        assertThrows( IllegalArgumentException.class,
            () -> service.runJUnitPluginTests( "", 60 ) );
    }

    @Test
    public void testRunJUnitPluginTests_nonExistentProject_returnsError()
    {
        try
        {
            String result = service.runJUnitPluginTests( "NonExistentProject_XYZ", 60 );
            assertTrue( result.startsWith( "Error" ),
                "Expected an error message for non-existent project, got: " + result );
        }
        catch ( IllegalStateException e )
        {
            assumeTrue( false, "Skipping: workspace not available (" + e.getMessage() + ")" );
        }
    }

    @Test
    public void testRunJUnitPluginTests_nullTimeout_usesDefault()
    {
        try
        {
            // Should not throw; null timeout is normalised internally to 120
            String result = service.runJUnitPluginTests( "NonExistentProject_XYZ", null );
            assertTrue( result.startsWith( "Error" ),
                "Expected an error for non-existent project" );
        }
        catch ( IllegalStateException e )
        {
            assumeTrue( false, "Skipping: workspace not available (" + e.getMessage() + ")" );
        }
    }

    // -------------------------------------------------------------------------
    // runJUnitPluginTestClass â input validation
    // -------------------------------------------------------------------------

    @Test
    public void testRunJUnitPluginTestClass_nullProjectName_throwsNPE()
    {
        assertThrows( NullPointerException.class,
            () -> service.runJUnitPluginTestClass( null, "com.example.MyTest", 60 ) );
    }

    @Test
    public void testRunJUnitPluginTestClass_nullClassName_throwsNPE()
    {
        assertThrows( NullPointerException.class,
            () -> service.runJUnitPluginTestClass( "SomeProject", null, 60 ) );
    }

    @Test
    public void testRunJUnitPluginTestClass_nonExistentProject_returnsError()
    {
        try
        {
            String result = service.runJUnitPluginTestClass( "NonExistentProject_XYZ", "com.example.MyTest", 60 );
            assertTrue( result.startsWith( "Error" ),
                "Expected an error message for non-existent project, got: " + result );
        }
        catch ( IllegalStateException | IllegalArgumentException e )
        {
            assumeTrue( false, "Skipping: workspace not available (" + e.getMessage() + ")" );
        }
    }

    /**
     * Integration test: launch a specific test class via the PDE JUnit launcher.
     * Verifies that IPDELauncherConstants.LOCATION suppresses the workspace-selection
     * dialog and that the launcher reports results correctly.
     *
     * This test requires a running Eclipse instance with the test project open.
     */
    @Test
    public void testRunJUnitPluginTestClass_realProject_passes()
    {
        try
        {
            String result = service.runJUnitPluginTestClass(
                "com.github.gradusnikov.eclipse.plugin.assistai.main.tests",
                "com.github.gradusnikov.eclipse.plugin.assistai.mcp.services.PDEServiceTest",
                120 );
            System.out.println( "runJUnitPluginTestClass result: " + result );
            // Skip if the test project is not available in this environment
            assumeTrue( !result.contains( "Project not found" ),
                "Skipping: test project not open in this environment" );
            assertTrue( result.contains( "Passed" ) || result.contains( "passed" ),
                "Expected passing tests in result, got: " + result );
        }
        catch ( IllegalStateException | IllegalArgumentException e )
        {
            assumeTrue( false, "Skipping: workspace not available (" + e.getMessage() + ")" );
        }
    }

    /**
     * Integration test: launch all tests in the project via the PDE JUnit launcher.
     * Verifies that the CONTAINER handle-identifier format (=projectName) is correct
     * and the launcher starts without "input element does not exist" error.
     *
     * This test requires a running Eclipse instance with the test project open.
     */
    @Test
    public void testRunJUnitPluginTests_realProject_launches()
    {
        try
        {
            String result = service.runJUnitPluginTests(
                "com.github.gradusnikov.eclipse.plugin.assistai.main.tests",
                120 );
            System.out.println( "runJUnitPluginTests result: " + result );
            // Skip if the test project is not available in this environment
            assumeTrue( !result.contains( "Project not found" ),
                "Skipping: test project not open in this environment" );
            // Must not contain the "input element does not exist" error from the old path bug
            assertTrue( !result.contains( "does not exist" ),
                "Got 'does not exist' error - container format is wrong: " + result );
            // Should either pass or time out (not a launch config error)
            assertTrue( result.contains( "Passed" ) || result.contains( "passed" )
                || result.contains( "timed out" ),
                "Expected test results or timeout, got: " + result );
        }
        catch ( IllegalStateException | IllegalArgumentException e )
        {
            assumeTrue( false, "Skipping: workspace not available (" + e.getMessage() + ")" );
        }
    }
}
