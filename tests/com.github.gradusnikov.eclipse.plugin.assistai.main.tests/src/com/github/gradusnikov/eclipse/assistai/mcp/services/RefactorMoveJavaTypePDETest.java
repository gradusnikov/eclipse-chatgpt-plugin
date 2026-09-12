package com.github.gradusnikov.eclipse.assistai.mcp.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.eclipse.core.resources.IContainer;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IFolder;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IProjectDescription;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.ILog;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.e4.core.contexts.ContextInjectionFactory;
import org.eclipse.e4.core.contexts.EclipseContextFactory;
import org.eclipse.e4.core.contexts.IEclipseContext;
import org.eclipse.e4.ui.di.UISynchronize;
import org.eclipse.jdt.core.IClasspathEntry;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.launching.JavaRuntime;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.github.gradusnikov.eclipse.assistai.Activator;
import com.github.gradusnikov.eclipse.assistai.mcp.results.DiagnosticCode;
import com.github.gradusnikov.eclipse.assistai.resources.EditResult;
import com.github.gradusnikov.eclipse.assistai.resources.EditResult.EditStatus;
import com.github.gradusnikov.eclipse.assistai.resources.ResourceCache;
import com.github.gradusnikov.eclipse.assistai.tools.ResourceUtilities;

/**
 * Moving a type between packages when the package name alone does not say where the
 * type should land - the layout of issue 159: a project with a resources folder first
 * on its classpath, depending on a second project that owns the target package.
 */
public class RefactorMoveJavaTypePDETest
{
    private static final String SOURCE = "MoveSourceProject";
    private static final String TARGET = "MoveTargetProject";
    private final NullProgressMonitor monitor = new NullProgressMonitor();

    private IProject source;
    private IProject target;
    private CodeEditingService service;
    private ResourceCache resourceCache;

    @BeforeEach
    public void beforeEach() throws Exception
    {
        target = createJavaProject( TARGET, List.of( "src" ), List.of() );
        // The resources folder comes first on purpose: it is where the old lookup put a
        // package it could not find.
        source = createJavaProject( SOURCE, List.of( "resources", "src" ), List.of( target ) );

        createFile( target, "src/shared/util/Existing.java", "package shared.util;\n\npublic class Existing {}\n" );
        createFile( source, "src/app/Mover.java", "package app;\n\npublic class Mover {}\n" );

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
        resourceCache = ContextInjectionFactory.make( ResourceCache.class, context );
        context.set( ResourceCache.class, resourceCache );
        service = ContextInjectionFactory.make( CodeEditingService.class, context );
    }

    @AfterEach
    public void afterEach() throws Exception
    {
        if ( resourceCache != null )
        {
            resourceCache.dispose();
        }
        for ( IProject project : List.of( source, target ) )
        {
            if ( project != null && project.exists() )
            {
                project.delete( true, true, monitor );
            }
        }
    }

    // ---- fixture -----------------------------------------------------------

    private IProject createJavaProject( String name, List<String> sourceFolders, List<IProject> required ) throws Exception
    {
        IProject project = ResourcesPlugin.getWorkspace().getRoot().getProject( name );
        if ( project.exists() )
        {
            project.delete( true, true, monitor );
        }
        IProjectDescription description = project.getWorkspace().newProjectDescription( name );
        description.setNatureIds( new String[] { JavaCore.NATURE_ID } );
        project.create( description, monitor );
        project.open( monitor );

        List<IClasspathEntry> classpath = new ArrayList<>();
        for ( String folderName : sourceFolders )
        {
            IFolder folder = project.getFolder( folderName );
            folder.create( IResource.NONE, true, monitor );
            classpath.add( JavaCore.newSourceEntry( folder.getFullPath() ) );
        }
        classpath.add( JavaCore.newContainerEntry( IPath.fromOSString( JavaRuntime.JRE_CONTAINER ) ) );
        for ( IProject dependency : required )
        {
            classpath.add( JavaCore.newProjectEntry( dependency.getFullPath() ) );
        }
        JavaCore.create( project ).setRawClasspath( classpath.toArray( new IClasspathEntry[0] ),
            project.getFullPath().append( "bin" ), monitor );
        return project;
    }

    private IFile createFile( IProject project, String path, String content ) throws Exception
    {
        IFile file = project.getFile( IPath.fromOSString( path ) );
        IContainer parent = file.getParent();
        List<IFolder> missing = new ArrayList<>();
        while ( parent instanceof IFolder folder && !folder.exists() )
        {
            missing.add( 0, folder );
            parent = folder.getParent();
        }
        for ( IFolder folder : missing )
        {
            folder.create( IResource.NONE, true, monitor );
        }
        file.create( new ByteArrayInputStream( content.getBytes( StandardCharsets.UTF_8 ) ), true, monitor );
        return file;
    }

    private static List<DiagnosticCode> codes( EditResult result )
    {
        return result.diagnostics().stream().map( d -> d.code() ).toList();
    }

    // ---- tests -------------------------------------------------------------

    @Test
    public void movesIntoTheProjectThatAlreadyHasThePackage() throws Exception
    {
        EditResult result = service.refactorMoveJavaType( SOURCE, "src/app/Mover.java", "shared.util" );

        assertEquals( EditStatus.APPLIED, result.status(), () -> result.diagnostics().toString() );
        // The reported failure: the file used to land in the source project's resources
        // folder, in a freshly created copy of a package that already existed next door.
        assertEquals( TARGET, result.projectName() );
        assertEquals( "src/shared/util/Mover.java", result.filePath() );
        assertTrue( target.getFile( "src/shared/util/Mover.java" ).exists() );
        assertFalse( source.getFile( "src/app/Mover.java" ).exists(), "the file was moved, not copied" );
        assertFalse( source.getFolder( "resources/shared" ).exists(), "nothing was created in the resources folder" );
        assertTrue( ResourceUtilities.readFileContent( target.getFile( "src/shared/util/Mover.java" ) )
            .contains( "package shared.util;" ) );
    }

    @Test
    public void createsAMissingPackageBesideTheFileNotInTheFirstSourceFolder() throws Exception
    {
        EditResult result = service.refactorMoveJavaType( SOURCE, "src/app/Mover.java", "app.fresh" );

        assertEquals( EditStatus.APPLIED, result.status(), () -> result.diagnostics().toString() );
        assertEquals( SOURCE, result.projectName() );
        assertEquals( "src/app/fresh/Mover.java", result.filePath() );
        assertTrue( source.getFile( "src/app/fresh/Mover.java" ).exists() );
        assertFalse( source.getFolder( "resources/app" ).exists(),
            "the first source folder on the classpath is not where a new package belongs" );
    }

    @Test
    public void refusesAPackageThatExistsInMoreThanOnePlace() throws Exception
    {
        createFile( source, "src/dup/A.java", "package dup;\n\npublic class A {}\n" );
        createFile( target, "src/dup/B.java", "package dup;\n\npublic class B {}\n" );

        EditResult result = service.refactorMoveJavaType( SOURCE, "src/app/Mover.java", "dup" );

        assertEquals( EditStatus.REJECTED, result.status() );
        assertEquals( List.of( DiagnosticCode.AMBIGUOUS_MATCH ), codes( result ) );
        String message = result.diagnostics().get( 0 ).message();
        assertTrue( message.contains( SOURCE + "/src" ) && message.contains( TARGET + "/src" ), message );
        assertTrue( source.getFile( "src/app/Mover.java" ).exists(), "nothing was moved" );
    }

    @Test
    public void anExplicitTargetProjectSettlesTheAmbiguity() throws Exception
    {
        createFile( source, "src/dup/A.java", "package dup;\n\npublic class A {}\n" );
        createFile( target, "src/dup/B.java", "package dup;\n\npublic class B {}\n" );

        EditResult result = service.refactorMoveJavaType( SOURCE, "src/app/Mover.java", "dup", TARGET, null );

        assertEquals( EditStatus.APPLIED, result.status(), () -> result.diagnostics().toString() );
        assertEquals( TARGET, result.projectName() );
        assertEquals( "src/dup/Mover.java", result.filePath() );
        assertTrue( target.getFile( "src/dup/Mover.java" ).exists() );
    }

    @Test
    public void anExplicitSourceFolderReceivesANewPackage() throws Exception
    {
        EditResult result = service.refactorMoveJavaType( SOURCE, "src/app/Mover.java", "app.generated", null, "resources" );

        assertEquals( EditStatus.APPLIED, result.status(), () -> result.diagnostics().toString() );
        assertEquals( SOURCE, result.projectName() );
        assertEquals( "resources/app/generated/Mover.java", result.filePath() );
        assertTrue( source.getFile( "resources/app/generated/Mover.java" ).exists() );
    }

    @Test
    public void anUnknownSourceFolderIsRefusedWithACode() throws Exception
    {
        EditResult result = service.refactorMoveJavaType( SOURCE, "src/app/Mover.java", "app.generated", null, "no/such/folder" );

        assertEquals( EditStatus.REJECTED, result.status() );
        assertEquals( List.of( DiagnosticCode.RESOURCE_NOT_FOUND ), codes( result ) );
        assertTrue( source.getFile( "src/app/Mover.java" ).exists(), "nothing was moved" );
    }
}
