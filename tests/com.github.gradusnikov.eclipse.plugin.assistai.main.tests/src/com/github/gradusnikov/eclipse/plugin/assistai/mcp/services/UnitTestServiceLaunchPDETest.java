package com.github.gradusnikov.eclipse.plugin.assistai.mcp.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IFolder;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IProjectDescription;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IncrementalProjectBuilder;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.ILog;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.debug.core.DebugPlugin;
import org.eclipse.debug.core.ILaunchConfiguration;
import org.eclipse.e4.core.contexts.ContextInjectionFactory;
import org.eclipse.e4.core.contexts.EclipseContextFactory;
import org.eclipse.e4.core.contexts.IEclipseContext;
import org.eclipse.e4.ui.di.UISynchronize;
import org.eclipse.jdt.core.IClasspathEntry;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.launching.JavaRuntime;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.github.gradusnikov.eclipse.assistai.Activator;
import com.github.gradusnikov.eclipse.assistai.mcp.results.TestRunResponse;
import com.github.gradusnikov.eclipse.assistai.mcp.results.TestRunResponse.RunStatus;
import com.github.gradusnikov.eclipse.assistai.mcp.services.UnitTestService;

/**
 * Launching plain JUnit tests through {@link UnitTestService}, against a real Java
 * project with JUnit 5 on its classpath.
 * <p>
 * The single-method launch is the case behind issue 158: the launch configuration
 * carried the method under a key JDT does not read, so JDT ran the whole class. A
 * two-method class makes the difference countable.
 */
public class UnitTestServiceLaunchPDETest
{
    private static final String PROJECT = "UnitTestLaunchTestProject";
    private static final NullProgressMonitor monitor = new NullProgressMonitor();

    private IProject project;
    private IJavaProject javaProject;
    private UnitTestService service;

    @BeforeEach
    public void beforeEach() throws Exception
    {
        project = ResourcesPlugin.getWorkspace().getRoot().getProject( PROJECT );
        if ( project.exists() )
        {
            project.delete( true, true, monitor );
        }
        IProjectDescription description = project.getWorkspace().newProjectDescription( PROJECT );
        description.setNatureIds( new String[] { JavaCore.NATURE_ID } );
        project.create( description, monitor );
        project.open( monitor );

        IFolder source = project.getFolder( "src" );
        source.create( IResource.NONE, true, monitor );
        IFolder sample = source.getFolder( "sample" );
        sample.create( IResource.NONE, true, monitor );

        javaProject = JavaCore.create( project );
        javaProject.setRawClasspath( new IClasspathEntry[] {
            JavaCore.newSourceEntry( source.getFullPath() ),
            JavaCore.newContainerEntry( IPath.fromOSString( JavaRuntime.JRE_CONTAINER ) ),
            JavaCore.newContainerEntry( IPath.fromOSString( "org.eclipse.jdt.junit.JUNIT_CONTAINER/5" ) )
        }, project.getFullPath().append( "bin" ), monitor );

        write( sample.getFile( "TwoTests.java" ), """
                package sample;

                import org.junit.jupiter.api.Test;

                public class TwoTests {
                    @Test
                    public void first() {
                    }

                    @Test
                    public void second() {
                    }
                }
                """ );
        write( sample.getFile( "Overloads.java" ), """
                package sample;

                import java.util.List;

                public class Overloads {
                    void plain() {}
                    void typed(String s, int[] n) {}
                    void generic(List<String> l) {}
                }
                """ );
        project.refreshLocal( IResource.DEPTH_INFINITE, monitor );
        project.build( IncrementalProjectBuilder.FULL_BUILD, monitor );

        IEclipseContext context = EclipseContextFactory.create();
        context.set( ILog.class, Activator.getDefault().getLog() );
        context.set( UISynchronize.class, new UISynchronize()
        {
            @Override
            public void syncExec( Runnable runnable )
            {
                runnable.run();
            }

            @Override
            public void asyncExec( Runnable runnable )
            {
                runnable.run();
            }

            @Override
            protected boolean isUIThread( Thread thread )
            {
                return false;
            }

            @Override
            protected void showBusyWhile( Runnable runnable )
            {
                runnable.run();
            }

            @Override
            protected boolean dispatchEvents()
            {
                return false;
            }
        } );
        service = ContextInjectionFactory.make( UnitTestService.class, context );
    }

    @AfterEach
    public void afterEach() throws Exception
    {
        if ( project != null && project.exists() )
        {
            project.delete( true, true, monitor );
        }
    }

    private void write( IFile file, String content ) throws Exception
    {
        file.create( new ByteArrayInputStream( content.getBytes( StandardCharsets.UTF_8 ) ), true, monitor );
    }

    @Test
    public void theTestNameIsSpelledTheWayJdtSpellsIt() throws Exception
    {
        IType overloads = javaProject.findType( "sample.Overloads" );

        assertEquals( "plain()", service.qualifiedTestName( overloads, "plain" ) );
        assertEquals( "typed(java.lang.String,int[])", service.qualifiedTestName( overloads, "typed" ) );
        assertEquals( "generic(java.util.List)", service.qualifiedTestName( overloads, "generic" ),
            "type arguments are erased, as in JDT's own launch shortcut" );
        assertEquals( "typed(java.lang.String,int[])", service.qualifiedTestName( overloads, "typed(java.lang.String,int[])" ),
            "a name the caller already spelled out is kept" );
        assertEquals( "missing", service.qualifiedTestName( overloads, "missing" ),
            "an unknown method is passed through for JDT to complain about" );
    }

    @Test
    public void aSingleMethodLaunchNamesTheMethodTheWayJdtReadsIt() throws Exception
    {
        TestRunResponse single = service.runTestMethod( PROJECT, "sample.TwoTests", "first", 120 );

        // The launch configuration the service saved is what JDT reads. Its single-method
        // key has to be JDT's own, TESTNAME - not the TEST_METHOD that was written before,
        // which JDT ignored while it ran the whole class.
        ILaunchConfiguration configuration = savedLaunchConfiguration( PROJECT + " - sample.TwoTests.first" );
        assertNotNull( configuration, "the service saves its launch configuration under a deterministic name" );
        assertEquals( "first()", configuration.getAttribute( "org.eclipse.jdt.junit.TESTNAME", "" ) );
        assertEquals( "", configuration.getAttribute( "org.eclipse.jdt.junit.TEST_METHOD", "" ),
            "the key JDT never read is not written any more" );

        // Whether the launched JVM can report back depends on the harness. Where it can,
        // the run must have executed one test, not two.
        assumeTrue( single.status() == RunStatus.COMPLETED,
            "Skipping the live count: the JUnit launch could not start here (" + single.summaryText() + ")" );
        assertEquals( 1, single.summary().total(), single.summaryText() );
        TestRunResponse whole = service.runClassTests( PROJECT, "sample.TwoTests", 120 );
        assertEquals( 2, whole.summary().total(), whole.summaryText() );
    }

    private static ILaunchConfiguration savedLaunchConfiguration( String name ) throws CoreException
    {
        for ( ILaunchConfiguration configuration : DebugPlugin.getDefault().getLaunchManager().getLaunchConfigurations() )
        {
            if ( name.equals( configuration.getName() ) )
            {
                return configuration;
            }
        }
        return null;
    }
}
