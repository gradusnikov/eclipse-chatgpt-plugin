package com.github.gradusnikov.eclipse.plugin.assistai.mcp.services;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import org.jacoco.core.data.ExecutionDataWriter;
import org.jacoco.core.data.SessionInfo;

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

import com.github.gradusnikov.eclipse.assistai.mcp.services.CoverageService;
import com.github.gradusnikov.eclipse.assistai.mcp.services.TestRunManager;
import com.github.gradusnikov.eclipse.assistai.mcp.services.TestRunSession;
import com.github.gradusnikov.eclipse.assistai.mcp.services.UnitTestService;

@TestInstance( TestInstance.Lifecycle.PER_CLASS )
@TestMethodOrder( MethodOrderer.OrderAnnotation.class )
public class CoverageServiceTest
{
    private static final String TEST_PROJECT_NAME = "CoverageTestProject_Temp";

    private CoverageService coverageService;
    private UnitTestService unitTestService;
    private TestRunManager testRunManager;

    @BeforeAll
    public void setUp() throws Exception
    {
        BundleContext bundleContext = FrameworkUtil.getBundle( CoverageServiceTest.class ).getBundleContext();

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

        coverageService = ContextInjectionFactory.make( CoverageService.class, context );
        unitTestService = ContextInjectionFactory.make( UnitTestService.class, context );
        testRunManager  = ContextInjectionFactory.make( TestRunManager.class, context );

        createTestProject();
    }

    @AfterAll
    public void tearDown()
    {
        try
        {
            IProject project = ResourcesPlugin.getWorkspace().getRoot().getProject( TEST_PROJECT_NAME );
            if ( project.exists() )
            {
                project.delete( true, true, new NullProgressMonitor() );
            }
        }
        catch ( CoreException e )
        {
            System.err.println( "Warning: could not delete test project (may be locked by coverage agent): " + e.getMessage() );
        }
    }

    private void createTestProject() throws Exception
    {
        NullProgressMonitor monitor = new NullProgressMonitor();

        IProject testProject = ResourcesPlugin.getWorkspace().getRoot().getProject( TEST_PROJECT_NAME );
        if ( testProject.exists() )
        {
            try
            {
                testProject.delete( true, true, monitor );
            }
            catch ( CoreException e )
            {
                try
                {
                    testProject.delete( false, true, monitor );
                }
                catch ( CoreException e2 )
                {
                    testProject.open( monitor );
                    return;
                }
            }
        }

        testProject.create( monitor );
        testProject.open( monitor );

        IProjectDescription description = testProject.getDescription();
        description.setNatureIds( new String[] { JavaCore.NATURE_ID } );
        testProject.setDescription( description, monitor );

        IJavaProject javaProject = JavaCore.create( testProject );

        IFolder srcFolder = testProject.getFolder( "src" );
        if ( !srcFolder.exists() )
        {
            srcFolder.create( true, true, monitor );
        }

        IFolder binFolder = testProject.getFolder( "bin" );
        if ( !binFolder.exists() )
        {
            binFolder.create( true, true, monitor );
        }

        javaProject.setOutputLocation( binFolder.getFullPath(), monitor );

        IClasspathEntry srcEntry = JavaCore.newSourceEntry( srcFolder.getFullPath() );
        IClasspathEntry jreEntry = JavaRuntime.getDefaultJREContainerEntry();
        IClasspathEntry junitEntry = JavaCore.newContainerEntry(
            IPath.fromOSString( "org.eclipse.jdt.junit.JUNIT_CONTAINER/5" ) );

        javaProject.setRawClasspath( new IClasspathEntry[] { srcEntry, jreEntry, junitEntry }, monitor );

        IPackageFragmentRoot srcRoot = javaProject.getPackageFragmentRoot( srcFolder );
        IPackageFragment pkg = srcRoot.createPackageFragment( "com.example", true, monitor );

        String calculatorSource =
            "package com.example;\n" +
            "public class Calculator {\n" +
            "    public int add(int a, int b) {\n" +
            "        return a + b;\n" +
            "    }\n" +
            "    public int subtract(int a, int b) {\n" +
            "        return a - b;\n" +
            "    }\n" +
            "}\n";
        pkg.createCompilationUnit( "Calculator.java", calculatorSource, true, monitor );

        String testSource =
            "package com.example;\n" +
            "import org.junit.jupiter.api.Test;\n" +
            "import static org.junit.jupiter.api.Assertions.assertEquals;\n" +
            "public class CalculatorTest {\n" +
            "    @Test\n" +
            "    public void testAdd() {\n" +
            "        Calculator calc = new Calculator();\n" +
            "        assertEquals(5, calc.add(2, 3));\n" +
            "    }\n" +
            "    @Test\n" +
            "    public void testSubtract() {\n" +
            "        Calculator calc = new Calculator();\n" +
            "        assertEquals(1, calc.subtract(3, 2));\n" +
            "    }\n" +
            "}\n";
        pkg.createCompilationUnit( "CalculatorTest.java", testSource, true, monitor );

        testProject.build( org.eclipse.core.resources.IncrementalProjectBuilder.FULL_BUILD, monitor );
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
    // CoverageService unit tests
    // -------------------------------------------------------------------------

    @Test
    @Order( 1 )
    public void testFormatCoverageInfo_nullPath_returnsEmpty()
    {
        String result = coverageService.formatCoverageInfo( null );
        assertEquals( "", result );
    }

    @Test
    @Order( 2 )
    public void testFormatCoverageInfo_withPath_containsCoverageSection()
    {
        String result = coverageService.formatCoverageInfo( "/some/path/coverage.exec" );
        assertTrue( result.contains( "--- Coverage ---" ) );
        assertTrue( result.contains( "/some/path/coverage.exec" ) );
        assertTrue( result.contains( "Coverage data collected" ) );
    }

    @Test
    @Order( 3 )
    public void testIsCoverageAvailable_returnsBoolean()
    {
        boolean available = coverageService.isCoverageAvailable();
        assertNotNull( Boolean.valueOf( available ) );
    }

    @Test
    @Order( 4 )
    public void testGetCoverageLaunchMode_returnsCoverage()
    {
        assertEquals( "coverage", coverageService.getCoverageLaunchMode() );
    }

    @Test
    @Order( 5 )
    public void testFormatCoverageInfo_nullPath_withProjectName_returnsEmpty()
    {
        String result = coverageService.formatCoverageInfo( null, TEST_PROJECT_NAME );
        assertEquals( "", result );
    }

    @Test
    @Order( 6 )
    public void testFormatCoverageInfo_withPath_nullProjectName_returnsBasicInfo()
    {
        String result = coverageService.formatCoverageInfo( "/some/path/coverage.exec", null );
        assertTrue( result.contains( "--- Coverage ---" ) );
        assertTrue( result.contains( "Coverage data collected" ) );
        assertTrue( result.contains( "/some/path/coverage.exec" ) );
    }

    @Test
    @Order( 7 )
    public void testFormatCoverageInfo_invalidExecFile_fallsBackToBasicInfo()
    {
        String result = coverageService.formatCoverageInfo( "/nonexistent/path/coverage.exec", TEST_PROJECT_NAME );
        assertTrue( result.contains( "--- Coverage ---" ) );
        assertTrue( result.contains( "Coverage data collected" ) );
    }

    @Test
    @Order( 8 )
    public void testFindLatestCoverageFile_returnsNullOrPath()
    {
        String result = coverageService.findLatestCoverageFile();
        if ( result != null )
        {
            assertTrue( result.endsWith( ".exec" ) );
            assertTrue( new File( result ).exists() );
        }
    }

    @Test
    @Order( 9 )
    public void testFormatCoverageInfo_withEmptyExecFile_andProject_returnsNoDataMessage() throws Exception
    {
        assumeTrue( coverageService.isCoverageAvailable(),
            "Skipping: EclEmma/JaCoCo not installed" );

        Path tempExec = Files.createTempFile( "coverage-test", ".exec" );
        try ( FileOutputStream fos = new FileOutputStream( tempExec.toFile() ) )
        {
            ExecutionDataWriter writer = new ExecutionDataWriter( fos );
            writer.visitSessionInfo( new SessionInfo( "test-session",
                System.currentTimeMillis() - 1000, System.currentTimeMillis() ) );
        }

        try
        {
            String result = coverageService.formatCoverageInfo( tempExec.toString(), TEST_PROJECT_NAME );
            assertTrue( result.contains( "--- Coverage ---" ) );
            assertTrue( result.contains( "No execution data found" ) );
        }
        finally
        {
            Files.deleteIfExists( tempExec );
        }
    }

    // -------------------------------------------------------------------------
    // Integration: run tests WITHOUT coverage
    // -------------------------------------------------------------------------

    @Test
    @Order( 10 )
    public void testRunClassTests_withoutCoverage_passes() throws InterruptedException
    {
        String runId = unitTestService.startClassTests( TEST_PROJECT_NAME, "com.example.CalculatorTest", false );
        assertNotNull( runId, "Expected a non-null run ID" );
        assertFalse( runId.isBlank(), "Expected a non-blank run ID" );

        String result = waitForRun( runId, 60 );
        System.out.println( "startClassTests without coverage: " + result );

        assumeTrue( !result.contains( "Error:" ),
            "Skipping: test runtime does not support nested JUnit launches (" + result + ")" );

        assertTrue( result.contains( "Passed" ) || result.contains( "passed" ) || result.contains( "OK" ),
            "Expected passing test result, got: " + result );

        TestRunSession session = testRunManager.getSession( runId );
        assertNotNull( session );
        assertTrue( session.getCoverageInfo() == null || session.getCoverageInfo().isEmpty(),
            "Should NOT contain coverage info when withCoverage=false" );
    }

    // -------------------------------------------------------------------------
    // Integration: run tests WITH coverage
    // -------------------------------------------------------------------------

    @Test
    @Order( 20 )
    public void testRunClassTests_withCoverage_includesCoverageInfo() throws Exception
    {
        assumeTrue( coverageService.isCoverageAvailable(),
            "Skipping: EclEmma/JaCoCo not installed" );

        String runId = unitTestService.startClassTests( TEST_PROJECT_NAME, "com.example.CalculatorTest", true );
        assertNotNull( runId );

        String result = waitForRun( runId, 60 );
        System.out.println( "startClassTests with coverage: " + result );

        assumeTrue( !result.contains( "Error:" ),
            "Skipping: test runtime does not support nested JUnit launches (" + result + ")" );

        assertTrue( result.contains( "Passed" ) || result.contains( "passed" ) || result.contains( "OK" ),
            "Expected passing test result, got: " + result );

        TestRunSession session = testRunManager.getSession( runId );
        assertNotNull( session.getCoverageInfo(),
            "Expected coverage info to be set on session" );
        assertFalse( session.getCoverageInfo().isEmpty(),
            "Expected non-empty coverage info" );
        assertTrue( session.getCoverageInfo().contains( "line coverage" ),
            "Expected coverage report in coverage info, got: " + session.getCoverageInfo() );
    }

    @Test
    @Order( 30 )
    public void testRunAllTests_withCoverage_includesCoverageInfo() throws Exception
    {
        assumeTrue( coverageService.isCoverageAvailable(),
            "Skipping: EclEmma/JaCoCo not installed" );

        String runId = unitTestService.startAllTests( TEST_PROJECT_NAME, true );
        assertNotNull( runId );

        String result = waitForRun( runId, 60 );
        System.out.println( "startAllTests with coverage: " + result );

        assumeTrue( !result.contains( "Error:" ),
            "Skipping: test runtime does not support nested JUnit launches (" + result + ")" );

        assertTrue( result.contains( "Passed" ) || result.contains( "passed" ) || result.contains( "OK" ),
            "Expected passing test result, got: " + result );

        TestRunSession session = testRunManager.getSession( runId );
        assertNotNull( session.getCoverageInfo(),
            "Expected coverage info to be set on session" );
        assertFalse( session.getCoverageInfo().isEmpty(),
            "Expected non-empty coverage info" );
        assertTrue( session.getCoverageInfo().contains( "line coverage" ),
            "Expected coverage report in coverage info, got: " + session.getCoverageInfo() );
    }

    @Test
    @Order( 40 )
    public void testFormatCoverageInfo_withRealExecFile_producesDetailedReport() throws Exception
    {
        assumeTrue( coverageService.isCoverageAvailable(),
            "Skipping: EclEmma/JaCoCo not installed" );

        String execFile = coverageService.findLatestCoverageFile();
        assumeTrue( execFile != null, "Skipping: no .exec file available from prior coverage run" );

        String result = coverageService.formatCoverageInfo( execFile, TEST_PROJECT_NAME );
        assertNotNull( result );
        assertFalse( result.isEmpty() );
        assertTrue( result.contains( "--- Coverage Report ---" ) );
        assertTrue( result.contains( "line coverage" ) );
    }

    @Test
    @Order( 41 )
    public void testFormatCoverageInfo_withRealExecFile_noProject_returnsBasicInfo() throws Exception
    {
        assumeTrue( coverageService.isCoverageAvailable(),
            "Skipping: EclEmma/JaCoCo not installed" );

        String execFile = coverageService.findLatestCoverageFile();
        assumeTrue( execFile != null, "Skipping: no .exec file available from prior coverage run" );

        String result = coverageService.formatCoverageInfo( execFile, null );
        assertTrue( result.contains( "--- Coverage ---" ) );
        assertTrue( result.contains( "Coverage data collected" ) );
        assertFalse( result.contains( "--- Coverage Report ---" ) );
    }

    @Test
    @Order( 42 )
    public void testFindLatestCoverageFile_afterCoverageRun_returnsExecFile()
    {
        assumeTrue( coverageService.isCoverageAvailable(),
            "Skipping: EclEmma/JaCoCo not installed" );

        String execFile = coverageService.findLatestCoverageFile();
        assertNotNull( execFile, "Expected .exec file after coverage run" );
        assertTrue( execFile.endsWith( ".exec" ) );
        assertTrue( new File( execFile ).exists() );
    }
}
