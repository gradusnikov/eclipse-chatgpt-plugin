package com.github.gradusnikov.eclipse.plugin.assistai.mcp.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

import com.github.gradusnikov.eclipse.assistai.mcp.results.ActiveTargetResponse;
import com.github.gradusnikov.eclipse.assistai.mcp.results.ActiveTargetResponse.TargetStatus;
import com.github.gradusnikov.eclipse.assistai.mcp.results.Diagnostic;
import com.github.gradusnikov.eclipse.assistai.mcp.results.DiagnosticCode;
import com.github.gradusnikov.eclipse.assistai.mcp.results.TestRunResponse;
import com.github.gradusnikov.eclipse.assistai.mcp.results.TestRunResponse.RunStatus;
import com.github.gradusnikov.eclipse.assistai.mcp.services.PDEService;

@TestInstance( TestInstance.Lifecycle.PER_CLASS )
@TestMethodOrder( MethodOrderer.OrderAnnotation.class )
public class ServicePluginPDETest extends AbstractOperationPDETest
{
    private static final String TEST_PLUGIN_PROJECT = "PDEServiceTest_PluginProject";

    private PDEService service;
    private BundleContext bundleContext;

    @BeforeAll
    public void setUp() throws Exception
    {
        bundleContext = FrameworkUtil.getBundle( ServicePluginPDETest.class ).getBundleContext();

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
        initOperationRegistry( context );

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

        // Determine the default VM's major Java version so compliance settings match at runtime
        org.eclipse.jdt.launching.IVMInstall defaultVm = org.eclipse.jdt.launching.JavaRuntime.getDefaultVMInstall();
        String javaVersion = "21"; // fallback
        if ( defaultVm instanceof org.eclipse.jdt.launching.AbstractVMInstall avm )
        {
            String v = avm.getJavaVersion();
            if ( v != null )
            {
                javaVersion = v.contains( "." ) ? v.substring( 0, v.indexOf( '.' ) ) : v;
            }
        }

        String manifest =
            "Manifest-Version: 1.0\n" +
            "Bundle-ManifestVersion: 2\n" +
            "Bundle-Name: PDE Test Plugin\n" +
            "Bundle-SymbolicName: " + TEST_PLUGIN_PROJECT + "\n" +
            "Bundle-Version: 1.0.0\n" +
            "Bundle-RequiredExecutionEnvironment: JavaSE-" + javaVersion + "\n" +
            "Require-Bundle: org.junit\n" +
            "Export-Package: com.example.pdetest\n";
        createFile( project, "META-INF/MANIFEST.MF", manifest, monitor );

        String buildProps =
            "source.. = src/\n" +
            "output.. = bin/\n" +
            "bin.includes = META-INF/,.\n";
        createFile( project, "build.properties", buildProps, monitor );

        // Force Java compliance to match the default VM
        javaProject.setOption( JavaCore.COMPILER_SOURCE, javaVersion );
        javaProject.setOption( JavaCore.COMPILER_COMPLIANCE, javaVersion );
        javaProject.setOption( JavaCore.COMPILER_CODEGEN_TARGET_PLATFORM, javaVersion );

        IClasspathEntry srcEntry = JavaCore.newSourceEntry( srcFolder.getFullPath() );
        // Use the default JRE container — resolves to whatever VM Eclipse is configured to use
        IClasspathEntry jreEntry = JavaCore.newContainerEntry(
            IPath.fromOSString( org.eclipse.jdt.launching.JavaRuntime.JRE_CONTAINER ) );
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

        String secondTestSource =
            "package com.example.pdetest;\n" +
            "import org.junit.Test;\n" +
            "import static org.junit.Assert.assertEquals;\n" +
            "public class SecondPluginTest {\n" +
            "    @Test\n" +
            "    public void testSecond() {\n" +
            "        assertEquals(4, 2 + 2);\n" +
            "    }\n" +
            "}\n";
        pkg.createCompilationUnit( "SecondPluginTest.java", secondTestSource, true, monitor );

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
    public void testGetActiveTarget_describesTheTargetInForce()
    {
        ActiveTargetResponse result = service.getActiveTarget();
        assertNotNull( result, "getActiveTarget must not return null" );
        assertNotEquals( TargetStatus.FAILED, result.status(),
            () -> String.valueOf( result.diagnostics() ) );
    }

    @Test
    @Order( 2 )
    public void testGetActiveTarget_countsBundlesOnlyOnceResolved()
    {
        ActiveTargetResponse result = service.getActiveTarget();
        assertEquals( result.resolved(), result.bundleCount() != null,
            "0 would say the target resolved to nothing; an unresolved target has no count" );
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
    public void testSetActiveTarget_missingFile_isAFailureWithACode()
    {
        ActiveTargetResponse result = service.setActiveTarget( "/NonExistentProject/does-not-exist.target" );
        assertNotNull( result );
        assertEquals( TargetStatus.FAILED, result.status() );
        assertEquals( List.of( DiagnosticCode.RESOURCE_NOT_FOUND ),
            result.diagnostics().stream().map( Diagnostic::code ).toList() );
    }

    // -------------------------------------------------------------------------
    // reloadTarget
    // -------------------------------------------------------------------------

    @Test
    @Order( 5 )
    public void testReloadTarget_reportsAStatus()
    {
        ActiveTargetResponse result = service.reloadTarget();
        assertNotNull( result );
        assertNotNull( result.status() );
    }

    @Test
    @Order( 6 )
    public void testReloadTarget_agreesWithGetActiveTarget()
    {
        // With no target file set there is nothing to reload - which reload used to call
        // an error while getActiveTarget called the identical state perfectly normal.
        ActiveTargetResponse reloaded = service.reloadTarget();
        assertEquals( service.getActiveTarget().explicitTarget(), reloaded.explicitTarget() );
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
    public void testRunJUnitPluginTests_nonExistentProject_reportsProjectNotFound()
    {
        TestRunResponse result = service.runJUnitPluginTests( "NonExistentProject_PDEPlugin", 10 );
        assertNotNull( result );
        // The caller can tell "you named a project that is not here" from "the tests ran
        // and failed" without reading either sentence.
        assertEquals( RunStatus.FAILED_TO_START, result.status() );
        assertEquals( List.of( DiagnosticCode.PROJECT_NOT_FOUND ),
            result.diagnostics().stream().map( Diagnostic::code ).toList() );
        assertEquals( 0, result.summary().total() );
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
    public void testRunJUnitPluginTestClass_nonExistentProject_reportsProjectNotFound()
    {
        TestRunResponse result = service.runJUnitPluginTestClass(
            "NonExistentProject_PDEPlugin", "com.example.MyTest", 10 );
        assertNotNull( result );
        assertEquals( RunStatus.FAILED_TO_START, result.status() );
        assertEquals( List.of( DiagnosticCode.PROJECT_NOT_FOUND ),
            result.diagnostics().stream().map( Diagnostic::code ).toList() );
        assertEquals( List.of( "com.example.MyTest" ), result.requestedClasses(),
            "what was asked for is echoed back even when nothing ran" );
    }

    // -------------------------------------------------------------------------
    // runJUnitPluginTestClasses - input validation
    // -------------------------------------------------------------------------

    @Test
    @Order( 12 )
    public void testRunJUnitPluginTestClasses_emptyClassNames_throwsIllegalArgument()
    {
        assertThrows( IllegalArgumentException.class,
            () -> service.runJUnitPluginTestClasses( "SomeProject", List.of(), 60 ) );
    }

    @Test
    @Order( 12 )
    public void testRunJUnitPluginTestClasses_missingClasses_reportsAllNames()
    {
        TestRunResponse result = service.runJUnitPluginTestClasses(
            TEST_PLUGIN_PROJECT, List.of( "missing.FirstPDETest", "missing.SecondPDETest" ), 60 );

        assertEquals( RunStatus.FAILED_TO_START, result.status() );
        assertEquals( List.of( DiagnosticCode.TEST_CLASS_NOT_FOUND ),
            result.diagnostics().stream().map( Diagnostic::code ).toList() );
        // Both names, not just the first one to fail to resolve.
        String message = result.diagnostics().get( 0 ).message();
        assertTrue( message.contains( "missing.FirstPDETest" ), message );
        assertTrue( message.contains( "missing.SecondPDETest" ), message );
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
    public void testRunJUnitPluginTestClass_realProject_passes() throws Exception
    {
        runWithOperationAndPrintConsoleOnProblems( "testRunJUnitPluginTestClass_realProject_passes", () -> {
            TestRunResponse result = service.runJUnitPluginTestClass(
                TEST_PLUGIN_PROJECT,
                "com.example.pdetest.SimplePluginTest",
                120 );
            System.out.println( "runJUnitPluginTestClass result: " + result );
            assertLaunched( result );
            assertEquals( RunStatus.COMPLETED, result.status(), result.summaryText() );
            assertTrue( result.summary().passed() > 0, result.summaryText() );
        } );
    }

    @Test
    @Order( 21 )
    public void testRunJUnitPluginTests_realProject_launches() throws Exception
    {
        runWithOperationAndPrintConsoleOnProblems( "testRunJUnitPluginTests_realProject_launches", () -> {
            TestRunResponse result = service.runJUnitPluginTests(
                TEST_PLUGIN_PROJECT,
                120 );
            System.out.println( "runJUnitPluginTests result: " + result );
            assertLaunched( result );
            // A wrong CONTAINER format used to surface as "does not exist" prose; it is now
            // a launch that never starts, which is what FAILED_TO_START names.
            assertTrue( result.status() == RunStatus.COMPLETED || result.status() == RunStatus.TIMED_OUT,
                result.summaryText() );
        } );
    }

    @Test
    @Order( 22 )
    public void testRunJUnitPluginTestClasses_realProject_runsSelectedClassesOnce() throws Exception
    {
        runWithOperationAndPrintConsoleOnProblems( "testRunJUnitPluginTestClasses_realProject_runsSelectedClassesOnce", () -> {
            TestRunResponse result = service.runJUnitPluginTestClasses(
                TEST_PLUGIN_PROJECT,
                List.of( "com.example.pdetest.SimplePluginTest",
                         "com.example.pdetest.SecondPluginTest",
                         "com.example.pdetest.SimplePluginTest" ),
                120 );
            System.out.println( "runJUnitPluginTestClasses result: " + result );
            assertLaunched( result );
            // The repeated class is de-duplicated, so two tests run, not three.
            assertEquals( 2, result.summary().total(), result.summaryText() );
            assertEquals( 2, result.summary().passed(), result.summaryText() );
        } );
    }

    private static void assertLaunched( TestRunResponse result )
    {
        assertTrue( result.status() != RunStatus.FAILED_TO_START,
            "Skipping: PDE launcher failed to start (" + result.summaryText() + ")" );
    }

    // -------------------------------------------------------------------------
    // includeAllPlugins parameter
    // -------------------------------------------------------------------------

    @Test
    @Order( 22 )
    public void testRunJUnitPluginTests_includeAllPlugins_nonExistentProject()
    {
        assertProjectNotFound(
            service.runJUnitPluginTests( "NonExistentProject_PDEPlugin", 10, false, true, List.of() ) );
    }

    @Test
    @Order( 23 )
    public void testRunJUnitPluginTests_selectedPlugins_nonExistentProject()
    {
        assertProjectNotFound(
            service.runJUnitPluginTests( "NonExistentProject_PDEPlugin", 10, false, false, List.of() ) );
    }

    @Test
    @Order( 24 )
    public void testRunJUnitPluginTests_withAdditionalBundles_nonExistentProject()
    {
        assertProjectNotFound( service.runJUnitPluginTests(
            "NonExistentProject_PDEPlugin", 10, false, false,
            List.of( "org.eclipse.core.runtime", "org.eclipse.ui" ) ) );
    }

    @Test
    @Order( 25 )
    public void testRunJUnitPluginTestClass_includeAllPlugins_nonExistentProject()
    {
        assertProjectNotFound( service.runJUnitPluginTestClass(
            "NonExistentProject_PDEPlugin", "com.example.MyTest", 10, false, true, List.of() ) );
    }

    @Test
    @Order( 26 )
    public void testRunJUnitPluginTestClass_withAdditionalBundles_nonExistentProject()
    {
        assertProjectNotFound( service.runJUnitPluginTestClass(
            "NonExistentProject_PDEPlugin", "com.example.MyTest", 10, false, false,
            List.of( "org.eclipse.core.runtime" ) ) );
    }

    private static void assertProjectNotFound( TestRunResponse result )
    {
        assertNotNull( result );
        assertEquals( RunStatus.FAILED_TO_START, result.status(), result.summaryText() );
        assertEquals( List.of( DiagnosticCode.PROJECT_NOT_FOUND ),
            result.diagnostics().stream().map( Diagnostic::code ).toList() );
    }

    // -------------------------------------------------------------------------
    // Integration: run with selected plugins mode on real project
    // -------------------------------------------------------------------------

    @Test
    @Order( 30 )
    public void testRunJUnitPluginTestClass_selectedPluginsMode_realProject() throws Exception
    {
        runWithOperationAndPrintConsoleOnProblems( "testRunJUnitPluginTestClass_selectedPluginsMode_realProject", () -> {
            TestRunResponse result = service.runJUnitPluginTestClass(
                TEST_PLUGIN_PROJECT,
                "com.example.pdetest.SimplePluginTest",
                120, false, false, List.of() );
            System.out.println( "runJUnitPluginTestClass (selected) result: " + result );
            assertLaunched( result );
            assertEquals( RunStatus.COMPLETED, result.status(), result.summaryText() );
            assertTrue( result.summary().passed() > 0, result.summaryText() );
        } );
    }

    @Test
    @Order( 31 )
    public void testRunJUnitPluginTestClass_allPluginsMode_realProject() throws Exception
    {
        runWithOperationAndPrintConsoleOnProblems( "testRunJUnitPluginTestClass_allPluginsMode_realProject", () -> {
            TestRunResponse result = service.runJUnitPluginTestClass(
                TEST_PLUGIN_PROJECT,
                "com.example.pdetest.SimplePluginTest",
                120, false, true, List.of() );
            System.out.println( "runJUnitPluginTestClass (all) result: " + result );
            assertLaunched( result );
            assertEquals( RunStatus.COMPLETED, result.status(), result.summaryText() );
            assertTrue( result.summary().passed() > 0, result.summaryText() );
        } );
    }

    @Test
    @Order( 32 )
    public void testRunJUnitPluginTests_selectedPluginsMode_realProject() throws Exception
    {
        runWithOperationAndPrintConsoleOnProblems( "testRunJUnitPluginTests_selectedPluginsMode_realProject", () -> {
            TestRunResponse result = service.runJUnitPluginTests(
                TEST_PLUGIN_PROJECT, 120, false, false, List.of() );
            System.out.println( "runJUnitPluginTests (selected) result: " + result );
            assertLaunched( result );
            assertTrue( result.status() == RunStatus.COMPLETED || result.status() == RunStatus.TIMED_OUT,
                result.summaryText() );
        } );
    }

}
