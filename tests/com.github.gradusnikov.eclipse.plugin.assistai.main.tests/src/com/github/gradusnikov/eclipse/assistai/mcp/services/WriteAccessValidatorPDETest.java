package com.github.gradusnikov.eclipse.assistai.mcp.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IFileState;
import org.eclipse.core.resources.IFolder;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IProjectDescription;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IWorkspaceRoot;
import org.eclipse.core.resources.IncrementalProjectBuilder;
import org.eclipse.core.resources.ResourceAttributes;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.ILog;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.core.runtime.Path;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.e4.core.contexts.ContextInjectionFactory;
import org.eclipse.e4.core.contexts.EclipseContextFactory;
import org.eclipse.e4.core.contexts.IEclipseContext;
import org.eclipse.e4.ui.di.UISynchronize;
import org.eclipse.jdt.core.IClasspathEntry;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.launching.JavaRuntime;
import org.eclipse.team.core.RepositoryProvider;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.github.gradusnikov.eclipse.assistai.Activator;
import com.github.gradusnikov.eclipse.assistai.mcp.results.CompilationProblemsResponse;
import com.github.gradusnikov.eclipse.assistai.mcp.results.DiagnosticCode;
import com.github.gradusnikov.eclipse.assistai.mcp.results.QuickFixResponse;
import com.github.gradusnikov.eclipse.assistai.resources.EditResult;
import com.github.gradusnikov.eclipse.assistai.resources.ResourceCache;
import com.github.gradusnikov.eclipse.assistai.testsupport.DialogExpectation;
import com.github.gradusnikov.eclipse.assistai.testsupport.FakeCheckoutRepositoryProvider;
import com.github.gradusnikov.eclipse.assistai.testsupport.TestDialogInterceptor;
import com.github.gradusnikov.eclipse.assistai.tools.ResourceUtilities;

/**
 * Writing files under a pessimistic version control system, against a team provider
 * that, like Perforce, keeps files read-only until they are checked out through
 * {@code validateEdit}.
 * <p>
 * "Not checked out" below means read-only in a project mapped to that provider. Each
 * scenario goes through the service method behind the corresponding MCP tool.
 */
@DisplayName( "Writing files under a pessimistic version control system" )
public class WriteAccessValidatorPDETest
{
    private static final String TEST_PROJECT_NAME = "WriteAccessValidatorTestProject";
    private static final String FILE_PATH = "src/Foo.java";
    private static final String ORIGINAL = "public class Foo\n{\n}\n";
    private static final String INSERTED = "    // inserted by the test";

    private final NullProgressMonitor monitor = new NullProgressMonitor();
    private IProject project;
    private IFile file;
    private IEclipseContext context;
    private CodeEditingService service;
    private ResourceCache resourceCache;

    @BeforeEach
    public void beforeEach() throws CoreException
    {
        IWorkspaceRoot root = ResourcesPlugin.getWorkspace().getRoot();
        project = root.getProject( TEST_PROJECT_NAME );
        if ( project.exists() )
        {
            deleteProject();
        }

        // The Java nature is added to the open project: only then does it register the
        // Java builder, which the quick-fix scenario needs for its compile error.
        project.create( monitor );
        project.open( monitor );
        IProjectDescription desc = project.getDescription();
        desc.setNatureIds( new String[] { JavaCore.NATURE_ID } );
        project.setDescription( desc, monitor );

        IFolder srcFolder = project.getFolder( "src" );
        srcFolder.create( IResource.NONE, true, monitor );
        IJavaProject javaProject = JavaCore.create( project );
        IClasspathEntry[] classpath = {
            JavaCore.newSourceEntry( srcFolder.getFullPath() ),
            JavaCore.newContainerEntry( new Path( JavaRuntime.JRE_CONTAINER ) )
        };
        javaProject.setRawClasspath( classpath, project.getFullPath().append( "bin" ), monitor );

        RepositoryProvider.map( project, FakeCheckoutRepositoryProvider.ID );
        file = createFile( FILE_PATH, ORIGINAL );
        FakeCheckoutRepositoryProvider.reset();

        context = EclipseContextFactory.create();
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
    public void afterEach() throws CoreException, InterruptedException
    {
        if ( resourceCache != null )
        {
            resourceCache.dispose();
        }

        // A quick fix builds the project and may leave an editor open; either one
        // holds the files the deletion below needs.
        Job.getJobManager().join( ResourcesPlugin.FAMILY_MANUAL_BUILD, monitor );
        Job.getJobManager().join( ResourcesPlugin.FAMILY_AUTO_BUILD, monitor );
        PlatformUI.getWorkbench().getDisplay().syncExec( () -> {
            IWorkbenchWindow window = PlatformUI.getWorkbench().getActiveWorkbenchWindow();
            IWorkbenchPage page = window == null ? null : window.getActivePage();
            if ( page != null )
            {
                page.closeAllEditors( false );
            }
        } );

        if ( project != null && project.exists() )
        {
            if ( RepositoryProvider.getProvider( project ) != null )
            {
                RepositoryProvider.unmap( project );
            }
            deleteProject();
        }
    }

    @Test
    @DisplayName( "Edit a file that is not checked out: checked out once, then edited" )
    public void editFileNotCheckedOut() throws CoreException, IOException
    {
        setReadOnly( file, true );

        EditResult result = insertIntoFile();

        assertTrue( result.changedResource(), () -> describe( result ) );
        assertTrue( ResourceUtilities.readFileContent( file ).contains( INSERTED ) );
        assertEquals( List.of( List.of( file ) ), FakeCheckoutRepositoryProvider.validatedFiles() );
        assertFalse( file.isReadOnly() );
    }

    @Test
    @DisplayName( "Edit a file that is already checked out: no checkout" )
    public void editFileAlreadyCheckedOut() throws CoreException, IOException
    {
        EditResult result = insertIntoFile();

        assertTrue( result.changedResource(), () -> describe( result ) );
        assertTrue( ResourceUtilities.readFileContent( file ).contains( INSERTED ) );
        assertEquals( 0, FakeCheckoutRepositoryProvider.validateEditCalls() );
    }

    @Test
    @DisplayName( "Search and replace across two files that are not checked out: one checkout for both" )
    public void searchAndReplaceInFilesNotCheckedOut() throws CoreException, IOException
    {
        IFile one = createFile( "src/search/One.txt", "value=OLD_VALUE_7f3a\n" );
        IFile two = createFile( "src/search/Two.txt", "other=OLD_VALUE_7f3a\n" );
        setReadOnly( one, true );
        setReadOnly( two, true );
        FakeCheckoutRepositoryProvider.reset();
        SearchService search = ContextInjectionFactory.make( SearchService.class, context );

        List<SearchService.SearchAndReplaceResult> results = search.searchAndReplace( "OLD_VALUE_7f3a", "NEW_VALUE",
                "*.txt" );

        assertTrue( results.stream().allMatch( r -> r.replacementsMade() == 1 ), () -> "results " + results );
        assertTrue( ResourceUtilities.readFileContent( one ).contains( "NEW_VALUE" ) );
        assertTrue( ResourceUtilities.readFileContent( two ).contains( "NEW_VALUE" ) );
        assertEquals( 1, FakeCheckoutRepositoryProvider.validateEditCalls() );
        assertEquals( Set.of( one, two ), Set.copyOf( FakeCheckoutRepositoryProvider.validatedFiles().get( 0 ) ) );
    }

    @Test
    @DisplayName( "Restore an earlier version of a file that is not checked out: checked out once, then restored" )
    public void restoreEarlierVersionOfFileNotCheckedOut() throws CoreException, IOException
    {
        String changed = ORIGINAL + "// changed\n";
        file.setContents( new ByteArrayInputStream( changed.getBytes( StandardCharsets.UTF_8 ) ),
                IResource.KEEP_HISTORY, monitor );
        IFileState[] history = file.getHistory( monitor );
        assertTrue( history.length > 0, "the change should have left the original in local history" );
        String timestamp = String.valueOf( history[0].getModificationTime() );
        setReadOnly( file, true );
        FakeCheckoutRepositoryProvider.reset();
        LocalHistoryService localHistory = ContextInjectionFactory.make( LocalHistoryService.class, context );

        EditResult result = localHistory.restoreFileVersion( TEST_PROJECT_NAME, FILE_PATH, timestamp );

        assertTrue( result.changedResource(), () -> describe( result ) );
        assertEquals( ORIGINAL, ResourceUtilities.readFileContent( file ) );
        assertEquals( List.of( List.of( file ) ), FakeCheckoutRepositoryProvider.validatedFiles() );
    }

    @Test
    @DisplayName( "Apply a quick fix to a file that is not checked out: checked out once, then fixed" )
    public void quickFixOnFileNotCheckedOut() throws CoreException, IOException, InterruptedException
    {
        IFile broken = createFile( "src/FixMe.java",
                "public class FixMe\n{\n    ArrayList<String> list = new ArrayList<>();\n}\n" );
        project.build( IncrementalProjectBuilder.FULL_BUILD, monitor );
        Job.getJobManager().join( ResourcesPlugin.FAMILY_MANUAL_BUILD, monitor );
        Job.getJobManager().join( ResourcesPlugin.FAMILY_AUTO_BUILD, monitor );

        // Find the fix the way an agent does: through getCompilationErrors.
        CodeAnalysisService analysis = ContextInjectionFactory.make( CodeAnalysisService.class, context );
        CompilationProblemsResponse problems = analysis.getCompilationErrors( TEST_PROJECT_NAME, "src/FixMe.java",
                "ERROR", 50 );
        long markerId = -1L;
        int importIndex = -1;
        for ( var reported : problems.files() )
        {
            for ( var problem : reported.problems() )
            {
                for ( var quickFix : problem.quickFixes() )
                {
                    if ( importIndex < 0 && quickFix.label().contains( "Import" )
                            && quickFix.label().contains( "ArrayList" ) )
                    {
                        markerId = problem.markerId();
                        importIndex = quickFix.index();
                    }
                }
            }
        }
        assertTrue( importIndex >= 0, () -> "no 'Import ArrayList' quick fix offered: " + problems.summaryText() );

        setReadOnly( broken, true );
        FakeCheckoutRepositoryProvider.reset();

        QuickFixResponse result = analysis.executeQuickFix( markerId, importIndex );

        assertEquals( QuickFixResponse.Status.APPLIED, result.status(), () -> "diagnostics " + result.diagnostics() );
        assertTrue( ResourceUtilities.readFileContent( broken ).contains( "import java.util.ArrayList" ) );
        assertEquals( List.of( List.of( broken ) ), FakeCheckoutRepositoryProvider.validatedFiles() );
    }

    @Test
    @DisplayName( "Move a folder with two files that are not checked out: one checkout for both" )
    public void moveFolderWithFilesNotCheckedOut() throws CoreException
    {
        IFile a = createFile( "src/pkg/A.txt", "a\n" );
        IFile b = createFile( "src/pkg/B.txt", "b\n" );
        setReadOnly( a, true );
        setReadOnly( b, true );
        FakeCheckoutRepositoryProvider.reset();

        EditResult result = service.moveResource( TEST_PROJECT_NAME, "src/pkg", "moved" );

        assertTrue( result.changedResource(), () -> describe( result ) );
        assertTrue( project.getFile( "moved/pkg/A.txt" ).exists() );
        assertTrue( project.getFile( "moved/pkg/B.txt" ).exists() );
        assertEquals( 1, FakeCheckoutRepositoryProvider.validateEditCalls() );
        assertEquals( Set.of( a, b ), Set.copyOf( FakeCheckoutRepositoryProvider.validatedFiles().get( 0 ) ) );
    }

    /**
     * The rename refactoring has no hook of its own: JDT asks {@code validateEdit} for
     * every file it is about to change during {@code checkFinalConditions}.
     */
    @Test
    @DisplayName( "Rename a class that another class uses, both not checked out: JDT checks out both at once" )
    public void renameClassUsedByAnotherClass() throws CoreException, IOException
    {
        IFile used = createFile( "src/Used.java", "public class Used\n{\n}\n" );
        IFile user = createFile( "src/User.java", "public class User\n{\n    Used field;\n}\n" );
        setReadOnly( used, true );
        setReadOnly( user, true );
        FakeCheckoutRepositoryProvider.reset();

        EditResult result = service.refactorRenameJavaType( TEST_PROJECT_NAME, "src/Used.java", "Renamed" );

        assertTrue( result.changedResource(), () -> describe( result ) );
        assertTrue( project.getFile( "src/Renamed.java" ).exists() );
        assertTrue( ResourceUtilities.readFileContent( user ).contains( "Renamed field;" ) );
        assertEquals( 1, FakeCheckoutRepositoryProvider.validateEditCalls() );
        assertEquals( Set.of( used, user ), Set.copyOf( FakeCheckoutRepositoryProvider.validatedFiles().get( 0 ) ) );
    }

    /**
     * Without a provider, Team UI's default validator asks whether to make the file
     * writable - the same prompt the editor shows. Declining it is the case where
     * nobody makes the file writable.
     */
    @Test
    @DisplayName( "Edit a read-only file outside version control, user declines to make it writable: READ_ONLY_RESOURCE" )
    public void editReadOnlyFileOutsideVersionControl() throws CoreException, IOException
    {
        RepositoryProvider.unmap( project );
        setReadOnly( file, true );
        TestDialogInterceptor.expect( DialogExpectation.withTitleContaining( "Read-only File" )
                .clickButtonContains( "No" ) );

        EditResult result = insertIntoFile();

        assertEquals( EditResult.EditStatus.REJECTED, result.status() );
        assertEquals( DiagnosticCode.READ_ONLY_RESOURCE, result.diagnostics().get( 0 ).code() );
        assertEquals( ORIGINAL, ResourceUtilities.readFileContent( file ) );
        assertEquals( 0, FakeCheckoutRepositoryProvider.validateEditCalls() );
        assertTrue( file.isReadOnly() );
    }

    private EditResult insertIntoFile()
    {
        return service.insertIntoFile( TEST_PROJECT_NAME, FILE_PATH, INSERTED, 3, IResource.NULL_STAMP, false );
    }

    private static String describe( EditResult result )
    {
        return "status " + result.status() + ", diagnostics " + result.diagnostics();
    }

    private void deleteProject() throws CoreException
    {
        // A read-only file cannot be deleted on Windows.
        makeWritable( project );
        project.delete( true, true, monitor );
    }

    private IFile createFile( String path, String content ) throws CoreException
    {
        IFile created = project.getFile( path );
        ResourceUtilities.createFolderHierarchy( (IFolder) created.getParent() );
        created.create( new ByteArrayInputStream( content.getBytes( StandardCharsets.UTF_8 ) ), true, monitor );
        return created;
    }

    private static void setReadOnly( IResource resource, boolean readOnly ) throws CoreException
    {
        ResourceAttributes attrs = resource.getResourceAttributes();
        attrs.setReadOnly( readOnly );
        resource.setResourceAttributes( attrs );
    }

    private static void makeWritable( IProject project ) throws CoreException
    {
        project.accept( resource -> {
            if ( resource.getType() == IResource.FILE && resource.isReadOnly() )
            {
                setReadOnly( resource, false );
            }
            return true;
        } );
    }
}
