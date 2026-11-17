package com.github.gradusnikov.eclipse.assistai.mcp.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IFolder;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IProjectDescription;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IncrementalProjectBuilder;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.FileLocator;
import org.eclipse.core.runtime.ILog;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.core.runtime.Platform;
import org.eclipse.debug.core.DebugPlugin;
import org.eclipse.debug.core.ILaunch;
import org.eclipse.debug.core.ILaunchConfiguration;
import org.eclipse.debug.core.ILaunchConfigurationType;
import org.eclipse.debug.core.ILaunchConfigurationWorkingCopy;
import org.eclipse.debug.core.ILaunchManager;
import org.eclipse.e4.core.contexts.ContextInjectionFactory;
import org.eclipse.e4.core.contexts.EclipseContextFactory;
import org.eclipse.e4.core.contexts.IEclipseContext;
import org.eclipse.e4.ui.di.UISynchronize;
import org.eclipse.jdt.core.IClasspathEntry;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.IMethod;
import org.eclipse.jdt.core.IPackageFragment;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.launching.IJavaLaunchConfigurationConstants;
import org.eclipse.jdt.launching.JavaRuntime;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.IWorkbench;
import org.eclipse.ui.PlatformUI;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.osgi.framework.Bundle;

import com.github.gradusnikov.eclipse.assistai.Activator;
import com.github.gradusnikov.eclipse.assistai.mcp.results.DiagnosticCode;
import com.github.gradusnikov.eclipse.assistai.mcp.results.LaunchConfigurationsResponse;
import com.github.gradusnikov.eclipse.assistai.mcp.results.LaunchResponse;
import com.github.gradusnikov.eclipse.assistai.mcp.results.TestNGLaunchConfigurationResponse;

/**
 * Creating TestNG launch configurations through {@link TestNGLaunchService}.
 * <p>
 * The default test runtime has no TestNG for Eclipse, which is the case that matters most:
 * AssistAI has to load there and say why it cannot help. Every check that comes before the
 * launch type is looked up - arguments, project, class, method, package, suite, groups, a
 * name clash - is therefore tested without TestNG. The fixture compiles against a stub
 * {@code org.testng.annotations.Test} then, so the group scan still sees resolved TestNG
 * annotations.
 * <p>
 * The tests that need the real launch type assume it is installed. Run them with
 * {@code ./mvnw verify -Ptestng}, which adds TestNG for Eclipse to the test runtime; the
 * fixture then compiles against TestNG's own jar, the saved configurations are compared
 * attribute for attribute with the ones upstream's own {@code LaunchUtil} saves, and one is
 * actually run.
 */
public class TestNGLaunchServicePDETest
{
    private static final String PROJECT = "TestNGLaunchTestProject";
    private static final String TESTNG_TYPE_ID = "org.testng.eclipse.launchconfig";
    private static final String P = "org.testng.eclipse.";
    private static final NullProgressMonitor monitor = new NullProgressMonitor();

    private IProject project;
    private IJavaProject javaProject;
    private TestNGLaunchService service;
    private JavaLaunchService launchService;
    private final List<String> configsToDelete = new ArrayList<>();

    @BeforeEach
    public void beforeEach() throws Exception
    {
        project = ResourcesPlugin.getWorkspace().getRoot().getProject( PROJECT );
        if ( project.exists() )
        {
            project.delete( true, true, monitor );
        }
        project.create( monitor );
        project.open( monitor );
        // Natures are configured - and the Java builder added - only by setDescription, not
        // by creating a project from a description, so without this nothing ever compiles.
        IProjectDescription description = project.getDescription();
        description.setNatureIds( new String[] { JavaCore.NATURE_ID } );
        project.setDescription( description, monitor );

        IFolder source = project.getFolder( "src" );
        source.create( IResource.NONE, true, monitor );
        IFolder sample = source.getFolder( "sample" );
        sample.create( IResource.NONE, true, monitor );

        List<IClasspathEntry> classpath = new ArrayList<>( List.of(
                JavaCore.newSourceEntry( source.getFullPath() ),
                JavaCore.newContainerEntry( IPath.fromOSString( JavaRuntime.JRE_CONTAINER ) ) ) );
        List<File> testngJars = testngLibraries();
        if ( !testngJars.isEmpty() )
        {
            for ( File jar : testngJars )
            {
                classpath.add( JavaCore.newLibraryEntry( IPath.fromOSString( jar.getAbsolutePath() ), null, null ) );
            }
        }
        else
        {
            IFolder annotations = source.getFolder( "org" );
            annotations.create( IResource.NONE, true, monitor );
            annotations = annotations.getFolder( "testng" );
            annotations.create( IResource.NONE, true, monitor );
            annotations = annotations.getFolder( "annotations" );
            annotations.create( IResource.NONE, true, monitor );
            write( annotations.getFile( "Test.java" ), """
                    package org.testng.annotations;

                    public @interface Test {
                        String[] groups() default {};
                    }
                    """ );
        }
        javaProject = JavaCore.create( project );
        javaProject.setRawClasspath( classpath.toArray( IClasspathEntry[]::new ),
                project.getFullPath().append( "bin" ), monitor );

        write( sample.getFile( "Groups.java" ), """
                package sample;

                public final class Groups {
                    public static final String SMOKE = "smoke";
                }
                """ );
        write( sample.getFile( "CalcTest.java" ), """
                package sample;

                import org.testng.annotations.Test;

                public class CalcTest {
                    @Test(groups = { "fast", Groups.SMOKE })
                    public void add() throws Exception {
                        mark( "add" );
                    }

                    @Test
                    public void sub() throws Exception {
                        mark( "sub" );
                    }

                    // Leaves a file behind per test that ran, where the launch's VM arguments say.
                    private static void mark( String name ) throws Exception {
                        String dir = System.getProperty( "assistai.marker.dir" );
                        if ( dir != null ) {
                            java.nio.file.Files.createFile( java.nio.file.Path.of( dir, name ) );
                        }
                    }
                }
                """ );
        write( sample.getFile( "SlowTest.java" ), """
                package sample;

                @org.testng.annotations.Test(groups = "slow, nightly")
                public class SlowTest {
                    public void run() {
                    }
                }
                """ );
        write( sample.getFile( "AbstractBase.java" ), """
                package sample;

                import org.testng.annotations.Test;

                public abstract class AbstractBase {
                    @Test(groups = "abstract-only")
                    public void inherited() {
                    }
                }
                """ );
        write( sample.getFile( "Outer.java" ), """
                package sample;

                import org.testng.annotations.Test;

                public class Outer {
                    public static class Inner {
                        @Test(groups = "nested")
                        public void deep() {
                        }
                    }
                }
                """ );
        write( sample.getFile( "SubTest.java" ), """
                package sample;

                public class SubTest extends AbstractBase {
                }
                """ );
        write( project.getFile( "testng.xml" ), """
                <!DOCTYPE suite SYSTEM "https://testng.org/testng-1.0.dtd">
                <suite name="fixture">
                  <test name="calc">
                    <classes>
                      <class name="sample.CalcTest"/>
                    </classes>
                  </test>
                </suite>
                """ );
        project.refreshLocal( IResource.DEPTH_INFINITE, monitor );
        project.build( IncrementalProjectBuilder.FULL_BUILD, monitor );

        IEclipseContext context = EclipseContextFactory.create();
        context.set( ILog.class, Activator.getDefault().getLog() );
        context.set( UISynchronize.class, new UISynchronize()
        {
            @Override public void syncExec( Runnable r ) { r.run(); }
            @Override public void asyncExec( Runnable r ) { r.run(); }
            @Override protected boolean isUIThread( Thread t ) { return false; }
            @Override protected void showBusyWhile( Runnable r ) { r.run(); }
            @Override protected boolean dispatchEvents() { return false; }
        } );
        service = ContextInjectionFactory.make( TestNGLaunchService.class, context );
        launchService = ContextInjectionFactory.make( JavaLaunchService.class, context );
    }

    @AfterEach
    public void afterEach() throws Exception
    {
        ILaunchManager lm = launchManager();
        for ( ILaunch launch : lm.getLaunches() )
        {
            if ( !launch.isTerminated() )
            {
                launch.terminate();
            }
        }
        for ( String name : configsToDelete )
        {
            ILaunchConfiguration config = find( name );
            if ( config != null )
            {
                config.delete();
            }
        }
        if ( project != null && project.exists() )
        {
            project.delete( true, true, monitor );
        }
    }

    // --- without TestNG for Eclipse ---

    @Test
    public void withoutTestNGTheToolSaysSoAndSavesNothing() throws Exception
    {
        assumeTrue( testngType() == null, "TestNG for Eclipse is installed here" );
        configsToDelete.add( "CalcTest" );

        TestNGLaunchConfigurationResponse response = service.createLaunchConfiguration( PROJECT, "sample.CalcTest",
                null, null, null, null, null, null, null );

        assertEquals( DiagnosticCode.TESTNG_LAUNCH_TYPE_MISSING, code( response ) );
        assertTrue( response.diagnostics().get( 0 ).message().contains( "testng-eclipse-update-site" ),
                "the diagnostic says where to get TestNG for Eclipse" );
        assertNull( response.configurationName() );
        assertNull( find( "CalcTest" ), "nothing is saved" );
    }

    @Test
    public void aRequestMustNameExactlyOneTarget()
    {
        assertEquals( DiagnosticCode.VALIDATION_ERROR,
                code( service.createLaunchConfiguration( PROJECT, null, null, null, null, null, null, null, null ) ),
                "no target" );
        assertEquals( DiagnosticCode.VALIDATION_ERROR,
                code( service.createLaunchConfiguration( PROJECT, "sample.CalcTest", null, "sample", null, null, null,
                        null, null ) ),
                "two targets" );
        assertEquals( DiagnosticCode.VALIDATION_ERROR,
                code( service.createLaunchConfiguration( PROJECT, null, "add", null, null, null, null, null, null ) ),
                "methodName without className" );
        assertEquals( DiagnosticCode.VALIDATION_ERROR,
                code( service.createLaunchConfiguration( PROJECT, null, null, null, null, " , ", null, null, null ) ),
                "groups that name no group" );
        assertEquals( DiagnosticCode.VALIDATION_ERROR,
                code( service.createLaunchConfiguration( PROJECT, "sample.CalcTest", null, null, null, null, "a/b",
                        null, null ) ),
                "a name the launch manager refuses" );
    }

    @Test
    public void unknownTargetsAreReportedByWhatIsMissing()
    {
        assertEquals( DiagnosticCode.PROJECT_NOT_FOUND,
                code( service.createLaunchConfiguration( "NoSuchProject", "sample.CalcTest", null, null, null, null,
                        null, null, null ) ) );
        assertEquals( DiagnosticCode.TEST_CLASS_NOT_FOUND,
                code( service.createLaunchConfiguration( PROJECT, "sample.Missing", null, null, null, null, null, null,
                        null ) ) );
        assertEquals( DiagnosticCode.RESOURCE_NOT_FOUND,
                code( service.createLaunchConfiguration( PROJECT, "sample.CalcTest", "missing", null, null, null, null,
                        null, null ) ) );
        assertEquals( DiagnosticCode.TEST_PACKAGE_NOT_FOUND,
                code( service.createLaunchConfiguration( PROJECT, null, null, "no.such.pkg", null, null, null, null,
                        null ) ) );
        assertEquals( DiagnosticCode.RESOURCE_NOT_FOUND,
                code( service.createLaunchConfiguration( PROJECT, null, null, null, "missing.xml", null, null, null,
                        null ) ) );
        assertEquals( DiagnosticCode.RESOURCE_NOT_FOUND,
                code( service.createLaunchConfiguration( PROJECT, null, null, null, null, "fast,undeclared", null, null,
                        null ) ),
                "every group must be declared somewhere, or the run would select nothing for it" );
    }

    @Test
    public void anInheritedMethodIsAValidTarget() throws Exception
    {
        configsToDelete.add( "SubTest.inherited" );

        TestNGLaunchConfigurationResponse response = service.createLaunchConfiguration( PROJECT, "sample.SubTest",
                "inherited", null, null, null, null, null, null );

        // Without TestNG the request gets as far as the launch type; with it, it is saved.
        if ( testngType() == null )
        {
            assertEquals( DiagnosticCode.TESTNG_LAUNCH_TYPE_MISSING, code( response ) );
        }
        else
        {
            assertTrue( response.diagnostics().isEmpty(), response.summaryText() );
            assertEquals( "SubTest.inherited", response.configurationName() );
        }
    }

    @Test
    public void aConfigurationOfAnotherTypeIsNeverOverwritten() throws Exception
    {
        ILaunchConfigurationType junit = launchManager().getLaunchConfigurationType( "org.eclipse.jdt.junit.launchconfig" );
        assertNotNull( junit );
        String name = "TestNGClashWithJUnit";
        configsToDelete.add( name );
        ILaunchConfigurationWorkingCopy wc = junit.newInstance( null, name );
        wc.setAttribute( IJavaLaunchConfigurationConstants.ATTR_PROJECT_NAME, "Elsewhere" );
        wc.setAttribute( IJavaLaunchConfigurationConstants.ATTR_MAIN_TYPE_NAME, "com.example.SomeJUnitTest" );
        ILaunchConfiguration original = wc.doSave();
        Map<String, Object> before = new TreeMap<>( original.getAttributes() );

        TestNGLaunchConfigurationResponse response = service.createLaunchConfiguration( PROJECT, "sample.CalcTest",
                null, null, null, null, name, null, null );

        assertEquals( DiagnosticCode.RESOURCE_ALREADY_EXISTS, code( response ) );
        ILaunchConfiguration after = find( name );
        assertEquals( "org.eclipse.jdt.junit.launchconfig", after.getType().getIdentifier() );
        assertEquals( before, new TreeMap<>( after.getAttributes() ), "the JUnit configuration is untouched" );
    }

    @Test
    public void groupsAreFoundWhereTestNGAnnotationsDeclareThem() throws Exception
    {
        Map<String, Set<String>> groups = service.findGroupDeclaringClasses( javaProject );

        assertEquals( Set.of( "sample.CalcTest" ), groups.get( "fast" ) );
        assertEquals( Set.of( "sample.CalcTest" ), groups.get( "smoke" ), "a constant resolves to its value" );
        assertEquals( Set.of( "sample.SlowTest" ), groups.get( "slow" ), "class-level groups count" );
        assertEquals( Set.of( "sample.SlowTest" ), groups.get( "nightly" ), "comma-separated values split" );
        assertEquals( Set.of( "sample.Outer$Inner" ), groups.get( "nested" ), "nested classes by binary name" );
        assertFalse( groups.containsKey( "abstract-only" ), "abstract classes cannot be instantiated by TestNG" );
    }

    // --- with TestNG for Eclipse (./mvnw verify -Ptestng) ---

    @Test
    public void aClassTargetSavesWhatRunAsTestNGSaves() throws Exception
    {
        assumeTestNG();
        configsToDelete.add( "CalcTest" );

        TestNGLaunchConfigurationResponse response = service.createLaunchConfiguration( PROJECT, "sample.CalcTest",
                null, null, null, null, null, null, null );

        assertTrue( response.diagnostics().isEmpty(), response.summaryText() );
        assertTrue( response.created() );
        assertEquals( "CalcTest", response.configurationName() );
        ILaunchConfiguration config = find( "CalcTest" );
        assertEquals( TESTNG_TYPE_ID, config.getType().getIdentifier() );
        assertEquals( PROJECT, config.getAttribute( IJavaLaunchConfigurationConstants.ATTR_PROJECT_NAME, "" ) );
        assertEquals( "org.testng.remote.RemoteTestNG",
                config.getAttribute( IJavaLaunchConfigurationConstants.ATTR_MAIN_TYPE_NAME, "" ) );
        assertEquals( 1, config.getAttribute( P + "TYPE", -1 ) );
        assertEquals( List.of( "sample.CalcTest" ), config.getAttribute( P + "CLASS_TEST_LIST", List.of() ) );
        assertEquals( List.of(), config.getAttribute( P + "METHOD_TEST_LIST", List.of( "unset" ) ) );
        assertEquals( List.of(), config.getAttribute( P + "PACKAGE_TEST_LIST", List.of( "unset" ) ) );
        assertEquals( Map.of(), config.getAttribute( P + "ALL_CLASS_METHODS", Map.of( "unset", "" ) ) );

        LaunchConfigurationsResponse listed = launchService.listLaunchConfigurations( "testng" );
        assertTrue( listed.configurations().stream().anyMatch( c -> c.name().equals( "CalcTest" ) ),
                "listLaunchConfigurations(typeFilter='testng') lists it" );
    }

    @Test
    public void aMethodTargetSavesTheMethodAndTheClassMethodsMap() throws Exception
    {
        assumeTestNG();
        configsToDelete.add( "CalcTest.add" );

        TestNGLaunchConfigurationResponse response = service.createLaunchConfiguration( PROJECT, "sample.CalcTest",
                "add", null, null, null, null, null, null );

        assertTrue( response.diagnostics().isEmpty(), response.summaryText() );
        ILaunchConfiguration config = find( "CalcTest.add" );
        assertEquals( 4, config.getAttribute( P + "TYPE", -1 ) );
        assertEquals( List.of( "sample.CalcTest" ), config.getAttribute( P + "CLASS_TEST_LIST", List.of() ) );
        assertEquals( List.of( "add" ), config.getAttribute( P + "METHOD_TEST_LIST", List.of() ) );
        assertEquals( Map.of( "sample.CalcTest", "add" ), config.getAttribute( P + "ALL_CLASS_METHODS", Map.of() ) );
    }

    @Test
    public void aSecondRequestUpdatesTheSameConfigurationAndClearsTheOldTarget() throws Exception
    {
        assumeTestNG();
        String name = "TestNGReused";
        configsToDelete.add( name );

        TestNGLaunchConfigurationResponse first = service.createLaunchConfiguration( PROJECT, null, null, "sample",
                null, null, name, null, null );
        TestNGLaunchConfigurationResponse second = service.createLaunchConfiguration( PROJECT, null, null, null, null,
                "fast", name, "-Dx=1", null );

        assertTrue( first.created() );
        assertFalse( second.created(), "an existing TestNG configuration is updated in place" );
        assertEquals( 1, java.util.Arrays.stream( launchManager().getLaunchConfigurations() )
                .filter( c -> c.getName().equals( name ) ).count(), "no duplicate is created" );
        ILaunchConfiguration config = find( name );
        assertEquals( 2, config.getAttribute( P + "TYPE", -1 ) );
        assertEquals( List.of(), config.getAttribute( P + "PACKAGE_TEST_LIST", List.of( "unset" ) ),
                "the group runner reads packages too, so the old package target must not linger" );
        assertEquals( List.of( "fast" ), config.getAttribute( P + "GROUP_LIST", List.of() ) );
        assertEquals( List.of( "sample.CalcTest" ), config.getAttribute( P + "GROUP_LIST_CLASS", List.of() ) );
        assertEquals( "-Dx=1", config.getAttribute( IJavaLaunchConfigurationConstants.ATTR_VM_ARGUMENTS, "" ) );
    }

    @Test
    public void suiteAndPackageTargetsFollowUpstreamNaming() throws Exception
    {
        assumeTestNG();
        configsToDelete.add( PROJECT + "_testng.xml" );
        configsToDelete.add( "package sample" );

        TestNGLaunchConfigurationResponse suite = service.createLaunchConfiguration( PROJECT, null, null, null,
                "testng.xml", null, null, null, null );
        TestNGLaunchConfigurationResponse pkg = service.createLaunchConfiguration( PROJECT, null, null, "sample",
                null, null, null, null, null );

        assertEquals( PROJECT + "_testng.xml", suite.configurationName() );
        assertEquals( 3, find( suite.configurationName() ).getAttribute( P + "TYPE", -1 ) );
        assertEquals( List.of( project.getFile( "testng.xml" ).getLocation().toOSString() ),
                find( suite.configurationName() ).getAttribute( P + "SUITE_TEST_LIST", List.of() ) );
        assertEquals( "package sample", pkg.configurationName() );
        assertEquals( 5, find( pkg.configurationName() ).getAttribute( P + "TYPE", -1 ) );
    }

    /**
     * The acceptance check: upstream's own launch shortcuts save a configuration, and ours
     * for the same element must carry the same attributes. Upstream's {@code LaunchUtil} is
     * called reflectively - it saves and launches - its configuration is read and removed,
     * then ours is created under the same name. Both {@code .launch} files are kept under
     * {@code <workspace>/testng-parity/} for inspection.
     */
    @Test
    public void configurationsMatchTheOnesUpstreamSaves() throws Exception
    {
        assumeTestNG();
        Class<?> launchUtil = Platform.getBundle( "org.testng.eclipse" ).loadClass( "org.testng.eclipse.util.LaunchUtil" );
        IType calc = javaProject.findType( "sample.CalcTest" );
        IMethod add = calc.getMethod( "add", new String[0] );
        IPackageFragment sample = calc.getPackageFragment();
        IFile suite = project.getFile( "testng.xml" );

        List<String> mismatches = new ArrayList<>();
        compare( mismatches, "CalcTest", () -> launchUtil
                .getMethod( "launchTypeConfiguration", IJavaProject.class, IType.class, String.class )
                .invoke( null, javaProject, calc, ILaunchManager.RUN_MODE ),
                () -> service.createLaunchConfiguration( PROJECT, "sample.CalcTest", null, null, null, null, null,
                        null, null ) );
        compare( mismatches, "CalcTest.add", () -> launchUtil
                .getMethod( "launchMethodConfiguration", IJavaProject.class, IMethod.class, String.class )
                .invoke( null, javaProject, add, ILaunchManager.RUN_MODE ),
                () -> service.createLaunchConfiguration( PROJECT, "sample.CalcTest", "add", null, null, null, null,
                        null, null ) );
        compare( mismatches, "package sample", () -> launchUtil
                .getMethod( "launchPackageConfiguration", IJavaProject.class, IPackageFragment.class, String.class )
                .invoke( null, javaProject, sample, ILaunchManager.RUN_MODE ),
                () -> service.createLaunchConfiguration( PROJECT, null, null, "sample", null, null, null, null, null ) );
        compare( mismatches, PROJECT + "_testng.xml", () -> launchUtil
                .getMethod( "launchSuiteConfiguration", IFile.class, String.class )
                .invoke( null, suite, ILaunchManager.RUN_MODE ),
                () -> service.createLaunchConfiguration( PROJECT, null, null, null, "testng.xml", null, null, null,
                        null ) );

        assertTrue( mismatches.isEmpty(), String.join( "\n", mismatches ) );
    }

    @Test
    public void aSavedConfigurationRunsThroughLaunchConfiguration() throws Exception
    {
        assumeTestNG();
        configsToDelete.add( "CalcTest" );
        Path markers = Files.createTempDirectory( "testng-markers" );
        TestNGLaunchConfigurationResponse created = service.createLaunchConfiguration( PROJECT, "sample.CalcTest",
                null, null, null, null, null, "-Dassistai.marker.dir=" + markers, null );
        assertTrue( created.diagnostics().isEmpty(), created.summaryText() );
        // TestNG for Eclipse runs a test JVM only against its result view, which it opens in
        // the active workbench window's page; RemoteTestNG reports there over a socket and,
        // with nothing listening, exits 0 having run nothing. Under Xvfb with no window
        // manager no window need be active, so make one active or say why this cannot run.
        project.refreshLocal( IResource.DEPTH_INFINITE, monitor );
        assertTrue( project.getFile( "bin/sample/CalcTest.class" ).exists(),
                "the fixture compiled: " + java.util.Arrays.toString( project.findMarkers(
                        org.eclipse.core.resources.IMarker.PROBLEM, true, IResource.DEPTH_INFINITE ) ) );
        assumeTrue( activateWorkbenchWindow(),
                "No active workbench window, so TestNG for Eclipse has no result view to run against" );

        LaunchResponse run = launchService.launchConfiguration( created.configurationName(), "run", 120 );

        assertFalse( run.timedOut(), run.summaryText() );
        assertEquals( Integer.valueOf( 0 ), run.exitCode(), run.summaryText() + "\n" + run.stdout().text()
                + "\n" + run.stderr().text() );
        // RemoteTestNG reports to the IDE over a socket, not to stdout, so the fixture's
        // tests leave a marker each - which also shows vmArgs reached the test JVM.
        assertTrue( Files.exists( markers.resolve( "add" ) ) && Files.exists( markers.resolve( "sub" ) ),
                "both CalcTest methods ran: " + run.stdout().text() );
    }

    // --- helpers ---

    private interface Step
    {
        Object run() throws Exception;
    }

    private void compare( List<String> mismatches, String name, Step upstream, Step ours ) throws Exception
    {
        configsToDelete.add( name );
        Path out = Path.of( ResourcesPlugin.getWorkspace().getRoot().getLocation().toOSString(), "testng-parity" );
        Files.createDirectories( out );

        upstream.run();
        ILaunchConfiguration theirs = waitFor( name );
        Map<String, Object> theirAttributes = new TreeMap<>( theirs.getAttributes() );
        copyLaunchFile( theirs, out.resolve( "upstream-" + name + ".launch" ) );
        terminateLaunches();
        theirs.delete();

        Object response = ours.run();
        ILaunchConfiguration mine = find( name );
        assertNotNull( mine, "AssistAI saved no '" + name + "': " + response );
        Map<String, Object> myAttributes = new TreeMap<>( mine.getAttributes() );
        copyLaunchFile( mine, out.resolve( "assistai-" + name + ".launch" ) );

        Set<String> keys = new TreeSet<>( theirAttributes.keySet() );
        keys.addAll( myAttributes.keySet() );
        for ( String key : keys )
        {
            Object a = theirAttributes.get( key );
            Object b = myAttributes.get( key );
            if ( a == null ? b != null : !a.equals( b ) )
            {
                mismatches.add( "'" + name + "' " + key + ": upstream=" + a + " assistai=" + b );
            }
        }
    }

    /** Upstream saves before it launches, but the save may still be in flight on another thread. */
    private ILaunchConfiguration waitFor( String name ) throws Exception
    {
        for ( int i = 0; i < 100; i++ )
        {
            ILaunchConfiguration config = find( name );
            if ( config != null )
            {
                return config;
            }
            Thread.sleep( 100 );
        }
        throw new AssertionError( "upstream saved no configuration named '" + name + "'" );
    }

    private void terminateLaunches() throws Exception
    {
        for ( int i = 0; i < 300; i++ )
        {
            boolean running = false;
            for ( ILaunch launch : launchManager().getLaunches() )
            {
                if ( !launch.isTerminated() )
                {
                    running = true;
                    if ( i > 100 )
                    {
                        launch.terminate();
                    }
                }
            }
            if ( !running )
            {
                return;
            }
            Thread.sleep( 100 );
        }
    }

    private static boolean activateWorkbenchWindow() throws Exception
    {
        if ( !PlatformUI.isWorkbenchRunning() )
        {
            return false;
        }
        IWorkbench workbench = PlatformUI.getWorkbench();
        Display display = workbench.getDisplay();
        display.syncExec( () -> {
            if ( workbench.getActiveWorkbenchWindow() == null && workbench.getWorkbenchWindowCount() > 0 )
            {
                Shell shell = workbench.getWorkbenchWindows()[0].getShell();
                shell.forceActive();
                shell.setFocus();
            }
        } );
        boolean[] active = new boolean[1];
        for ( int i = 0; i < 50 && !active[0]; i++ )
        {
            Thread.sleep( 100 );
            display.syncExec( () -> active[0] = workbench.getActiveWorkbenchWindow() != null );
        }
        return active[0];
    }

    private static void copyLaunchFile( ILaunchConfiguration config, Path target ) throws Exception
    {
        @SuppressWarnings( "deprecation" )
        IPath location = config.getLocation();
        if ( location != null && location.toFile().isFile() )
        {
            Files.copy( location.toFile().toPath(), target, java.nio.file.StandardCopyOption.REPLACE_EXISTING );
        }
    }

    /**
     * TestNG and what it needs at run time, as a real project's build would put them on its
     * classpath - RemoteTestNG itself fails to start without JCommander. Empty when the
     * runtime has no TestNG, and the fixture then compiles against a stub annotation.
     */
    private static List<File> testngLibraries() throws Exception
    {
        List<File> jars = new ArrayList<>();
        if ( Platform.getBundle( "org.testng" ) == null )
        {
            return jars;
        }
        for ( String id : List.of( "org.testng", "com.beust.jcommander", "slf4j.api" ) )
        {
            Bundle bundle = Platform.getBundle( id );
            if ( bundle != null )
            {
                jars.add( FileLocator.getBundleFile( bundle ) );
            }
        }
        return jars;
    }

    private static ILaunchConfigurationType testngType()
    {
        return launchManager().getLaunchConfigurationType( TESTNG_TYPE_ID );
    }

    private static void assumeTestNG()
    {
        assumeTrue( testngType() != null,
                "TestNG for Eclipse is not installed here; run with ./mvnw verify -Ptestng" );
    }

    private static ILaunchManager launchManager()
    {
        return DebugPlugin.getDefault().getLaunchManager();
    }

    private static ILaunchConfiguration find( String name ) throws Exception
    {
        for ( ILaunchConfiguration config : launchManager().getLaunchConfigurations() )
        {
            if ( config.getName().equals( name ) )
            {
                return config;
            }
        }
        return null;
    }

    private static DiagnosticCode code( TestNGLaunchConfigurationResponse response )
    {
        assertFalse( response.diagnostics().isEmpty(), "expected a diagnostic: " + response.summaryText() );
        return response.diagnostics().get( 0 ).code();
    }

    private void write( IFile file, String content ) throws Exception
    {
        file.create( new ByteArrayInputStream( content.getBytes( StandardCharsets.UTF_8 ) ), true, monitor );
    }
}
