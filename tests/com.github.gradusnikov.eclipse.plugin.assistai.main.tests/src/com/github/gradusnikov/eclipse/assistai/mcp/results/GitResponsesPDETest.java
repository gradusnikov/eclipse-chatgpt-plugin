package com.github.gradusnikov.eclipse.assistai.mcp.results;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IProjectDescription;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IWorkspace;
import org.eclipse.core.resources.IWorkspaceRoot;
import org.eclipse.core.runtime.ILog;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.e4.core.contexts.ContextInjectionFactory;
import org.eclipse.e4.core.contexts.EclipseContextFactory;
import org.eclipse.e4.core.contexts.IEclipseContext;
import org.eclipse.e4.ui.di.UISynchronize;
import org.eclipse.egit.core.op.ConnectProviderOperation;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.Constants;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.osgi.framework.BundleContext;
import org.osgi.framework.FrameworkUtil;
import org.osgi.util.tracker.ServiceTracker;

import com.github.gradusnikov.eclipse.assistai.Activator;
import com.github.gradusnikov.eclipse.assistai.mcp.McpOutputSchemas;
import com.github.gradusnikov.eclipse.assistai.mcp.results.GitStatusResponse.ChangeType;
import com.github.gradusnikov.eclipse.assistai.mcp.results.GitStatusResponse.GitFileChange;
import com.github.gradusnikov.eclipse.assistai.mcp.services.EditorService;
import com.github.gradusnikov.eclipse.assistai.mcp.services.GitService;
import com.github.gradusnikov.eclipse.assistai.tools.UISynchronizeCallable;

/**
 * The structured results of the Git tools.
 * <p>
 * Two things are checked: that a caller can act on what comes back - a changed file
 * names the project and project-relative path the reading and editing tools take, a
 * commit carries its sha and a comparable timestamp, the checked-out branch is a flag -
 * and that an empty result is still a result, with counts saying so rather than a
 * sentence in place of the data.
 * <p>
 * Assertions are on fields only. Wording is not part of the contract.
 */
public class GitResponsesPDETest
{
    private static final String TEST_PROJECT_NAME = "GitResponsesTestProject";
    private static final NullProgressMonitor monitor = new NullProgressMonitor();

    private IProject project;
    private Git git;
    private GitService service;
    private File repoDir;

    @BeforeEach
    public void beforeEach() throws Exception
    {
        BundleContext bundleContext = FrameworkUtil.getBundle( GitResponsesPDETest.class ).getBundleContext();
        ServiceTracker<IWorkspace, IWorkspace> workspaceTracker = new ServiceTracker<>( bundleContext, IWorkspace.class, null );
        workspaceTracker.open();
        IWorkspace workspace = workspaceTracker.getService();
        IWorkspaceRoot root = workspace.getRoot();

        project = root.getProject( TEST_PROJECT_NAME );
        if ( project.exists() )
        {
            project.delete( true, true, monitor );
        }

        IProjectDescription description = workspace.newProjectDescription( TEST_PROJECT_NAME );
        project.create( description, monitor );
        project.open( monitor );

        repoDir = project.getLocation().toFile();
        git = Git.init().setDirectory( repoDir ).setInitialBranch( "master" ).call();
        git.getRepository().updateRef( Constants.HEAD ).link( Constants.R_HEADS + "master" );

        File source = new File( repoDir, "src/Hello.java" );
        source.getParentFile().mkdirs();
        Files.writeString( source.toPath(), "public class Hello {}\n", StandardCharsets.UTF_8 );
        Files.writeString( new File( repoDir, "README.md" ).toPath(), "# Test Project\n", StandardCharsets.UTF_8 );

        commitEverything( "Initial commit" );

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
            // Let whatever EGit/resource-change jobs the unmap or earlier operations
            // queued actually finish before the project disappears underneath them.
            waitForPendingJobsAndUnmapToAvoidExceptions(project);

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

    /**
     * Unmaps the Team provider and waits for the workspace's build and change-notification
     * jobs to drain. Teardown that deletes a project out from under a still-running job
     * (EGit's index diff cache, PDE's classpath container updater, etc.) turns that job's
     * next step into a spurious failure logged against a project that, by then, no longer exists.
     * @param projectToUnmap 
     */
    public static void waitForPendingJobsAndUnmapToAvoidExceptions(IProject projectToUnmap) throws InterruptedException
    {
        // Disconnect the Team provider first so EGit drops its GitProjectData for this
        // project synchronously, on this thread. Without this, EGit's resource-change
        // listener (IndexDiffCacheEntry) can still be reacting - asynchronously, on a
        // job - to the delete below when it goes looking for GitProjectData.properties
        // that the delete already removed, logging a NoSuchFileException that has
        // nothing to do with this test and everything to do with its teardown racing
        // EGit's own bookkeeping.
        try
        {
            if (projectToUnmap != null) org.eclipse.team.core.RepositoryProvider.unmap( projectToUnmap );
        }
        catch ( Exception e )
        {
            // Best effort - the delete below is still attempted either way.
        }
        
        org.eclipse.core.runtime.jobs.IJobManager jobManager = org.eclipse.core.runtime.jobs.Job.getJobManager();
        jobManager.join( org.eclipse.core.resources.ResourcesPlugin.FAMILY_AUTO_BUILD, monitor );
        jobManager.join( org.eclipse.core.resources.ResourcesPlugin.FAMILY_MANUAL_BUILD, monitor );
    }

    private void commitEverything( String message ) throws Exception
    {
        git.add().addFilepattern( "." ).call();
        git.add().setUpdate( true ).addFilepattern( "." ).call();
        git.commit().setMessage( message )
                .setAuthor( "AssistAI Tests", "assistai-tests@example.invalid" )
                .setCommitter( "AssistAI Tests", "assistai-tests@example.invalid" )
                .call();
    }

    private static GitFileChange findByPath( List<GitFileChange> changes, String filePath )
    {
        return changes.stream().filter( change -> filePath.equals( change.filePath() ) ).findFirst()
                .orElseThrow( () -> new AssertionError( filePath + " not among " + changes ) );
    }

    // ---- schema ----------------------------------------------------------

    @SuppressWarnings( "unchecked" )
    private static Map<String, Object> properties( Class<?> type )
    {
        Map<String, Object> schema = McpOutputSchemas.forType( type );
        assertNotNull( schema, type.getSimpleName() + " must advertise a schema" );
        return (Map<String, Object>) schema.get( "properties" );
    }

    @SuppressWarnings( "unchecked" )
    private static Map<String, Object> itemsOf( Map<String, Object> properties, String field )
    {
        Map<String, Object> array = (Map<String, Object>) properties.get( field );
        assertNotNull( array, field + " should be advertised" );
        assertEquals( "array", array.get( "type" ), field + " should be an array" );
        return (Map<String, Object>) ( (Map<String, Object>) array.get( "items" ) ).get( "properties" );
    }

    @SuppressWarnings( "unchecked" )
    private static Map<String, Object> field( Map<String, Object> properties, String name )
    {
        Map<String, Object> schema = (Map<String, Object>) properties.get( name );
        assertNotNull( schema, name + " should be advertised, saw " + properties.keySet() );
        return schema;
    }

    @Test
    public void statusAdvertisesWhatIsNeededToOpenAChangedFile()
    {
        Map<String, Object> fields = properties( GitStatusResponse.class );

        for ( String list : List.of( "staged", "unstaged", "untracked", "conflicting" ) )
        {
            Map<String, Object> change = itemsOf( fields, list );

            // A change is only actionable if it can be fed back into the read/edit tools,
            // which address files by project and project-relative path.
            assertTrue( change.containsKey( "projectName" ), list + ": " + change.keySet() );
            assertTrue( change.containsKey( "filePath" ), list + ": " + change.keySet() );
            assertTrue( change.containsKey( "repoPath" ), list + ": " + change.keySet() );

            @SuppressWarnings( "unchecked" )
            List<String> changeTypes = (List<String>) field( change, "changeType" ).get( "enum" );
            assertTrue( changeTypes.contains( "MODIFIED" ), changeTypes.toString() );
            assertTrue( changeTypes.contains( "DELETED" ), changeTypes.toString() );
        }

        assertEquals( "boolean", field( fields, "clean" ).get( "type" ) );
        assertEquals( "integer", field( fields, "totalChanges" ).get( "type" ) );
    }

    @Test
    public void logAdvertisesAnEpochTimestampRatherThanAFormattedDate()
    {
        Map<String, Object> fields = properties( GitLogResponse.class );
        Map<String, Object> commit = itemsOf( fields, "commits" );

        assertEquals( "integer", field( commit, "authorTimeMillis" ).get( "type" ),
                "a client should compare and format the time itself, not parse it back" );
        assertTrue( commit.containsKey( "sha" ) );
        assertTrue( commit.containsKey( "shortSha" ) );
        assertTrue( commit.containsKey( "authorEmail" ) );
        assertTrue( commit.containsKey( "shortMessage" ) );
        assertEquals( "boolean", field( fields, "truncated" ).get( "type" ) );
    }

    @Test
    public void branchesAdvertiseTheCurrentFlagAndTheTwoLists()
    {
        Map<String, Object> fields = properties( GitBranchResponse.class );

        assertEquals( "boolean", field( itemsOf( fields, "branches" ), "current" ).get( "type" ) );
        assertTrue( itemsOf( fields, "remoteBranches" ).containsKey( "name" ) );
        assertTrue( fields.containsKey( "currentBranch" ) );
    }

    @Test
    public void stashEntriesAdvertiseIndexAndSha()
    {
        Map<String, Object> stash = itemsOf( properties( GitStashListResponse.class ), "stashes" );

        assertEquals( "integer", field( stash, "index" ).get( "type" ) );
        assertTrue( stash.containsKey( "sha" ), "an index shifts when another stash is pushed; a sha does not" );
        assertTrue( stash.containsKey( "message" ) );
    }

    // ---- path resolution -------------------------------------------------

    @Test
    public void locateResolvesARepositoryPathAgainstTheProjectThatOwnsIt()
    {
        Map<String, String> projects = new LinkedHashMap<>();
        projects.put( "", "Repository" );
        projects.put( "plugins/com.example.plugin", "com.example.plugin" );

        GitFileChange change = GitStatusResponse.locate( "plugins/com.example.plugin/src/A.java", ChangeType.MODIFIED, projects );

        assertEquals( "com.example.plugin", change.projectName(), "the innermost project owns the file" );
        assertEquals( "src/A.java", change.filePath(), "the editing tools take a project-relative path" );
        assertEquals( "plugins/com.example.plugin/src/A.java", change.repoPath(), "the Git tools still take a repository path" );
        assertEquals( ChangeType.MODIFIED, change.changeType() );
    }

    @Test
    public void locateFallsBackToAProjectAtTheRepositoryRoot()
    {
        Map<String, String> projects = new LinkedHashMap<>();
        projects.put( "", "Repository" );
        projects.put( "plugins/com.example.plugin", "com.example.plugin" );

        GitFileChange change = GitStatusResponse.locate( "README.md", ChangeType.UNTRACKED, projects );

        assertEquals( "Repository", change.projectName() );
        assertEquals( "README.md", change.filePath() );
    }

    @Test
    public void locateLeavesAFileNoProjectCoversWithoutAPath()
    {
        GitFileChange change = GitStatusResponse.locate( "tools/build.sh", ChangeType.UNTRACKED,
                Map.of( "plugins/com.example.plugin", "com.example.plugin" ) );

        // Inventing a project-relative path here would name a file no tool can open.
        assertNull( change.projectName() );
        assertNull( change.filePath() );
        assertEquals( "tools/build.sh", change.repoPath() );
    }

    // ---- empty results ---------------------------------------------------

    @Test
    public void aCleanWorkingTreeIsAResult()
    {
        GitStatusResponse response = GitStatusResponse.of( "P", "main", null, null, null,
                List.of(), List.of(), List.of(), List.of() );

        assertTrue( response.clean() );
        assertEquals( 0, response.totalChanges() );
        assertTrue( response.staged().isEmpty() );
        assertTrue( response.unstaged().isEmpty() );
        assertTrue( response.untracked().isEmpty() );
        assertTrue( response.conflicting().isEmpty() );
    }

    @Test
    public void anEmptyStashIsACountNotASentence()
    {
        GitStashListResponse response = GitStashListResponse.of( "P", List.of() );

        assertEquals( 0, response.totalStashes() );
        assertTrue( response.stashes().isEmpty() );
    }

    @Test
    public void anEmptyLogIsACount()
    {
        GitLogResponse response = GitLogResponse.of( "P", "main", List.of(), false );

        assertEquals( 0, response.commitCount() );
        assertFalse( response.truncated() );
    }

    // ---- against a repository --------------------------------------------

    @Test
    public void statusNamesTheProjectAndProjectRelativePathOfAModifiedFile() throws Exception
    {
        Files.writeString( new File( repoDir, "src/Hello.java" ).toPath(), "public class Hello { int x; }\n",
                StandardCharsets.UTF_8 );
        project.refreshLocal( IResource.DEPTH_INFINITE, monitor );

        GitStatusResponse status = service.getStatus( TEST_PROJECT_NAME );

        GitFileChange change = findByPath( status.unstaged(), "src/Hello.java" );
        assertEquals( TEST_PROJECT_NAME, change.projectName() );
        assertEquals( "src/Hello.java", change.repoPath() );
        assertEquals( ChangeType.MODIFIED, change.changeType() );
        assertFalse( status.clean() );
        assertEquals( status.totalChanges(), status.staged().size() + status.unstaged().size()
                + status.untracked().size() + status.conflicting().size() );
        assertEquals( "master", status.branch() );
        assertEquals( TEST_PROJECT_NAME, status.projectName() );
    }

    @Test
    public void statusSeparatesUntrackedFromStaged() throws Exception
    {
        Files.writeString( new File( repoDir, "notes.txt" ).toPath(), "note\n", StandardCharsets.UTF_8 );
        project.refreshLocal( IResource.DEPTH_INFINITE, monitor );

        GitStatusResponse untrackedStatus = service.getStatus( TEST_PROJECT_NAME );
        GitFileChange untracked = findByPath( untrackedStatus.untracked(), "notes.txt" );
        assertEquals( ChangeType.UNTRACKED, untracked.changeType() );
        assertEquals( TEST_PROJECT_NAME, untracked.projectName() );
        assertTrue( untrackedStatus.staged().isEmpty() );

        service.addFiles( TEST_PROJECT_NAME, "notes.txt" );

        GitStatusResponse stagedStatus = service.getStatus( TEST_PROJECT_NAME );
        GitFileChange staged = findByPath( stagedStatus.staged(), "notes.txt" );
        assertEquals( ChangeType.ADDED, staged.changeType() );
        assertTrue( stagedStatus.untracked().stream().noneMatch( change -> "notes.txt".equals( change.filePath() ) ),
                "a staged file is no longer untracked" );
    }

    @Test
    public void statusReportsACleanTreeWithoutProse() throws Exception
    {
        commitEverything( "Commit everything" );
        project.refreshLocal( IResource.DEPTH_INFINITE, monitor );

        GitStatusResponse status = service.getStatus( TEST_PROJECT_NAME );

        assertTrue( status.clean(), "unexpected changes: " + status.staged() + status.unstaged() + status.untracked() );
        assertEquals( 0, status.totalChanges() );
    }

    @Test
    public void statusReportsNoUpstreamAsNullRatherThanZero()
    {
        GitStatusResponse status = service.getStatus( TEST_PROJECT_NAME );

        // The repository has no remote, so "level with upstream" would be a lie.
        assertNull( status.upstreamBranch() );
        assertNull( status.aheadCount() );
        assertNull( status.behindCount() );
    }

    @Test
    public void logCarriesCommitIdentityAndTimestamp()
    {
        GitLogResponse log = service.getLog( TEST_PROJECT_NAME, 20 );

        assertEquals( 1, log.commitCount() );
        assertFalse( log.truncated() );

        GitLogResponse.GitCommit commit = log.commits().get( 0 );
        assertEquals( 40, commit.sha().length() );
        assertEquals( 7, commit.shortSha().length() );
        assertTrue( commit.sha().startsWith( commit.shortSha() ) );
        assertEquals( "AssistAI Tests", commit.author() );
        assertEquals( "assistai-tests@example.invalid", commit.authorEmail() );
        assertEquals( "Initial commit", commit.shortMessage() );
        assertEquals( "Initial commit", commit.message() );
        assertTrue( commit.authorTimeMillis() > 0, "epoch milliseconds, so a client can compare commits" );
    }

    @Test
    public void logFlagsThatHistoryGoesFurtherBack() throws Exception
    {
        Files.writeString( new File( repoDir, "second.txt" ).toPath(), "second\n", StandardCharsets.UTF_8 );
        commitEverything( "Second commit" );
        Files.writeString( new File( repoDir, "third.txt" ).toPath(), "third\n", StandardCharsets.UTF_8 );
        commitEverything( "Third commit" );

        GitLogResponse limited = service.getLog( TEST_PROJECT_NAME, 2 );
        assertEquals( 2, limited.commitCount() );
        assertEquals( 2, limited.commits().size() );
        assertTrue( limited.truncated() );
        assertEquals( "Third commit", limited.commits().get( 0 ).shortMessage(), "most recent first" );

        GitLogResponse all = service.getLog( TEST_PROJECT_NAME, 20 );
        assertEquals( 3, all.commitCount() );
        assertFalse( all.truncated() );
    }

    @Test
    public void branchListMarksTheCheckedOutBranch()
    {
        service.createBranch( TEST_PROJECT_NAME, "feature-x", null );

        GitBranchResponse branches = service.listBranches( TEST_PROJECT_NAME, false );

        assertEquals( "master", branches.currentBranch() );
        assertEquals( 2, branches.totalBranches() );
        assertTrue( branches.remoteBranches().isEmpty() );

        GitBranchResponse.GitBranch master = branches.branches().stream()
                .filter( branch -> "master".equals( branch.name() ) ).findFirst().orElseThrow();
        GitBranchResponse.GitBranch feature = branches.branches().stream()
                .filter( branch -> "feature-x".equals( branch.name() ) ).findFirst().orElseThrow();

        assertTrue( master.current() );
        assertFalse( feature.current() );
        assertEquals( "refs/heads/feature-x", feature.fullName() );
        assertEquals( 40, feature.sha().length() );
    }

    @Test
    public void stashListIndexesEntriesFromTheMostRecent() throws Exception
    {
        assertEquals( 0, service.stashList( TEST_PROJECT_NAME ).totalStashes() );

        Files.writeString( new File( repoDir, "src/Hello.java" ).toPath(), "public class Hello { int stashed; }\n",
                StandardCharsets.UTF_8 );
        project.refreshLocal( IResource.DEPTH_INFINITE, monitor );
        service.stash( TEST_PROJECT_NAME, "a stashed change" );

        GitStashListResponse stashes = service.stashList( TEST_PROJECT_NAME );

        assertEquals( 1, stashes.totalStashes() );
        GitStashListResponse.GitStash entry = stashes.stashes().get( 0 );
        assertEquals( 0, entry.index() );
        assertEquals( "stash@{0}", entry.ref() );
        assertEquals( 40, entry.sha().length() );
        assertTrue( entry.message().contains( "a stashed change" ), entry.message() );
    }
}
