package com.github.gradusnikov.eclipse.assistai.mcp.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import org.eclipse.core.resources.ICommand;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IFolder;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IProjectDescription;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IWorkspaceRoot;
import org.eclipse.core.resources.IncrementalProjectBuilder;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.ILog;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.e4.core.contexts.ContextInjectionFactory;
import org.eclipse.e4.core.contexts.EclipseContextFactory;
import org.eclipse.e4.core.contexts.IEclipseContext;
import org.eclipse.jdt.core.IClasspathEntry;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.launching.JavaRuntime;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.github.gradusnikov.eclipse.assistai.Activator;
import com.github.gradusnikov.eclipse.assistai.mcp.results.TypeResolutionResponse;
import com.github.gradusnikov.eclipse.assistai.resources.ResourceReadResult;
import com.github.gradusnikov.eclipse.assistai.resources.SourceOrigin;
import com.github.gradusnikov.eclipse.assistai.services.AiIgnoreService;

/**
 * A type that lives in a JAR inside a workspace project, with a source attachment.
 * <p>
 * JDT reports such a type's resource as the JAR file. {@code getSource} used to take any
 * file it got as the source to read, so it returned the JAR's bytes as text - megabytes
 * of them - labelled as editable workspace source, with the JAR as the file to edit. The
 * attached source, which Eclipse itself shows on open, is what it must return.
 */
public class LibrarySourcePDETest
{
    private static final String BUILD_PROJECT = "LibrarySourceBuildProject";
    private static final String CONSUMER_PROJECT = "LibrarySourceConsumerProject";
    private static final String SOURCE = "package lib;\n\n/** A thing in a JAR. */\npublic class Thing\n{\n    public int size()\n    {\n        return 42;\n    }\n}\n";

    private final NullProgressMonitor monitor = new NullProgressMonitor();
    private IProject buildProject;
    private IProject consumer;
    private JavaDocService service;

    @BeforeEach
    public void beforeEach() throws Exception
    {
        IWorkspaceRoot root = ResourcesPlugin.getWorkspace().getRoot();
        byte[] classBytes = compileThing( root );

        consumer = createJavaProject( root, CONSUMER_PROJECT );
        IFolder lib = consumer.getFolder( "lib" );
        lib.create( IResource.NONE, true, monitor );
        IFile jar = lib.getFile( "thing.jar" );
        jar.create( new ByteArrayInputStream( jar( "lib/Thing.class", classBytes ) ), true, monitor );
        IFile sources = lib.getFile( "thing-sources.jar" );
        sources.create( new ByteArrayInputStream( jar( "lib/Thing.java", SOURCE.getBytes( StandardCharsets.UTF_8 ) ) ), true, monitor );

        IJavaProject javaProject = JavaCore.create( consumer );
        javaProject.setRawClasspath( new IClasspathEntry[] {
                JavaRuntime.getDefaultJREContainerEntry(),
                JavaCore.newLibraryEntry( jar.getFullPath(), sources.getFullPath(), null ) },
                consumer.getFullPath().append( "bin" ), monitor );

        // The build project also resolves lib.Thing, as workspace source; it must not be the one that answers.
        buildProject.delete( true, true, monitor );

        IEclipseContext context = EclipseContextFactory.create();
        context.set( ILog.class, Activator.getDefault().getLog() );
        context.set( AiIgnoreService.class, ContextInjectionFactory.make( AiIgnoreService.class, context ) );
        service = ContextInjectionFactory.make( JavaDocService.class, context );
    }

    @AfterEach
    public void afterEach() throws CoreException
    {
        for ( IProject project : new IProject[] { buildProject, consumer } )
        {
            if ( project != null && project.exists() )
            {
                project.delete( true, true, monitor );
            }
        }
    }

    @Test
    public void getSourceReturnsTheAttachedSourceNotTheJar()
    {
        ResourceReadResult result = service.getSourceWithResource( "lib.Thing" );

        assertEquals( ResourceReadResult.ReadStatus.OK, result.status(), result.toString() );
        assertEquals( SourceOrigin.ATTACHED_SOURCE, result.origin() );
        assertTrue( result.content().contains( "public class Thing" ), result.content() );
        assertFalse( result.content().startsWith( "PK" ), "the JAR's own bytes are not source" );
        assertTrue( result.readOnly(), "attached source cannot be written back" );
        assertNull( result.filePath(), "a JAR is not a file the editing tools may be pointed at" );
    }

    @Test
    public void typeResolutionNamesTheJarButOffersNoFileToEdit()
    {
        TypeResolutionResponse response = service.explainTypeResolution( CONSUMER_PROJECT, "lib.Thing" );

        assertEquals( TypeResolutionResponse.Status.OK, response.status() );
        assertEquals( SourceOrigin.ATTACHED_SOURCE, response.sourceOrigin() );
        assertEquals( "/" + CONSUMER_PROJECT + "/lib/thing.jar", response.classpathEntryPath() );
        assertNull( response.filePath(), "the JAR is reported as the classpath entry, not as a file to edit" );
        assertNull( response.projectName() );
    }

    // ---- fixture ---------------------------------------------------------

    /** Compiles {@code lib.Thing} in a throwaway project and returns its class file. */
    private byte[] compileThing( IWorkspaceRoot root ) throws Exception
    {
        buildProject = createJavaProject( root, BUILD_PROJECT );
        IJavaProject javaProject = JavaCore.create( buildProject );
        createFolder( buildProject, "src" );
        createFolder( buildProject, "src/lib" );
        buildProject.getFile( "src/lib/Thing.java" )
                .create( new ByteArrayInputStream( SOURCE.getBytes( StandardCharsets.UTF_8 ) ), true, monitor );
        javaProject.setRawClasspath( new IClasspathEntry[] {
                JavaCore.newSourceEntry( buildProject.getFullPath().append( "src" ) ),
                JavaRuntime.getDefaultJREContainerEntry() },
                buildProject.getFullPath().append( "bin" ), monitor );

        buildProject.build( IncrementalProjectBuilder.FULL_BUILD, monitor );
        Job.getJobManager().join( ResourcesPlugin.FAMILY_MANUAL_BUILD, monitor );
        Job.getJobManager().join( ResourcesPlugin.FAMILY_AUTO_BUILD, monitor );
        buildProject.refreshLocal( IResource.DEPTH_INFINITE, monitor );

        IFile classFile = buildProject.getFile( "bin/lib/Thing.class" );
        assertTrue( classFile.exists(), "the fixture did not compile; the JRE container may be unresolved in this workspace" );
        try ( var in = classFile.getContents() )
        {
            return in.readAllBytes();
        }
    }

    private IProject createJavaProject( IWorkspaceRoot root, String name ) throws CoreException
    {
        IProject project = root.getProject( name );
        if ( project.exists() )
        {
            project.delete( true, true, monitor );
        }
        IProjectDescription description = root.getWorkspace().newProjectDescription( name );
        description.setNatureIds( new String[] { JavaCore.NATURE_ID } );
        ICommand javaBuilder = description.newCommand();
        javaBuilder.setBuilderName( JavaCore.BUILDER_ID );
        description.setBuildSpec( new ICommand[] { javaBuilder } );
        project.create( description, monitor );
        project.open( monitor );
        createFolder( project, "bin" );
        return project;
    }

    private void createFolder( IProject project, String path ) throws CoreException
    {
        IFolder folder = project.getFolder( path );
        if ( !folder.exists() )
        {
            folder.create( IResource.NONE, true, monitor );
        }
    }

    private static byte[] jar( String entryName, byte[] content ) throws IOException
    {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try ( JarOutputStream out = new JarOutputStream( bytes ) )
        {
            out.putNextEntry( new JarEntry( entryName ) );
            out.write( content );
            out.closeEntry();
        }
        return bytes.toByteArray();
    }
}
