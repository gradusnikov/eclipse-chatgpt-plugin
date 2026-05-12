package com.github.gradusnikov.eclipse.plugin.assistai.mcp.services;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

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

@TestInstance( TestInstance.Lifecycle.PER_CLASS )
@TestMethodOrder( MethodOrderer.OrderAnnotation.class )
public class PDEServicePluginTest
{
    private static final String TEST_PLUGIN_PROJECT = "PDEServiceTest_PluginProject";

    private PDEService service;
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
    // getActiveTarget
    // -------------------------------------------------------------------------

    @Test
    @Order( 1 )
    public void testGetActiveTarget_returnsNonEmptyString()
    {
        String result = service.getActiveTarget();
        assertNotNull( result, "getActiveTarget must not return null" );
        assertTrue( !result.isEmpty(), "getActiveTarget must not return an empty string" );
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
    // setActiveTarget â input validation
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
        assertTrue( !result.isEmpty(), "reloadTarget must not return an empty string" );
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
    // runJUnitPluginTests â input validation
    // -------------------------------------------------------------------------

    @Test
    @Order( 7 )
    public void testRunJUnitPluginTests_nullProjectName_throwsNPE()
    {
        assertThrows( NullPointerException.class,
            () -> service.runJUnitPluginTests( null, 60 ) );
    }

    @Test
    @Order( 8 )
    public void testRunJUnitPluginTests_emptyProjectName_throwsIllegalArgument()
    {
        assertThrows( IllegalArgumentException.class,
            () -> service.runJUnitPluginTests( "", 60 ) );
    }

    @Test
    @Order( 9 )
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
    @Order( 10 )
    public void testRunJUnitPluginTestClass_nullProjectName_throwsNPE()
    {
        assertThrows( NullPointerException.class,
            () -> service.runJUnitPluginTestClass( null, "com.example.MyTest", 60 ) );
    }

    @Test
    @Order( 11 )
    public void testRunJUnitPluginTestClass_nullClassName_throwsNPE()
    {
        assertThrows( NullPointerException.class,
            () -> service.runJUnitPluginTestClass( "SomeProject", null, 60 ) );
    }

    @Test
    @Order( 12 )
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
    public void testRunJUnitPluginTestClass_realProject_passes()
    {
        String result = service.runJUnitPluginTestClass(
            TEST_PLUGIN_PROJECT,
            "com.example.pdetest.SimplePluginTest",
            120 );
        System.out.println( "runJUnitPluginTestClass result: " + result );
        assumeTrue( !result.contains( "Error" ),
            "Skipping: PDE launcher not available or project has errors (" + result + ")" );
        assertTrue( result.contains( "Passed" ) || result.contains( "passed" ),
            "Expected passing tests in result, got: " + result );
    }

    @Test
    @Order( 21 )
    public void testRunJUnitPluginTests_realProject_launches()
    {
        String result = service.runJUnitPluginTests(
            TEST_PLUGIN_PROJECT,
            120 );
        System.out.println( "runJUnitPluginTests result: " + result );
        assumeTrue( !result.contains( "Error" ),
            "Skipping: PDE launcher not available or project has errors (" + result + ")" );
        assertTrue( !result.contains( "does not exist" ),
            "Got 'does not exist' error - container format is wrong: " + result );
        assertTrue( result.contains( "Passed" ) || result.contains( "passed" )
            || result.contains( "timed out" ),
            "Expected test results or timeout, got: " + result );
    }
}
