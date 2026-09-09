package com.github.gradusnikov.eclipse.plugin.assistai.mcp.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IProjectDescription;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IWorkspace;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.ILog;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.e4.core.contexts.ContextInjectionFactory;
import org.eclipse.e4.core.contexts.EclipseContextFactory;
import org.eclipse.e4.core.contexts.IEclipseContext;
import org.eclipse.e4.ui.di.UISynchronize;
import org.eclipse.egit.core.op.ConnectProviderOperation;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.revwalk.RevCommit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.github.gradusnikov.eclipse.assistai.Activator;
import com.github.gradusnikov.eclipse.assistai.mcp.results.DiagnosticCode;
import com.github.gradusnikov.eclipse.assistai.mcp.results.GitApplyCommitsResponse;
import com.github.gradusnikov.eclipse.assistai.mcp.results.GitApplyCommitsResponse.ApplyStatus;
import com.github.gradusnikov.eclipse.assistai.mcp.results.GitCommitResponse;
import com.github.gradusnikov.eclipse.assistai.mcp.results.GitDiffResponse;
import com.github.gradusnikov.eclipse.assistai.mcp.results.GitDiffResponse.FileChangeType;
import com.github.gradusnikov.eclipse.assistai.mcp.results.GitDiffResponse.GitFileDiff;
import com.github.gradusnikov.eclipse.assistai.mcp.results.GitDiscardResponse;
import com.github.gradusnikov.eclipse.assistai.mcp.results.GitLogResponse;
import com.github.gradusnikov.eclipse.assistai.mcp.results.GitMergeResponse;
import com.github.gradusnikov.eclipse.assistai.mcp.results.GitMergeResponse.MergeStatus;
import com.github.gradusnikov.eclipse.assistai.mcp.results.GitRebaseResponse;
import com.github.gradusnikov.eclipse.assistai.mcp.results.GitRebaseResponse.RebaseStatus;
import com.github.gradusnikov.eclipse.assistai.mcp.results.GitResetResponse;
import com.github.gradusnikov.eclipse.assistai.mcp.results.GitResetResponse.ResetMode;
import com.github.gradusnikov.eclipse.assistai.mcp.results.GitShowResponse;
import com.github.gradusnikov.eclipse.assistai.mcp.results.GitStageResponse;
import com.github.gradusnikov.eclipse.assistai.mcp.results.GitStatusResponse;
import com.github.gradusnikov.eclipse.assistai.mcp.results.GitStatusResponse.ChangeType;
import com.github.gradusnikov.eclipse.assistai.mcp.results.GitTagListResponse;
import com.github.gradusnikov.eclipse.assistai.mcp.results.GitTagResponse;
import com.github.gradusnikov.eclipse.assistai.mcp.results.GitTagResponse.TagOperation;
import com.github.gradusnikov.eclipse.assistai.mcp.services.EditorService;
import com.github.gradusnikov.eclipse.assistai.mcp.services.GitService;
import com.github.gradusnikov.eclipse.assistai.tools.UISynchronizeCallable;

/**
 * The history-rewriting half of {@link GitService}: merging, rebasing, replaying
 * commits, tags, and the ways of undoing work. Every test starts from a fresh
 * repository with one commit, so the branches it builds are small enough to reason
 * about by hand.
 */
public class GitServiceBranchingPDETest
{
    private static final String PROJECT = "GitServiceBranchingTestProject";
    private static final NullProgressMonitor monitor = new NullProgressMonitor();

    private IProject project;
    private Git git;
    private GitService service;
    private File repoDir;

    @BeforeEach
    public void beforeEach() throws Exception
    {
        IWorkspace workspace = ResourcesPlugin.getWorkspace();
        project = workspace.getRoot().getProject( PROJECT );
        if ( project.exists() )
        {
            project.delete( true, true, monitor );
        }
        IProjectDescription description = workspace.newProjectDescription( PROJECT );
        project.create( description, monitor );
        project.open( monitor );

        repoDir = project.getLocation().toFile();
        git = Git.init().setDirectory( repoDir ).setInitialBranch( "master" ).call();
        git.getRepository().updateRef( Constants.HEAD ).link( Constants.R_HEADS + "master" );

        write( "README.md", "# Test Project\n" );
        write( "src/Hello.java", "public class Hello {}\n" );
        commitAll( "Initial commit" );

        new ConnectProviderOperation( project, git.getRepository().getDirectory() ).execute( monitor );
        project.refreshLocal( IResource.DEPTH_INFINITE, monitor );

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
            for ( int attempt = 0; attempt < 5; attempt++ )
            {
                try
                {
                    project.delete( true, true, monitor );
                    break;
                }
                catch ( Exception e )
                {
                    if ( attempt == 4 )
                    {
                        throw e;
                    }
                    Thread.sleep( 500 );
                }
            }
        }
    }

    // ---- fixture helpers -------------------------------------------------

    private void write( String path, String content ) throws Exception
    {
        File file = new File( repoDir, path );
        file.getParentFile().mkdirs();
        Files.writeString( file.toPath(), content, StandardCharsets.UTF_8 );
    }

    private String read( String path ) throws Exception
    {
        return Files.readString( new File( repoDir, path ).toPath(), StandardCharsets.UTF_8 );
    }

    private RevCommit commitAll( String message ) throws Exception
    {
        git.add().addFilepattern( "." ).call();
        git.add().setUpdate( true ).addFilepattern( "." ).call();
        return git.commit()
                .setMessage( message )
                .setAuthor( "AssistAI Tests", "assistai-tests@example.invalid" )
                .setCommitter( "AssistAI Tests", "assistai-tests@example.invalid" )
                .call();
    }

    /** A branch off the given start point carrying one commit that changes README.md. */
    private void branchWithReadme( String branch, String startPoint, String content ) throws Exception
    {
        git.checkout().setCreateBranch( true ).setName( branch ).setStartPoint( startPoint ).call();
        write( "README.md", content );
        commitAll( branch + " edit" );
        git.checkout().setName( "master" ).call();
    }

    private String head() throws Exception
    {
        return git.getRepository().resolve( Constants.HEAD ).getName();
    }

    // ---- merge -------------------------------------------------------------

    @Test
    public void mergeFastForwardsWhenItCan() throws Exception
    {
        git.checkout().setCreateBranch( true ).setName( "feature" ).call();
        write( "feature.txt", "feature\n" );
        commitAll( "Add feature" );
        git.checkout().setName( "master" ).call();

        GitMergeResponse result = service.merge( PROJECT, "feature", null, false, null );

        assertEquals( MergeStatus.FAST_FORWARD, result.status(), result.summaryText() );
        assertEquals( git.getRepository().resolve( "feature" ).getName(), result.newHead() );
        assertTrue( new File( repoDir, "feature.txt" ).exists(), "the working tree was moved forward" );
        assertTrue( result.refreshedProjects().contains( PROJECT ), "a merge rewrites the working tree, so the project is refreshed" );
    }

    @Test
    public void mergeWithNoFastForwardCreatesAMergeCommit() throws Exception
    {
        git.checkout().setCreateBranch( true ).setName( "feature" ).call();
        write( "feature.txt", "feature\n" );
        commitAll( "Add feature" );
        git.checkout().setName( "master" ).call();

        GitMergeResponse result = service.merge( PROJECT, "feature", "NO_FF", false, "Merge feature" );

        assertEquals( MergeStatus.MERGED, result.status(), result.summaryText() );
        assertNotNull( result.commit(), "the merge commit is the handle for what happened" );
        assertEquals( "Merge feature", result.commit().shortMessage() );
        RevCommit head = git.log().setMaxCount( 1 ).call().iterator().next();
        assertEquals( 2, head.getParentCount(), "a real merge commit has both parents" );
    }

    @Test
    public void mergeReportsConflictsAsFilesAndAHardResetAbandonsThem() throws Exception
    {
        write( "README.md", "# master\n" );
        commitAll( "Master edit" );
        branchWithReadme( "feature", "HEAD~1", "# feature\n" );

        GitMergeResponse result = service.merge( PROJECT, "feature", null, false, null );

        assertEquals( MergeStatus.CONFLICTED, result.status(), result.summaryText() );
        assertEquals( 1, result.conflicting().size() );
        assertEquals( PROJECT, result.conflicting().get( 0 ).projectName() );
        assertEquals( "README.md", result.conflicting().get( 0 ).filePath(), "the editing tools take a project-relative path" );
        assertEquals( List.of( DiagnosticCode.MERGE_CONFLICT ),
            result.diagnostics().stream().map( d -> d.code() ).toList() );
        assertTrue( read( "README.md" ).contains( "<<<<<<<" ), "conflict markers are in the working tree" );

        GitResetResponse reset = service.resetToRevision( PROJECT, "HEAD", "HARD" );
        assertEquals( ResetMode.HARD, reset.mode() );
        assertTrue( service.getStatus( PROJECT ).clean(), "a hard reset to HEAD abandons the merge" );
        assertEquals( "# master\n", read( "README.md" ) );
    }

    @Test
    public void mergeOfAnUnknownRevisionIsAFailureWithACode()
    {
        GitMergeResponse result = service.merge( PROJECT, "no-such-branch", null, false, null );

        assertEquals( MergeStatus.FAILED, result.status() );
        assertEquals( List.of( DiagnosticCode.REVISION_NOT_FOUND ),
            result.diagnostics().stream().map( d -> d.code() ).toList() );
    }

    // ---- rebase ------------------------------------------------------------

    @Test
    public void rebaseReplaysTheBranchOntoTheUpstream() throws Exception
    {
        write( "master.txt", "master\n" );
        commitAll( "Master work" );
        git.checkout().setCreateBranch( true ).setName( "feature" ).setStartPoint( "HEAD~1" ).call();
        write( "feature.txt", "feature\n" );
        commitAll( "Feature work" );

        GitRebaseResponse result = service.rebase( PROJECT, "master", null );

        assertEquals( RebaseStatus.OK, result.status(), result.summaryText() );
        assertEquals( "feature", result.currentBranch() );
        assertTrue( new File( repoDir, "master.txt" ).exists(), "the branch now contains the upstream's work" );
        RevCommit head = git.log().setMaxCount( 1 ).call().iterator().next();
        assertEquals( "Feature work", head.getShortMessage() );
        assertEquals( git.getRepository().resolve( "master" ).getName(), head.getParent( 0 ).getName(),
            "the replayed commit sits directly on the upstream" );
    }

    @Test
    public void rebaseStopsOnAConflictAndCanBeAborted() throws Exception
    {
        write( "README.md", "# master\n" );
        commitAll( "Master edit" );
        git.checkout().setCreateBranch( true ).setName( "feature" ).setStartPoint( "HEAD~1" ).call();
        write( "README.md", "# feature\n" );
        commitAll( "Feature edit" );

        GitRebaseResponse stopped = service.rebase( PROJECT, "master", "BEGIN" );

        assertEquals( RebaseStatus.STOPPED, stopped.status(), stopped.summaryText() );
        assertNotNull( stopped.stoppedAt(), "the paused commit is what the caller must resolve" );
        assertEquals( "Feature edit", stopped.stoppedAt().shortMessage() );
        assertEquals( 1, stopped.conflicting().size() );
        assertEquals( "README.md", stopped.conflicting().get( 0 ).filePath() );

        GitRebaseResponse aborted = service.rebase( PROJECT, null, "ABORT" );

        assertEquals( RebaseStatus.ABORTED, aborted.status(), aborted.summaryText() );
        assertEquals( "feature", aborted.currentBranch() );
        assertTrue( service.getStatus( PROJECT ).clean(), "an abort leaves nothing behind" );
        assertEquals( "# feature\n", read( "README.md" ), "the branch is back where it started" );
    }

    @Test
    public void rebaseWithNothingInProgressIsAFailureWithACode()
    {
        GitRebaseResponse result = service.rebase( PROJECT, null, "CONTINUE" );

        assertEquals( RebaseStatus.FAILED, result.status() );
        assertEquals( List.of( DiagnosticCode.WRONG_REPOSITORY_STATE ),
            result.diagnostics().stream().map( d -> d.code() ).toList() );
    }

    // ---- cherry-pick and revert -------------------------------------------

    @Test
    public void cherryPickCreatesANewCommitWithTheSameChange() throws Exception
    {
        git.checkout().setCreateBranch( true ).setName( "feature" ).call();
        write( "picked.txt", "picked\n" );
        RevCommit picked = commitAll( "Picked change" );
        git.checkout().setName( "master" ).call();

        GitApplyCommitsResponse result = service.cherryPick( PROJECT, List.of( picked.getName() ) );

        assertEquals( ApplyStatus.APPLIED, result.status(), result.summaryText() );
        assertEquals( 1, result.createdCommits().size() );
        assertEquals( "Picked change", result.createdCommits().get( 0 ).shortMessage() );
        assertNotEquals( picked.getName(), result.createdCommits().get( 0 ).sha(), "a cherry-pick is a new commit" );
        assertEquals( result.createdCommits().get( 0 ).sha(), result.newHead() );
        assertTrue( new File( repoDir, "picked.txt" ).exists() );
    }

    @Test
    public void revertUndoesACommitWithANewOne() throws Exception
    {
        write( "temp.txt", "temp\n" );
        RevCommit added = commitAll( "Add temp" );

        GitApplyCommitsResponse result = service.revert( PROJECT, List.of( added.getName() ) );

        assertEquals( ApplyStatus.APPLIED, result.status(), result.summaryText() );
        assertFalse( new File( repoDir, "temp.txt" ).exists(), "the reverted commit's change is gone" );
        assertEquals( 1, result.createdCommits().size() );
        assertTrue( result.createdCommits().get( 0 ).shortMessage().startsWith( "Revert" ),
            result.createdCommits().get( 0 ).shortMessage() );
        assertEquals( 3, service.getLog( PROJECT, 10 ).commitCount(), "history is kept, not rewritten" );
    }

    @Test
    public void cherryPickOfAnUnknownCommitIsAFailureWithACode()
    {
        GitApplyCommitsResponse result = service.cherryPick( PROJECT, List.of( "0123456789abcdef0123456789abcdef01234567" ) );

        assertEquals( ApplyStatus.FAILED, result.status() );
        assertEquals( List.of( DiagnosticCode.REVISION_NOT_FOUND ),
            result.diagnostics().stream().map( d -> d.code() ).toList() );
    }

    // ---- tags --------------------------------------------------------------

    @Test
    public void tagsAreCreatedListedAndDeleted() throws Exception
    {
        GitTagResponse lightweight = service.createTag( PROJECT, "v0.1", null, null );
        assertEquals( TagOperation.CREATED, lightweight.operation() );
        assertFalse( lightweight.tag().annotated() );
        assertEquals( head(), lightweight.tag().targetSha() );
        assertEquals( lightweight.tag().sha(), lightweight.tag().targetSha(), "a lightweight tag is the commit itself" );

        GitTagResponse annotated = service.createTag( PROJECT, "v0.2", "Release 0.2", "HEAD" );
        assertTrue( annotated.tag().annotated() );
        assertEquals( "Release 0.2", annotated.tag().message() );
        assertEquals( head(), annotated.tag().targetSha() );
        assertNotEquals( annotated.tag().sha(), annotated.tag().targetSha(), "an annotated tag is its own object" );

        GitTagListResponse list = service.listTags( PROJECT );
        assertEquals( 2, list.totalTags(), list.summaryText() );
        assertTrue( list.tags().stream().anyMatch( tag -> tag.name().equals( "v0.1" ) && !tag.annotated() ) );
        assertTrue( list.tags().stream().anyMatch( tag -> tag.name().equals( "v0.2" ) && tag.annotated() ) );

        GitTagResponse deleted = service.deleteTag( PROJECT, "v0.1" );
        assertEquals( TagOperation.DELETED, deleted.operation() );
        assertEquals( "v0.1", deleted.tag().name() );
        assertEquals( 1, service.listTags( PROJECT ).totalTags() );
        assertThrows( IllegalArgumentException.class, () -> service.deleteTag( PROJECT, "v0.1" ) );
    }

    // ---- show --------------------------------------------------------------

    @Test
    public void showReportsTheCommitAndItsChange() throws Exception
    {
        write( "src/Hello.java", "public class Hello { int x; }\n" );
        write( "added.txt", "new\n" );
        RevCommit change = commitAll( "Change hello" );

        GitShowResponse show = service.show( PROJECT, change.getName(), null );

        assertEquals( change.getName(), show.commit().sha() );
        assertEquals( "Change hello", show.commit().shortMessage() );
        assertEquals( 1, show.parentShas().size() );
        assertEquals( 2, show.totalFiles(), show.summaryText() );
        GitFileDiff added = show.files().stream().filter( f -> "added.txt".equals( f.repoPath() ) ).findFirst().orElseThrow();
        assertEquals( FileChangeType.ADDED, added.changeType() );
        assertEquals( PROJECT, added.projectName() );
        assertEquals( "added.txt", added.filePath() );
        GitFileDiff modified = show.files().stream().filter( f -> "src/Hello.java".equals( f.repoPath() ) ).findFirst().orElseThrow();
        assertEquals( FileChangeType.MODIFIED, modified.changeType() );
        assertTrue( show.unifiedDiff().contains( "+public class Hello { int x; }" ), show.unifiedDiff() );

        GitShowResponse root = service.show( PROJECT, "HEAD~1", null );
        assertTrue( root.parentShas().isEmpty(), "a root commit has no parent" );
        // Shown against the empty tree, so every file of the initial commit is an addition -
        // including the .project Eclipse wrote when the fixture project was created.
        assertTrue( root.totalFiles() >= 2, root.summaryText() );
        for ( String expected : List.of( "README.md", "src/Hello.java" ) )
        {
            GitFileDiff file = root.files().stream().filter( f -> expected.equals( f.repoPath() ) ).findFirst().orElseThrow();
            assertEquals( FileChangeType.ADDED, file.changeType(), expected );
        }

        GitShowResponse filtered = service.show( PROJECT, change.getName(), "added.txt" );
        assertEquals( 1, filtered.totalFiles() );

        assertThrows( IllegalArgumentException.class, () -> service.show( PROJECT, "no-such-revision", null ) );
    }

    // ---- undoing -----------------------------------------------------------

    @Test
    public void resetToRevisionSoftKeepsTheWorkStaged() throws Exception
    {
        write( "soft.txt", "soft\n" );
        RevCommit committed = commitAll( "Soft commit" );

        GitResetResponse reset = service.resetToRevision( PROJECT, "HEAD~1", "SOFT" );

        assertEquals( committed.getName(), reset.previousHead(), "the previous head is the handle for undoing the reset" );
        assertEquals( head(), reset.newHead() );
        assertEquals( ResetMode.SOFT, reset.mode() );
        GitStatusResponse status = service.getStatus( PROJECT );
        assertEquals( 1, status.staged().size(), status.summaryText() );
        assertEquals( "soft.txt", status.staged().get( 0 ).filePath() );
        assertEquals( ChangeType.ADDED, status.staged().get( 0 ).changeType() );

        assertThrows( IllegalArgumentException.class, () -> service.resetToRevision( PROJECT, "no-such-revision", "HARD" ) );
    }

    @Test
    public void discardChangesRestoresTrackedFilesOnly() throws Exception
    {
        write( "README.md", "# changed\n" );
        write( "untracked.txt", "u\n" );

        GitDiscardResponse result = service.discardChanges( PROJECT, "README.md, untracked.txt" );

        assertEquals( 1, result.totalFiles(), result.summaryText() );
        assertEquals( "README.md", result.files().get( 0 ).filePath() );
        assertEquals( ChangeType.MODIFIED, result.files().get( 0 ).changeType() );
        assertEquals( "# Test Project\n", read( "README.md" ), "the working tree content came back from the index" );
        assertTrue( new File( repoDir, "untracked.txt" ).exists(), "untracked files are not Git's to discard" );

        GitDiscardResponse nothing = service.discardChanges( PROJECT, "README.md" );
        assertEquals( 0, nothing.totalFiles(), "an unchanged file is reported as nothing discarded, not as success" );
    }

    // ---- commit, log and diff extensions ----------------------------------

    @Test
    public void commitCanStageTrackedChangesAndAmend() throws Exception
    {
        write( "README.md", "# v2\n" );

        GitCommitResponse first = service.commit( PROJECT, "Update readme", false, true );
        assertEquals( "Update readme", first.commit().shortMessage() );
        assertTrue( service.getStatus( PROJECT ).clean(), "all=true staged the tracked change" );

        GitCommitResponse amended = service.commit( PROJECT, "Update readme (amended)", true, false );
        assertNotEquals( first.commit().sha(), amended.commit().sha() );

        GitLogResponse log = service.getLog( PROJECT, 10 );
        assertEquals( 2, log.commitCount(), "amending replaces the commit rather than adding one" );
        assertEquals( "Update readme (amended)", log.commits().get( 0 ).shortMessage() );
    }

    @Test
    public void logCanStartFromARevisionAndFilterByPath() throws Exception
    {
        write( "a.txt", "a\n" );
        commitAll( "Add a" );
        write( "b.txt", "b\n" );
        commitAll( "Add b" );

        GitLogResponse byPath = service.getLog( PROJECT, 10, null, "a.txt" );
        assertEquals( 1, byPath.commitCount(), byPath.summaryText() );
        assertEquals( "Add a", byPath.commits().get( 0 ).shortMessage() );

        GitLogResponse fromRevision = service.getLog( PROJECT, 10, "HEAD~1", null );
        assertEquals( 2, fromRevision.commitCount(), fromRevision.summaryText() );
        assertEquals( "Add a", fromRevision.commits().get( 0 ).shortMessage() );
        assertEquals( "HEAD~1", fromRevision.branch(), "the log says what it was walked from" );

        assertThrows( IllegalArgumentException.class, () -> service.getLog( PROJECT, 10, "no-such-revision", null ) );
    }

    @Test
    public void diffCanCompareRevisionsAndARevisionWithTheWorkingTree() throws Exception
    {
        write( "README.md", "# v2\n" );
        commitAll( "Second" );

        GitDiffResponse between = service.getDiff( PROJECT, false, null, false, "HEAD~1", "HEAD" );
        assertFalse( between.identical() );
        assertEquals( 1, between.totalFiles(), between.summaryText() );
        assertEquals( "HEAD~1", between.fromLabel() );
        assertEquals( "HEAD", between.toLabel() );
        assertEquals( git.getRepository().resolve( "HEAD~1" ).getName(), between.baseRevision() );
        assertTrue( between.unifiedDiff().contains( "+# v2" ), between.unifiedDiff() );

        write( "README.md", "# v3\n" );
        GitDiffResponse toWorkingTree = service.getDiff( PROJECT, true, null, false, "HEAD~1", null );
        assertEquals( "WORKING_TREE", toWorkingTree.toLabel() );
        assertFalse( toWorkingTree.staged(), "a revision comparison is neither the staged nor the unstaged diff" );
        assertTrue( toWorkingTree.unifiedDiff().contains( "+# v3" ), toWorkingTree.unifiedDiff() );
    }

    @Test
    public void addAndResetAcceptCommaSeparatedPathsAndDot() throws Exception
    {
        write( "one.txt", "1\n" );
        write( "two.txt", "2\n" );
        write( "three.txt", "3\n" );

        GitStageResponse added = service.addFiles( PROJECT, "one.txt, two.txt" );
        assertEquals( 2, added.totalFiles(), added.summaryText() );

        GitStageResponse reset = service.resetFiles( PROJECT, "one.txt" );
        assertEquals( 1, reset.totalFiles(), reset.summaryText() );
        assertEquals( "one.txt", reset.files().get( 0 ).filePath() );

        GitStageResponse everything = service.addFiles( PROJECT, "." );
        assertEquals( 2, everything.totalFiles(), "one.txt and three.txt joined two.txt, which was already staged" );

        GitStageResponse unstagedAll = service.resetFiles( PROJECT, "." );
        assertEquals( 3, unstagedAll.totalFiles(), unstagedAll.summaryText() );
        assertTrue( service.getStatus( PROJECT ).staged().isEmpty() );
    }
}
