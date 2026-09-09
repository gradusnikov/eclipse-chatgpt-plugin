package com.github.gradusnikov.eclipse.assistai.mcp.services;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.ByteArrayInputStream;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.Collections;
import java.util.TreeMap;

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
import org.eclipse.jgit.api.ApplyResult;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.ListBranchCommand;
import org.eclipse.jgit.api.errors.CheckoutConflictException;
import org.eclipse.jgit.api.errors.NotMergedException;
import org.eclipse.jgit.api.errors.StashApplyFailureException;
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
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.patch.FileHeader;
import org.eclipse.jgit.patch.Patch;
import org.eclipse.jgit.treewalk.EmptyTreeIterator;
import org.eclipse.jgit.treewalk.FileTreeIterator;
import org.eclipse.jgit.treewalk.filter.PathFilterGroup;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.treewalk.AbstractTreeIterator;
import org.eclipse.jgit.treewalk.CanonicalTreeParser;
import org.eclipse.ui.IEditorPart;
import org.eclipse.egit.core.credentials.CredentialsStore;
import org.eclipse.egit.core.credentials.UserPasswordCredentials;
import org.eclipse.jgit.api.CheckoutCommand;
import org.eclipse.jgit.api.CherryPickCommand;
import org.eclipse.jgit.api.CherryPickResult;
import org.eclipse.jgit.api.FetchCommand;
import org.eclipse.jgit.api.LogCommand;
import org.eclipse.jgit.api.MergeCommand;
import org.eclipse.jgit.api.MergeCommand.FastForwardMode;
import org.eclipse.jgit.api.MergeResult;
import org.eclipse.jgit.api.PullCommand;
import org.eclipse.jgit.api.PullResult;
import org.eclipse.jgit.api.PushCommand;
import org.eclipse.jgit.api.RebaseCommand;
import org.eclipse.jgit.api.RebaseResult;
import org.eclipse.jgit.api.ResetCommand;
import org.eclipse.jgit.api.ResetCommand.ResetType;
import org.eclipse.jgit.api.RevertCommand;
import org.eclipse.jgit.api.Status;
import org.eclipse.jgit.api.TagCommand;
import org.eclipse.jgit.api.errors.InvalidConfigurationException;
import org.eclipse.jgit.api.errors.InvalidRemoteException;
import org.eclipse.jgit.api.errors.RefNotFoundException;
import org.eclipse.jgit.api.errors.TransportException;
import org.eclipse.jgit.api.errors.WrongRepositoryStateException;
import org.eclipse.jgit.lib.ConfigConstants;
import org.eclipse.jgit.lib.RefUpdate;
import org.eclipse.jgit.lib.StoredConfig;
import org.eclipse.jgit.revwalk.RevObject;
import org.eclipse.jgit.revwalk.RevTag;
import org.eclipse.jgit.transport.CredentialsProvider;
import org.eclipse.jgit.transport.FetchResult;
import org.eclipse.jgit.transport.PushResult;
import org.eclipse.jgit.transport.RemoteConfig;
import org.eclipse.jgit.transport.RemoteRefUpdate;
import org.eclipse.jgit.transport.TrackingRefUpdate;
import org.eclipse.jgit.transport.URIish;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.osgi.framework.BundleContext;
import org.osgi.framework.FrameworkUtil;
import org.osgi.framework.ServiceReference;

import com.github.gradusnikov.eclipse.assistai.mcp.results.Diagnostic;
import com.github.gradusnikov.eclipse.assistai.mcp.results.DiagnosticCode;
import com.github.gradusnikov.eclipse.assistai.mcp.results.GitBranchResponse;
import com.github.gradusnikov.eclipse.assistai.mcp.results.GitCheckoutResponse;
import com.github.gradusnikov.eclipse.assistai.mcp.results.GitCommitResponse;
import com.github.gradusnikov.eclipse.assistai.mcp.results.GitDeleteBranchResponse;
import com.github.gradusnikov.eclipse.assistai.mcp.results.GitDiffResponse;
import com.github.gradusnikov.eclipse.assistai.mcp.results.GitLogResponse;
import com.github.gradusnikov.eclipse.assistai.mcp.results.GitStagePatchResponse;
import com.github.gradusnikov.eclipse.assistai.mcp.results.GitStageResponse;
import com.github.gradusnikov.eclipse.assistai.mcp.results.GitStageResponse.StageOperation;
import com.github.gradusnikov.eclipse.assistai.mcp.results.GitStashListResponse;
import com.github.gradusnikov.eclipse.assistai.mcp.results.GitStashListResponse.GitStash;
import com.github.gradusnikov.eclipse.assistai.mcp.results.GitStashPopResponse;
import com.github.gradusnikov.eclipse.assistai.mcp.results.GitStashResponse;
import com.github.gradusnikov.eclipse.assistai.mcp.results.GitStatusResponse;
import com.github.gradusnikov.eclipse.assistai.mcp.results.GitStatusResponse.ChangeType;
import com.github.gradusnikov.eclipse.assistai.mcp.results.GitStatusResponse.GitFileChange;
import com.github.gradusnikov.eclipse.assistai.mcp.results.GitApplyCommitsResponse;
import com.github.gradusnikov.eclipse.assistai.mcp.results.GitApplyCommitsResponse.ApplyOperation;
import com.github.gradusnikov.eclipse.assistai.mcp.results.GitApplyCommitsResponse.ApplyStatus;
import com.github.gradusnikov.eclipse.assistai.mcp.results.GitDiscardResponse;
import com.github.gradusnikov.eclipse.assistai.mcp.results.GitFetchResponse;
import com.github.gradusnikov.eclipse.assistai.mcp.results.GitFetchResponse.GitRefUpdate;
import com.github.gradusnikov.eclipse.assistai.mcp.results.GitLogResponse.GitCommit;
import com.github.gradusnikov.eclipse.assistai.mcp.results.GitMergeResponse;
import com.github.gradusnikov.eclipse.assistai.mcp.results.GitMergeResponse.MergeStatus;
import com.github.gradusnikov.eclipse.assistai.mcp.results.GitPullResponse;
import com.github.gradusnikov.eclipse.assistai.mcp.results.GitPullResponse.PullStatus;
import com.github.gradusnikov.eclipse.assistai.mcp.results.GitPushResponse;
import com.github.gradusnikov.eclipse.assistai.mcp.results.GitPushResponse.GitPushUpdate;
import com.github.gradusnikov.eclipse.assistai.mcp.results.GitPushResponse.PushStatus;
import com.github.gradusnikov.eclipse.assistai.mcp.results.GitRebaseResponse;
import com.github.gradusnikov.eclipse.assistai.mcp.results.GitRebaseResponse.RebaseOperation;
import com.github.gradusnikov.eclipse.assistai.mcp.results.GitRebaseResponse.RebaseStatus;
import com.github.gradusnikov.eclipse.assistai.mcp.results.GitRemoteListResponse;
import com.github.gradusnikov.eclipse.assistai.mcp.results.GitRemoteListResponse.GitRemote;
import com.github.gradusnikov.eclipse.assistai.mcp.results.GitResetResponse;
import com.github.gradusnikov.eclipse.assistai.mcp.results.GitResetResponse.ResetMode;
import com.github.gradusnikov.eclipse.assistai.mcp.results.GitShowResponse;
import com.github.gradusnikov.eclipse.assistai.mcp.results.GitTagListResponse;
import com.github.gradusnikov.eclipse.assistai.mcp.results.GitTagListResponse.GitTag;
import com.github.gradusnikov.eclipse.assistai.mcp.results.GitTagResponse;
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

    private boolean refreshProject(String projectName)
    {
        try
        {
            IProject project = ResourcesPlugin.getWorkspace().getRoot().getProject(projectName);
            project.refreshLocal(IResource.DEPTH_INFINITE, null);
            return true;
        }
        catch (CoreException e)
        {
            logger.error("Failed to refresh project: " + projectName, e);
            return false;
        }
    }

    /**
     * Refreshes every project mapped into the repository, and says which ones it
     * managed.
     * <p>
     * An operation that rewrites the working tree - a checkout, a stash pop, the
     * save-and-restore cycle of a patch stage - rewrites it for the whole repository,
     * and one repository routinely holds several Eclipse projects. Refreshing only the
     * project that happened to be named left every sibling stale, with nothing saying
     * so.
     */
    private List<String> refreshMappedProjects(Repository repository)
    {
        List<String> refreshed = new ArrayList<>();
        for (String projectName : mappedProjects(repository).values())
        {
            if (refreshProject(projectName))
            {
                refreshed.add(projectName);
            }
        }
        refreshed.sort(Comparator.naturalOrder());
        return refreshed;
    }

    /**
     * The project's folder relative to the repository root, empty for a project sitting
     * at that root.
     */
    private String projectPrefix(String projectName)
    {
        IProject project = ResourcesPlugin.getWorkspace().getRoot().getProject(projectName);
        RepositoryMapping mapping = RepositoryMapping.getMapping(project);
        String prefix = mapping == null ? "" : mapping.getRepoRelativePath(project);
        return prefix == null ? "" : prefix;
    }

    /** The commit HEAD points at, or null in a repository with no commits. */
    private String headSha(Repository repository)
    {
        try
        {
            ObjectId head = repository.resolve(Constants.HEAD);
            return head == null ? null : head.getName();
        }
        catch (IOException e)
        {
            return null;
        }
    }

    /** The checked-out branch, or null when it cannot be read. */
    private String currentBranch(Repository repository)
    {
        try
        {
            return repository.getBranch();
        }
        catch (IOException e)
        {
            return null;
        }
    }

    /**
     * The projects mapped into a repository, keyed by their folder relative to the
     * repository root - the empty string for a project sitting at that root.
     * <p>
     * Git reports every path from the repository root, but the reading and editing tools
     * address a file by project and project-relative path, and one repository routinely
     * holds several projects. Without this map a status entry names a file that no other
     * tool can open.
     */
    private Map<String, String> mappedProjects(Repository repository)
    {
        Map<String, String> prefixes = new LinkedHashMap<>();
        java.io.File repositoryDirectory = repository.getDirectory();

        for (IProject project : ResourcesPlugin.getWorkspace().getRoot().getProjects())
        {
            if (!project.isAccessible())
            {
                continue;
            }
            RepositoryMapping mapping = RepositoryMapping.getMapping(project);
            if (mapping == null || mapping.getRepository() == null
                    || !Objects.equals(repositoryDirectory, mapping.getRepository().getDirectory()))
            {
                continue;
            }
            String prefix = mapping.getRepoRelativePath(project);
            prefixes.putIfAbsent(prefix == null ? "" : prefix, project.getName());
        }
        return prefixes;
    }

    /**
     * The working tree state: staged, unstaged, untracked and conflicting files, the
     * current branch, and how far it has drifted from its upstream.
     * <p>
     * A clean working tree is reported as a clean flag with empty lists, not as a
     * sentence, so a caller can branch on it.
     */
    public GitStatusResponse getStatus(String projectName)
    {
        Repository repository = getRepository(projectName);
        try (Git git = new Git(repository))
        {
            var status = git.status().call();
            String branch = repository.getBranch();

            String upstreamBranch = null;
            Integer aheadCount = null;
            Integer behindCount = null;
            try
            {
                BranchTrackingStatus tracking = BranchTrackingStatus.of(repository, branch);
                if (tracking != null)
                {
                    String remoteTracking = tracking.getRemoteTrackingBranch();
                    upstreamBranch = remoteTracking != null && remoteTracking.startsWith(Constants.R_REMOTES)
                            ? remoteTracking.substring(Constants.R_REMOTES.length())
                            : remoteTracking;
                    aheadCount = tracking.getAheadCount();
                    behindCount = tracking.getBehindCount();
                }
            }
            catch (Exception e)
            {
                // A branch that tracks nothing. Left as a null upstream, which is what it
                // is, rather than reported as a failure to produce a status.
            }

            return GitStatusResponse.from(projectName, branch, upstreamBranch, aheadCount, behindCount, status,
                    mappedProjects(repository));
        }
        catch (Exception e)
        {
            throw new RuntimeException("Failed to get git status: " + e.getMessage(), e);
        }
    }

    /**
     * The most recent commits on the current branch.
     * <p>
     * One more commit than asked for is walked, so that "the history goes further back"
     * can be answered without counting the whole graph.
     */
    public GitLogResponse getLog(String projectName, int maxCount)
    {
        return getLog(projectName, maxCount, null, null);
    }

    /**
     * The most recent commits reachable from a revision, optionally only those that
     * touch some paths.
     *
     * @param revision the branch, tag or commit to walk back from; HEAD when null
     * @param pathFilter comma-separated project-relative files or folders; null for all
     */
    public GitLogResponse getLog(String projectName, int maxCount, String revision, String pathFilter)
    {
        Repository repository = getRepository(projectName);
        int limit = Math.max(maxCount, 0);
        List<String> repositoryPaths = resolveDiffPaths(projectName, pathFilter);

        try (Git git = new Git(repository))
        {
            LogCommand command = git.log().setMaxCount(limit + 1);
            String from = repository.getBranch();
            if (revision != null && !revision.isBlank())
            {
                command.add(resolveRevision(repository, revision.trim()));
                from = revision.trim();
            }
            for (String path : repositoryPaths)
            {
                command.addPath(path);
            }

            List<RevCommit> commits = new ArrayList<>();
            for (RevCommit commit : command.call())
            {
                commits.add(commit);
            }

            boolean truncated = commits.size() > limit;
            if (truncated)
            {
                commits = commits.subList(0, limit);
            }

            return GitLogResponse.from(projectName, from, commits, truncated);
        }
        catch (IllegalArgumentException e)
        {
            throw e;
        }
        catch (Exception e)
        {
            throw new RuntimeException("Failed to get git log: " + e.getMessage(), e);
        }
    }

    /**
     * Stages a pathspec, and reports what the index actually gained.
     * <p>
     * JGit succeeds against a pathspec that matches no file, so echoing the caller's own
     * pattern back - "Added: src/Typo.java" - confirmed a stage that never happened and
     * the commit that followed was silently wrong. The index is compared before and
     * after instead.
     * <p>
     * The pattern is project-relative, like every other path these tools take, and is
     * translated to the repository-relative form Git needs by {@link #resolvePathspecs}.
     */
    public GitStageResponse addFiles(String projectName, String filePattern)
    {
        Repository repository = getRepository(projectName);
        List<String> pathspecs = resolvePathspecs(projectName, filePattern);
        ReentrantLock lock = getRepositoryLock(repository);
        lock.lock();

        List<GitFileChange> staged;
        try (Git git = new Git(repository))
        {
            IndexSnapshot before = indexSnapshot(repository);
            for (String pathspec : pathspecs)
            {
                String pattern = pathspec.isEmpty() ? "." : pathspec;
                git.add().addFilepattern(pattern).call();
                git.add().setUpdate(true).addFilepattern(pattern).call();
            }
            staged = indexDelta(before, indexSnapshot(repository), mappedProjects(repository));
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
        return GitStageResponse.of(projectName, StageOperation.STAGE, filePattern, staged);
    }

    /**
     * Stages the changes a unified diff describes, leaving the working tree as it was.
     * <p>
     * It works by saving the working-tree content of every file the patch names,
     * checking those files out clean, applying the patch to the clean copies, staging
     * the result and putting the saved content back. The caller's uncommitted edits are
     * therefore off disk for the length of the middle three steps, which is why the
     * restore runs in a {@code finally} rather than at the end of the happy path: it
     * used to sit inside the {@code try}, so a patch that failed to apply - the common
     * case for a generated patch - left every file it touched reverted and the edits
     * gone, while the caller saw only "Failed to stage patch".
     */
    public GitStagePatchResponse stagePatch(String projectName, String patch)
    {
        Repository repository = getRepository(projectName);
        Map<String, String> projects = mappedProjects(repository);
        ReentrantLock lock = getRepositoryLock(repository);
        lock.lock();

        GitStagePatchResponse response;
        try (Git gitCmd = new Git(repository))
        {
            File workTree = repository.getWorkTree();

            Patch parsedPatch = new Patch();
            parsedPatch.parse(new ByteArrayInputStream(patch.getBytes(StandardCharsets.UTF_8)));

            if (parsedPatch.getFiles().isEmpty())
            {
                // Reported as a success until now, so an unparseable patch and a staged
                // one were told apart only by reading the sentence back.
                String detail = parsedPatch.getErrors().isEmpty()
                        ? ""
                        : " First parser error: " + parsedPatch.getErrors().get(0).getMessage();
                return GitStagePatchResponse.rejected(projectName,
                        Diagnostic.fatal(DiagnosticCode.PATCH_APPLY_FAILED,
                                "The patch parsed to no file headers, so nothing was staged. A patch needs"
                                        + " '--- a/<path>' and '+++ b/<path>' lines and at least one '@@' hunk"
                                        + " header." + detail));
            }

            List<Diagnostic> pathProblems = validatePatchPaths(repository, projectName, parsedPatch);
            if (!pathProblems.isEmpty())
            {
                // Nothing has been touched yet, so the working tree is intact.
                return GitStagePatchResponse.failed(projectName, true, List.of(), pathProblems);
            }

            Map<File, byte[]> savedWorkingTree = new LinkedHashMap<>();
            for (FileHeader fileHeader : parsedPatch.getFiles())
            {
                File file = new File(workTree, patchPath(fileHeader));
                if (file.isFile())
                {
                    savedWorkingTree.put(file, Files.readAllBytes(file.toPath()));
                }
            }

            IndexSnapshot before = indexSnapshot(repository);
            Exception failure = null;
            List<Diagnostic> restoreProblems;
            try
            {
                var checkoutCmd = gitCmd.checkout();
                for (FileHeader fileHeader : parsedPatch.getFiles())
                {
                    checkoutCmd.addPath(patchPath(fileHeader));
                }
                checkoutCmd.call();

                ApplyResult result = gitCmd.apply()
                    .setPatch(new ByteArrayInputStream(patch.getBytes(StandardCharsets.UTF_8)))
                    .call();

                stageFiles(repository, workTree, result.getUpdatedFiles());
            }
            catch (Exception e)
            {
                failure = e;
            }
            finally
            {
                restoreProblems = restoreWorkingTree(savedWorkingTree);
            }

            List<String> restoredPaths = repoRelativePaths(workTree, savedWorkingTree.keySet());
            boolean workingTreePreserved = restoreProblems.isEmpty();

            if (failure != null)
            {
                List<Diagnostic> diagnostics = new ArrayList<>();
                diagnostics.add(Diagnostic.fatal(DiagnosticCode.PATCH_APPLY_FAILED,
                        "The patch did not apply: " + failure.getMessage()));
                diagnostics.addAll(restoreProblems);
                response = GitStagePatchResponse.failed(projectName, workingTreePreserved, restoredPaths, diagnostics);
            }
            else
            {
                response = GitStagePatchResponse.staged(projectName,
                        indexDelta(before, indexSnapshot(repository), projects), workingTreePreserved, restoredPaths,
                        restoreProblems);
            }
        }
        catch (Exception e)
        {
            throw new RuntimeException("Failed to stage patch: " + e.getMessage(), e);
        }
        finally
        {
            lock.unlock();
        }

        refreshMappedProjects(repository);
        return response;
    }

    /** The repository-relative path a patch file header addresses. */
    private static String patchPath(FileHeader fileHeader)
    {
        return fileHeader.getChangeType() == DiffEntry.ChangeType.DELETE
                ? fileHeader.getOldPath()
                : fileHeader.getNewPath();
    }

    /**
     * Checks that every path the patch names exists where the patch says it does.
     * <p>
     * A patch addresses files from the repository root, while {@code gitDiff},
     * {@code gitReadFile} and every editing tool take a path relative to the Eclipse
     * project - and one repository routinely holds several projects, so the two forms
     * differ for all but a project sitting at the repository root. A patch written in
     * the project's terms used to check out and apply against paths that do not exist,
     * stage nothing, and still report success. Where the project-relative reading of a
     * path does resolve, the diagnostic says so and gives the repository path to use.
     */
    private List<Diagnostic> validatePatchPaths(Repository repository, String projectName, Patch parsedPatch)
    {
        File workTree = repository.getWorkTree();
        String projectPrefix = projectPrefix(projectName);
        List<Diagnostic> problems = new ArrayList<>();

        for (FileHeader fileHeader : parsedPatch.getFiles())
        {
            if (fileHeader.getChangeType() == DiffEntry.ChangeType.ADD)
            {
                // A patch that creates a file names a path that is not there yet.
                continue;
            }
            String path = patchPath(fileHeader);
            if (new File(workTree, path).isFile())
            {
                continue;
            }

            String repositoryPath = projectPrefix.isEmpty() ? null : projectPrefix + "/" + path;
            if (repositoryPath != null && new File(workTree, repositoryPath).isFile())
            {
                problems.add(Diagnostic.fatal(DiagnosticCode.PATCH_APPLY_FAILED,
                        "Patch path '" + path + "' does not exist in the repository. Patch paths are"
                                + " repository-relative, and project '" + projectName + "' sits at '" + projectPrefix
                                + "' inside the repository: write the header as '" + repositoryPath + "'."));
            }
            else
            {
                problems.add(Diagnostic.fatal(DiagnosticCode.PATCH_APPLY_FAILED,
                        "Patch path '" + path + "' does not exist in the repository working tree."));
            }
        }
        return problems;
    }

    /** Writes the patched content of each file into the index, leaving the file alone. */
    private void stageFiles(Repository repository, File workTree, List<File> files) throws IOException
    {
        DirCache dirCache = repository.lockDirCache();
        try
        {
            DirCacheEditor editor = dirCache.editor();
            ObjectInserter inserter = repository.newObjectInserter();

            for (File file : files)
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
    }

    /**
     * Puts back the exact bytes each file held before the patch was applied.
     * <p>
     * Never throws. It runs on the failure path, where an exception raised here would
     * replace the report of what went wrong, and the caller would learn neither that
     * the patch failed nor that its edits are missing.
     *
     * @return one diagnostic per file whose content could not be written back, empty
     *         when the working tree is exactly as it was
     */
    private List<Diagnostic> restoreWorkingTree(Map<File, byte[]> savedWorkingTree)
    {
        List<Diagnostic> problems = new ArrayList<>();
        for (Map.Entry<File, byte[]> entry : savedWorkingTree.entrySet())
        {
            try
            {
                Files.write(entry.getKey().toPath(), entry.getValue());
            }
            catch (Exception e)
            {
                logger.error("Failed to restore working tree file: " + entry.getKey(), e);
                problems.add(Diagnostic.fatal(DiagnosticCode.INTERNAL_ERROR,
                        "The working tree content of '" + entry.getKey().getName() + "' could not be restored after"
                                + " staging the patch, and the file now holds its committed content: "
                                + e.getMessage()));
            }
        }
        return problems;
    }

    private static List<String> repoRelativePaths(File workTree, Collection<File> files)
    {
        List<String> paths = new ArrayList<>();
        for (File file : files)
        {
            paths.add(workTree.toPath().relativize(file.toPath()).toString().replace('\\', '/'));
        }
        paths.sort(Comparator.naturalOrder());
        return paths;
    }

    /**
     * The commit a {@code gitCommit} produced.
     * <p>
     * The sha used to be glued to the commit's subject by a single space, and subjects
     * contain spaces themselves - so the obvious split recovered the sha and mangled the
     * message. It is the handle for every next action, and is now a field.
     */
    public GitCommitResponse commit(String projectName, String message)
    {
        return commit(projectName, message, false, false);
    }

    /**
     * Commits the index, optionally staging tracked changes first or replacing the
     * previous commit.
     *
     * @param amend whether to replace the previous commit rather than add one after it
     * @param all whether to stage every tracked file's modifications and deletions
     *            first, repository-wide - {@code git commit -a}; new files still need
     *            {@code gitAdd}
     */
    public GitCommitResponse commit(String projectName, String message, boolean amend, boolean all)
    {
        Repository repository = getRepository(projectName);
        ReentrantLock lock = getRepositoryLock(repository);
        lock.lock();
        try (Git git = new Git(repository))
        {
            RevCommit commit = git.commit().setMessage(message).setAmend(amend).setAll(all).call();
            String branch = repository.getBranch();
            refreshProject(projectName);
            return GitCommitResponse.of(projectName, branch, GitLogResponse.toCommit(commit));
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

    /**
     * The staged or unstaged differences of the repository a project is mapped into.
     * <p>
     * Three outcomes used to share the one returned String - hunks, an empty string, and
     * the sentence "No commits yet." - so an unchanged tree, a fresh repository and a
     * failure were told apart by reading prose. The hunks are still a string, because a
     * unified diff is a machine format with a parser everywhere, but each file in it is
     * also listed with the project and project-relative path the reading and editing
     * tools take, since Git names paths from the repository root and none of them accept
     * that form.
     */
    public GitDiffResponse getDiff(String projectName, boolean staged, String pathFilter, boolean ignoreWhitespace)
    {
        return getDiff(projectName, staged, pathFilter, ignoreWhitespace, null, null);
    }

    /**
     * The differences between two revisions, or between a revision and the working
     * tree, when {@code fromRevision} or {@code toRevision} is given; otherwise the
     * staged or unstaged differences.
     *
     * @param fromRevision the older side; HEAD when only toRevision is given
     * @param toRevision the newer side; the working tree when null
     */
    public GitDiffResponse getDiff(String projectName, boolean staged, String pathFilter, boolean ignoreWhitespace,
            String fromRevision, String toRevision)
    {
        Repository repository = getRepository(projectName);
        List<String> repositoryPaths = resolveDiffPaths(projectName, pathFilter);
        Map<String, String> projects = mappedProjects(repository);
        boolean betweenRevisions = (fromRevision != null && !fromRevision.isBlank())
                || (toRevision != null && !toRevision.isBlank());
        // A comparison of two revisions is neither the staged nor the unstaged diff.
        boolean stagedDiff = staged && !betweenRevisions;

        try (var out = new ByteArrayOutputStream();
             var formatter = new DiffFormatter(out))
        {
            ObjectId head = repository.resolve(Constants.HEAD);

            RawTextComparator comparator = ignoreWhitespace ? RawTextComparator.WS_IGNORE_ALL : RawTextComparator.DEFAULT;
            formatter.setRepository(repository);
            formatter.setDiffComparator(comparator);
            formatter.setDetectRenames(true);
            if (!repositoryPaths.isEmpty())
            {
                formatter.setPathFilter(PathFilterGroup.createFromStrings(repositoryPaths));
            }

            AbstractTreeIterator oldTree;
            AbstractTreeIterator newTree;
            String fromLabel;
            String toLabel;
            String baseRevision;
            if (betweenRevisions)
            {
                String from = fromRevision == null || fromRevision.isBlank() ? Constants.HEAD : fromRevision.trim();
                ObjectId fromId = resolveRevision(repository, from);
                oldTree = prepareTreeParser(repository, fromId);
                fromLabel = from;
                baseRevision = fromId.getName();
                if (toRevision == null || toRevision.isBlank())
                {
                    newTree = new FileTreeIterator(repository);
                    toLabel = "WORKING_TREE";
                }
                else
                {
                    newTree = prepareTreeParser(repository, resolveRevision(repository, toRevision.trim()));
                    toLabel = toRevision.trim();
                }
            }
            else
            {
                // A repository with no commits has no HEAD to compare against. The empty
                // tree is what Git itself compares a fresh index with, so the staged changes
                // are still reported rather than replaced by a sentence.
                oldTree = stagedDiff
                        ? (head == null ? new EmptyTreeIterator() : prepareTreeParser(repository, head))
                        : prepareIndexTreeParser(repository);
                newTree = stagedDiff
                        ? prepareIndexTreeParser(repository)
                        : new FileTreeIterator(repository);
                fromLabel = stagedDiff ? (head == null ? "EMPTY_TREE" : "HEAD") : "INDEX";
                toLabel = stagedDiff ? "INDEX" : "WORKING_TREE";
                baseRevision = head == null ? null : head.getName();
            }

            List<DiffEntry> diffs = formatter.scan(oldTree, newTree);

            // The line counts come from the EditList JGit builds to render each file, so
            // they agree with the body by construction.
            List<GitDiffResponse.GitFileDiff> files = new ArrayList<>();
            for (DiffEntry diff : diffs)
            {
                files.add(GitDiffResponse.file(formatter.toFileHeader(diff), projects));
            }
            formatter.format(diffs);

            return GitDiffResponse.of(projectName, stagedDiff, fromLabel, toLabel, baseRevision, files,
                    out.toString(StandardCharsets.UTF_8));
        }
        catch (IllegalArgumentException e)
        {
            throw e;
        }
        catch (Exception e)
        {
            throw new RuntimeException("Failed to get diff: " + e.getMessage(), e);
        }
    }

    private List<String> resolveDiffPaths(String projectName, String pathFilter)
    {
        if (pathFilter == null || pathFilter.isBlank())
        {
            return List.of();
        }
        List<String> pathspecs = resolvePathspecs(projectName, pathFilter);
        // An entry standing for the whole repository makes a filter pointless.
        return pathspecs.contains("") ? List.of() : pathspecs;
    }

    /**
     * The repository's branches, with the checked-out one flagged.
     * <p>
     * Remote-tracking branches are kept in their own list: only a local branch can be
     * checked out or deleted, and a caller choosing one should not have to notice a
     * {@code origin/} prefix to know that.
     */
    public GitBranchResponse listBranches(String projectName, boolean includeRemote)
    {
        Repository repository = getRepository(projectName);
        try (Git git = new Git(repository))
        {
            var cmd = git.branchList();
            if (includeRemote)
            {
                cmd.setListMode(ListBranchCommand.ListMode.ALL);
            }
            List<Ref> refs = cmd.call();

            return GitBranchResponse.from(projectName, repository.getBranch(), refs);
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

    /**
     * Deletes a branch, or says why it did not.
     * <p>
     * Refusing to delete unmerged work is the safety check doing its job, not a fault,
     * and it is the one failure here whose remedy is mechanical - so it arrives as
     * {@code deleted: false} with a code naming the retry, rather than as an exception a
     * caller has to read.
     */
    public GitDeleteBranchResponse deleteBranch(String projectName, String branchName, boolean force)
    {
        Repository repository = getRepository(projectName);
        try (Git git = new Git(repository))
        {
            List<String> deleted = git.branchDelete().setBranchNames(branchName).setForce(force).call();
            return GitDeleteBranchResponse.deleted(projectName, branchName, force, deleted);
        }
        catch (NotMergedException e)
        {
            return GitDeleteBranchResponse.failed(projectName, branchName, force,
                    Diagnostic.fatal(DiagnosticCode.BRANCH_NOT_MERGED,
                            "Branch '" + branchName + "' is not fully merged into HEAD, so deleting it would lose"
                                    + " commits. Call again with force=true to delete it anyway."));
        }
        catch (Exception e)
        {
            throw new RuntimeException("Failed to delete branch: " + e.getMessage(), e);
        }
    }

    /**
     * Switches the working tree to another branch.
     * <p>
     * Two things were prose. A checkout blocked by local changes threw with JGit's
     * conflicting paths flattened into the message, so the one actionable fact - which
     * files stand in the way - had to be recovered by splitting a sentence. And a
     * checkout rewrites the working tree of the whole repository, which routinely holds
     * more than one project, while only the named project was refreshed.
     */
    public GitCheckoutResponse checkoutBranch(String projectName, String branchName)
    {
        Repository repository = getRepository(projectName);
        Map<String, String> projects = mappedProjects(repository);
        String previousBranch = currentBranch(repository);

        ReentrantLock lock = getRepositoryLock(repository);
        lock.lock();
        try (Git git = new Git(repository))
        {
            git.checkout().setName(branchName).call();
        }
        catch (CheckoutConflictException e)
        {
            List<GitFileChange> blocking = new ArrayList<>();
            for (String path : e.getConflictingPaths())
            {
                blocking.add(GitStatusResponse.locate(path, ChangeType.CONFLICTING, projects));
            }
            return GitCheckoutResponse.blocked(projectName, branchName, previousBranch, headSha(repository), blocking,
                    Diagnostic.fatal(DiagnosticCode.CHECKOUT_CONFLICT,
                            "Local changes to " + blocking.size() + " file(s) would be overwritten by checking out '"
                                    + branchName + "'. Commit, stash or reset them first."));
        }
        catch (Exception e)
        {
            throw new RuntimeException("Failed to checkout branch: " + e.getMessage(), e);
        }
        finally
        {
            lock.unlock();
        }

        List<String> refreshed = refreshMappedProjects(repository);
        return GitCheckoutResponse.switched(projectName, branchName, previousBranch, currentBranch(repository),
                headSha(repository), refreshed);
    }

    /**
     * Resets index entries to HEAD, and reports what actually left the staged set.
     * <p>
     * As with {@link #addFiles}, echoing the caller's own pathspec back confirmed an
     * unstage that a non-matching pattern never performed.
     */
    public GitStageResponse resetFiles(String projectName, String filePattern)
    {
        Repository repository = getRepository(projectName);
        List<String> pathspecs = resolvePathspecs(projectName, filePattern);
        ReentrantLock lock = getRepositoryLock(repository);
        lock.lock();

        List<GitFileChange> unstaged;
        try (Git git = new Git(repository))
        {
            IndexSnapshot before = indexSnapshot(repository);
            ResetCommand command = git.reset();
            if (!pathspecs.contains(""))
            {
                // Without paths a mixed reset covers the whole index, which is what "."
                // means for a project at the repository root.
                pathspecs.forEach(command::addPath);
            }
            command.call();
            unstaged = indexDelta(before, indexSnapshot(repository), mappedProjects(repository));
        }
        catch (Exception e)
        {
            throw new RuntimeException("Failed to reset files: " + e.getMessage(), e);
        }
        finally
        {
            lock.unlock();
        }
        refreshProject(projectName);
        return GitStageResponse.of(projectName, StageOperation.UNSTAGE, filePattern, unstaged);
    }

    /**
     * Stashes the working tree.
     * <p>
     * The stash commit - the only durable handle on the work just taken off the working
     * tree - used to be returned inside a sentence, and having nothing to stash was
     * returned as another sentence in place of a result, while the sibling
     * {@link #stashList} already reported an empty stash as a count.
     */
    public GitStashResponse stash(String projectName, String message)
    {
        Repository repository = getRepository(projectName);
        ReentrantLock lock = getRepositoryLock(repository);
        lock.lock();

        GitStashResponse response;
        try (Git git = new Git(repository))
        {
            var cmd = git.stashCreate();
            if (message != null && !message.isEmpty())
            {
                cmd.setWorkingDirectoryMessage(message);
            }
            RevCommit stashRef = cmd.call();
            int totalStashes = git.stashList().call().size();

            response = stashRef == null
                    ? GitStashResponse.nothingToStash(projectName, totalStashes)
                    : GitStashResponse.stashed(projectName,
                            new GitStash(0, "stash@{0}", stashRef.getName(), stashRef.getShortMessage()),
                            totalStashes);
        }
        catch (Exception e)
        {
            throw new RuntimeException("Failed to stash: " + e.getMessage(), e);
        }
        finally
        {
            lock.unlock();
        }
        refreshMappedProjects(repository);
        return response;
    }

    /**
     * Applies the most recent stash entry and, if that succeeded, removes it.
     * <p>
     * "Applied and dropped stash." was a lie in the one case a caller must notice: a
     * stash that does not apply cleanly throws out of the apply, so the drop never runs
     * - the entry is still there, the working tree carries conflict markers, and none of
     * that reached the caller. Applying and dropping are two facts and are reported as
     * two.
     */
    public GitStashPopResponse stashPop(String projectName)
    {
        Repository repository = getRepository(projectName);
        Map<String, String> projects = mappedProjects(repository);
        ReentrantLock lock = getRepositoryLock(repository);
        lock.lock();

        GitStashPopResponse response;
        try (Git git = new Git(repository))
        {
            List<RevCommit> stashes = new ArrayList<>(git.stashList().call());
            if (stashes.isEmpty())
            {
                // An empty stash is a result, exactly as it is for gitStashList.
                return GitStashPopResponse.nothingToApply(projectName);
            }
            RevCommit top = stashes.get(0);

            try
            {
                git.stashApply().call();
                git.stashDrop().call();
                response = GitStashPopResponse.applied(projectName, "stash@{0}", top.getName(), top.getShortMessage());
            }
            catch (StashApplyFailureException e)
            {
                List<GitFileChange> conflicting = new ArrayList<>();
                for (String path : git.status().call().getConflicting())
                {
                    conflicting.add(GitStatusResponse.locate(path, ChangeType.CONFLICTING, projects));
                }
                response = GitStashPopResponse.conflicted(projectName, "stash@{0}", top.getName(),
                        top.getShortMessage(), conflicting,
                        Diagnostic.fatal(DiagnosticCode.MERGE_CONFLICT,
                                "The stash did not apply cleanly, so it was kept as stash@{0} and the working tree"
                                        + " holds conflict markers. Resolve the conflicting files, then drop it."));
            }
        }
        catch (Exception e)
        {
            throw new RuntimeException("Failed to pop stash: " + e.getMessage(), e);
        }
        finally
        {
            lock.unlock();
        }

        refreshMappedProjects(repository);
        return response;
    }

    /**
     * The stash entries, most recent first.
     * <p>
     * An empty stash is a result: the response says so with a count of zero, so a caller
     * never has to tell an empty stash from a failed call by reading a sentence.
     */
    public GitStashListResponse stashList(String projectName)
    {
        Repository repository = getRepository(projectName);
        try (Git git = new Git(repository))
        {
            return GitStashListResponse.from(projectName, git.stashList().call());
        }
        catch (Exception e)
        {
            throw new RuntimeException("Failed to list stashes: " + e.getMessage(), e);
        }
    }

    // -------------------------------------------------------------------------
    // Pathspecs
    // -------------------------------------------------------------------------

    /**
     * Project-relative pathspecs, as every other tool takes them, translated to the
     * repository-relative form Git needs.
     * <p>
     * {@code gitAdd} and {@code gitReset} used to hand the caller's pattern to JGit
     * untouched, which reads it from the repository root. For the common layout of
     * several projects under one repository, "src/Foo.java" of a project living in
     * {@code plugins/x} therefore matched nothing - silently, since Git does not fail on
     * an unmatched pathspec - while {@code gitStatus} reported the very same file by that
     * project-relative path. Comma-separated entries are accepted, and an entry of
     * {@code "."} means the project; the empty string in the result stands for the whole
     * repository, which is what "." is for a project sitting at the repository root.
     */
    private List<String> resolvePathspecs(String projectName, String filePattern)
    {
        String prefix = projectPrefix(projectName);
        List<String> pathspecs = new ArrayList<>();
        for (String raw : (filePattern == null ? "" : filePattern).split(","))
        {
            String path = raw.trim().replace('\\', '/');
            if (path.isEmpty() || path.equals(".") || path.equals("./"))
            {
                pathspecs.add(prefix);
                continue;
            }
            Path normalizedPath = Path.of(path).normalize();
            String normalized = normalizedPath.toString().replace('\\', '/');
            if (normalizedPath.isAbsolute() || normalized.equals("..") || normalized.startsWith("../")
                    || path.matches("^[A-Za-z]:.*"))
            {
                throw new IllegalArgumentException("Path must be relative to the Eclipse project: " + raw.trim());
            }
            if (normalized.isEmpty())
            {
                pathspecs.add(prefix);
                continue;
            }
            pathspecs.add(prefix.isBlank() ? normalized : prefix + "/" + normalized);
        }
        return pathspecs;
    }

    /** Whether a repository path falls under one of the resolved pathspecs. */
    private static boolean matchesPathspec(String repoPath, List<String> pathspecs)
    {
        for (String pathspec : pathspecs)
        {
            if (pathspec.isEmpty() || repoPath.equals(pathspec) || repoPath.startsWith(pathspec + "/"))
            {
                return true;
            }
        }
        return false;
    }

    /** A revision expression resolved to an object, or an explanation of why not. */
    private static ObjectId resolveRevision(Repository repository, String revision) throws IOException
    {
        if (revision == null || revision.isBlank())
        {
            throw new IllegalArgumentException("A revision is required.");
        }
        ObjectId objectId = repository.resolve(revision);
        if (objectId == null || !repository.getObjectDatabase().has(objectId))
        {
            throw new IllegalArgumentException("Git revision could not be resolved: " + revision);
        }
        return objectId;
    }

    private static List<GitFileChange> locateAll(Collection<String> repoPaths, ChangeType changeType,
            Map<String, String> projects)
    {
        List<GitFileChange> located = new ArrayList<>();
        if (repoPaths != null)
        {
            for (String path : new TreeSet<>(repoPaths))
            {
                located.add(GitStatusResponse.locate(path, changeType, projects));
            }
        }
        return located;
    }

    private List<GitFileChange> conflictingFiles(Git git, Map<String, String> projects) throws Exception
    {
        return locateAll(git.status().call().getConflicting(), ChangeType.CONFLICTING, projects);
    }

    private static GitCommit commitOf(Repository repository, ObjectId objectId) throws IOException
    {
        if (objectId == null)
        {
            return null;
        }
        try (RevWalk walk = new RevWalk(repository))
        {
            return GitLogResponse.toCommit(walk.parseCommit(objectId));
        }
    }

    /** The commits reachable from {@code newHead} but not from {@code previousHead}, oldest first. */
    private static List<GitCommit> commitsBetween(Git git, Repository repository, String previousHead, String newHead)
            throws Exception
    {
        if (newHead == null || newHead.equals(previousHead))
        {
            return List.of();
        }
        LogCommand log = git.log();
        if (previousHead == null)
        {
            log.add(ObjectId.fromString(newHead));
        }
        else
        {
            log.addRange(ObjectId.fromString(previousHead), ObjectId.fromString(newHead));
        }
        List<GitCommit> commits = new ArrayList<>();
        for (RevCommit commit : log.call())
        {
            commits.add(GitLogResponse.toCommit(commit));
        }
        Collections.reverse(commits);
        return commits;
    }

    // -------------------------------------------------------------------------
    // Merging, rebasing and replaying commits
    // -------------------------------------------------------------------------

    /**
     * Merges a branch, tag or commit into the checked-out branch.
     * <p>
     * JGit answers with a status and the paths involved, and each status needs a
     * different next step from the caller - commit the resolved conflicts, clear the
     * blocking local changes, commit the squashed result. They are passed on as a
     * status with the files resolved to projects, rather than collapsed into "merged" or
     * thrown.
     *
     * @param fastForwardMode FF, NO_FF or FF_ONLY; FF when null
     * @param squash whether to leave the merged result staged instead of committing it
     */
    public GitMergeResponse merge(String projectName, String ref, String fastForwardMode, boolean squash, String message)
    {
        Objects.requireNonNull(ref, "ref");
        FastForwardMode mode = fastForwardMode == null || fastForwardMode.isBlank()
                ? FastForwardMode.FF
                : FastForwardMode.valueOf(fastForwardMode.trim().toUpperCase());

        Repository repository = getRepository(projectName);
        Map<String, String> projects = mappedProjects(repository);
        ReentrantLock lock = getRepositoryLock(repository);
        lock.lock();

        String previousHead;
        MergeResult result;
        List<GitFileChange> conflicting = List.of();
        try (Git git = new Git(repository))
        {
            ObjectId target = repository.resolve(ref);
            if (target == null || !repository.getObjectDatabase().has(target))
            {
                return GitMergeResponse.of(projectName, MergeStatus.FAILED, ref, headSha(repository), headSha(repository),
                        null, List.of(), List.of(), List.of(),
                        List.of(Diagnostic.fatal(DiagnosticCode.REVISION_NOT_FOUND,
                                "Git revision could not be resolved: " + ref)));
            }
            previousHead = headSha(repository);

            MergeCommand command = git.merge().setFastForward(mode).setSquash(squash);
            Ref namedRef = repository.findRef(ref);
            if (namedRef != null)
            {
                command.include(namedRef);
            }
            else
            {
                command.include(ref, target);
            }
            if (message != null && !message.isBlank())
            {
                command.setMessage(message);
            }
            result = command.call();
            if (result.getMergeStatus() == MergeResult.MergeStatus.CONFLICTING)
            {
                conflicting = conflictingFiles(git, projects);
            }
        }
        catch (WrongRepositoryStateException e)
        {
            return GitMergeResponse.of(projectName, MergeStatus.FAILED, ref, headSha(repository), headSha(repository),
                    null, List.of(), List.of(), List.of(),
                    List.of(Diagnostic.fatal(DiagnosticCode.WRONG_REPOSITORY_STATE, e.getMessage())));
        }
        catch (Exception e)
        {
            throw new RuntimeException("Failed to merge: " + e.getMessage(), e);
        }
        finally
        {
            lock.unlock();
        }

        List<String> refreshed = refreshMappedProjects(repository);
        String newHead = headSha(repository);
        try
        {
            return switch (result.getMergeStatus())
            {
                case FAST_FORWARD -> GitMergeResponse.of(projectName, MergeStatus.FAST_FORWARD, ref, previousHead, newHead,
                        null, List.of(), List.of(), refreshed, List.of());
                case ALREADY_UP_TO_DATE -> GitMergeResponse.of(projectName, MergeStatus.ALREADY_UP_TO_DATE, ref,
                        previousHead, newHead, null, List.of(), List.of(), refreshed, List.of());
                case MERGED -> GitMergeResponse.of(projectName, MergeStatus.MERGED, ref, previousHead, newHead,
                        commitOf(repository, result.getNewHead()), List.of(), List.of(), refreshed, List.of());
                case FAST_FORWARD_SQUASHED, MERGED_SQUASHED, MERGED_SQUASHED_NOT_COMMITTED, MERGED_NOT_COMMITTED ->
                    GitMergeResponse.of(projectName, MergeStatus.MERGED_NOT_COMMITTED, ref, previousHead, newHead, null,
                            List.of(), List.of(), refreshed, List.of());
                case CONFLICTING -> GitMergeResponse.of(projectName, MergeStatus.CONFLICTED, ref, previousHead, newHead,
                        null, conflicting, List.of(), refreshed,
                        List.of(Diagnostic.fatal(DiagnosticCode.MERGE_CONFLICT,
                                "Merging '" + ref + "' left conflict markers in " + conflicting.size()
                                        + " file(s). Resolve them, gitAdd them and gitCommit to finish the merge,"
                                        + " or gitResetToRevision HEAD with mode HARD to abandon it.")));
                case CHECKOUT_CONFLICT ->
                {
                    List<GitFileChange> blocking = locateAll(result.getCheckoutConflicts(), ChangeType.MODIFIED, projects);
                    yield GitMergeResponse.of(projectName, MergeStatus.BLOCKED, ref, previousHead, newHead, null,
                            List.of(), blocking, refreshed,
                            List.of(Diagnostic.fatal(DiagnosticCode.CHECKOUT_CONFLICT,
                                    "Local changes to " + blocking.size() + " file(s) would be overwritten by merging '"
                                            + ref + "'. Commit, stash or discard them first.")));
                }
                case FAILED ->
                {
                    Map<String, ?> failing = result.getFailingPaths();
                    List<GitFileChange> blocking = locateAll(failing == null ? List.of() : failing.keySet(),
                            ChangeType.MODIFIED, projects);
                    yield GitMergeResponse.of(projectName, MergeStatus.BLOCKED, ref, previousHead, newHead, null,
                            List.of(), blocking, refreshed,
                            List.of(Diagnostic.fatal(DiagnosticCode.CHECKOUT_CONFLICT,
                                    "Merging '" + ref + "' was refused because of " + blocking.size()
                                            + " dirty file(s): " + failing + ". Commit, stash or discard them first.")));
                }
                default -> GitMergeResponse.of(projectName, MergeStatus.FAILED, ref, previousHead, newHead, null,
                        List.of(), List.of(), refreshed,
                        List.of(Diagnostic.fatal(DiagnosticCode.INTERNAL_ERROR,
                                "JGit reported merge status " + result.getMergeStatus() + ".")));
            };
        }
        catch (IOException e)
        {
            throw new RuntimeException("Failed to describe the merge: " + e.getMessage(), e);
        }
    }

    /**
     * Rebases the checked-out branch onto an upstream, or drives a rebase already in
     * progress on.
     * <p>
     * A rebase pauses on conflicts and stays paused across calls, so every call reports
     * where the repository now is - which commit it stopped on, which files conflict -
     * and the caller answers with the next {@link RebaseOperation}.
     *
     * @param upstream the ref to rebase onto; required for BEGIN, ignored otherwise
     * @param operationName BEGIN (default), CONTINUE, SKIP or ABORT
     */
    public GitRebaseResponse rebase(String projectName, String upstream, String operationName)
    {
        RebaseOperation operation = operationName == null || operationName.isBlank()
                ? RebaseOperation.BEGIN
                : RebaseOperation.valueOf(operationName.trim().toUpperCase());
        if (operation == RebaseOperation.BEGIN && (upstream == null || upstream.isBlank()))
        {
            throw new IllegalArgumentException("An upstream is required to begin a rebase.");
        }
        String onto = operation == RebaseOperation.BEGIN ? upstream.trim() : null;

        Repository repository = getRepository(projectName);
        Map<String, String> projects = mappedProjects(repository);
        ReentrantLock lock = getRepositoryLock(repository);
        lock.lock();

        RebaseResult result;
        Diagnostic failure = null;
        List<GitFileChange> conflicting = List.of();
        try (Git git = new Git(repository))
        {
            RebaseCommand command = git.rebase();
            if (operation == RebaseOperation.BEGIN)
            {
                ObjectId ontoId = repository.resolve(onto);
                if (ontoId == null || !repository.getObjectDatabase().has(ontoId))
                {
                    return GitRebaseResponse.of(projectName, operation, RebaseStatus.FAILED, onto,
                            currentBranch(repository), headSha(repository), null, List.of(), List.of(), List.of(),
                            List.of(Diagnostic.fatal(DiagnosticCode.REVISION_NOT_FOUND,
                                    "Git revision could not be resolved: " + onto)));
                }
                command.setUpstream(onto);
            }
            else
            {
                command.setOperation(RebaseCommand.Operation.valueOf(operation.name()));
            }
            result = command.call();
            if (result.getStatus() == RebaseResult.Status.STOPPED)
            {
                conflicting = conflictingFiles(git, projects);
            }
        }
        catch (WrongRepositoryStateException e)
        {
            result = null;
            failure = Diagnostic.fatal(DiagnosticCode.WRONG_REPOSITORY_STATE,
                    operation == RebaseOperation.BEGIN
                            ? "The repository is mid-operation, so a rebase cannot start: " + e.getMessage()
                            : "No rebase is in progress to " + operation.name().toLowerCase() + ": " + e.getMessage());
        }
        catch (RefNotFoundException e)
        {
            result = null;
            failure = Diagnostic.fatal(DiagnosticCode.REVISION_NOT_FOUND, e.getMessage());
        }
        catch (Exception e)
        {
            throw new RuntimeException("Failed to rebase: " + e.getMessage(), e);
        }
        finally
        {
            lock.unlock();
        }

        List<String> refreshed = refreshMappedProjects(repository);
        String branch = currentBranch(repository);
        String head = headSha(repository);
        if (failure != null)
        {
            return GitRebaseResponse.of(projectName, operation, RebaseStatus.FAILED, onto, branch, head, null, List.of(),
                    List.of(), refreshed, List.of(failure));
        }

        return switch (result.getStatus())
        {
            case OK -> GitRebaseResponse.of(projectName, operation, RebaseStatus.OK, onto, branch, head, null, List.of(),
                    List.of(), refreshed, List.of());
            case UP_TO_DATE -> GitRebaseResponse.of(projectName, operation, RebaseStatus.UP_TO_DATE, onto, branch, head,
                    null, List.of(), List.of(), refreshed, List.of());
            case FAST_FORWARD -> GitRebaseResponse.of(projectName, operation, RebaseStatus.FAST_FORWARD, onto, branch,
                    head, null, List.of(), List.of(), refreshed, List.of());
            case ABORTED -> GitRebaseResponse.of(projectName, operation, RebaseStatus.ABORTED, onto, branch, head, null,
                    List.of(), List.of(), refreshed, List.of());
            case NOTHING_TO_COMMIT -> GitRebaseResponse.of(projectName, operation, RebaseStatus.NOTHING_TO_COMMIT, onto,
                    branch, head, null, List.of(), List.of(), refreshed, List.of());
            case STOPPED ->
            {
                GitCommit stoppedAt = result.getCurrentCommit() == null
                        ? null
                        : GitLogResponse.toCommit(result.getCurrentCommit());
                yield GitRebaseResponse.of(projectName, operation, RebaseStatus.STOPPED, onto, branch, head, stoppedAt,
                        conflicting, List.of(), refreshed,
                        List.of(Diagnostic.fatal(DiagnosticCode.MERGE_CONFLICT,
                                "Replaying " + (stoppedAt == null ? "a commit" : stoppedAt.shortSha()) + " left "
                                        + conflicting.size() + " conflicting file(s). Resolve and gitAdd them, then call"
                                        + " gitRebase with operation CONTINUE - or SKIP that commit, or ABORT.")));
            }
            case UNCOMMITTED_CHANGES ->
            {
                List<GitFileChange> blocking = locateAll(result.getUncommittedChanges(), ChangeType.MODIFIED, projects);
                yield GitRebaseResponse.of(projectName, operation, RebaseStatus.UNCOMMITTED_CHANGES, onto, branch, head,
                        null, List.of(), blocking, refreshed,
                        List.of(Diagnostic.fatal(DiagnosticCode.UNCOMMITTED_CHANGES,
                                blocking.size() + " uncommitted change(s) stop the rebase. Commit or stash them first.")));
            }
            case CONFLICTS ->
            {
                List<GitFileChange> blocking = locateAll(result.getConflicts(), ChangeType.MODIFIED, projects);
                yield GitRebaseResponse.of(projectName, operation, RebaseStatus.BLOCKED, onto, branch, head, null,
                        List.of(), blocking, refreshed,
                        List.of(Diagnostic.fatal(DiagnosticCode.CHECKOUT_CONFLICT,
                                "Local changes to " + blocking.size() + " file(s) would be overwritten. Commit, stash or"
                                        + " discard them first.")));
            }
            case FAILED ->
            {
                Map<String, ?> failing = result.getFailingPaths();
                List<GitFileChange> blocking = locateAll(failing == null ? List.of() : failing.keySet(),
                        ChangeType.MODIFIED, projects);
                yield GitRebaseResponse.of(projectName, operation, RebaseStatus.FAILED, onto, branch, head, null,
                        List.of(), blocking, refreshed,
                        List.of(Diagnostic.fatal(DiagnosticCode.CHECKOUT_CONFLICT,
                                "The rebase failed on " + blocking.size() + " file(s): " + failing + ".")));
            }
            default -> GitRebaseResponse.of(projectName, operation, RebaseStatus.FAILED, onto, branch, head, null,
                    List.of(), List.of(), refreshed,
                    List.of(Diagnostic.fatal(DiagnosticCode.INTERNAL_ERROR,
                            "JGit reported rebase status " + result.getStatus() + ", which this tool does not drive.")));
        };
    }

    /**
     * Applies the changes of existing commits to the checked-out branch as new commits.
     */
    public GitApplyCommitsResponse cherryPick(String projectName, List<String> commits)
    {
        return applyCommits(projectName, ApplyOperation.CHERRY_PICK, commits);
    }

    /**
     * Undoes the changes of existing commits with new commits.
     */
    public GitApplyCommitsResponse revert(String projectName, List<String> commits)
    {
        return applyCommits(projectName, ApplyOperation.REVERT, commits);
    }

    private GitApplyCommitsResponse applyCommits(String projectName, ApplyOperation operation, List<String> commits)
    {
        if (commits == null || commits.isEmpty())
        {
            throw new IllegalArgumentException("At least one commit is required.");
        }
        List<String> requested = commits.stream().map(String::trim).filter(c -> !c.isEmpty()).toList();

        Repository repository = getRepository(projectName);
        Map<String, String> projects = mappedProjects(repository);
        ReentrantLock lock = getRepositoryLock(repository);
        lock.lock();

        String previousHead;
        ApplyStatus status;
        List<GitFileChange> conflicting = List.of();
        List<GitFileChange> blocking = List.of();
        Diagnostic failure = null;
        List<GitCommit> created = List.of();
        try (Git git = new Git(repository))
        {
            previousHead = headSha(repository);
            List<ObjectId> ids = new ArrayList<>();
            for (String commit : requested)
            {
                ObjectId id = repository.resolve(commit);
                if (id == null || !repository.getObjectDatabase().has(id))
                {
                    return GitApplyCommitsResponse.of(projectName, operation, ApplyStatus.FAILED, requested, previousHead,
                            previousHead, List.of(), List.of(), List.of(), List.of(),
                            List.of(Diagnostic.fatal(DiagnosticCode.REVISION_NOT_FOUND,
                                    "Git revision could not be resolved: " + commit)));
                }
                ids.add(id);
            }

            Map<String, ?> failingPaths = null;
            if (operation == ApplyOperation.CHERRY_PICK)
            {
                CherryPickCommand command = git.cherryPick();
                ids.forEach(command::include);
                CherryPickResult result = command.call();
                status = switch (result.getStatus())
                {
                    case OK -> ApplyStatus.APPLIED;
                    case CONFLICTING -> ApplyStatus.CONFLICTED;
                    default -> ApplyStatus.BLOCKED;
                };
                failingPaths = result.getFailingPaths();
            }
            else
            {
                RevertCommand command = git.revert();
                ids.forEach(command::include);
                RevCommit newHead = command.call();
                if (newHead != null)
                {
                    status = ApplyStatus.APPLIED;
                }
                else if (!command.getUnmergedPaths().isEmpty())
                {
                    status = ApplyStatus.CONFLICTED;
                }
                else
                {
                    status = ApplyStatus.BLOCKED;
                    failingPaths = command.getFailingResult() == null ? null : command.getFailingResult().getFailingPaths();
                }
            }

            if (status == ApplyStatus.CONFLICTED)
            {
                conflicting = conflictingFiles(git, projects);
                failure = Diagnostic.fatal(DiagnosticCode.MERGE_CONFLICT,
                        "A commit did not apply cleanly; " + conflicting.size() + " file(s) hold conflict markers."
                                + " Resolve and gitAdd them and gitCommit, or gitResetToRevision HEAD with mode HARD"
                                + " to abandon the operation.");
            }
            else if (status == ApplyStatus.BLOCKED)
            {
                blocking = locateAll(failingPaths == null ? List.of() : failingPaths.keySet(), ChangeType.MODIFIED, projects);
                failure = Diagnostic.fatal(DiagnosticCode.CHECKOUT_CONFLICT,
                        "Local changes to " + blocking.size() + " file(s) block the operation: " + failingPaths
                                + ". Commit, stash or discard them first.");
            }
            created = commitsBetween(git, repository, previousHead, headSha(repository));
        }
        catch (WrongRepositoryStateException e)
        {
            return GitApplyCommitsResponse.of(projectName, operation, ApplyStatus.FAILED, requested, headSha(repository),
                    headSha(repository), List.of(), List.of(), List.of(), List.of(),
                    List.of(Diagnostic.fatal(DiagnosticCode.WRONG_REPOSITORY_STATE, e.getMessage())));
        }
        catch (Exception e)
        {
            throw new RuntimeException("Failed to " + (operation == ApplyOperation.CHERRY_PICK ? "cherry-pick: " : "revert: ")
                    + e.getMessage(), e);
        }
        finally
        {
            lock.unlock();
        }

        List<String> refreshed = refreshMappedProjects(repository);
        return GitApplyCommitsResponse.of(projectName, operation, status, requested, previousHead, headSha(repository),
                created, conflicting, blocking, refreshed, failure == null ? List.of() : List.of(failure));
    }

    // -------------------------------------------------------------------------
    // Tags and single commits
    // -------------------------------------------------------------------------

    /**
     * Creates a tag - annotated when a message is given, lightweight otherwise.
     *
     * @param revision what to tag; HEAD when null
     */
    public GitTagResponse createTag(String projectName, String tagName, String message, String revision)
    {
        Objects.requireNonNull(tagName, "tagName");
        Repository repository = getRepository(projectName);
        try (Git git = new Git(repository); RevWalk walk = new RevWalk(repository))
        {
            ObjectId target = resolveRevision(repository, revision == null || revision.isBlank() ? Constants.HEAD : revision);
            RevObject object = walk.parseAny(target);
            boolean annotated = message != null && !message.isBlank();

            TagCommand command = git.tag().setName(tagName).setObjectId(object).setAnnotated(annotated);
            if (annotated)
            {
                command.setMessage(message);
            }
            Ref ref = command.call();
            return GitTagResponse.created(projectName, describeTag(repository, ref));
        }
        catch (IllegalArgumentException e)
        {
            throw e;
        }
        catch (Exception e)
        {
            throw new RuntimeException("Failed to create tag: " + e.getMessage(), e);
        }
    }

    public GitTagListResponse listTags(String projectName)
    {
        Repository repository = getRepository(projectName);
        try (Git git = new Git(repository))
        {
            List<GitTag> tags = new ArrayList<>();
            for (Ref ref : git.tagList().call())
            {
                tags.add(describeTag(repository, ref));
            }
            return GitTagListResponse.of(projectName, tags);
        }
        catch (Exception e)
        {
            throw new RuntimeException("Failed to list tags: " + e.getMessage(), e);
        }
    }

    public GitTagResponse deleteTag(String projectName, String tagName)
    {
        Objects.requireNonNull(tagName, "tagName");
        Repository repository = getRepository(projectName);
        try (Git git = new Git(repository))
        {
            Ref ref = repository.findRef(Constants.R_TAGS + tagName);
            if (ref == null)
            {
                throw new IllegalArgumentException("No tag named '" + tagName + "'.");
            }
            GitTag tag = describeTag(repository, ref);
            git.tagDelete().setTags(tagName).call();
            return GitTagResponse.deleted(projectName, tag);
        }
        catch (IllegalArgumentException e)
        {
            throw e;
        }
        catch (Exception e)
        {
            throw new RuntimeException("Failed to delete tag: " + e.getMessage(), e);
        }
    }

    private static GitTag describeTag(Repository repository, Ref ref) throws IOException
    {
        Ref peeled = repository.getRefDatabase().peel(ref);
        ObjectId objectId = ref.getObjectId();
        ObjectId peeledId = peeled.getPeeledObjectId();
        boolean annotated = peeledId != null;

        String message = null;
        if (annotated)
        {
            try (RevWalk walk = new RevWalk(repository))
            {
                RevObject object = walk.parseAny(objectId);
                if (object instanceof RevTag tag)
                {
                    message = tag.getFullMessage() == null ? null : tag.getFullMessage().strip();
                }
            }
        }
        return new GitTag(Repository.shortenRefName(ref.getName()), ref.getName(),
                objectId == null ? null : objectId.getName(),
                annotated ? peeledId.getName() : (objectId == null ? null : objectId.getName()),
                annotated, message);
    }

    /**
     * One commit and the change it introduced, against its first parent - or against
     * the empty tree for a root commit, as {@code git show} does.
     */
    public GitShowResponse show(String projectName, String revision, String pathFilter)
    {
        Repository repository = getRepository(projectName);
        Map<String, String> projects = mappedProjects(repository);
        List<String> repositoryPaths = resolveDiffPaths(projectName, pathFilter);
        String requested = revision == null || revision.isBlank() ? Constants.HEAD : revision;

        try (RevWalk walk = new RevWalk(repository);
             var out = new ByteArrayOutputStream();
             var formatter = new DiffFormatter(out))
        {
            RevCommit commit = walk.parseCommit(resolveRevision(repository, requested));
            List<String> parents = new ArrayList<>();
            for (RevCommit parent : commit.getParents())
            {
                parents.add(parent.getName());
            }

            AbstractTreeIterator oldTree = commit.getParentCount() == 0
                    ? new EmptyTreeIterator()
                    : prepareTreeParser(repository, commit.getParent(0).getId());
            AbstractTreeIterator newTree = prepareTreeParser(repository, commit.getId());

            formatter.setRepository(repository);
            formatter.setDetectRenames(true);
            if (!repositoryPaths.isEmpty())
            {
                formatter.setPathFilter(PathFilterGroup.createFromStrings(repositoryPaths));
            }

            List<DiffEntry> diffs = formatter.scan(oldTree, newTree);
            List<GitDiffResponse.GitFileDiff> files = new ArrayList<>();
            for (DiffEntry diff : diffs)
            {
                files.add(GitDiffResponse.file(formatter.toFileHeader(diff), projects));
            }
            formatter.format(diffs);

            return GitShowResponse.of(projectName, requested, GitLogResponse.toCommit(commit), parents, files,
                    out.toString(StandardCharsets.UTF_8));
        }
        catch (IllegalArgumentException e)
        {
            throw e;
        }
        catch (Exception e)
        {
            throw new RuntimeException("Failed to show commit: " + e.getMessage(), e);
        }
    }

    // -------------------------------------------------------------------------
    // Remotes
    // -------------------------------------------------------------------------

    public GitRemoteListResponse listRemotes(String projectName)
    {
        Repository repository = getRepository(projectName);
        try (Git git = new Git(repository))
        {
            List<GitRemote> remotes = new ArrayList<>();
            for (RemoteConfig config : git.remoteList().call())
            {
                String fetchUrl = config.getURIs().isEmpty() ? null : config.getURIs().get(0).toString();
                String pushUrl = config.getPushURIs().isEmpty() ? fetchUrl : config.getPushURIs().get(0).toString();
                remotes.add(new GitRemote(config.getName(), fetchUrl, pushUrl));
            }
            return GitRemoteListResponse.of(projectName, remotes);
        }
        catch (Exception e)
        {
            throw new RuntimeException("Failed to list remotes: " + e.getMessage(), e);
        }
    }

    /**
     * Fetches from a remote and reports every remote-tracking ref that moved.
     *
     * @param remote the remote; {@code origin} when null
     * @param prune whether to drop remote-tracking refs the remote no longer has
     */
    public GitFetchResponse fetch(String projectName, String remote, boolean prune)
    {
        String remoteName = remote == null || remote.isBlank() ? Constants.DEFAULT_REMOTE_NAME : remote.trim();
        Repository repository = getRepository(projectName);
        try (Git git = new Git(repository))
        {
            FetchCommand command = git.fetch().setRemote(remoteName).setRemoveDeletedRefs(prune);
            CredentialsProvider credentials = credentialsFor(repository, remoteName);
            if (credentials != null)
            {
                command.setCredentialsProvider(credentials);
            }
            FetchResult result = command.call();
            return GitFetchResponse.of(projectName, remoteName, refUpdates(result), result.getMessages());
        }
        catch (InvalidRemoteException e)
        {
            return GitFetchResponse.failed(projectName, remoteName, Diagnostic.fatal(DiagnosticCode.REMOTE_OPERATION_FAILED,
                    "No remote named '" + remoteName + "': " + e.getMessage()));
        }
        catch (TransportException e)
        {
            return GitFetchResponse.failed(projectName, remoteName, Diagnostic.fatal(DiagnosticCode.REMOTE_OPERATION_FAILED,
                    "The remote '" + remoteName + "' could not be fetched from: " + e.getMessage()));
        }
        catch (Exception e)
        {
            throw new RuntimeException("Failed to fetch: " + e.getMessage(), e);
        }
    }

    /**
     * Fetches and then merges - or, with {@code rebase}, rebases onto - the upstream of
     * the checked-out branch.
     *
     * @param remote the remote; the branch's configured remote, else {@code origin},
     *            when null
     * @param remoteBranch the branch on the remote; the branch's configured upstream
     *            when null
     */
    public GitPullResponse pull(String projectName, String remote, String remoteBranch, boolean rebase)
    {
        Repository repository = getRepository(projectName);
        Map<String, String> projects = mappedProjects(repository);
        ReentrantLock lock = getRepositoryLock(repository);
        lock.lock();

        String branch = currentBranch(repository);
        StoredConfig config = repository.getConfig();
        String configuredRemote = config.getString(ConfigConstants.CONFIG_BRANCH_SECTION, branch, ConfigConstants.CONFIG_KEY_REMOTE);
        String configuredMerge = config.getString(ConfigConstants.CONFIG_BRANCH_SECTION, branch, ConfigConstants.CONFIG_KEY_MERGE);
        String remoteName = remote != null && !remote.isBlank() ? remote.trim()
                : configuredRemote != null ? configuredRemote : Constants.DEFAULT_REMOTE_NAME;
        String remoteBranchName = remoteBranch != null && !remoteBranch.isBlank() ? remoteBranch.trim()
                : configuredMerge != null ? Repository.shortenRefName(configuredMerge) : null;

        String previousHead = headSha(repository);
        PullResult result = null;
        Diagnostic failure = null;
        List<GitFileChange> conflicting = List.of();
        try (Git git = new Git(repository))
        {
            PullCommand command = git.pull().setRemote(remoteName).setRebase(rebase);
            if (remoteBranchName != null)
            {
                command.setRemoteBranchName(remoteBranchName);
            }
            CredentialsProvider credentials = credentialsFor(repository, remoteName);
            if (credentials != null)
            {
                command.setCredentialsProvider(credentials);
            }
            result = command.call();

            boolean stoppedOnConflicts = (result.getRebaseResult() != null
                    && result.getRebaseResult().getStatus() == RebaseResult.Status.STOPPED)
                    || (result.getMergeResult() != null
                            && result.getMergeResult().getMergeStatus() == MergeResult.MergeStatus.CONFLICTING);
            if (stoppedOnConflicts)
            {
                conflicting = conflictingFiles(git, projects);
            }
        }
        catch (InvalidRemoteException | TransportException e)
        {
            failure = Diagnostic.fatal(DiagnosticCode.REMOTE_OPERATION_FAILED,
                    "The remote '" + remoteName + "' could not be pulled from: " + e.getMessage());
        }
        catch (InvalidConfigurationException e)
        {
            failure = Diagnostic.fatal(DiagnosticCode.VALIDATION_ERROR,
                    "The branch '" + branch + "' has no upstream configured and no remoteBranch was given: "
                            + e.getMessage() + " Pass remoteBranch, or gitPush with setUpstream=true first.");
        }
        catch (WrongRepositoryStateException e)
        {
            failure = Diagnostic.fatal(DiagnosticCode.WRONG_REPOSITORY_STATE, e.getMessage());
        }
        catch (Exception e)
        {
            throw new RuntimeException("Failed to pull: " + e.getMessage(), e);
        }
        finally
        {
            lock.unlock();
        }

        List<String> refreshed = refreshMappedProjects(repository);
        String newHead = headSha(repository);
        if (failure != null)
        {
            return GitPullResponse.of(projectName, remoteName, remoteBranchName, rebase, PullStatus.FAILED, 0,
                    previousHead, newHead, List.of(), List.of(), refreshed, List.of(failure));
        }

        int fetched = result.getFetchResult() == null ? 0 : refUpdates(result.getFetchResult()).size();
        PullStatus status;
        List<GitFileChange> blocking = List.of();
        Diagnostic diagnostic = null;
        if (result.getRebaseResult() != null)
        {
            RebaseResult rebaseResult = result.getRebaseResult();
            switch (rebaseResult.getStatus())
            {
                case OK -> status = PullStatus.REBASED;
                case UP_TO_DATE -> status = PullStatus.ALREADY_UP_TO_DATE;
                case FAST_FORWARD -> status = PullStatus.FAST_FORWARD;
                case STOPPED ->
                {
                    status = PullStatus.CONFLICTED;
                    diagnostic = Diagnostic.fatal(DiagnosticCode.MERGE_CONFLICT,
                            "The rebase stopped with " + conflicting.size() + " conflicting file(s). Resolve and gitAdd"
                                    + " them, then gitRebase with operation CONTINUE - or ABORT.");
                }
                case UNCOMMITTED_CHANGES ->
                {
                    status = PullStatus.BLOCKED;
                    blocking = locateAll(rebaseResult.getUncommittedChanges(), ChangeType.MODIFIED, projects);
                    diagnostic = Diagnostic.fatal(DiagnosticCode.UNCOMMITTED_CHANGES,
                            blocking.size() + " uncommitted change(s) stop the rebase. Commit or stash them first.");
                }
                case CONFLICTS ->
                {
                    status = PullStatus.BLOCKED;
                    blocking = locateAll(rebaseResult.getConflicts(), ChangeType.MODIFIED, projects);
                    diagnostic = Diagnostic.fatal(DiagnosticCode.CHECKOUT_CONFLICT,
                            "Local changes to " + blocking.size() + " file(s) would be overwritten. Commit, stash or"
                                    + " discard them first.");
                }
                default ->
                {
                    status = PullStatus.FAILED;
                    diagnostic = Diagnostic.fatal(DiagnosticCode.INTERNAL_ERROR,
                            "JGit reported rebase status " + rebaseResult.getStatus() + ".");
                }
            }
        }
        else if (result.getMergeResult() != null)
        {
            MergeResult mergeResult = result.getMergeResult();
            switch (mergeResult.getMergeStatus())
            {
                case FAST_FORWARD -> status = PullStatus.FAST_FORWARD;
                case MERGED -> status = PullStatus.MERGED;
                case ALREADY_UP_TO_DATE -> status = PullStatus.ALREADY_UP_TO_DATE;
                case CONFLICTING ->
                {
                    status = PullStatus.CONFLICTED;
                    diagnostic = Diagnostic.fatal(DiagnosticCode.MERGE_CONFLICT,
                            "The merge left " + conflicting.size() + " conflicting file(s). Resolve and gitAdd them and"
                                    + " gitCommit, or gitResetToRevision HEAD with mode HARD to abandon it.");
                }
                case CHECKOUT_CONFLICT ->
                {
                    status = PullStatus.BLOCKED;
                    blocking = locateAll(mergeResult.getCheckoutConflicts(), ChangeType.MODIFIED, projects);
                    diagnostic = Diagnostic.fatal(DiagnosticCode.CHECKOUT_CONFLICT,
                            "Local changes to " + blocking.size() + " file(s) would be overwritten. Commit, stash or"
                                    + " discard them first.");
                }
                case FAILED ->
                {
                    status = PullStatus.BLOCKED;
                    Map<String, ?> failing = mergeResult.getFailingPaths();
                    blocking = locateAll(failing == null ? List.of() : failing.keySet(), ChangeType.MODIFIED, projects);
                    diagnostic = Diagnostic.fatal(DiagnosticCode.CHECKOUT_CONFLICT,
                            "The merge was refused because of " + blocking.size() + " dirty file(s): " + failing + ".");
                }
                default ->
                {
                    status = PullStatus.FAILED;
                    diagnostic = Diagnostic.fatal(DiagnosticCode.INTERNAL_ERROR,
                            "JGit reported merge status " + mergeResult.getMergeStatus() + ".");
                }
            }
        }
        else
        {
            status = PullStatus.ALREADY_UP_TO_DATE;
        }

        return GitPullResponse.of(projectName, remoteName, remoteBranchName, rebase, status, fetched, previousHead, newHead,
                conflicting, blocking, refreshed, diagnostic == null ? List.of() : List.of(diagnostic));
    }

    /**
     * Pushes a branch to a remote.
     *
     * @param remote the remote; {@code origin} when null
     * @param branch the local branch; the checked-out one when null
     * @param setUpstream whether to make the pushed branch the upstream of the local one
     *            afterwards, so later pulls and pushes need no arguments
     * @param force whether to overwrite the remote branch even when it has commits the
     *            local one does not
     */
    public GitPushResponse push(String projectName, String remote, String branch, boolean setUpstream, boolean force)
    {
        String remoteName = remote == null || remote.isBlank() ? Constants.DEFAULT_REMOTE_NAME : remote.trim();
        Repository repository = getRepository(projectName);
        try (Git git = new Git(repository))
        {
            String localBranch = branch == null || branch.isBlank() ? repository.getBranch() : branch.trim();
            if (localBranch == null || repository.findRef(Constants.R_HEADS + localBranch) == null)
            {
                throw new IllegalArgumentException("No local branch named '" + localBranch + "' to push.");
            }

            PushCommand command = git.push().setRemote(remoteName).setForce(force).add(Constants.R_HEADS + localBranch);
            CredentialsProvider credentials = credentialsFor(repository, remoteName);
            if (credentials != null)
            {
                command.setCredentialsProvider(credentials);
            }

            List<GitPushUpdate> updates = new ArrayList<>();
            boolean pushed = false;
            boolean rejected = false;
            boolean failed = false;
            for (PushResult result : command.call())
            {
                for (RemoteRefUpdate update : result.getRemoteUpdates())
                {
                    updates.add(new GitPushUpdate(update.getSrcRef(), update.getRemoteName(), update.getStatus().name(),
                            update.getMessage()));
                    switch (update.getStatus())
                    {
                        case OK -> pushed = true;
                        case UP_TO_DATE -> { }
                        case REJECTED_NONFASTFORWARD, REJECTED_NODELETE, REJECTED_REMOTE_CHANGED, REJECTED_OTHER_REASON ->
                            rejected = true;
                        default -> failed = true;
                    }
                }
            }

            PushStatus status = failed ? PushStatus.FAILED : rejected ? PushStatus.REJECTED
                    : pushed ? PushStatus.PUSHED : PushStatus.UP_TO_DATE;
            List<Diagnostic> diagnostics = new ArrayList<>();
            if (rejected)
            {
                diagnostics.add(Diagnostic.fatal(DiagnosticCode.PUSH_REJECTED,
                        "The remote refused the push - usually because it has commits '" + localBranch
                                + "' does not. gitPull (or gitFetch and gitRebase) first, or push with force=true to"
                                + " overwrite the remote branch."));
            }
            else if (failed)
            {
                diagnostics.add(Diagnostic.fatal(DiagnosticCode.REMOTE_OPERATION_FAILED,
                        "The push did not complete; see the per-ref status."));
            }

            if (setUpstream && (status == PushStatus.PUSHED || status == PushStatus.UP_TO_DATE))
            {
                StoredConfig config = repository.getConfig();
                config.setString(ConfigConstants.CONFIG_BRANCH_SECTION, localBranch, ConfigConstants.CONFIG_KEY_REMOTE, remoteName);
                config.setString(ConfigConstants.CONFIG_BRANCH_SECTION, localBranch, ConfigConstants.CONFIG_KEY_MERGE,
                        Constants.R_HEADS + localBranch);
                config.save();
            }
            return GitPushResponse.of(projectName, remoteName, status, updates, diagnostics);
        }
        catch (InvalidRemoteException e)
        {
            return GitPushResponse.of(projectName, remoteName, PushStatus.FAILED, List.of(),
                    List.of(Diagnostic.fatal(DiagnosticCode.REMOTE_OPERATION_FAILED,
                            "No remote named '" + remoteName + "': " + e.getMessage())));
        }
        catch (TransportException e)
        {
            return GitPushResponse.of(projectName, remoteName, PushStatus.FAILED, List.of(),
                    List.of(Diagnostic.fatal(DiagnosticCode.REMOTE_OPERATION_FAILED,
                            "The remote '" + remoteName + "' could not be pushed to: " + e.getMessage())));
        }
        catch (IllegalArgumentException e)
        {
            throw e;
        }
        catch (Exception e)
        {
            throw new RuntimeException("Failed to push: " + e.getMessage(), e);
        }
    }

    private static List<GitRefUpdate> refUpdates(FetchResult result)
    {
        List<GitRefUpdate> updates = new ArrayList<>();
        for (TrackingRefUpdate update : result.getTrackingRefUpdates())
        {
            if (update.getResult() == RefUpdate.Result.NO_CHANGE)
            {
                continue;
            }
            updates.add(new GitRefUpdate(update.getLocalName(), update.getRemoteName(), shaOrNull(update.getOldObjectId()),
                    shaOrNull(update.getNewObjectId()), update.getResult().name()));
        }
        return updates;
    }

    private static String shaOrNull(ObjectId objectId)
    {
        return objectId == null || ObjectId.zeroId().equals(objectId) ? null : objectId.getName();
    }

    /**
     * Credentials for an HTTP(S) remote, from EGit's secure store - the same place the
     * IDE's own fetch and push look, so a remote that works from the Git Repositories
     * view works here. SSH remotes need nothing here: EGit installs its SSH session
     * factory into JGit at startup, so keys and agents behave as they do in the IDE.
     */
    private CredentialsProvider credentialsFor(Repository repository, String remoteName)
    {
        try
        {
            RemoteConfig config = new RemoteConfig(repository.getConfig(), remoteName);
            List<URIish> uris = config.getURIs();
            if (uris.isEmpty())
            {
                return null;
            }
            URIish uri = uris.get(0);
            if (uri.getScheme() == null || !uri.getScheme().startsWith("http"))
            {
                return null;
            }

            BundleContext context = FrameworkUtil.getBundle(GitService.class).getBundleContext();
            ServiceReference<CredentialsStore> reference = context.getServiceReference(CredentialsStore.class);
            if (reference == null)
            {
                return null;
            }
            try
            {
                UserPasswordCredentials credentials = context.getService(reference).getCredentials(uri);
                return credentials == null
                        ? null
                        : new UsernamePasswordCredentialsProvider(credentials.getUser(), credentials.getPassword());
            }
            finally
            {
                context.ungetService(reference);
            }
        }
        catch (Exception e)
        {
            logger.warn("Could not read the stored Git credentials for remote '" + remoteName + "': " + e.getMessage());
            return null;
        }
    }

    // -------------------------------------------------------------------------
    // Undoing
    // -------------------------------------------------------------------------

    /**
     * Moves the checked-out branch to a revision.
     * <p>
     * SOFT leaves the index and working tree as they are, so the commits between the
     * two revisions become staged changes; MIXED also resets the index; HARD also
     * rewrites the working tree, which discards uncommitted work. A HARD reset to HEAD
     * is also how a conflicted merge, cherry-pick or revert is abandoned.
     *
     * @param modeName SOFT, MIXED (default) or HARD
     */
    public GitResetResponse resetToRevision(String projectName, String revision, String modeName)
    {
        ResetMode mode = modeName == null || modeName.isBlank()
                ? ResetMode.MIXED
                : ResetMode.valueOf(modeName.trim().toUpperCase());
        String requested = revision == null || revision.isBlank() ? Constants.HEAD : revision.trim();

        Repository repository = getRepository(projectName);
        ReentrantLock lock = getRepositoryLock(repository);
        lock.lock();

        String previousHead;
        try (Git git = new Git(repository))
        {
            resolveRevision(repository, requested);
            previousHead = headSha(repository);
            git.reset().setMode(ResetType.valueOf(mode.name())).setRef(requested).call();
        }
        catch (IllegalArgumentException e)
        {
            throw e;
        }
        catch (Exception e)
        {
            throw new RuntimeException("Failed to reset: " + e.getMessage(), e);
        }
        finally
        {
            lock.unlock();
        }

        List<String> refreshed = refreshMappedProjects(repository);
        return GitResetResponse.of(projectName, mode, requested, previousHead, headSha(repository), refreshed);
    }

    /**
     * Restores the working-tree content of tracked files from the index, dropping
     * their uncommitted modifications - {@code git checkout -- <path>}. Untracked
     * files and staged changes are left alone.
     */
    public GitDiscardResponse discardChanges(String projectName, String filePattern)
    {
        List<String> pathspecs = resolvePathspecs(projectName, filePattern);
        boolean wholeRepository = pathspecs.contains("");

        Repository repository = getRepository(projectName);
        Map<String, String> projects = mappedProjects(repository);
        ReentrantLock lock = getRepositoryLock(repository);
        lock.lock();

        List<GitFileChange> restored = new ArrayList<>();
        try (Git git = new Git(repository))
        {
            Status before = git.status().call();
            Map<String, ChangeType> candidates = new TreeMap<>();
            for (String path : before.getModified())
            {
                if (matchesPathspec(path, pathspecs))
                {
                    candidates.put(path, ChangeType.MODIFIED);
                }
            }
            for (String path : before.getMissing())
            {
                if (matchesPathspec(path, pathspecs))
                {
                    candidates.put(path, ChangeType.DELETED);
                }
            }

            if (!candidates.isEmpty())
            {
                CheckoutCommand command = git.checkout();
                if (wholeRepository)
                {
                    command.setAllPaths(true);
                }
                else
                {
                    pathspecs.forEach(command::addPath);
                }
                command.call();

                Status after = git.status().call();
                for (Map.Entry<String, ChangeType> candidate : candidates.entrySet())
                {
                    String path = candidate.getKey();
                    if (!after.getModified().contains(path) && !after.getMissing().contains(path))
                    {
                        restored.add(GitStatusResponse.locate(path, candidate.getValue(), projects));
                    }
                }
            }
        }
        catch (Exception e)
        {
            throw new RuntimeException("Failed to discard changes: " + e.getMessage(), e);
        }
        finally
        {
            lock.unlock();
        }

        List<String> refreshed = refreshMappedProjects(repository);
        return GitDiscardResponse.of(projectName, filePattern, restored, refreshed);
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

    /**
     * The index at a moment in time: the blob each path points at, and what each path is
     * staged as relative to HEAD.
     * <p>
     * Both halves are needed to say what an operation actually staged. The blob answers
     * "did this entry change at all" - restaging an edited file leaves the kind of change
     * alone, so comparing kinds would miss it - and the kind answers "as what", which is
     * what a caller reads.
     */
    private record IndexSnapshot(Map<String, ObjectId> blobs, Map<String, ChangeType> stagedTypes)
    {
    }

    private IndexSnapshot indexSnapshot(Repository repository) throws IOException
    {
        Map<String, ObjectId> blobs = new LinkedHashMap<>();
        DirCache dirCache = repository.readDirCache();
        for (int i = 0; i < dirCache.getEntryCount(); i++)
        {
            DirCacheEntry entry = dirCache.getEntry(i);
            blobs.put(entry.getPathString(), entry.getObjectId());
        }

        Map<String, ChangeType> stagedTypes = new LinkedHashMap<>();
        try (var out = new ByteArrayOutputStream();
             var formatter = new DiffFormatter(out))
        {
            formatter.setRepository(repository);
            formatter.setDetectRenames(false);

            ObjectId head = repository.resolve(Constants.HEAD);
            AbstractTreeIterator headTree = head == null
                    ? new EmptyTreeIterator()
                    : prepareTreeParser(repository, head);

            for (DiffEntry diff : formatter.scan(headTree, prepareIndexTreeParser(repository)))
            {
                switch (diff.getChangeType())
                {
                    case ADD, COPY -> stagedTypes.put(diff.getNewPath(), ChangeType.ADDED);
                    case DELETE -> stagedTypes.put(diff.getOldPath(), ChangeType.DELETED);
                    default -> stagedTypes.put(diff.getNewPath(), ChangeType.MODIFIED);
                }
            }
        }
        return new IndexSnapshot(blobs, stagedTypes);
    }

    /**
     * The index entries an operation created, changed or dropped.
     * <p>
     * This is what {@code gitAdd} and {@code gitReset} report instead of echoing the
     * caller's own pathspec: a pattern that matches nothing produces an empty list rather
     * than a confirmation.
     */
    private static List<GitFileChange> indexDelta(IndexSnapshot before, IndexSnapshot after,
            Map<String, String> projectsByPrefix)
    {
        Set<String> paths = new TreeSet<>();
        paths.addAll(before.blobs().keySet());
        paths.addAll(after.blobs().keySet());

        List<GitFileChange> changed = new ArrayList<>();
        for (String path : paths)
        {
            if (Objects.equals(before.blobs().get(path), after.blobs().get(path)))
            {
                continue;
            }
            ChangeType changeType = after.stagedTypes().get(path);
            if (changeType == null)
            {
                // The entry no longer differs from HEAD - it was unstaged - so what it
                // had been staged as is the useful thing to report.
                changeType = before.stagedTypes().getOrDefault(path, ChangeType.MODIFIED);
            }
            changed.add(GitStatusResponse.locate(path, changeType, projectsByPrefix));
        }
        return changed;
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
        return formatDiffEntries(repository, diffs, RawTextComparator.DEFAULT);
    }

    private String formatDiffEntries(Repository repository, List<DiffEntry> diffs, RawTextComparator comparator) throws IOException
    {
        try (var out = new ByteArrayOutputStream();
             var formatter = new DiffFormatter(out))
        {
            formatter.setRepository(repository);
            formatter.setDiffComparator(comparator);
            formatter.setDetectRenames(true);
            for (DiffEntry diff : diffs)
            {
                formatter.format(diff);
            }
            return out.toString("UTF-8");
        }
    }
}
