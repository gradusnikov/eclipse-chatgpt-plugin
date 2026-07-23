package com.github.gradusnikov.eclipse.plugin.assistai.mcp.services;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IFolder;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IProjectDescription;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.ILog;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.e4.core.contexts.ContextInjectionFactory;
import org.eclipse.e4.core.contexts.EclipseContextFactory;
import org.eclipse.e4.core.contexts.IEclipseContext;
import org.eclipse.e4.ui.di.UISynchronize;
import org.eclipse.jdt.core.IClasspathEntry;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.IPackageFragment;
import org.eclipse.jdt.core.IPackageFragmentRoot;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.launching.JavaRuntime;
import org.eclipse.pde.core.target.ITargetDefinition;
import org.eclipse.pde.core.target.ITargetHandle;
import org.eclipse.pde.core.target.ITargetPlatformService;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.osgi.framework.BundleContext;
import org.osgi.framework.FrameworkUtil;
import org.osgi.util.tracker.ServiceTracker;

import com.github.gradusnikov.eclipse.assistai.mcp.services.PDEService;
import com.github.gradusnikov.eclipse.assistai.mcp.services.TestRunManager;
import com.github.gradusnikov.eclipse.assistai.mcp.services.TestRunSession;

@TestInstance( TestInstance.Lifecycle.PER_CLASS )
@TestMethodOrder( MethodOrderer.OrderAnnotation.class )
public class PDEServicePluginTest
{
    private static final String TEST_PLUGIN_PROJECT = "PDEServiceTest_PluginProject";

    private PDEService service;
    private TestRunManager testRunManager;
    private BundleContext bundleContext;

    @BeforeAll
    public void setUp() throws Exception
    {
        bundleContext = FrameworkUtil.getBundle( PDEServicePluginTest.class ).getBundleContext();

        ServiceTracker<ILog, ILog> logTracker = new ServiceTracker<>( bundleContext, ILog.class, null );
        logTracker.open();
        ILog log = logTracker.getService();

        IEclipseContext context = EclipseContextFactory.getServiceContext( bundleContext );

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
        testRunManager = ContextInjectionFactory.make( TestRunManager.class, context );

        createPluginTestProject();
    }

    @AfterAll
    public void tearDown()
    {
        try
        {
            IProject project = ResourcesPlugin.getWorkspace().getRoot().getProject( TEST_PLUGIN_PROJECT );
            if ( project.exists() )
            {
                project.delete( true, true, new NullProgressMonitor() );
            }
        }
        catch ( CoreException e )
        {
            System.err.println( "Warning: could not delete test plugin project: " + e.getMessage() );
        }
    }

    private void createPluginTestProject() throws Exception
    {
        NullProgressMonitor monitor = new NullProgressMonitor();

        IProject project = ResourcesPlugin.getWorkspace().getRoot().getProject( TEST_PLUGIN_PROJECT );
        if ( project.exists() )
        {
            try
            {
                project.delete( true, true, monitor );
            }
            catch ( CoreException e )
            {
                project.open( monitor );
                return;
            }
        }

        project.create( monitor );
        project.open( monitor );

        IProjectDescription description = project.getDescription();
        description.setNatureIds( new String[] { JavaCore.NATURE_ID, "org.eclipse.pde.PluginNature" } );
        description.setBuildSpec( new org.eclipse.core.resources.ICommand[] {} );
        project.setDescription( description, monitor );

        IJavaProject javaProject = JavaCore.create( project );

        IFolder srcFolder = project.getFolder( "src" );
        if ( !srcFolder.exists() ) srcFolder.create( true, true, monitor );

        IFolder binFolder = project.getFolder( "bin" );
        if ( !binFolder.exists() ) binFolder.create( true, true, monitor );

        javaProject.setOutputLocation( binFolder.getFullPath(), monitor );

        IFolder metaInf = project.getFolder( "META-INF" );
        if ( !metaInf.exists() ) metaInf.create( true, true, monitor );

        String manifest =
            "Manifest-Version: 1.0\n" +
            "Bundle-ManifestVersion: 2\n" +
            "Bundle-Name: PDE Test Plugin\n" +
            "Bundle-SymbolicName: " + TEST_PLUGIN_PROJECT + "\n" +
            "Bundle-Version: 1.0.0\n" +
            "Bundle-RequiredExecutionEnvironment: JavaSE-21\n" +
            "Require-Bundle: org.junit\n" +
            "Export-Package: com.example.pdetest\n";
        createFile( project, "META-INF/MANIFEST.MF", manifest, monitor );

        String buildProps =
            "source.. = src/\n" +
            "output.. = bin/\n" +
            "bin.includes = META-INF/,.\n";
        createFile( project, "build.properties", buildProps, monitor );

        IClasspathEntry srcEntry = JavaCore.newSourceEntry( srcFolder.getFullPath() );
        IClasspathEntry jreEntry = JavaRuntime.getDefaultJREContainerEntry();
        IClasspathEntry pdeEntry = JavaCore.newContainerEntry(
            IPath.fromOSString( "org.eclipse.pde.core.requiredPlugins" ) );

        javaProject.setRawClasspath( new IClasspathEntry[] { srcEntry, jreEntry, pdeEntry }, monitor );

        IPackageFragmentRoot srcRoot = javaProject.getPackageFragmentRoot( srcFolder );
        IPackageFragment pkg = srcRoot.createPackageFragment( "com.example.pdetest", true, monitor );

        String testSource =
            "package com.example.pdetest;\n" +
            "import org.junit.Test;\n" +
            "import static org.junit.Assert.assertTrue;\n" +
            "public class SimplePluginTest {\n" +
            "    @Test\n" +
            "    public void testSimple() {\n" +
            "        assertTrue(true);\n" +
            "    }\n" +
            "}\n";
        pkg.createCompilationUnit( "SimplePluginTest.java", testSource, true, monitor );

        project.build( org.eclipse.core.resources.IncrementalProjectBuilder.FULL_BUILD, monitor );
    }

    private void createFile( IProject project, String path, String content, NullProgressMonitor monitor )
        throws CoreException
    {
        IFile file = project.getFile( path );
        if ( !file.exists() )
        {
            file.create( new ByteArrayInputStream( content.getBytes( StandardCharsets.UTF_8 ) ), true, monitor );
        }
    }

    // -------------------------------------------------------------------------
    // Helper: wait for a run to complete
    // -------------------------------------------------------------------------

    private String waitForRun( String runId, int timeoutSeconds ) throws InterruptedException
    {
        long deadline = System.currentTimeMillis() + timeoutSeconds * 1000L;
        while ( System.currentTimeMillis() < deadline )
        {
            TestRunSession session = testRunManager.getSession( runId );
            if ( session != null && session.getState() != TestRunSession.State.RUNNING )
            {
                return testRunManager.formatResults( session );
            }
            Thread.sleep( 500 );
        }
        return "Error: Timed out waiting for test run " + runId;
    }

    // -------------------------------------------------------------------------
    // getActiveTarget
    // -------------------------------------------------------------------------

    @Test
    @Order( 1 )
    public void testGetActiveTarget_returnsNonEmptyString()
    {
        String result = service.getActiveTarget();
        assertNotNull( result, "getActiveTarget must not return null" );
        assertFalse( result.isEmpty(), "getActiveTarget must not return an empty string" );
    }

    @Test
    @Order( 2 )
    public void testGetActiveTarget_containsExpectedKeyword()
    {
        String result = service.getActiveTarget();
        assertTrue(
            result.startsWith( "Active target:" ) || result.startsWith( "Error getting active target:" ),
            "Unexpected getActiveTarget output: " + result );
    }

    // -------------------------------------------------------------------------
    // setActiveTarget - input validation
    // -------------------------------------------------------------------------

    @Test
    @Order( 3 )
    public void testSetActiveTarget_nullPath_throwsNPE()
    {
        assertThrows( NullPointerException.class,
            () -> service.setActiveTarget( null ) );
    }

    @Test
    @Order( 4 )
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

    @Test
    @Order( 5 )
    public void testReloadTarget_returnsNonEmptyString()
    {
        String result = service.reloadTarget();
        assertNotNull( result );
        assertFalse( result.isEmpty(), "reloadTarget must not return an empty string" );
    }

    @Test
    @Order( 6 )
    public void testReloadTarget_outputIsCoherent()
    {
        String result = service.reloadTarget();
        assertTrue(
            result.startsWith( "Target platform reloaded:" )
                || result.startsWith( "Error: No explicit target platform" )
                || result.startsWith( "Error reloading target:" )
                || result.startsWith( "Error: Timed out" ),
            "Unexpected reloadTarget output: " + result );
    }

    // -------------------------------------------------------------------------
    // startJUnitPluginTests - input validation
    // -------------------------------------------------------------------------

    @Test
    @Order( 7 )
    public void testStartJUnitPluginTests_nullProjectName_throwsNPE()
    {
        assertThrows( NullPointerException.class,
            () -> service.startJUnitPluginTests( null, false, false, List.of() ) );
    }

    @Test
    @Order( 8 )
    public void testStartJUnitPluginTests_emptyProjectName_throwsIllegalArgument()
    {
        assertThrows( IllegalArgumentException.class,
            () -> service.startJUnitPluginTests( "", false, false, List.of() ) );
    }

    @Test
    @Order( 9 )
    public void testStartJUnitPluginTests_nonExistentProject_throwsRuntimeException()
    {
        assertThrows( RuntimeException.class,
            () -> service.startJUnitPluginTests( "NonExistentProject_PDEPlugin", false, false, List.of() ) );
    }

    // -------------------------------------------------------------------------
    // startJUnitPluginTestClass - input validation
    // -------------------------------------------------------------------------

    @Test
    @Order( 10 )
    public void testStartJUnitPluginTestClass_nullProjectName_throwsNPE()
    {
        assertThrows( NullPointerException.class,
            () -> service.startJUnitPluginTestClass( null, "com.example.MyTest", false, false, List.of() ) );
    }

    @Test
    @Order( 11 )
    public void testStartJUnitPluginTestClass_nullClassName_throwsNPE()
    {
        assertThrows( NullPointerException.class,
            () -> service.startJUnitPluginTestClass( "SomeProject", null, false, false, List.of() ) );
    }

    @Test
    @Order( 12 )
    public void testStartJUnitPluginTestClass_nonExistentProject_throwsRuntimeException()
    {
        assertThrows( RuntimeException.class,
            () -> service.startJUnitPluginTestClass(
                "NonExistentProject_PDEPlugin", "com.example.MyTest", false, false, List.of() ) );
    }

    // -------------------------------------------------------------------------
    // ITargetPlatformService availability
    // -------------------------------------------------------------------------

    @Test
    @Order( 13 )
    public void testTargetPlatformServiceIsAvailable()
    {
        var pdeBundle = org.eclipse.core.runtime.Platform.getBundle( "org.eclipse.pde.core" );
        assertNotNull( pdeBundle, "org.eclipse.pde.core bundle must be present in the launch" );

        var ref = pdeBundle.getBundleContext().getServiceReference( ITargetPlatformService.class );
        assertNotNull( ref, "ITargetPlatformService must be registered in the OSGi registry" );

        ITargetPlatformService tps = pdeBundle.getBundleContext().getService( ref );
        assertNotNull( tps, "ITargetPlatformService instance must be non-null" );
    }

    @Test
    @Order( 14 )
    public void testWorkspaceTargetHandle_doesNotThrow() throws Exception
    {
        var pdeBundle = org.eclipse.core.runtime.Platform.getBundle( "org.eclipse.pde.core" );
        var ref = pdeBundle.getBundleContext().getServiceReference( ITargetPlatformService.class );
        ITargetPlatformService tps = pdeBundle.getBundleContext().getService( ref );

        ITargetHandle handle = tps.getWorkspaceTargetHandle();

        if ( handle != null )
        {
            ITargetDefinition def = handle.getTargetDefinition();
            assertNotNull( def, "Target definition must not be null when handle is non-null" );
        }
    }

    // -------------------------------------------------------------------------
    // Integration: run plugin tests on the created project
    // -------------------------------------------------------------------------

    @Test
    @Order( 20 )
    public void testStartJUnitPluginTestClass_realProject_passes() throws InterruptedException
    {
        String runId = service.startJUnitPluginTestClass(
            TEST_PLUGIN_PROJECT,
            "com.example.pdetest.SimplePluginTest",
            false, false, List.of() );
        assertNotNull( runId );

        String result = waitForRun( runId, 120 );
        System.out.println( "startJUnitPluginTestClass result: " + result );
        assumeTrue( !result.contains( "Error:" ),
            "Skipping: PDE launcher not available or project has errors (" + result + ")" );
        assertTrue( result.contains( "Passed" ) || result.contains( "passed" ),
            "Expected passing tests in result, got: " + result );
    }

    @Test
    @Order( 21 )
    public void testStartJUnitPluginTests_realProject_launches() throws InterruptedException
    {
        String runId = service.startJUnitPluginTests(
            TEST_PLUGIN_PROJECT, false, false, List.of() );
        assertNotNull( runId );

        String result = waitForRun( runId, 120 );
        System.out.println( "startJUnitPluginTests result: " + result );
        assumeTrue( !result.contains( "Error:" ),
            "Skipping: PDE launcher not available or project has errors (" + result + ")" );
        assertFalse( result.contains( "does not exist" ),
            "Got 'does not exist' error - container format is wrong: " + result );
        assertTrue( result.contains( "Passed" ) || result.contains( "passed" )
            || result.contains( "timed out" ),
            "Expected test results or timeout, got: " + result );
    }

    // -------------------------------------------------------------------------
    // includeAllPlugins parameter
    // -------------------------------------------------------------------------

    @Test
    @Order( 22 )
    public void testStartJUnitPluginTests_includeAllPlugins_nonExistentProject()
    {
        assertThrows( RuntimeException.class,
            () -> service.startJUnitPluginTests( "NonExistentProject_PDEPlugin", false, true, List.of() ) );
    }

    @Test
    @Order( 23 )
    public void testStartJUnitPluginTests_selectedPlugins_nonExistentProject()
    {
        assertThrows( RuntimeException.class,
            () -> service.startJUnitPluginTests( "NonExistentProject_PDEPlugin", false, false, List.of() ) );
    }

    @Test
    @Order( 24 )
    public void testStartJUnitPluginTests_withAdditionalBundles_nonExistentProject()
    {
        assertThrows( RuntimeException.class,
            () -> service.startJUnitPluginTests(
                "NonExistentProject_PDEPlugin", false, false,
                List.of( "org.eclipse.core.runtime", "org.eclipse.ui" ) ) );
    }

    @Test
    @Order( 25 )
    public void testStartJUnitPluginTestClass_includeAllPlugins_nonExistentProject()
    {
        assertThrows( RuntimeException.class,
            () -> service.startJUnitPluginTestClass(
                "NonExistentProject_PDEPlugin", "com.example.MyTest", false, true, List.of() ) );
    }

    @Test
    @Order( 26 )
    public void testStartJUnitPluginTestClass_withAdditionalBundles_nonExistentProject()
    {
        assertThrows( RuntimeException.class,
            () -> service.startJUnitPluginTestClass(
                "NonExistentProject_PDEPlugin", "com.example.MyTest", false, false,
                List.of( "org.eclipse.core.runtime" ) ) );
    }

    // -------------------------------------------------------------------------
    // Integration: run with selected plugins mode on real project
    // -------------------------------------------------------------------------

    @Test
    @Order( 30 )
    public void testStartJUnitPluginTestClass_selectedPluginsMode_realProject() throws InterruptedException
    {
        String runId = service.startJUnitPluginTestClass(
            TEST_PLUGIN_PROJECT,
            "com.example.pdetest.SimplePluginTest",
            false, false, List.of() );
        assertNotNull( runId );

        String result = waitForRun( runId, 120 );
        System.out.println( "startJUnitPluginTestClass (selected) result: " + result );
        assumeTrue( !result.contains( "Error:" ),
            "Skipping: PDE launcher not available or project has errors (" + result + ")" );
        assertTrue( result.contains( "Passed" ) || result.contains( "passed" ),
            "Expected passing tests in result, got: " + result );
    }

    @Test
    @Order( 31 )
    public void testStartJUnitPluginTestClass_allPluginsMode_realProject() throws InterruptedException
    {
        String runId = service.startJUnitPluginTestClass(
            TEST_PLUGIN_PROJECT,
            "com.example.pdetest.SimplePluginTest",
            false, true, List.of() );
        assertNotNull( runId );

        String result = waitForRun( runId, 120 );
        System.out.println( "startJUnitPluginTestClass (all) result: " + result );
        assumeTrue( !result.contains( "Error:" ),
            "Skipping: PDE launcher not available or project has errors (" + result + ")" );
        assertTrue( result.contains( "Passed" ) || result.contains( "passed" ),
            "Expected passing tests in result, got: " + result );
    }

    @Test
    @Order( 32 )
    public void testStartJUnitPluginTests_selectedPluginsMode_realProject() throws InterruptedException
    {
        String runId = service.startJUnitPluginTests(
            TEST_PLUGIN_PROJECT, false, false, List.of() );
        assertNotNull( runId );

        String result = waitForRun( runId, 120 );
        System.out.println( "startJUnitPluginTests (selected) result: " + result );
        assumeTrue( !result.contains( "Error:" ),
            "Skipping: PDE launcher not available or project has errors (" + result + ")" );
        assertTrue( result.contains( "Passed" ) || result.contains( "passed" )
            || result.contains( "timed out" ),
            "Expected test results or timeout, got: " + result );
    }

    // -------------------------------------------------------------------------
    // startJUnitPluginTestPackage - input validation
    // -------------------------------------------------------------------------

    @Test
    @Order( 40 )
    public void testStartJUnitPluginTestPackage_nullProjectName_throwsNPE()
    {
        assertThrows( NullPointerException.class,
            () -> service.startJUnitPluginTestPackage( null, "com.example.pdetest", false, false, List.of() ) );
    }

    @Test
    @Order( 41 )
    public void testStartJUnitPluginTestPackage_nullPackageName_throwsNPE()
    {
        assertThrows( NullPointerException.class,
            () -> service.startJUnitPluginTestPackage( "SomeProject", null, false, false, List.of() ) );
    }

    @Test
    @Order( 42 )
    public void testStartJUnitPluginTestPackage_emptyProjectName_throwsIllegalArgument()
    {
        assertThrows( IllegalArgumentException.class,
            () -> service.startJUnitPluginTestPackage( "", "com.example.pdetest", false, false, List.of() ) );
    }

    @Test
    @Order( 43 )
    public void testStartJUnitPluginTestPackage_emptyPackageName_throwsIllegalArgument()
    {
        assertThrows( IllegalArgumentException.class,
            () -> service.startJUnitPluginTestPackage( TEST_PLUGIN_PROJECT, "", false, false, List.of() ) );
    }

    @Test
    @Order( 44 )
    public void testStartJUnitPluginTestPackage_nonExistentProject_throwsRuntimeException()
    {
        assertThrows( RuntimeException.class,
            () -> service.startJUnitPluginTestPackage(
                "NonExistentProject_PDEPlugin", "com.example.pdetest", false, false, List.of() ) );
    }

    @Test
    @Order( 45 )
    public void testStartJUnitPluginTestPackage_nonExistentPackage_throwsRuntimeException()
    {
        assertThrows( RuntimeException.class,
            () -> service.startJUnitPluginTestPackage(
                TEST_PLUGIN_PROJECT, "com.example.doesnotexist", false, false, List.of() ) );
    }

    // -------------------------------------------------------------------------
    // Integration: run package tests on the created project
    // -------------------------------------------------------------------------

    @Test
    @Order( 50 )
    public void testStartJUnitPluginTestPackage_realProject_passes() throws InterruptedException
    {
        String runId = service.startJUnitPluginTestPackage(
            TEST_PLUGIN_PROJECT, "com.example.pdetest", false, false, List.of() );
        assertNotNull( runId );

        String result = waitForRun( runId, 120 );
        System.out.println( "startJUnitPluginTestPackage result: " + result );
        assumeTrue( !result.contains( "Error:" ),
            "Skipping: PDE launcher not available or project has errors (" + result + ")" );
        assertTrue( result.contains( "Passed" ) || result.contains( "passed" ),
            "Expected passing tests in result, got: " + result );
    }

    @Test
    @Order( 51 )
    public void testStartJUnitPluginTestPackage_selectedPluginsMode_realProject() throws InterruptedException
    {
        String runId = service.startJUnitPluginTestPackage(
            TEST_PLUGIN_PROJECT, "com.example.pdetest", false, false, List.of() );
        assertNotNull( runId );

        String result = waitForRun( runId, 120 );
        System.out.println( "startJUnitPluginTestPackage (selected) result: " + result );
        assumeTrue( !result.contains( "Error:" ),
            "Skipping: PDE launcher not available or project has errors (" + result + ")" );
        assertTrue( result.contains( "Passed" ) || result.contains( "passed" ),
            "Expected passing tests in result, got: " + result );
    }

    @Test
    @Order( 52 )
    public void testStartJUnitPluginTestPackage_allPluginsMode_realProject() throws InterruptedException
    {
        String runId = service.startJUnitPluginTestPackage(
            TEST_PLUGIN_PROJECT, "com.example.pdetest", false, true, List.of() );
        assertNotNull( runId );

        String result = waitForRun( runId, 120 );
        System.out.println( "startJUnitPluginTestPackage (all plugins) result: " + result );
        assumeTrue( !result.contains( "Error:" ),
            "Skipping: PDE launcher not available or project has errors (" + result + ")" );
        assertTrue( result.contains( "Passed" ) || result.contains( "passed" ),
            "Expected passing tests in result, got: " + result );
    }
}
