package com.github.gradusnikov.eclipse.assistai.mcp.servers;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import org.eclipse.e4.core.di.annotations.Creatable;

import com.github.gradusnikov.eclipse.assistai.mcp.annotations.McpServer;
import com.github.gradusnikov.eclipse.assistai.mcp.annotations.Tool;
import com.github.gradusnikov.eclipse.assistai.mcp.annotations.ToolParam;
import com.github.gradusnikov.eclipse.assistai.mcp.results.GitApplyCommitsResponse;
import com.github.gradusnikov.eclipse.assistai.mcp.results.GitBranchResponse;
import com.github.gradusnikov.eclipse.assistai.mcp.results.GitCheckoutResponse;
import com.github.gradusnikov.eclipse.assistai.mcp.results.GitCommitResponse;
import com.github.gradusnikov.eclipse.assistai.mcp.results.GitDeleteBranchResponse;
import com.github.gradusnikov.eclipse.assistai.mcp.results.GitDiffResponse;
import com.github.gradusnikov.eclipse.assistai.mcp.results.GitDiscardResponse;
import com.github.gradusnikov.eclipse.assistai.mcp.results.GitFetchResponse;
import com.github.gradusnikov.eclipse.assistai.mcp.results.GitLogResponse;
import com.github.gradusnikov.eclipse.assistai.mcp.results.GitMergeResponse;
import com.github.gradusnikov.eclipse.assistai.mcp.results.GitPullResponse;
import com.github.gradusnikov.eclipse.assistai.mcp.results.GitPushResponse;
import com.github.gradusnikov.eclipse.assistai.mcp.results.GitRebaseResponse;
import com.github.gradusnikov.eclipse.assistai.mcp.results.GitRemoteListResponse;
import com.github.gradusnikov.eclipse.assistai.mcp.results.GitResetResponse;
import com.github.gradusnikov.eclipse.assistai.mcp.results.GitShowResponse;
import com.github.gradusnikov.eclipse.assistai.mcp.results.GitStagePatchResponse;
import com.github.gradusnikov.eclipse.assistai.mcp.results.GitStageResponse;
import com.github.gradusnikov.eclipse.assistai.mcp.results.GitStashListResponse;
import com.github.gradusnikov.eclipse.assistai.mcp.results.GitStashPopResponse;
import com.github.gradusnikov.eclipse.assistai.mcp.results.GitStashResponse;
import com.github.gradusnikov.eclipse.assistai.mcp.results.GitStatusResponse;
import com.github.gradusnikov.eclipse.assistai.mcp.results.GitTagListResponse;
import com.github.gradusnikov.eclipse.assistai.mcp.results.GitTagResponse;
import com.github.gradusnikov.eclipse.assistai.mcp.services.GitService;

import jakarta.inject.Inject;

@Creatable
@McpServer(name = "eclipse-git")
public class EclipseGitMcpServer
{
    @Inject
    private GitService gitService;

    private static boolean flag(String value)
    {
        return Optional.ofNullable(value).map(String::trim).map(Boolean::parseBoolean).orElse(false);
    }

    private static List<String> commaSeparated(String value)
    {
        if (value == null || value.isBlank())
        {
            return List.of();
        }
        return Arrays.stream(value.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
    }

    // -------------------------------------------------------------------------
    // Inspecting
    // -------------------------------------------------------------------------

    @Tool(name = "gitStatus", description = "Reports the working tree status of the Git repository associated with the project: "
            + "separate staged, unstaged, untracked and conflicting lists, the current branch and its distance from its upstream. "
            + "Every entry names its Eclipse projectName and a project-relative filePath, which the reading and editing tools take, "
            + "plus the repository-relative repoPath the Git tools take. A clean working tree is reported as clean=true with empty lists.", type = "object",
            outputType = GitStatusResponse.class)
    public GitStatusResponse gitStatus(
            @ToolParam(name = "projectName", description = "The Eclipse project name (use listProjects to find it)", required = true) String projectName)
    {
        return gitService.getStatus(projectName);
    }

    @Tool(name = "gitLog", description = "Lists the most recent commits reachable from a revision - the current branch by default - "
            + "optionally only those touching some paths. Each commit reports sha, shortSha, author, authorEmail, authorTimeMillis "
            + "(epoch milliseconds), the full message and its first line. The truncated flag says whether the history goes further "
            + "back than maxCount.", type = "object",
            outputType = GitLogResponse.class)
    public GitLogResponse gitLog(
            @ToolParam(name = "projectName", description = "The Eclipse project name", required = true) String projectName,
            @ToolParam(name = "maxCount", description = "Maximum number of commits to show (default: 20)", required = false) String maxCount,
            @ToolParam(name = "revision", description = "Branch, tag or commit to walk back from. Default: HEAD", required = false) String revision,
            @ToolParam(name = "pathFilter", description = "Optional comma-separated files or folders relative to the Eclipse project; only commits touching them are listed", required = false) String pathFilter)
    {
        int count = Optional.ofNullable(maxCount).map(Integer::parseInt).orElse(20);
        return gitService.getLog(projectName, count, revision, pathFilter);
    }

    @Tool(name = "gitShow", description = "Shows one commit and the change it introduced against its first parent - what 'git show' "
            + "prints. The commit has the shape gitLog uses and the change the shape gitDiff uses: per-file entries resolved to an "
            + "Eclipse projectName and project-relative filePath with addedLines/removedLines, plus the unifiedDiff with "
            + "repository-relative paths. parentShas is empty for a root commit and has two entries for a merge commit.", type = "object",
            outputType = GitShowResponse.class)
    public GitShowResponse gitShow(
            @ToolParam(name = "projectName", description = "The Eclipse project name", required = true) String projectName,
            @ToolParam(name = "revision", description = "Branch, tag or commit sha. Default: HEAD", required = false) String revision,
            @ToolParam(name = "pathFilter", description = "Optional comma-separated files or folders relative to the Eclipse project", required = false) String pathFilter)
    {
        return gitService.show(projectName, revision, pathFilter);
    }

    @Tool(name = "gitReadFile", description = "Reads a UTF-8 text file from a Git revision without changing the working tree. The path is relative to the Eclipse project. Use revision 'INDEX' to read the staged version; otherwise revision defaults to HEAD and may be a branch, tag, or commit.", type = "object")
    public String gitReadFile(
            @ToolParam(name = "projectName", description = "The Eclipse project name", required = true) String projectName,
            @ToolParam(name = "filePath", description = "File path relative to the Eclipse project", required = true) String filePath,
            @ToolParam(name = "revision", description = "Git branch, tag, commit, or 'INDEX'. Default: HEAD", required = false) String revision)
    {
        String effectiveRevision = Optional.ofNullable(revision).filter(value -> !value.isBlank()).orElse("HEAD");
        return gitService.readFileAtRevision(projectName, filePath, effectiveRevision);
    }

    @Tool(name = "gitDiff", description = "Shows a unified diff. Without revisions it compares the index with the working tree, or with "
            + "staged='true' HEAD with the index. With fromRevision and/or toRevision it compares two revisions, or a revision with the "
            + "working tree when toRevision is omitted (fromRevision defaults to HEAD when only toRevision is given). Optionally limited to "
            + "comma-separated project-relative files/directories, with whitespace changes ignored. The hunks are in unifiedDiff, which names "
            + "paths from the repository root; the files list additionally resolves each of them to an Eclipse projectName and project-relative "
            + "filePath that the reading and editing tools accept, with per-file addedLines/removedLines. identical=true means the two sides "
            + "are the same, and baseRevision is null in a repository with no commits.", type = "object",
            outputType = GitDiffResponse.class)
    public GitDiffResponse gitDiff(
            @ToolParam(name = "projectName", description = "The Eclipse project name", required = true) String projectName,
            @ToolParam(name = "staged", description = "If 'true', shows staged (cached) changes instead of unstaged. Ignored when a revision is given. Default: false", required = false) String staged,
            @ToolParam(name = "pathFilter", description = "Optional comma-separated file or directory paths relative to the Eclipse project", required = false) String pathFilter,
            @ToolParam(name = "ignoreWhitespace", description = "If 'true', ignores whitespace when formatting hunks. Default: false", required = false) String ignoreWhitespace,
            @ToolParam(name = "fromRevision", description = "Optional older side of the comparison: branch, tag or commit (e.g. 'main', 'HEAD~3')", required = false) String fromRevision,
            @ToolParam(name = "toRevision", description = "Optional newer side of the comparison; the working tree when omitted", required = false) String toRevision)
    {
        return gitService.getDiff(projectName, flag(staged), pathFilter, flag(ignoreWhitespace), fromRevision, toRevision);
    }

    // -------------------------------------------------------------------------
    // Staging and committing
    // -------------------------------------------------------------------------

    @Tool(name = "gitAdd", description = "Stages files for the next commit. Paths are relative to the Eclipse project - the filePath "
            + "gitStatus reports - and may be comma-separated; '.' stages every change under the project (new, modified and deleted files). "
            + "Reports the files whose index entry actually changed, each naming its Eclipse projectName and project-relative filePath "
            + "as well as the repository-relative repoPath. A pattern that matches no changed file is totalFiles=0 with an empty list - "
            + "Git does not fail on it, so check the count rather than assuming the pattern matched.", type = "object",
            outputType = GitStageResponse.class)
    public GitStageResponse gitAdd(
            @ToolParam(name = "projectName", description = "The Eclipse project name", required = true) String projectName,
            @ToolParam(name = "filePattern", description = "Comma-separated files or folders relative to the Eclipse project (e.g. 'src/com/example/MyClass.java'), or '.' for the whole project", required = true) String filePattern)
    {
        return gitService.addFiles(projectName, filePattern);
    }

    @Tool(name = "gitReset", description = "Unstages files from the index (equivalent to 'git reset HEAD <file>'). Does not modify the working tree. "
            + "Paths are relative to the Eclipse project and may be comma-separated; '.' unstages everything under the project. "
            + "Reports the index entries that actually left the staged set, each naming its Eclipse projectName and project-relative filePath "
            + "plus the repository-relative repoPath, with changeType being what the file had been staged as. A pattern matching nothing "
            + "is totalFiles=0. To move the branch itself - undo a commit, discard everything - use gitResetToRevision.", type = "object",
            outputType = GitStageResponse.class)
    public GitStageResponse gitReset(
            @ToolParam(name = "projectName", description = "The Eclipse project name", required = true) String projectName,
            @ToolParam(name = "filePattern", description = "Comma-separated files or folders relative to the Eclipse project, or '.' for the whole project", required = true) String filePattern)
    {
        return gitService.resetFiles(projectName, filePattern);
    }

    @Tool(name = "gitStagePatch", description = "Stages specific changes from a unified diff patch into the index without modifying the working tree. "
            + "Use this to stage partial file changes for selective commits. The patch must be in standard unified diff format with file headers "
            + "(--- a/path and +++ b/path) and @@ hunk headers. IMPORTANT: patch paths are relative to the REPOSITORY root, not to the Eclipse "
            + "project - unlike gitDiff, gitReadFile and the editing tools, which take project-relative paths. The two differ whenever the project "
            + "does not sit at the repository root; gitStatus reports both forms as filePath and repoPath, and the unifiedDiff of gitDiff already "
            + "uses the repository form. status is STAGED or FAILED, files lists what actually reached the index, and workingTreePreserved says "
            + "whether the uncommitted content of every touched file was put back.", type = "object",
            outputType = GitStagePatchResponse.class)
    public GitStagePatchResponse gitStagePatch(
            @ToolParam(name = "projectName", description = "The Eclipse project name", required = true) String projectName,
            @ToolParam(name = "patch", description = "A unified diff patch string to stage. Must include file headers (--- a/path, +++ b/path) and @@ hunk headers.", required = true) String patch)
    {
        return gitService.stagePatch(projectName, patch);
    }

    @Tool(name = "gitCommit", description = "Commits the currently staged changes with the given message. With all='true' every tracked "
            + "file's modifications and deletions are staged first, repository-wide ('git commit -a'; new files still need gitAdd). With "
            + "amend='true' the previous commit is replaced instead of a new one being added. Returns the new commit as sha, shortSha, author, "
            + "authorEmail, authorTimeMillis, message and shortMessage - the same shape gitLog reports - so the sha is a field rather than a "
            + "prefix of a sentence.", type = "object",
            outputType = GitCommitResponse.class)
    public GitCommitResponse gitCommit(
            @ToolParam(name = "projectName", description = "The Eclipse project name", required = true) String projectName,
            @ToolParam(name = "message", description = "The commit message", required = true) String message,
            @ToolParam(name = "amend", description = "If 'true', replaces the previous commit. Default: false", required = false) String amend,
            @ToolParam(name = "all", description = "If 'true', stages all tracked modifications and deletions before committing. Default: false", required = false) String all)
    {
        return gitService.commit(projectName, message, flag(amend), flag(all));
    }

    @Tool(name = "gitDiscardChanges", description = "Discards uncommitted modifications of tracked files by restoring their working-tree "
            + "content from the index ('git checkout -- <path>'). Staged changes and untracked files are left alone. Paths are relative to the "
            + "Eclipse project and may be comma-separated; '.' covers the whole project. This cannot be undone, so gitStash first when in doubt. "
            + "Reports the files that were actually restored, each naming its Eclipse projectName, project-relative filePath and repoPath; a "
            + "pattern matching no changed tracked file is totalFiles=0.", type = "object",
            outputType = GitDiscardResponse.class)
    public GitDiscardResponse gitDiscardChanges(
            @ToolParam(name = "projectName", description = "The Eclipse project name", required = true) String projectName,
            @ToolParam(name = "filePattern", description = "Comma-separated files or folders relative to the Eclipse project, or '.' for the whole project", required = true) String filePattern)
    {
        return gitService.discardChanges(projectName, filePattern);
    }

    @Tool(name = "gitResetToRevision", description = "Moves the checked-out branch to a revision ('git reset'). mode SOFT keeps the index and "
            + "working tree, so the commits between become staged changes - the way to undo a commit while keeping its work ('HEAD~1'); "
            + "MIXED (default) also resets the index; HARD also rewrites the working tree, which discards uncommitted work, and is how a "
            + "conflicted merge, cherry-pick or revert is abandoned (HARD to HEAD). previousHead is the handle for undoing the reset. "
            + "Every Eclipse project mapped into the repository is refreshed afterwards.", type = "object",
            outputType = GitResetResponse.class)
    public GitResetResponse gitResetToRevision(
            @ToolParam(name = "projectName", description = "The Eclipse project name", required = true) String projectName,
            @ToolParam(name = "revision", description = "Branch, tag or commit to move to (e.g. 'HEAD~1', 'origin/main', a sha). Default: HEAD", required = false) String revision,
            @ToolParam(name = "mode", description = "SOFT, MIXED or HARD. Default: MIXED", required = false) String mode)
    {
        return gitService.resetToRevision(projectName, revision, mode);
    }

    // -------------------------------------------------------------------------
    // Branches
    // -------------------------------------------------------------------------

    @Tool(name = "gitBranch", description = "Lists the branches of the repository. Local branches are in 'branches', each with a 'current' flag "
            + "for the checked-out one, and remote-tracking branches are in 'remoteBranches'. Branch 'name' is what gitCheckout, "
            + "gitCreateBranch and gitDeleteBranch take.", type = "object",
            outputType = GitBranchResponse.class)
    public GitBranchResponse gitBranch(
            @ToolParam(name = "projectName", description = "The Eclipse project name", required = true) String projectName,
            @ToolParam(name = "includeRemote", description = "If 'true', includes remote-tracking branches. Default: false", required = false) String includeRemote)
    {
        return gitService.listBranches(projectName, flag(includeRemote));
    }

    @Tool(name = "gitCreateBranch", description = "Creates a new branch. Does not switch to it - use gitCheckout to switch.", type = "object")
    public String gitCreateBranch(
            @ToolParam(name = "projectName", description = "The Eclipse project name", required = true) String projectName,
            @ToolParam(name = "branchName", description = "Name of the new branch to create", required = true) String branchName,
            @ToolParam(name = "startPoint", description = "Optional start point (branch name, tag, or commit SHA). Defaults to HEAD.", required = false) String startPoint)
    {
        return gitService.createBranch(projectName, branchName, startPoint);
    }

    @Tool(name = "gitDeleteBranch", description = "Deletes a branch. Cannot delete the currently checked-out branch. "
            + "deleted says whether the branch is gone and deletedRefs lists the refs that were removed. A branch that is not fully merged "
            + "is refused with deleted=false and a BRANCH_NOT_MERGED diagnostic; retry with force='true' to delete it anyway.", type = "object",
            outputType = GitDeleteBranchResponse.class)
    public GitDeleteBranchResponse gitDeleteBranch(
            @ToolParam(name = "projectName", description = "The Eclipse project name", required = true) String projectName,
            @ToolParam(name = "branchName", description = "Name of the branch to delete", required = true) String branchName,
            @ToolParam(name = "force", description = "If 'true', force-deletes even if the branch is not fully merged. Default: false", required = false) String force)
    {
        return gitService.deleteBranch(projectName, branchName, flag(force));
    }

    @Tool(name = "gitCheckout", description = "Checks out a branch, switching the working tree to that branch. "
            + "status is SWITCHED or BLOCKED: when local changes would be overwritten nothing is switched, blockingFiles names them "
            + "(projectName, filePath, repoPath) and a CHECKOUT_CONFLICT diagnostic is attached. A checkout rewrites the whole repository, "
            + "so refreshedProjects lists every Eclipse project that was refreshed, not only the one that was named.", type = "object",
            outputType = GitCheckoutResponse.class)
    public GitCheckoutResponse gitCheckout(
            @ToolParam(name = "projectName", description = "The Eclipse project name", required = true) String projectName,
            @ToolParam(name = "branchName", description = "The branch name to checkout", required = true) String branchName)
    {
        return gitService.checkoutBranch(projectName, branchName);
    }

    @Tool(name = "gitMerge", description = "Merges a branch, tag or commit into the checked-out branch. status is FAST_FORWARD, MERGED "
            + "(commit carries the new merge commit), ALREADY_UP_TO_DATE, MERGED_NOT_COMMITTED (a squash: the result is staged, gitCommit it), "
            + "CONFLICTED (conflict markers are in the working tree; conflicting names the files with projectName/filePath/repoPath and a "
            + "MERGE_CONFLICT diagnostic is attached - resolve them, gitAdd them and gitCommit, or gitResetToRevision HEAD with mode HARD to "
            + "abandon), BLOCKED (local changes named in blockingFiles would be overwritten; nothing was merged) or FAILED. Every Eclipse "
            + "project mapped into the repository is refreshed afterwards.", type = "object",
            outputType = GitMergeResponse.class)
    public GitMergeResponse gitMerge(
            @ToolParam(name = "projectName", description = "The Eclipse project name", required = true) String projectName,
            @ToolParam(name = "ref", description = "The branch, tag or commit to merge into the current branch", required = true) String ref,
            @ToolParam(name = "fastForward", description = "FF (fast-forward when possible, default), NO_FF (always create a merge commit) or FF_ONLY (refuse unless a fast-forward)", required = false) String fastForward,
            @ToolParam(name = "squash", description = "If 'true', stages the merged result without committing it. Default: false", required = false) String squash,
            @ToolParam(name = "message", description = "Optional message for the merge commit", required = false) String message)
    {
        return gitService.merge(projectName, ref, fastForward, flag(squash), message);
    }

    @Tool(name = "gitRebase", description = "Rebases the checked-out branch onto an upstream, or drives a rebase already in progress. "
            + "operation BEGIN (default, needs upstream) starts it; on a conflict status is STOPPED, stoppedAt names the commit being "
            + "replayed, conflicting names the files (projectName/filePath/repoPath) and the repository stays mid-rebase - resolve and "
            + "gitAdd them, then call again with operation CONTINUE, or SKIP that commit, or ABORT to put the branch back. Other statuses: "
            + "OK, UP_TO_DATE, FAST_FORWARD, ABORTED, NOTHING_TO_COMMIT (the replayed commit became empty: SKIP or ABORT), "
            + "UNCOMMITTED_CHANGES and BLOCKED (local changes in blockingFiles stop it; commit or stash them first) and FAILED. "
            + "Every Eclipse project mapped into the repository is refreshed afterwards.", type = "object",
            outputType = GitRebaseResponse.class)
    public GitRebaseResponse gitRebase(
            @ToolParam(name = "projectName", description = "The Eclipse project name", required = true) String projectName,
            @ToolParam(name = "upstream", description = "The branch, tag or commit to rebase onto (e.g. 'main', 'origin/main'). Required for BEGIN, ignored otherwise", required = false) String upstream,
            @ToolParam(name = "operation", description = "BEGIN, CONTINUE, SKIP or ABORT. Default: BEGIN", required = false) String operation)
    {
        return gitService.rebase(projectName, upstream, operation);
    }

    @Tool(name = "gitCherryPick", description = "Applies the changes of one or more existing commits to the checked-out branch as new commits. "
            + "status APPLIED lists the created commits (createdCommits, in the shape gitLog uses); CONFLICTED means a commit did not apply "
            + "cleanly - conflicting names the files, the commits before it are already on the branch, and the repository is mid-operation "
            + "until they are resolved, staged and committed, or abandoned with gitResetToRevision HEAD mode HARD; BLOCKED means local changes "
            + "(blockingFiles) would be overwritten and nothing was applied.", type = "object",
            outputType = GitApplyCommitsResponse.class)
    public GitApplyCommitsResponse gitCherryPick(
            @ToolParam(name = "projectName", description = "The Eclipse project name", required = true) String projectName,
            @ToolParam(name = "commits", description = "Comma-separated commit shas or refs to apply, oldest first", required = true) String commits)
    {
        return gitService.cherryPick(projectName, commaSeparated(commits));
    }

    @Tool(name = "gitRevert", description = "Undoes the changes of one or more existing commits with new commits that apply the reverse "
            + "patch, leaving history intact. status APPLIED lists the created commits; CONFLICTED means a reverse patch did not apply "
            + "cleanly - conflicting names the files and the repository is mid-operation until they are resolved, staged and committed, or "
            + "abandoned with gitResetToRevision HEAD mode HARD; BLOCKED means local changes (blockingFiles) would be overwritten and nothing "
            + "was applied.", type = "object",
            outputType = GitApplyCommitsResponse.class)
    public GitApplyCommitsResponse gitRevert(
            @ToolParam(name = "projectName", description = "The Eclipse project name", required = true) String projectName,
            @ToolParam(name = "commits", description = "Comma-separated commit shas or refs to undo", required = true) String commits)
    {
        return gitService.revert(projectName, commaSeparated(commits));
    }

    // -------------------------------------------------------------------------
    // Tags
    // -------------------------------------------------------------------------

    @Tool(name = "gitTagList", description = "Lists the repository's tags. Each reports name (what every revision parameter and gitDeleteTag "
            + "take), fullName, sha, targetSha (the commit it points at), whether it is annotated, and the annotated tag's message.", type = "object",
            outputType = GitTagListResponse.class)
    public GitTagListResponse gitTagList(
            @ToolParam(name = "projectName", description = "The Eclipse project name", required = true) String projectName)
    {
        return gitService.listTags(projectName);
    }

    @Tool(name = "gitTag", description = "Creates a tag at a revision (HEAD by default): annotated when a message is given, lightweight "
            + "otherwise. Reports the tag in the shape gitTagList uses.", type = "object",
            outputType = GitTagResponse.class)
    public GitTagResponse gitTag(
            @ToolParam(name = "projectName", description = "The Eclipse project name", required = true) String projectName,
            @ToolParam(name = "tagName", description = "The tag name (e.g. 'v1.2.0')", required = true) String tagName,
            @ToolParam(name = "message", description = "Optional message; given, the tag is annotated", required = false) String message,
            @ToolParam(name = "revision", description = "Branch, tag or commit to tag. Default: HEAD", required = false) String revision)
    {
        return gitService.createTag(projectName, tagName, message, revision);
    }

    @Tool(name = "gitDeleteTag", description = "Deletes a local tag. Reports what was known about the tag before it was removed. "
            + "A tag that does not exist is an error.", type = "object",
            outputType = GitTagResponse.class)
    public GitTagResponse gitDeleteTag(
            @ToolParam(name = "projectName", description = "The Eclipse project name", required = true) String projectName,
            @ToolParam(name = "tagName", description = "The tag name", required = true) String tagName)
    {
        return gitService.deleteTag(projectName, tagName);
    }

    // -------------------------------------------------------------------------
    // Stashes
    // -------------------------------------------------------------------------

    @Tool(name = "gitStash", description = "Stashes the current working directory and index changes, reverting the working tree to HEAD. "
            + "stashed=false with a null stash means the working tree was already clean - an outcome, not a failure. When something was "
            + "stashed, stash carries its index, its stash@{n} ref, the commit sha it is stored as and its message, the same shape "
            + "gitStashList reports.", type = "object",
            outputType = GitStashResponse.class)
    public GitStashResponse gitStash(
            @ToolParam(name = "projectName", description = "The Eclipse project name", required = true) String projectName,
            @ToolParam(name = "message", description = "Optional message to describe the stash", required = false) String message)
    {
        return gitService.stash(projectName, message);
    }

    @Tool(name = "gitStashPop", description = "Applies the most recent stash entry and, if that succeeded, removes it. "
            + "status is APPLIED, CONFLICTED or NOTHING_TO_APPLY. On CONFLICTED the stash was kept (dropped=false), the working tree holds "
            + "conflict markers, conflicting names the affected files and a MERGE_CONFLICT diagnostic is attached - do not treat it as done.", type = "object",
            outputType = GitStashPopResponse.class)
    public GitStashPopResponse gitStashPop(
            @ToolParam(name = "projectName", description = "The Eclipse project name", required = true) String projectName)
    {
        return gitService.stashPop(projectName);
    }

    @Tool(name = "gitStashList", description = "Lists the stash entries, most recent first. Each entry reports its index, its stash@{n} ref, "
            + "the commit sha it is stored as, and its message. An empty stash is totalStashes=0 with an empty list.", type = "object",
            outputType = GitStashListResponse.class)
    public GitStashListResponse gitStashList(
            @ToolParam(name = "projectName", description = "The Eclipse project name", required = true) String projectName)
    {
        return gitService.stashList(projectName);
    }

    // -------------------------------------------------------------------------
    // Remotes
    // -------------------------------------------------------------------------

    @Tool(name = "gitRemoteList", description = "Lists the remotes configured for the repository, each with its name - what gitFetch, "
            + "gitPull and gitPush take - and its fetch and push URLs.", type = "object",
            outputType = GitRemoteListResponse.class)
    public GitRemoteListResponse gitRemoteList(
            @ToolParam(name = "projectName", description = "The Eclipse project name", required = true) String projectName)
    {
        return gitService.listRemotes(projectName);
    }

    @Tool(name = "gitFetch", description = "Fetches from a remote without touching the working tree. status is UPDATED (updates lists every "
            + "remote-tracking ref that moved, with oldSha/newSha), UP_TO_DATE, or FAILED with a REMOTE_OPERATION_FAILED diagnostic when the "
            + "remote could not be reached or refused the credentials. HTTPS credentials come from EGit's secure store, the same place the "
            + "IDE's own fetch uses; SSH uses the IDE's keys and agent.", type = "object", longExecution = true,
            outputType = GitFetchResponse.class)
    public GitFetchResponse gitFetch(
            @ToolParam(name = "projectName", description = "The Eclipse project name", required = true) String projectName,
            @ToolParam(name = "remote", description = "The remote name. Default: origin", required = false) String remote,
            @ToolParam(name = "prune", description = "If 'true', removes remote-tracking refs the remote no longer has. Default: false", required = false) String prune)
    {
        return gitService.fetch(projectName, remote, flag(prune));
    }

    @Tool(name = "gitPull", description = "Fetches and then merges - or with rebase='true' rebases onto - the upstream of the checked-out "
            + "branch. status is FAST_FORWARD, MERGED, REBASED, ALREADY_UP_TO_DATE, CONFLICTED (conflicting names the files; the repository is "
            + "mid-merge or mid-rebase - finish it with gitAdd and gitCommit or gitRebase CONTINUE, or abandon it), BLOCKED (local changes in "
            + "blockingFiles would be overwritten; only the fetch happened) or FAILED. A branch with no configured upstream needs remoteBranch, "
            + "or a gitPush with setUpstream='true' first. Every Eclipse project mapped into the repository is refreshed afterwards.", type = "object",
            longExecution = true, outputType = GitPullResponse.class)
    public GitPullResponse gitPull(
            @ToolParam(name = "projectName", description = "The Eclipse project name", required = true) String projectName,
            @ToolParam(name = "remote", description = "The remote name. Default: the branch's configured remote, else origin", required = false) String remote,
            @ToolParam(name = "remoteBranch", description = "The branch on the remote. Default: the branch's configured upstream", required = false) String remoteBranch,
            @ToolParam(name = "rebase", description = "If 'true', rebases local commits onto the fetched ones instead of merging. Default: false", required = false) String rebase)
    {
        return gitService.pull(projectName, remote, remoteBranch, flag(rebase));
    }

    @Tool(name = "gitPush", description = "Pushes a local branch - the checked-out one by default - to a remote. status is PUSHED, UP_TO_DATE, "
            + "REJECTED (the remote has commits the branch does not: gitPull first, or push again with force='true' to overwrite; a "
            + "PUSH_REJECTED diagnostic says so) or FAILED (REMOTE_OPERATION_FAILED: unreachable, or credentials refused). updates reports each "
            + "ref's outcome in Git's own words. setUpstream='true' makes the pushed branch the local branch's upstream, so later gitPull and "
            + "gitPush calls need no arguments. HTTPS credentials come from EGit's secure store; SSH uses the IDE's keys and agent.", type = "object",
            longExecution = true, outputType = GitPushResponse.class)
    public GitPushResponse gitPush(
            @ToolParam(name = "projectName", description = "The Eclipse project name", required = true) String projectName,
            @ToolParam(name = "remote", description = "The remote name. Default: origin", required = false) String remote,
            @ToolParam(name = "branch", description = "The local branch to push. Default: the checked-out branch", required = false) String branch,
            @ToolParam(name = "setUpstream", description = "If 'true', records the remote branch as the local branch's upstream ('git push -u'). Default: false", required = false) String setUpstream,
            @ToolParam(name = "force", description = "If 'true', overwrites the remote branch even when it has commits the local one does not. Default: false", required = false) String force)
    {
        return gitService.push(projectName, remote, branch, flag(setUpstream), flag(force));
    }
}
