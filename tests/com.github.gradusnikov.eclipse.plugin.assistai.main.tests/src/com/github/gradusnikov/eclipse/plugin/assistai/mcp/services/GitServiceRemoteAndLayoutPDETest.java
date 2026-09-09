package com.github.gradusnikov.eclipse.plugin.assistai.mcp.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IProjectDescription;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IWorkspace;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.ILog;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.e4.core.contexts.ContextInjectionFactory;
import org.eclipse.e4.core.contexts.EclipseContextFactory;
import org.eclipse.e4.core.contexts.IEclipseContext;
import org.eclipse.e4.ui.di.UISynchronize;
import org.eclipse.egit.core.op.ConnectProviderOperation;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.transport.URIish;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.github.gradusnikov.eclipse.assistai.Activator;
import com.github.gradusnikov.eclipse.assistai.mcp.results.DiagnosticCode;
import com.github.gradusnikov.eclipse.assistai.mcp.results.GitDiffResponse;
import com.github.gradusnikov.eclipse.assistai.mcp.results.GitFetchResponse;
import com.github.gradusnikov.eclipse.assistai.mcp.results.GitFetchResponse.FetchStatus;
import com.github.gradusnikov.eclipse.assistai.mcp.results.GitPullResponse;
import com.github.gradusnikov.eclipse.assistai.mcp.results.GitPullResponse.PullStatus;
import com.github.gradusnikov.eclipse.assistai.mcp.results.GitPushResponse;
import com.github.gradusnikov.eclipse.assistai.mcp.results.GitPushResponse.PushStatus;
import com.github.gradusnikov.eclipse.assistai.mcp.results.GitRemoteListResponse;
import com.github.gradusnikov.eclipse.assistai.mcp.results.GitStageResponse;
import com.github.gradusnikov.eclipse.assistai.mcp.results.GitStatusResponse;
import com.github.gradusnikov.eclipse.assistai.mcp.services.EditorService;
import com.github.gradusnikov.eclipse.assistai.mcp.services.GitService;
import com.github.gradusnikov.eclipse.assistai.tools.UISynchronizeCallable;

/**
 * {@link GitService} against the layout this plugin itself has: a repository whose
 * root is not a project, with an Eclipse project nested in {@code plugins/nested}.
 * That is where a project-relative path and a repository-relative path differ, and
 * where {@code gitAdd} used to match nothing. The same fixture gets a local bare
 * repository as {@code origin}, so fetch, pull and push are exercised for real
 * without a network.
 */
public class GitServiceRemoteAndLayoutPDETest
{
    private static final String NESTED = "GitNestedTestProject";
    private static final NullProgressMonitor monitor = new NullProgressMonitor();

    private Path root;
    private File repoDir;
    private File nestedDir;
    private File bareDir;
    private IProject project;
    private Git git;
    private GitService service;

    @BeforeEach
    public void beforeEach() throws Exception
    {
        root = Files.createTempDirectory( "assistai-git-layout" );
        repoDir = root.resolve( "repo" ).toFile();
        nestedDir = new File( repoDir, "plugins/nested" );
        bareDir = root.resolve( "origin.git" ).toFile();
        nestedDir.mkdirs();

        git = Git.init().setDirectory( repoDir ).setInitialBranch( "master" ).call();
        git.getRepository().updateRef( Constants.HEAD ).link( Constants.R_HEADS + "master" );
        writeRepo( "README.md", "# Repository root\n" );
        writeRepo( "plugins/nested/src/Nested.java", "public class Nested {}\n" );
        commitAll( "Initial commit" );

        Git.init().setBare( true ).setInitialBranch( "master" ).setDirectory( bareDir ).call().close();
        git.remoteAdd().setName( "origin" ).setUri( new URIish( bareDir.getAbsolutePath() ) ).call();

        IWorkspace workspace = ResourcesPlugin.getWorkspace();
        project = workspace.getRoot().getProject( NESTED );
        if ( project.exists() )
        {
            project.delete( true, true, monitor );
        }
        IProjectDescription description = workspace.newProjectDescription( NESTED );
        description.setLocation( IPath.fromOSString( nestedDir.getAbsolutePath() ) );
        project.create( description, monitor );
        project.open( monitor );

        new ConnectProviderOperation( project, git.getRepository().getDirectory() ).execute( monitor );
        project.refreshLocal( IResource.DEPTH_INFINITE, monitor );
        // Creating the project wrote a .project into the nested folder. Committed here so
        // the tests below see only the files they create themselves.
        commitAll( "Add Eclipse project metadata" );

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
            protected boolean dispatchEvents()
            {
                return true;
            }

            @Override
            protected void showBusyWhile( Runnable runnable )
            {
                runnable.run();
            }

            @Override
            protected boolean isUIThread( Thread thread )
            {
                return true;
            }
        } );
        context.set( UISynchronizeCallable.class, ContextInjectionFactory.make( UISynchronizeCallable.class, context ) );
        context.set( EditorService.class, ContextInjectionFactory.make( EditorService.class, context ) );
        service = ContextInjectionFactory.make( GitService.class, context );
    }

    @AfterEach
    public void afterEach() throws Exception
    {
        if ( git != null )
        {
            git.close();
        }
        if ( project != null && project.exists() )
        {
            project.delete( true, true, monitor );
        }
        if ( root != null )
        {
            deleteRecursively( root );
        }
    }

    // ---- fixture helpers -------------------------------------------------

    private void writeRepo( String repoPath, String content ) throws IOException
    {
        File file = new File( repoDir, repoPath );
        file.getParentFile().mkdirs();
        Files.writeString( file.toPath(), content, StandardCharsets.UTF_8 );
    }

    private void commitAll( String message ) throws Exception
    {
        git.add().addFilepattern( "." ).call();
        git.add().setUpdate( true ).addFilepattern( "." ).call();
        git.commit()
                .setMessage( message )
                .setAuthor( "AssistAI Tests", "assistai-tests@example.invalid" )
                .setCommitter( "AssistAI Tests", "assistai-tests@example.invalid" )
                .call();
    }

    /** Someone else's clone of origin, with one more commit pushed to it. */
    private void pushFromAnotherClone( String fileName, String message ) throws Exception
    {
        File other = Files.createTempDirectory( root, "other" ).toFile();
        try ( Git otherGit = Git.cloneRepository().setURI( bareDir.getAbsolutePath() ).setDirectory( other ).call() )
        {
            Files.writeString( new File( other, fileName ).toPath(), message + "\n", StandardCharsets.UTF_8 );
            otherGit.add().addFilepattern( "." ).call();
            otherGit.commit()
                    .setMessage( message )
                    .setAuthor( "Someone Else", "someone@example.invalid" )
                    .setCommitter( "Someone Else", "someone@example.invalid" )
                    .call();
            otherGit.push().call();
        }
    }

    private static void deleteRecursively( Path directory ) throws IOException
    {
        if ( !Files.exists( directory ) )
        {
            return;
        }
        try ( Stream<Path> paths = Files.walk( directory ) )
        {
            paths.sorted( Comparator.reverseOrder() ).forEach( path -> path.toFile().delete() );
        }
    }

    // ---- project-relative paths in a nested project ----------------------

    @Test
    public void stagingAndUnstagingResolveProjectRelativePaths() throws Exception
    {
        writeRepo( "plugins/nested/src/Nested.java", "public class Nested { int x; }\n" );
        project.refreshLocal( IResource.DEPTH_INFINITE, monitor );

        GitStageResponse added = service.addFiles( NESTED, "src/Nested.java" );

        // This is the case that used to stage nothing: the pattern was handed to Git
        // untouched, and Git read it from the repository root.
        assertEquals( 1, added.totalFiles(), added.summaryText() );
        assertEquals( NESTED, added.files().get( 0 ).projectName() );
        assertEquals( "src/Nested.java", added.files().get( 0 ).filePath() );
        assertEquals( "plugins/nested/src/Nested.java", added.files().get( 0 ).repoPath() );

        GitStageResponse reset = service.resetFiles( NESTED, "src/Nested.java" );
        assertEquals( 1, reset.totalFiles(), reset.summaryText() );
        assertTrue( service.getStatus( NESTED ).staged().isEmpty() );
    }

    @Test
    public void dotMeansTheProjectNotTheRepository() throws Exception
    {
        writeRepo( "outside.txt", "outside\n" );
        writeRepo( "plugins/nested/inside.txt", "inside\n" );
        project.refreshLocal( IResource.DEPTH_INFINITE, monitor );

        GitStageResponse staged = service.addFiles( NESTED, "." );

        assertEquals( 1, staged.totalFiles(), staged.summaryText() );
        assertEquals( "plugins/nested/inside.txt", staged.files().get( 0 ).repoPath() );
        GitStatusResponse status = service.getStatus( NESTED );
        assertTrue( status.untracked().stream().anyMatch( change -> "outside.txt".equals( change.repoPath() ) ),
            "a file outside the project is left alone" );

        GitStageResponse unstaged = service.resetFiles( NESTED, "." );
        assertEquals( 1, unstaged.totalFiles(), unstaged.summaryText() );
    }

    @Test
    public void diffPathFilterIsProjectRelative() throws Exception
    {
        writeRepo( "README.md", "# changed root\n" );
        writeRepo( "plugins/nested/src/Nested.java", "public class Nested { int y; }\n" );
        project.refreshLocal( IResource.DEPTH_INFINITE, monitor );

        GitDiffResponse diff = service.getDiff( NESTED, false, "src/Nested.java", false );

        assertEquals( 1, diff.totalFiles(), diff.summaryText() );
        assertEquals( "src/Nested.java", diff.files().get( 0 ).filePath() );
        assertEquals( "plugins/nested/src/Nested.java", diff.files().get( 0 ).repoPath() );

        GitDiffResponse wholeProject = service.getDiff( NESTED, false, ".", false );
        assertEquals( 1, wholeProject.totalFiles(), "'.' is the project, so the root README is not in it" );
    }

    // ---- remotes -----------------------------------------------------------

    @Test
    public void pushFetchAndPullRoundTripThroughALocalRemote() throws Exception
    {
        GitRemoteListResponse remotes = service.listRemotes( NESTED );
        assertEquals( 1, remotes.totalRemotes(), remotes.summaryText() );
        assertEquals( "origin", remotes.remotes().get( 0 ).name() );

        GitPushResponse push = service.push( NESTED, "origin", null, true, false );
        assertEquals( PushStatus.PUSHED, push.status(), push.summaryText() );
        assertEquals( 1, push.updates().size() );
        assertEquals( "OK", push.updates().get( 0 ).status() );
        assertEquals( "origin", git.getRepository().getConfig().getString( "branch", "master", "remote" ),
            "setUpstream recorded the tracking configuration" );

        assertEquals( PushStatus.UP_TO_DATE, service.push( NESTED, null, null, false, false ).status() );

        pushFromAnotherClone( "remote.txt", "Remote change" );

        GitFetchResponse fetch = service.fetch( NESTED, null, false );
        assertEquals( FetchStatus.UPDATED, fetch.status(), fetch.summaryText() );
        assertEquals( 1, fetch.totalUpdates() );
        assertEquals( "refs/remotes/origin/master", fetch.updates().get( 0 ).localRef() );
        assertEquals( "FAST_FORWARD", fetch.updates().get( 0 ).result() );
        assertFalse( new File( repoDir, "remote.txt" ).exists(), "a fetch does not touch the working tree" );

        assertEquals( FetchStatus.UP_TO_DATE, service.fetch( NESTED, null, false ).status() );

        GitPullResponse pull = service.pull( NESTED, null, null, false );
        assertEquals( PullStatus.FAST_FORWARD, pull.status(), pull.summaryText() );
        assertTrue( new File( repoDir, "remote.txt" ).exists(), "a pull brings the change into the working tree" );
        assertTrue( pull.refreshedProjects().contains( NESTED ) );

        assertEquals( PullStatus.ALREADY_UP_TO_DATE, service.pull( NESTED, null, null, false ).status() );
    }

    @Test
    public void pushIsRejectedWhenTheRemoteHasMovedOnUnlessForced() throws Exception
    {
        assertEquals( PushStatus.PUSHED, service.push( NESTED, null, null, true, false ).status() );
        pushFromAnotherClone( "theirs.txt", "Their change" );
        writeRepo( "plugins/nested/mine.txt", "mine\n" );
        commitAll( "My change" );

        GitPushResponse rejected = service.push( NESTED, null, null, false, false );

        assertEquals( PushStatus.REJECTED, rejected.status(), rejected.summaryText() );
        assertEquals( "REJECTED_NONFASTFORWARD", rejected.updates().get( 0 ).status() );
        assertEquals( List.of( DiagnosticCode.PUSH_REJECTED ),
            rejected.diagnostics().stream().map( d -> d.code() ).toList() );

        GitPushResponse forced = service.push( NESTED, null, null, false, true );
        assertEquals( PushStatus.PUSHED, forced.status(), forced.summaryText() );
    }

    @Test
    public void anUnknownRemoteIsAFailureWithACode()
    {
        GitFetchResponse fetch = service.fetch( NESTED, "nowhere", false );

        assertEquals( FetchStatus.FAILED, fetch.status() );
        assertEquals( List.of( DiagnosticCode.REMOTE_OPERATION_FAILED ),
            fetch.diagnostics().stream().map( d -> d.code() ).toList() );

        GitPushResponse push = service.push( NESTED, "nowhere", null, false, false );
        assertEquals( PushStatus.FAILED, push.status() );
    }
}
