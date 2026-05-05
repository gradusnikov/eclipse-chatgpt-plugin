package com.github.gradusnikov.eclipse.plugin.assistai.mcp.services;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.eclipse.core.runtime.ILog;
import org.eclipse.e4.core.contexts.ContextInjectionFactory;
import org.eclipse.e4.core.contexts.EclipseContextFactory;
import org.eclipse.e4.core.contexts.IEclipseContext;
import org.eclipse.e4.ui.di.UISynchronize;
import org.eclipse.pde.core.target.ITargetDefinition;
import org.eclipse.pde.core.target.ITargetHandle;
import org.eclipse.pde.core.target.ITargetPlatformService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.osgi.framework.BundleContext;
import org.osgi.framework.FrameworkUtil;
import org.osgi.util.tracker.ServiceTracker;

import com.github.gradusnikov.eclipse.assistai.mcp.services.PDEService;

/**
 * JUnit Plug-in Test for {@link PDEService}.
 * <p>
 * Must be run as a <em>JUnit Plug-in Test</em> (PDE launcher) so that real
 * OSGi services â {@link ITargetPlatformService}, {@link ILog},
 * {@link UISynchronize} â are available from the running Eclipse instance.
 */
public class PDEServicePluginTest
{
    private PDEService service;
    private BundleContext bundleContext;

    @BeforeEach
    public void setUp() throws Exception
    {
        bundleContext = FrameworkUtil.getBundle( PDEServicePluginTest.class ).getBundleContext();

        // Obtain ILog from OSGi service registry
        ServiceTracker<ILog, ILog> logTracker = new ServiceTracker<>( bundleContext, ILog.class, null );
        logTracker.open();
        ILog log = logTracker.getService();

        IEclipseContext context = EclipseContextFactory.getServiceContext( bundleContext );

        // Provide a no-op UISynchronize that runs everything inline (safe in test thread)
        context.set( UISynchronize.class, new UISynchronize()
        {
            @Override
            public void syncExec( Runnable runnable ) { runnable.run(); }

            @Override
            public void asyncExec( Runnable runnable ) { runnable.run(); }

            @Override
            protected boolean isUIThread( Thread thread ) { return false; }

            @Override
            protected void showBusyWhile( Runnable runnable ) { runnable.run(); }

            @Override
            protected boolean dispatchEvents() { return false; }
        } );

        if ( log != null )
        {
            context.set( ILog.class, log );
        }

        service = ContextInjectionFactory.make( PDEService.class, context );
    }

    // -------------------------------------------------------------------------
    // getActiveTarget
    // -------------------------------------------------------------------------

    /**
     * With a live PDE runtime, {@code getActiveTarget()} must always return a
     * non-null, non-empty string â either the name of the active target or the
     * "running platform" fallback message.
     */
    @Test
    public void testGetActiveTarget_returnsNonEmptyString()
    {
        String result = service.getActiveTarget();
        assertNotNull( result, "getActiveTarget must not return null" );
        assertTrue( !result.isEmpty(), "getActiveTarget must not return an empty string" );
    }

    @Test
    public void testGetActiveTarget_containsExpectedKeyword()
    {
        String result = service.getActiveTarget();
        // Must start with "Active target:" or "Error getting active target:"
        assertTrue(
            result.startsWith( "Active target:" ) || result.startsWith( "Error getting active target:" ),
            "Unexpected getActiveTarget output: " + result );
    }

    // -------------------------------------------------------------------------
    // setActiveTarget â input validation (no actual .target file needed)
    // -------------------------------------------------------------------------

    @Test
    public void testSetActiveTarget_nullPath_throwsNPE()
    {
        assertThrows( NullPointerException.class,
            () -> service.setActiveTarget( null ) );
    }

    @Test
    public void testSetActiveTarget_missingFile_returnsErrorString()
    {
        String result = service.setActiveTarget( "/NonExistentProject/does-not-exist.target" );
        assertNotNull( result );
        assertTrue( result.startsWith( "Error" ),
            "Expected error message for missing .target file, got: " + result );
    }

    // -------------------------------------------------------------------------
    // reloadTarget
    // -------------------------------------------------------------------------

    /**
     * Either the active target is reloaded successfully, or there is no
     * explicit target set â both cases must yield a non-empty string.
     */
    @Test
    public void testReloadTarget_returnsNonEmptyString()
    {
        String result = service.reloadTarget();
        assertNotNull( result );
        assertTrue( !result.isEmpty(), "reloadTarget must not return an empty string" );
    }

    @Test
    public void testReloadTarget_outputIsCoherent()
    {
        String result = service.reloadTarget();
        // Must be one of the three known prefixes
        assertTrue(
            result.startsWith( "Target platform reloaded:" )
                || result.startsWith( "Error: No explicit target platform" )
                || result.startsWith( "Error reloading target:" )
                || result.startsWith( "Error: Timed out" ),
            "Unexpected reloadTarget output: " + result );
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
    public void testRunJUnitPluginTests_nonExistentProject_returnsErrorString()
    {
        String result = service.runJUnitPluginTests( "NonExistentProject_PDEPlugin", 10 );
        assertNotNull( result );
        assertTrue( result.startsWith( "Error" ),
            "Expected error for non-existent project, got: " + result );
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
    public void testRunJUnitPluginTestClass_nonExistentProject_returnsErrorString()
    {
        String result = service.runJUnitPluginTestClass(
            "NonExistentProject_PDEPlugin", "com.example.MyTest", 10 );
        assertNotNull( result );
        assertTrue( result.startsWith( "Error" ),
            "Expected error for non-existent project, got: " + result );
    }

    // -------------------------------------------------------------------------
    // ITargetPlatformService availability
    // -------------------------------------------------------------------------

    /**
     * Sanity-check: verifies that {@link ITargetPlatformService} is actually
     * resolvable via {@code org.eclipse.pde.core} bundle in this OSGi runtime.
     * If this test fails, the PDE bundle is not part of the launch configuration.
     */
    @Test
    public void testTargetPlatformServiceIsAvailable()
    {
        var pdeBundle = org.eclipse.core.runtime.Platform.getBundle( "org.eclipse.pde.core" );
        assertNotNull( pdeBundle, "org.eclipse.pde.core bundle must be present in the launch" );

        var ref = pdeBundle.getBundleContext().getServiceReference( ITargetPlatformService.class );
        assertNotNull( ref, "ITargetPlatformService must be registered in the OSGi registry" );

        ITargetPlatformService tps = pdeBundle.getBundleContext().getService( ref );
        assertNotNull( tps, "ITargetPlatformService instance must be non-null" );
    }

    /**
     * Verifies that the workspace target handle can be retrieved (may be null
     * if no explicit target is set, which is also a valid state).
     */
    @Test
    public void testWorkspaceTargetHandle_doesNotThrow() throws Exception
    {
        var pdeBundle = org.eclipse.core.runtime.Platform.getBundle( "org.eclipse.pde.core" );
        var ref = pdeBundle.getBundleContext().getServiceReference( ITargetPlatformService.class );
        ITargetPlatformService tps = pdeBundle.getBundleContext().getService( ref );

        // getWorkspaceTargetHandle() returns null when using the running platform
        ITargetHandle handle = tps.getWorkspaceTargetHandle();

        if ( handle != null )
        {
            ITargetDefinition def = handle.getTargetDefinition();
            assertNotNull( def, "Target definition must not be null when handle is non-null" );
        }
        // null handle is valid â means "running platform" is active
    }
}
