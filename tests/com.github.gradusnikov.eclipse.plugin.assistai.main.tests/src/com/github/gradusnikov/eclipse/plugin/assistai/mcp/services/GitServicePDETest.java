package com.github.gradusnikov.eclipse.plugin.assistai.mcp.services;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IProjectDescription;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IWorkspace;
import org.eclipse.core.resources.IWorkspaceRoot;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.ILog;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.e4.core.contexts.ContextInjectionFactory;
import org.eclipse.e4.core.contexts.EclipseContextFactory;
import org.eclipse.e4.core.contexts.IEclipseContext;
import org.eclipse.egit.core.op.ConnectProviderOperation;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.dircache.DirCache;
import org.eclipse.jgit.dircache.DirCacheEntry;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectLoader;
import org.eclipse.jgit.lib.Repository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.osgi.framework.BundleContext;
import org.osgi.framework.FrameworkUtil;
import org.osgi.util.tracker.ServiceTracker;

import org.eclipse.e4.ui.di.UISynchronize;

import com.github.gradusnikov.eclipse.assistai.Activator;
import com.github.gradusnikov.eclipse.assistai.mcp.results.DiagnosticCode;
import com.github.gradusnikov.eclipse.assistai.mcp.results.GitBranchResponse;
import com.github.gradusnikov.eclipse.assistai.mcp.results.GitCheckoutResponse;
import com.github.gradusnikov.eclipse.assistai.mcp.results.GitCheckoutResponse.CheckoutStatus;
import com.github.gradusnikov.eclipse.assistai.mcp.results.GitCommitResponse;
import com.github.gradusnikov.eclipse.assistai.mcp.results.GitDeleteBranchResponse;
import com.github.gradusnikov.eclipse.assistai.mcp.results.GitDiffResponse;
import com.github.gradusnikov.eclipse.assistai.mcp.results.GitDiffResponse.FileChangeType;
import com.github.gradusnikov.eclipse.assistai.mcp.results.GitDiffResponse.GitFileDiff;
import com.github.gradusnikov.eclipse.assistai.mcp.results.GitLogResponse;
import com.github.gradusnikov.eclipse.assistai.mcp.results.GitResponsesPDETest;
import com.github.gradusnikov.eclipse.assistai.mcp.results.GitStagePatchResponse;
import com.github.gradusnikov.eclipse.assistai.mcp.results.GitStagePatchResponse.PatchStatus;
import com.github.gradusnikov.eclipse.assistai.mcp.results.GitStageResponse;
import com.github.gradusnikov.eclipse.assistai.mcp.results.GitStageResponse.StageOperation;
import com.github.gradusnikov.eclipse.assistai.mcp.results.GitStashListResponse;
import com.github.gradusnikov.eclipse.assistai.mcp.results.GitStashPopResponse;
import com.github.gradusnikov.eclipse.assistai.mcp.results.GitStashPopResponse.PopStatus;
import com.github.gradusnikov.eclipse.assistai.mcp.results.GitStashResponse;
import com.github.gradusnikov.eclipse.assistai.mcp.results.GitStatusResponse;
import com.github.gradusnikov.eclipse.assistai.mcp.results.GitStatusResponse.ChangeType;
import com.github.gradusnikov.eclipse.assistai.mcp.services.EditorService;
import com.github.gradusnikov.eclipse.assistai.mcp.services.GitService;
import com.github.gradusnikov.eclipse.assistai.tools.UISynchronizeCallable;

public class GitServicePDETest
{
    private static final String TEST_PROJECT_NAME = "GitServiceTestProject";
    private static final NullProgressMonitor monitor = new NullProgressMonitor();

    private IProject project;
    private Git git;
    private GitService service;
    private File repoDir;

    @BeforeEach
    public void beforeEach() throws Exception
    {
        BundleContext bundleContext = FrameworkUtil.getBundle(GitServicePDETest.class).getBundleContext();
        ServiceTracker<IWorkspace, IWorkspace> workspaceTracker = new ServiceTracker<>(bundleContext, IWorkspace.class, null);
        workspaceTracker.open();
        IWorkspace workspace = workspaceTracker.getService();
        IWorkspaceRoot root = workspace.getRoot();

        project = root.getProject(TEST_PROJECT_NAME);
        if (project.exists())
        {
            project.delete(true, true, monitor);
        }

        IProjectDescription desc = workspace.newProjectDescription(TEST_PROJECT_NAME);
        project.create(desc, monitor);
        project.open(monitor);

        repoDir = project.getLocation().toFile();
        git = Git.init().setDirectory(repoDir).setInitialBranch("master").call();
        git.getRepository().updateRef(Constants.HEAD).link(Constants.R_HEADS + "master");

        File srcFile = new File(repoDir, "src/Hello.java");
        srcFile.getParentFile().mkdirs();
        Files.writeString(srcFile.toPath(),
            "package src;\n\npublic class Hello {\n    public void greet() {\n        System.out.println(\"Hello\");\n    }\n\n    public void farewell() {\n        System.out.println(\"Goodbye\");\n    }\n}\n",
            StandardCharsets.UTF_8);

        File readmeFile = new File(repoDir, "README.md");
        Files.writeString(readmeFile.toPath(), "# Test Project\n", StandardCharsets.UTF_8);

        git.add().addFilepattern(".").call();
        git.commit()
                .setMessage("Initial commit")
                .setAuthor("AssistAI Tests", "assistai-tests@example.invalid")
                .setCommitter("AssistAI Tests", "assistai-tests@example.invalid")
                .call();

        ConnectProviderOperation connectOp = new ConnectProviderOperation(project, git.getRepository().getDirectory());
        connectOp.execute(monitor);

        project.refreshLocal(IResource.DEPTH_INFINITE, monitor);

        IEclipseContext context = EclipseContextFactory.create();
        context.set(ILog.class, Activator.getDefault().getLog());
        context.set(UISynchronize.class, new UISynchronize()
        {
            @Override
            public void syncExec(Runnable runnable) { runnable.run(); }
            @Override
            public void asyncExec(Runnable runnable) { runnable.run(); }
            @Override
            protected boolean dispatchEvents() { return true; }
            @Override
            protected void showBusyWhile(Runnable runnable) { runnable.run(); }
            @Override
            protected boolean isUIThread(Thread thread) { return true; }
        });
        context.set(UISynchronizeCallable.class, ContextInjectionFactory.make(UISynchronizeCallable.class, context));
        context.set(EditorService.class, ContextInjectionFactory.make(EditorService.class, context));
        service = ContextInjectionFactory.make(GitService.class, context);
    }

    @AfterEach
    public void afterEach() throws Exception
    {
        if (git != null)
        {
            git.close();
        }
        if (project != null && project.exists())
        {
            // Let whatever EGit/resource-change jobs the unmap or earlier operations
            // queued actually finish before the project disappears underneath them.
        	GitResponsesPDETest.waitForPendingJobsAndUnmapToAvoidExceptions(project);

        	for (int attempt = 0; attempt < 5; attempt++)
            {
                try
                {
                    project.delete(true, true, monitor);
                    break;
                }
                catch (Exception e)
                {
                    if (attempt == 4) throw e;
                    Thread.sleep(500);
                }
            }
        }
    }

    @Test
    public void testStagePatch_stagesOnlyPatchedHunk() throws Exception
    {
        File srcFile = new File(repoDir, "src/Hello.java");
        Files.writeString(srcFile.toPath(),
            "package src;\n\npublic class Hello {\n    public void greet() {\n        System.out.println(\"Hi there\");\n    }\n\n    public void farewell() {\n        System.out.println(\"See ya\");\n    }\n}\n",
            StandardCharsets.UTF_8);

        project.refreshLocal(IResource.DEPTH_INFINITE, monitor);

        String patch = "--- a/src/Hello.java\n"
            + "+++ b/src/Hello.java\n"
            + "@@ -2,7 +2,7 @@\n"
            + " \n"
            + " public class Hello {\n"
            + "     public void greet() {\n"
            + "-        System.out.println(\"Hello\");\n"
            + "+        System.out.println(\"Hi there\");\n"
            + "     }\n"
            + " \n"
            + "     public void farewell() {\n";

        GitStagePatchResponse result = service.stagePatch(TEST_PROJECT_NAME, patch);
        assertEquals(GitStagePatchResponse.PatchStatus.STAGED, result.status(), result.summaryText());
        assertTrue(result.workingTreePreserved(), "the caller's uncommitted work must survive");

        Repository repository = git.getRepository();
        DirCache dirCache = repository.readDirCache();
        DirCacheEntry entry = dirCache.getEntry("src/Hello.java");
        assertNotNull(entry, "src/Hello.java should be in the index");

        ObjectLoader loader = repository.open(entry.getObjectId());
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        loader.copyTo(baos);
        String stagedContent = baos.toString(StandardCharsets.UTF_8);

        assertTrue(stagedContent.contains("Hi there"), "Staged content should contain the patched hunk");
        assertTrue(stagedContent.contains("\"Goodbye\""), "Staged content should NOT contain the second change (farewell should still say Goodbye)");

        String workingTreeContent = Files.readString(srcFile.toPath(), StandardCharsets.UTF_8);
        assertTrue(workingTreeContent.contains("See ya"), "Working tree should still have both changes");
    }

    @Test
    public void testStagePatch_multipleFiles() throws Exception
    {
        File srcFile = new File(repoDir, "src/Hello.java");
        Files.writeString(srcFile.toPath(),
            "package src;\n\npublic class Hello {\n    public void greet() {\n        System.out.println(\"Hi\");\n    }\n\n    public void farewell() {\n        System.out.println(\"Goodbye\");\n    }\n}\n",
            StandardCharsets.UTF_8);

        File readmeFile = new File(repoDir, "README.md");
        Files.writeString(readmeFile.toPath(), "# Test Project\n\nUpdated readme.\n", StandardCharsets.UTF_8);

        project.refreshLocal(IResource.DEPTH_INFINITE, monitor);

        String patch = "--- a/README.md\n"
            + "+++ b/README.md\n"
            + "@@ -1 +1,3 @@\n"
            + " # Test Project\n"
            + "+\n"
            + "+Updated readme.\n";

        GitStagePatchResponse result = service.stagePatch(TEST_PROJECT_NAME, patch);
        assertEquals(GitStagePatchResponse.PatchStatus.STAGED, result.status(), result.summaryText());
        assertTrue(result.workingTreePreserved(), "the caller's uncommitted work must survive");

        Repository repository = git.getRepository();
        DirCache dirCache = repository.readDirCache();

        DirCacheEntry readmeEntry = dirCache.getEntry("README.md");
        assertNotNull(readmeEntry);
        ObjectLoader loader = repository.open(readmeEntry.getObjectId());
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        loader.copyTo(baos);
        String stagedReadme = baos.toString(StandardCharsets.UTF_8);
        assertTrue(stagedReadme.contains("Updated readme"), "README should be staged with new content");

        DirCacheEntry helloEntry = dirCache.getEntry("src/Hello.java");
        assertNotNull(helloEntry);
        ObjectLoader helloLoader = repository.open(helloEntry.getObjectId());
        ByteArrayOutputStream helloBaos = new ByteArrayOutputStream();
        helloLoader.copyTo(helloBaos);
        String stagedHello = helloBaos.toString(StandardCharsets.UTF_8);
        assertTrue(stagedHello.contains("\"Hello\""), "Hello.java should NOT be staged (still original content)");
    }

    @Test
    public void testStagePatch_invalidPatch_returnsNoFiles()
    {
        GitStagePatchResponse result = service.stagePatch(TEST_PROJECT_NAME, "this is not a valid patch");
        // A patch that parses to no file headers staged nothing, so it is a failure -
        // reporting it as success meant the next commit was empty or wrong.
        assertEquals(GitStagePatchResponse.PatchStatus.FAILED, result.status());
        assertEquals(0, result.totalFiles());
        assertTrue(result.workingTreePreserved(), "nothing was touched, so nothing was lost");
        assertFalse(result.diagnostics().isEmpty());
    }

    @Test
    public void testGetStatus_showsModifiedFiles() throws Exception
    {
        File srcFile = new File(repoDir, "src/Hello.java");
        Files.writeString(srcFile.toPath(),
            "package src;\n\npublic class Hello {\n    public void greet() {\n        System.out.println(\"Modified\");\n    }\n}\n",
            StandardCharsets.UTF_8);

        project.refreshLocal(IResource.DEPTH_INFINITE, monitor);

        GitStatusResponse status = service.getStatus(TEST_PROJECT_NAME);
        assertFalse(status.clean());
        assertTrue(status.unstaged().stream().anyMatch(change -> "src/Hello.java".equals(change.filePath())),
                "Status should show the modified file: " + status.unstaged());
    }

    @Test
    public void testGetLog_showsCommitHistory() throws Exception
    {
        GitLogResponse log = service.getLog(TEST_PROJECT_NAME, 10);
        assertTrue(log.commits().stream().anyMatch(commit -> "Initial commit".equals(commit.shortMessage())),
                "Log should contain the initial commit");
    }

    @Test
    public void testGetDiff_noChanges() throws Exception
    {
        // A clean tree and a diff that could not be produced both rendered as no hunks,
        // which is why this used to assert "empty OR not an error" and prove nothing.
        GitDiffResponse diff = service.getDiff(TEST_PROJECT_NAME, false, null, false);
        assertTrue(diff.identical(), diff.unifiedDiff());
        assertEquals(0, diff.addedLines());
        assertEquals(0, diff.removedLines());
    }

    @Test
    public void testGetDiff_staged() throws Exception
    {
        File srcFile = new File(repoDir, "src/Hello.java");
        Files.writeString(srcFile.toPath(),
            "package src;\n\npublic class Hello {\n    public void greet() {\n        System.out.println(\"Staged change\");\n    }\n\n    public void farewell() {\n        System.out.println(\"Goodbye\");\n    }\n}\n",
            StandardCharsets.UTF_8);

        project.refreshLocal(IResource.DEPTH_INFINITE, monitor);
        service.addFiles(TEST_PROJECT_NAME, ".");

        GitDiffResponse diff = service.getDiff(TEST_PROJECT_NAME, true, null, false);
        assertFalse(diff.identical());
        assertTrue(diff.unifiedDiff().contains("Staged change"), "Staged diff should show the staged change");
        assertTrue(diff.addedLines() > 0, "the counts come from the EditList, not from the rendering");
    }

    @Test
    public void testGetDiff_filtersProjectRelativePaths() throws Exception
    {
        Files.writeString(new File(repoDir, "README.md").toPath(), "# Changed readme\n", StandardCharsets.UTF_8);
        Files.writeString(new File(repoDir, "src/Hello.java").toPath(),
                "package src;\npublic class Hello { String value = \"changed source\"; }\n", StandardCharsets.UTF_8);
        project.refreshLocal(IResource.DEPTH_INFINITE, monitor);

        GitDiffResponse diff = service.getDiff(TEST_PROJECT_NAME, false, "README.md", false);

        assertTrue(diff.unifiedDiff().contains("Changed readme"));
        assertFalse(diff.unifiedDiff().contains("changed source"));
        assertFalse(diff.unifiedDiff().contains("src/Hello.java"));
    }

    @Test
    public void testGetDiff_rejectsPathTraversal()
    {
        assertThrows(IllegalArgumentException.class,
                () -> service.getDiff(TEST_PROJECT_NAME, false, "../outside.txt", false));
    }

    @Test
    public void testGetDiff_canIgnoreWhitespace() throws Exception
    {
        Files.writeString(new File(repoDir, "README.md").toPath(), "#   Test   Project\n", StandardCharsets.UTF_8);
        project.refreshLocal(IResource.DEPTH_INFINITE, monitor);

        GitDiffResponse diff = service.getDiff(TEST_PROJECT_NAME, false, "README.md", true);

        assertFalse(diff.unifiedDiff().contains("-# Test Project"));
        assertFalse(diff.unifiedDiff().contains("+#   Test   Project"));
    }

    @Test
    public void testListBranches() throws Exception
    {
        GitBranchResponse branches = service.listBranches(TEST_PROJECT_NAME, false);
        assertTrue(branches.branches().stream().anyMatch(branch -> branch.current()), "Should flag the checked-out branch");
        assertEquals("master", branches.currentBranch());
    }

    @Test
    public void testListBranches_includeRemote() throws Exception
    {
        GitBranchResponse branches = service.listBranches(TEST_PROJECT_NAME, true);
        assertNotNull(branches);
        assertTrue(branches.remoteBranches().isEmpty(), "The test repository has no remotes");
    }

    @Test
    public void testCreateAndDeleteBranch() throws Exception
    {
        String createResult = service.createBranch(TEST_PROJECT_NAME, "feature-test", null);
        assertTrue(createResult.contains("feature-test"), "Should confirm branch creation");

        GitBranchResponse branches = service.listBranches(TEST_PROJECT_NAME, false);
        assertTrue(branches.branches().stream().anyMatch(branch -> "feature-test".equals(branch.name())),
                "New branch should appear in list");

        GitDeleteBranchResponse deleteResult = service.deleteBranch(TEST_PROJECT_NAME, "feature-test", false);
        // Fully qualified, as JGit returns it: "refs/heads/x" and "refs/remotes/origin/x"
        // are different refs and a bare name cannot tell them apart.
        assertEquals(List.of("refs/heads/feature-test"), deleteResult.deletedRefs());
    }

    @Test
    public void testCreateBranch_withStartPoint() throws Exception
    {
        String createResult = service.createBranch(TEST_PROJECT_NAME, "from-head", "HEAD");
        assertTrue(createResult.contains("from-head"), "Should confirm branch creation from HEAD");
        service.deleteBranch(TEST_PROJECT_NAME, "from-head", false);
    }

    @Test
    public void testCheckoutBranch() throws Exception
    {
        service.createBranch(TEST_PROJECT_NAME, "checkout-test", null);
        GitCheckoutResponse result = service.checkoutBranch(TEST_PROJECT_NAME, "checkout-test");
        assertEquals(GitCheckoutResponse.CheckoutStatus.SWITCHED, result.status(), result.summaryText());
        assertEquals("checkout-test", result.currentBranch());
        assertTrue(result.blockingFiles().isEmpty(), "a clean checkout is blocked by nothing");

        GitStatusResponse status = service.getStatus(TEST_PROJECT_NAME);
        assertEquals("checkout-test", status.branch(), "Status should report the new branch");

        service.checkoutBranch(TEST_PROJECT_NAME, "master");
        service.deleteBranch(TEST_PROJECT_NAME, "checkout-test", false);
    }

    @Test
    public void testResetFiles() throws Exception
    {
        File newFile = new File(repoDir, "resettest.txt");
        Files.writeString(newFile.toPath(), "reset test content\n", StandardCharsets.UTF_8);

        project.refreshLocal(IResource.DEPTH_INFINITE, monitor);
        service.addFiles(TEST_PROJECT_NAME, "resettest.txt");

        GitDiffResponse stagedDiff = service.getDiff(TEST_PROJECT_NAME, true, null, false);
        assertTrue(stagedDiff.unifiedDiff().contains("reset test content"), "File should be staged");

        GitStageResponse resetResult = service.resetFiles(TEST_PROJECT_NAME, "resettest.txt");
        assertEquals(GitStageResponse.StageOperation.UNSTAGE, resetResult.operation());
        assertEquals(1, resetResult.totalFiles(), resetResult.summaryText());

        GitDiffResponse afterReset = service.getDiff(TEST_PROJECT_NAME, true, null, false);
        assertFalse(afterReset.unifiedDiff().contains("reset test content"), "Staged changes should be gone after reset");
    }

    @Test
    public void testStashAndPop() throws Exception
    {
        File srcFile = new File(repoDir, "src/Hello.java");
        Files.writeString(srcFile.toPath(),
            "package src;\n\npublic class Hello {\n    // stash test\n}\n",
            StandardCharsets.UTF_8);

        project.refreshLocal(IResource.DEPTH_INFINITE, monitor);

        GitStashResponse stashResult = service.stash(TEST_PROJECT_NAME, "test stash");
        assertTrue(stashResult.stashed(), stashResult.summaryText());
        assertNotNull(stashResult.stash().sha(),
                "the stash commit SHA is data, as gitStashList already reports it");

        GitStashListResponse stashListResult = service.stashList(TEST_PROJECT_NAME);
        assertEquals(1, stashListResult.totalStashes());
        assertTrue(stashListResult.stashes().get(0).message().contains("test stash"),
                "Stash list should contain our stash message");

        GitStashPopResponse popResult = service.stashPop(TEST_PROJECT_NAME);
        assertEquals(GitStashPopResponse.PopStatus.APPLIED, popResult.status(), popResult.summaryText());
        assertTrue(popResult.dropped(), "a clean pop drops the stash; a conflicted one retains it");

        String content = Files.readString(srcFile.toPath(), StandardCharsets.UTF_8);
        assertTrue(content.contains("stash test"), "Working tree should have stashed changes back");
    }

    @Test
    public void testStashList_empty() throws Exception
    {
        GitStashListResponse result = service.stashList(TEST_PROJECT_NAME);
        assertEquals(0, result.totalStashes(), "An empty stash is a count, not a sentence");
        assertTrue(result.stashes().isEmpty());
    }

    @Test
    public void testReadFileAtRevision_readsHeadInsteadOfWorkingTree() throws Exception
    {
        Files.writeString(new File(repoDir, "README.md").toPath(), "# Working tree\n", StandardCharsets.UTF_8);

        assertEquals("# Test Project\n", service.readFileAtRevision(TEST_PROJECT_NAME, "README.md", "HEAD"));
    }

    @Test
    public void testReadFileAtRevision_readsIndex() throws Exception
    {
        Files.writeString(new File(repoDir, "README.md").toPath(), "# Staged version\n", StandardCharsets.UTF_8);
        git.add().addFilepattern("README.md").call();
        Files.writeString(new File(repoDir, "README.md").toPath(), "# Working tree version\n", StandardCharsets.UTF_8);

        assertEquals("# Staged version\n", service.readFileAtRevision(TEST_PROJECT_NAME, "README.md", "INDEX"));
    }

    @Test
    public void testReadFileAtRevision_rejectsPathTraversal()
    {
        assertThrows(IllegalArgumentException.class,
                () -> service.readFileAtRevision(TEST_PROJECT_NAME, "../outside.txt", "HEAD"));
    }

    @Test
    public void testGetRepository_nonExistentProject()
    {
        assertThrows(RuntimeException.class, () -> {
            service.getStatus("NonExistentProject12345");
        });
    }

    @Test
    public void testAddAndCommit() throws Exception
    {
        File newFile = new File(repoDir, "newfile.txt");
        Files.writeString(newFile.toPath(), "new content\n", StandardCharsets.UTF_8);

        project.refreshLocal(IResource.DEPTH_INFINITE, monitor);

        GitStageResponse addResult = service.addFiles(TEST_PROJECT_NAME, "newfile.txt");
        // JGit's add succeeds against a pathspec matching nothing, so the count is the
        // only thing that distinguishes a real stage from a typo in the path.
        assertEquals(1, addResult.totalFiles(), addResult.summaryText());

        GitCommitResponse commitResult = service.commit(TEST_PROJECT_NAME, "Add new file");
        assertNotNull(commitResult.commit().sha(), "the SHA is the handle for every next action");
        assertEquals("Add new file", commitResult.commit().shortMessage());

    }
}
