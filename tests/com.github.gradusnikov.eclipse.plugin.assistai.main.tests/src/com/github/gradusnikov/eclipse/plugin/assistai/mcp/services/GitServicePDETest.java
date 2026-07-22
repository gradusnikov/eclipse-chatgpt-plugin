package com.github.gradusnikov.eclipse.plugin.assistai.mcp.services;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

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
        git = Git.init().setDirectory(repoDir).call();

        File srcFile = new File(repoDir, "src/Hello.java");
        srcFile.getParentFile().mkdirs();
        Files.writeString(srcFile.toPath(),
            "package src;\n\npublic class Hello {\n    public void greet() {\n        System.out.println(\"Hello\");\n    }\n\n    public void farewell() {\n        System.out.println(\"Goodbye\");\n    }\n}\n",
            StandardCharsets.UTF_8);

        File readmeFile = new File(repoDir, "README.md");
        Files.writeString(readmeFile.toPath(), "# Test Project\n", StandardCharsets.UTF_8);

        git.add().addFilepattern(".").call();
        git.commit().setMessage("Initial commit").call();

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

        String result = service.stagePatch(TEST_PROJECT_NAME, patch);
        assertTrue(result.contains("staged successfully"), "Expected success message, got: " + result);

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

        String result = service.stagePatch(TEST_PROJECT_NAME, patch);
        assertTrue(result.contains("staged successfully"), "Expected success message, got: " + result);

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
        String result = service.stagePatch(TEST_PROJECT_NAME, "this is not a valid patch");
        assertTrue(result.contains("No files"), "Expected no files message, got: " + result);
    }

    @Test
    public void testGetStatus_showsModifiedFiles() throws Exception
    {
        File srcFile = new File(repoDir, "src/Hello.java");
        Files.writeString(srcFile.toPath(),
            "package src;\n\npublic class Hello {\n    public void greet() {\n        System.out.println(\"Modified\");\n    }\n}\n",
            StandardCharsets.UTF_8);

        project.refreshLocal(IResource.DEPTH_INFINITE, monitor);

        String status = service.getStatus(TEST_PROJECT_NAME);
        assertTrue(status.contains("Hello.java"), "Status should show modified file");
    }

    @Test
    public void testGetLog_showsCommitHistory() throws Exception
    {
        String log = service.getLog(TEST_PROJECT_NAME, 10);
        assertTrue(log.contains("Initial commit"), "Log should contain initial commit message");
    }

    @Test
    public void testGetDiff_noChanges() throws Exception
    {
        String diff = service.getDiff(TEST_PROJECT_NAME, false);
        assertTrue(diff.isEmpty() || !diff.contains("Error"), "Clean repo should have empty or no-error diff");
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

        String diff = service.getDiff(TEST_PROJECT_NAME, true);
        assertTrue(diff.contains("Staged change"), "Staged diff should show the staged change");
    }

    @Test
    public void testListBranches() throws Exception
    {
        String branches = service.listBranches(TEST_PROJECT_NAME, false);
        assertTrue(branches.contains("master") || branches.contains("main"), "Should list the default branch");
    }

    @Test
    public void testListBranches_includeRemote() throws Exception
    {
        String branches = service.listBranches(TEST_PROJECT_NAME, true);
        assertNotNull(branches);
    }

    @Test
    public void testCreateAndDeleteBranch() throws Exception
    {
        String createResult = service.createBranch(TEST_PROJECT_NAME, "feature-test", null);
        assertTrue(createResult.contains("feature-test"), "Should confirm branch creation");

        String branches = service.listBranches(TEST_PROJECT_NAME, false);
        assertTrue(branches.contains("feature-test"), "New branch should appear in list");

        String deleteResult = service.deleteBranch(TEST_PROJECT_NAME, "feature-test", false);
        assertTrue(deleteResult.contains("feature-test"), "Should confirm branch deletion");
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
        String result = service.checkoutBranch(TEST_PROJECT_NAME, "checkout-test");
        assertTrue(result.contains("checkout-test"), "Should confirm checkout");

        String status = service.getStatus(TEST_PROJECT_NAME);
        assertTrue(status.contains("checkout-test"), "Status should show new branch");

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

        String stagedDiff = service.getDiff(TEST_PROJECT_NAME, true);
        assertTrue(stagedDiff.contains("reset test content"), "File should be staged");

        String resetResult = service.resetFiles(TEST_PROJECT_NAME, "resettest.txt");
        assertTrue(resetResult.contains("Unstaged"), "Should confirm reset, got: " + resetResult);

        String afterReset = service.getDiff(TEST_PROJECT_NAME, true);
        assertFalse(afterReset.contains("reset test content"), "Staged changes should be gone after reset");
    }

    @Test
    public void testStashAndPop() throws Exception
    {
        File srcFile = new File(repoDir, "src/Hello.java");
        Files.writeString(srcFile.toPath(),
            "package src;\n\npublic class Hello {\n    // stash test\n}\n",
            StandardCharsets.UTF_8);

        project.refreshLocal(IResource.DEPTH_INFINITE, monitor);

        String stashResult = service.stash(TEST_PROJECT_NAME, "test stash");
        assertTrue(stashResult.contains("Stash") || stashResult.contains("stash"), "Should confirm stash: " + stashResult);

        String stashListResult = service.stashList(TEST_PROJECT_NAME);
        assertTrue(stashListResult.contains("test stash"), "Stash list should contain our stash message");

        String popResult = service.stashPop(TEST_PROJECT_NAME);
        assertTrue(popResult.contains("Stash") || popResult.contains("stash") || popResult.contains("applied"), "Should confirm pop: " + popResult);

        String content = Files.readString(srcFile.toPath(), StandardCharsets.UTF_8);
        assertTrue(content.contains("stash test"), "Working tree should have stashed changes back");
    }

    @Test
    public void testStashList_empty() throws Exception
    {
        String result = service.stashList(TEST_PROJECT_NAME);
        assertTrue(result.contains("No stash") || result.isEmpty() || result.contains("stash"), "Should handle empty stash list");
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

        String addResult = service.addFiles(TEST_PROJECT_NAME, "newfile.txt");
        assertTrue(addResult.contains("Added"), "Should confirm file was added");

        String commitResult = service.commit(TEST_PROJECT_NAME, "Add new file");
        assertTrue(commitResult.contains("Committed"), "Should confirm commit");
        assertTrue(commitResult.contains("Add new file"), "Should contain commit message");
    }
}
