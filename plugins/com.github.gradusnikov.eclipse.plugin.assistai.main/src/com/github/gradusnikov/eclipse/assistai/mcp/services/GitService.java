package com.github.gradusnikov.eclipse.assistai.mcp.services;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ByteArrayInputStream;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import java.nio.file.Path;

import org.eclipse.jgit.lib.ObjectLoader;
import org.eclipse.jgit.treewalk.TreeWalk;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.ILog;
import org.eclipse.e4.core.di.annotations.Creatable;
import org.eclipse.egit.core.project.RepositoryMapping;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.ListBranchCommand;
import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.diff.DiffFormatter;
import org.eclipse.jgit.diff.RawTextComparator;
import org.eclipse.jgit.lib.BranchTrackingStatus;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectInserter;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.dircache.DirCache;
import org.eclipse.jgit.dircache.DirCacheEntry;
import org.eclipse.jgit.dircache.DirCacheEditor;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.treewalk.AbstractTreeIterator;
import org.eclipse.jgit.treewalk.CanonicalTreeParser;
import org.eclipse.ui.IEditorPart;

import com.github.gradusnikov.eclipse.assistai.tools.UISynchronizeCallable;

import jakarta.inject.Inject;

@Creatable
@SuppressWarnings("restriction")
public class GitService
{
    private static final ConcurrentHashMap<String, ReentrantLock> repoLocks = new ConcurrentHashMap<>();

    @Inject
    private ILog logger;

    @Inject
    public UISynchronizeCallable uiSync;

    @Inject
    public EditorService editorService;

    private ReentrantLock getRepositoryLock(Repository repository)
    {
        return repoLocks.computeIfAbsent(repository.getDirectory().getAbsolutePath(), k -> new ReentrantLock());
    }

    private Repository getRepository(String projectName)
    {
        IProject project = ResourcesPlugin.getWorkspace().getRoot().getProject(projectName);
        if (!project.exists())
        {
            throw new RuntimeException("Project not found: " + projectName);
        }
        RepositoryMapping mapping = RepositoryMapping.getMapping(project);
        if (mapping == null)
        {
            throw new RuntimeException("Project is not mapped to a Git repository: " + projectName);
        }
        return mapping.getRepository();
    }

    private void refreshProject(String projectName)
    {
        try
        {
            IProject project = ResourcesPlugin.getWorkspace().getRoot().getProject(projectName);
            project.refreshLocal(IResource.DEPTH_INFINITE, null);
        }
        catch (CoreException e)
        {
            logger.error("Failed to refresh project: " + projectName, e);
        }
    }

    public String getStatus(String projectName)
    {
        Repository repository = getRepository(projectName);
        try (Git git = new Git(repository))
        {
            var status = git.status().call();
            var sb = new StringBuilder();

            String branch = repository.getBranch();
            sb.append("On branch ").append(branch).append("\n");

            try
            {
                BranchTrackingStatus tracking = BranchTrackingStatus.of(repository, branch);
                if (tracking != null)
                {
                    int ahead = tracking.getAheadCount();
                    int behind = tracking.getBehindCount();
                    if (ahead > 0 && behind > 0)
                        sb.append("Your branch is ").append(ahead).append(" ahead, ").append(behind).append(" behind its remote tracking branch.\n");
                    else if (ahead > 0)
                        sb.append("Your branch is ").append(ahead).append(" commit(s) ahead of its remote tracking branch.\n");
                    else if (behind > 0)
                        sb.append("Your branch is ").append(behind).append(" commit(s) behind its remote tracking branch.\n");
                }
            }
            catch (Exception e)
            {
                // tracking info not available
            }
            sb.append("\n");

            Set<String> added = status.getAdded();
            Set<String> changed = status.getChanged();
            Set<String> removed = status.getRemoved();
            if (!added.isEmpty() || !changed.isEmpty() || !removed.isEmpty())
            {
                sb.append("Changes to be committed:\n");
                added.forEach(f -> sb.append("  new file:   ").append(f).append("\n"));
                changed.forEach(f -> sb.append("  modified:   ").append(f).append("\n"));
                removed.forEach(f -> sb.append("  deleted:    ").append(f).append("\n"));
                sb.append("\n");
            }

            Set<String> modified = status.getModified();
            Set<String> missing = status.getMissing();
            if (!modified.isEmpty() || !missing.isEmpty())
            {
                sb.append("Changes not staged for commit:\n");
                modified.forEach(f -> sb.append("  modified:   ").append(f).append("\n"));
                missing.forEach(f -> sb.append("  deleted:    ").append(f).append("\n"));
                sb.append("\n");
            }

            Set<String> untracked = status.getUntracked();
            if (!untracked.isEmpty())
            {
                sb.append("Untracked files:\n");
                untracked.forEach(f -> sb.append("  ").append(f).append("\n"));
                sb.append("\n");
            }

            Set<String> conflicting = status.getConflicting();
            if (!conflicting.isEmpty())
            {
                sb.append("Unmerged paths:\n");
                conflicting.forEach(f -> sb.append("  both modified: ").append(f).append("\n"));
                sb.append("\n");
            }

            if (added.isEmpty() && changed.isEmpty() && removed.isEmpty()
                    && modified.isEmpty() && missing.isEmpty()
                    && untracked.isEmpty() && conflicting.isEmpty())
            {
                sb.append("nothing to commit, working tree clean\n");
            }

            return sb.toString();
        }
        catch (Exception e)
        {
            throw new RuntimeException("Failed to get git status: " + e.getMessage(), e);
        }
    }

    @SuppressWarnings("deprecation")
	public String getLog(String projectName, int maxCount)
    {
        Repository repository = getRepository(projectName);
        try (Git git = new Git(repository))
        {
            var log = git.log().setMaxCount(maxCount).call();
            var sb = new StringBuilder();

            for (RevCommit commit : log)
            {
                sb.append("commit ").append(commit.getName()).append("\n");
                PersonIdent author = commit.getAuthorIdent();
                sb.append("Author: ").append(author.getName())
                  .append(" <").append(author.getEmailAddress()).append(">\n");
                var sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z");
                sdf.setTimeZone(author.getTimeZone());
                sb.append("Date:   ").append(sdf.format(author.getWhen())).append("\n");
                sb.append("\n    ").append(commit.getFullMessage().trim().replace("\n", "\n    ")).append("\n\n");
            }

            return sb.toString();
        }
        catch (Exception e)
        {
            throw new RuntimeException("Failed to get git log: " + e.getMessage(), e);
        }
    }

    public String addFiles(String projectName, String filePattern)
    {
        Repository repository = getRepository(projectName);
        ReentrantLock lock = getRepositoryLock(repository);
        lock.lock();
        try
        {
            try (Git git = new Git(repository))
            {
                git.add().addFilepattern(filePattern).call();
                git.add().setUpdate(true).addFilepattern(filePattern).call();
            }
        }
        catch (Exception e)
        {
            throw new RuntimeException("Failed to add files: " + e.getMessage(), e);
        }
        finally
        {
            lock.unlock();
        }
        refreshProject(projectName);
        return "Added: " + filePattern;
    }

    public String stagePatch(String projectName, String patch)
    {
        Repository repository = getRepository(projectName);
        ReentrantLock lock = getRepositoryLock(repository);
        lock.lock();
        try (Git gitCmd = new Git(repository))
        {
            java.io.File workTree = repository.getWorkTree();

            // Parse the patch to find affected files
            org.eclipse.jgit.patch.Patch parsedPatch = new org.eclipse.jgit.patch.Patch();
            parsedPatch.parse(new ByteArrayInputStream(patch.getBytes(StandardCharsets.UTF_8)));

            if (parsedPatch.getFiles().isEmpty())
            {
                return "No files affected by patch.";
            }

            // For each file in the patch: save working tree, restore HEAD, apply patch, stage, restore working tree
            java.util.Map<java.io.File, byte[]> savedWorkingTree = new java.util.HashMap<>();

            for (var fileHeader : parsedPatch.getFiles())
            {
                String filePath = fileHeader.getNewPath();
                java.io.File file = new java.io.File(workTree, filePath);
                if (file.exists())
                {
                    savedWorkingTree.put(file, Files.readAllBytes(file.toPath()));
                }
            }

            // Checkout affected files from HEAD to restore original content
            var checkoutCmd = gitCmd.checkout();
            for (var fileHeader : parsedPatch.getFiles())
            {
                checkoutCmd.addPath(fileHeader.getNewPath());
            }
            checkoutCmd.call();

            // Apply the patch to the now-clean working tree files
            org.eclipse.jgit.api.ApplyResult result = gitCmd.apply()
                .setPatch(new ByteArrayInputStream(patch.getBytes(StandardCharsets.UTF_8)))
                .call();

            // Stage the patched files
            DirCache dirCache = repository.lockDirCache();
            try
            {
                DirCacheEditor editor = dirCache.editor();
                ObjectInserter inserter = repository.newObjectInserter();

                for (var file : result.getUpdatedFiles())
                {
                    byte[] content = Files.readAllBytes(file.toPath());
                    ObjectId blobId = inserter.insert(Constants.OBJ_BLOB, content);

                    String repoRelativePath = workTree.toPath()
                        .relativize(file.toPath()).toString().replace('\\', '/');

                    editor.add(new DirCacheEditor.PathEdit(repoRelativePath)
                    {
                        @Override
                        public void apply(DirCacheEntry ent)
                        {
                            ent.setObjectId(blobId);
                            ent.setFileMode(org.eclipse.jgit.lib.FileMode.REGULAR_FILE);
                            ent.setLength(content.length);
                            ent.setLastModified(java.time.Instant.now());
                        }
                    });
                }

                inserter.flush();
                editor.commit();
            }
            finally
            {
                dirCache.unlock();
            }

            // Restore working tree to the original modified state
            for (var entry : savedWorkingTree.entrySet())
            {
                Files.write(entry.getKey().toPath(), entry.getValue());
            }

            refreshProject(projectName);
            return "Patch staged successfully. Files: " + result.getUpdatedFiles().size();
        }
        catch (Exception e)
        {
            throw new RuntimeException("Failed to stage patch: " + e.getMessage(), e);
        }
        finally
        {
            lock.unlock();
        }
    }

    public String commit(String projectName, String message)
    {
        Repository repository = getRepository(projectName);
        ReentrantLock lock = getRepositoryLock(repository);
        lock.lock();
        try (Git git = new Git(repository))
        {
            RevCommit commit = git.commit().setMessage(message).call();
            refreshProject(projectName);
            return "Committed: " + commit.getName() + " " + commit.getShortMessage();
        }
        catch (Exception e)
        {
            throw new RuntimeException("Failed to commit: " + e.getMessage(), e);
        }
        finally
        {
            lock.unlock();
        }
    }

    public String readFileAtRevision(String projectName, String filePath, String revision)
    {
        Objects.requireNonNull(projectName, "projectName");
        Objects.requireNonNull(filePath, "filePath");
        Objects.requireNonNull(revision, "revision");

        IProject project = ResourcesPlugin.getWorkspace().getRoot().getProject(projectName);
        if (!project.exists())
        {
            throw new RuntimeException("Project not found: " + projectName);
        }

        RepositoryMapping mapping = RepositoryMapping.getMapping(project);
        if (mapping == null)
        {
            throw new RuntimeException("Project is not mapped to a Git repository: " + projectName);
        }

        String inputPath = filePath.replace('\\', '/');
        Path normalizedPath = Path.of(inputPath).normalize();
        String normalized = normalizedPath.toString().replace('\\', '/');
        if (inputPath.isBlank() || normalizedPath.isAbsolute() || normalized.equals("..") || normalized.startsWith("../")
                || inputPath.matches("^[A-Za-z]:.*"))
        {
            throw new IllegalArgumentException("File path must be relative to the Eclipse project: " + filePath);
        }

        String projectPrefix = mapping.getRepoRelativePath(project);
        String repositoryPath = projectPrefix == null || projectPrefix.isBlank() ? normalized : projectPrefix + "/" + normalized;
        Repository repository = mapping.getRepository();

        try
        {
            ObjectLoader loader;
            if ("INDEX".equalsIgnoreCase(revision))
            {
                DirCacheEntry entry = repository.readDirCache().getEntry(repositoryPath);
                if (entry == null)
                {
                    throw new IllegalArgumentException("File '" + filePath + "' was not found in the Git index.");
                }
                loader = repository.open(entry.getObjectId());
            }
            else
            {
                ObjectId treeId = repository.resolve(revision + "^{tree}");
                if (treeId == null)
                {
                    throw new IllegalArgumentException("Git revision could not be resolved: " + revision);
                }
                try (TreeWalk treeWalk = TreeWalk.forPath(repository, repositoryPath, treeId))
                {
                    if (treeWalk == null)
                    {
                        throw new IllegalArgumentException("File '" + filePath + "' was not found at revision '" + revision + "'.");
                    }
                    loader = repository.open(treeWalk.getObjectId(0));
                }
            }

            if (loader.getSize() > 5 * 1024 * 1024)
            {
                throw new IllegalArgumentException("Git file is larger than the 5 MiB read limit: " + filePath);
            }

            byte[] content = loader.getBytes();
            for (byte value : content)
            {
                if (value == 0)
                {
                    throw new IllegalArgumentException("Git file appears to be binary: " + filePath);
                }
            }
            return new String(content, StandardCharsets.UTF_8);
        }
        catch (IOException e)
        {
            throw new RuntimeException("Failed to read Git file: " + e.getMessage(), e);
        }
    }

    public String getDiff(String projectName, boolean staged)
    {
        Repository repository = getRepository(projectName);
        try (Git git = new Git(repository))
        {
            ObjectId head = repository.resolve("HEAD");
            if (head == null)
            {
                return "No commits yet.";
            }

            var diffCommand = git.diff();
            if (staged)
            {
                diffCommand.setCached(true);
                var headTree = prepareTreeParser(repository, head);
                diffCommand.setOldTree(headTree);
            }

            List<DiffEntry> diffs = diffCommand.call();
            return formatDiffEntries(repository, diffs);
        }
        catch (Exception e)
        {
            throw new RuntimeException("Failed to get diff: " + e.getMessage(), e);
        }
    }

    public String listBranches(String projectName, boolean includeRemote)
    {
        Repository repository = getRepository(projectName);
        try (Git git = new Git(repository))
        {
            String currentBranch = repository.getBranch();
            var sb = new StringBuilder();

            var cmd = git.branchList();
            if (includeRemote)
            {
                cmd.setListMode(ListBranchCommand.ListMode.ALL);
            }

            var branches = cmd.call();
            for (var ref : branches)
            {
                String name = ref.getName();
                if (name.startsWith("refs/heads/"))
                    name = name.substring("refs/heads/".length());
                else if (name.startsWith("refs/remotes/"))
                    name = name.substring("refs/remotes/".length());

                boolean isCurrent = name.equals(currentBranch);
                sb.append(isCurrent ? "* " : "  ").append(name).append("\n");
            }

            return sb.toString();
        }
        catch (Exception e)
        {
            throw new RuntimeException("Failed to list branches: " + e.getMessage(), e);
        }
    }

    public String createBranch(String projectName, String branchName, String startPoint)
    {
        Repository repository = getRepository(projectName);
        try (Git git = new Git(repository))
        {
            var cmd = git.branchCreate().setName(branchName);
            if (startPoint != null && !startPoint.isEmpty())
            {
                cmd.setStartPoint(startPoint);
            }
            cmd.call();
            refreshProject(projectName);
            return "Created branch: " + branchName;
        }
        catch (Exception e)
        {
            throw new RuntimeException("Failed to create branch: " + e.getMessage(), e);
        }
    }

    public String deleteBranch(String projectName, String branchName, boolean force)
    {
        Repository repository = getRepository(projectName);
        try (Git git = new Git(repository))
        {
            var cmd = git.branchDelete().setBranchNames(branchName).setForce(force);
            var deleted = cmd.call();
            return "Deleted branch: " + String.join(", ", deleted);
        }
        catch (Exception e)
        {
            throw new RuntimeException("Failed to delete branch: " + e.getMessage(), e);
        }
    }

    public String checkoutBranch(String projectName, String branchName)
    {
        Repository repository = getRepository(projectName);
        ReentrantLock lock = getRepositoryLock(repository);
        lock.lock();
        try (Git git = new Git(repository))
        {
            git.checkout().setName(branchName).call();
            refreshProject(projectName);
            return "Switched to branch: " + branchName;
        }
        catch (Exception e)
        {
            throw new RuntimeException("Failed to checkout branch: " + e.getMessage(), e);
        }
        finally
        {
            lock.unlock();
        }
    }

    public String resetFiles(String projectName, String filePattern)
    {
        Repository repository = getRepository(projectName);
        ReentrantLock lock = getRepositoryLock(repository);
        lock.lock();
        try (Git git = new Git(repository))
        {
            git.reset().addPath(filePattern).call();
            refreshProject(projectName);
            return "Unstaged: " + filePattern;
        }
        catch (Exception e)
        {
            throw new RuntimeException("Failed to reset files: " + e.getMessage(), e);
        }
        finally
        {
            lock.unlock();
        }
    }

    public String stash(String projectName, String message)
    {
        Repository repository = getRepository(projectName);
        ReentrantLock lock = getRepositoryLock(repository);
        lock.lock();
        try (Git git = new Git(repository))
        {
            var cmd = git.stashCreate();
            if (message != null && !message.isEmpty())
            {
                cmd.setWorkingDirectoryMessage(message);
            }
            var stashRef = cmd.call();
            refreshProject(projectName);
            if (stashRef == null)
            {
                return "No local changes to stash.";
            }
            return "Stashed working directory: " + stashRef.getName();
        }
        catch (Exception e)
        {
            throw new RuntimeException("Failed to stash: " + e.getMessage(), e);
        }
        finally
        {
            lock.unlock();
        }
    }

    public String stashPop(String projectName)
    {
        Repository repository = getRepository(projectName);
        ReentrantLock lock = getRepositoryLock(repository);
        lock.lock();
        try (Git git = new Git(repository))
        {
            git.stashApply().call();
            git.stashDrop().call();
            refreshProject(projectName);
            return "Applied and dropped stash.";
        }
        catch (Exception e)
        {
            throw new RuntimeException("Failed to pop stash: " + e.getMessage(), e);
        }
        finally
        {
            lock.unlock();
        }
    }

    public String stashList(String projectName)
    {
        Repository repository = getRepository(projectName);
        try (Git git = new Git(repository))
        {
            var stashes = git.stashList().call();
            if (!stashes.iterator().hasNext())
            {
                return "No stashes found.";
            }
            var sb = new StringBuilder();
            int index = 0;
            for (RevCommit stash : stashes)
            {
                sb.append("stash@{").append(index++).append("}: ")
                  .append(stash.getShortMessage()).append("\n");
            }
            return sb.toString();
        }
        catch (Exception e)
        {
            throw new RuntimeException("Failed to list stashes: " + e.getMessage(), e);
        }
    }

    public String getCurrentDiff()
    {
        return uiSync.syncCall(() -> {
            var activeResource = editorService.getActiveEditor()
                    .map(IEditorPart::getEditorInput)
                    .map(editorInput -> editorInput.getAdapter(IResource.class))
                    .orElseThrow(() -> new RuntimeException("No active resource available."));
            var mapping = RepositoryMapping.getMapping(activeResource);
            var repository = mapping.getRepository();
            try (var git = new Git(repository))
            {
                var head = repository.resolve("HEAD");
                if (Objects.isNull(head))
                {
                    return "Initial commit: No previous commits found.";
                }
                else
                {
                    var headTree = prepareTreeParser(repository, head);
                    var indexTree = prepareIndexTreeParser(repository);
                    var stagedChanges = git.diff().setOldTree(headTree).setNewTree(indexTree).call();
                    return formatDiffEntries(git.getRepository(), stagedChanges);
                }
            }
            catch (Exception e)
            {
                throw new RuntimeException(e);
            }
        });
    }

    private static AbstractTreeIterator prepareTreeParser(Repository repository, ObjectId objectId) throws IOException
    {
        try (RevWalk walk = new RevWalk(repository))
        {
            var commit = walk.parseCommit(objectId);
            var treeId = commit.getTree().getId();
            try (var reader = repository.newObjectReader())
            {
                return new CanonicalTreeParser(null, reader, treeId);
            }
        }
    }

    private static AbstractTreeIterator prepareIndexTreeParser(Repository repository) throws IOException
    {
        try (var inserter = repository.newObjectInserter();
             var reader = repository.newObjectReader())
        {
            var treeId = repository.readDirCache().writeTree(inserter);
            return new CanonicalTreeParser(null, reader, treeId);
        }
    }

    private String formatDiffEntries(Repository repository, List<DiffEntry> diffs) throws IOException
    {
        try (var out = new ByteArrayOutputStream();
             var formatter = new DiffFormatter(out))
        {
            formatter.setRepository(repository);
            formatter.setDiffComparator(RawTextComparator.DEFAULT);
            formatter.setDetectRenames(true);
            for (DiffEntry diff : diffs)
            {
                formatter.format(diff);
            }
            return out.toString("UTF-8");
        }
    }
}
