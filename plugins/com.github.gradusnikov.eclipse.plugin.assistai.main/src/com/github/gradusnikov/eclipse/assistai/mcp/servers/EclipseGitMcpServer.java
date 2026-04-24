package com.github.gradusnikov.eclipse.assistai.mcp.servers;

import java.util.Optional;

import org.eclipse.e4.core.di.annotations.Creatable;

import com.github.gradusnikov.eclipse.assistai.mcp.annotations.McpServer;
import com.github.gradusnikov.eclipse.assistai.mcp.annotations.Tool;
import com.github.gradusnikov.eclipse.assistai.mcp.annotations.ToolParam;
import com.github.gradusnikov.eclipse.assistai.mcp.services.GitService;

import jakarta.inject.Inject;

@Creatable
@McpServer(name = "eclipse-git")
public class EclipseGitMcpServer
{
    @Inject
    private GitService gitService;

    @Tool(name = "gitStatus", description = "Shows the working tree status of the Git repository associated with the project. Displays staged, unstaged, untracked files and current branch info.", type = "object")
    public String gitStatus(
            @ToolParam(name = "projectName", description = "The Eclipse project name (use listProjects to find it)", required = true) String projectName)
    {
        return gitService.getStatus(projectName);
    }

    @Tool(name = "gitLog", description = "Shows the commit history of the Git repository associated with the project.", type = "object")
    public String gitLog(
            @ToolParam(name = "projectName", description = "The Eclipse project name", required = true) String projectName,
            @ToolParam(name = "maxCount", description = "Maximum number of commits to show (default: 20)", required = false) String maxCount)
    {
        int count = Optional.ofNullable(maxCount).map(Integer::parseInt).orElse(20);
        return gitService.getLog(projectName, count);
    }

    @Tool(name = "gitAdd", description = "Stages files for the next commit. Use '.' to stage all changes (new, modified, and deleted files).", type = "object")
    public String gitAdd(
            @ToolParam(name = "projectName", description = "The Eclipse project name", required = true) String projectName,
            @ToolParam(name = "filePattern", description = "File pattern to add (e.g., '.' for all, 'src/com/example/MyClass.java' for a specific file)", required = true) String filePattern)
    {
        return gitService.addFiles(projectName, filePattern);
    }

    @Tool(name = "gitCommit", description = "Commits the currently staged changes with the given message.", type = "object")
    public String gitCommit(
            @ToolParam(name = "projectName", description = "The Eclipse project name", required = true) String projectName,
            @ToolParam(name = "message", description = "The commit message", required = true) String message)
    {
        return gitService.commit(projectName, message);
    }

    @Tool(name = "gitDiff", description = "Shows the diff of changes in unified diff format. By default shows unstaged (working tree) changes; set staged to 'true' to see staged changes.", type = "object")
    public String gitDiff(
            @ToolParam(name = "projectName", description = "The Eclipse project name", required = true) String projectName,
            @ToolParam(name = "staged", description = "If 'true', shows staged (cached) changes instead of unstaged. Default: false", required = false) String staged)
    {
        boolean isStagedDiff = Optional.ofNullable(staged).map(Boolean::parseBoolean).orElse(false);
        return gitService.getDiff(projectName, isStagedDiff);
    }

    @Tool(name = "gitBranch", description = "Lists branches in the repository. The current branch is marked with an asterisk (*).", type = "object")
    public String gitBranch(
            @ToolParam(name = "projectName", description = "The Eclipse project name", required = true) String projectName,
            @ToolParam(name = "includeRemote", description = "If 'true', includes remote-tracking branches. Default: false", required = false) String includeRemote)
    {
        boolean remote = Optional.ofNullable(includeRemote).map(Boolean::parseBoolean).orElse(false);
        return gitService.listBranches(projectName, remote);
    }

    @Tool(name = "gitCreateBranch", description = "Creates a new branch. Does not switch to it â use gitCheckout to switch.", type = "object")
    public String gitCreateBranch(
            @ToolParam(name = "projectName", description = "The Eclipse project name", required = true) String projectName,
            @ToolParam(name = "branchName", description = "Name of the new branch to create", required = true) String branchName,
            @ToolParam(name = "startPoint", description = "Optional start point (branch name, tag, or commit SHA). Defaults to HEAD.", required = false) String startPoint)
    {
        return gitService.createBranch(projectName, branchName, startPoint);
    }

    @Tool(name = "gitDeleteBranch", description = "Deletes a branch. Cannot delete the currently checked-out branch.", type = "object")
    public String gitDeleteBranch(
            @ToolParam(name = "projectName", description = "The Eclipse project name", required = true) String projectName,
            @ToolParam(name = "branchName", description = "Name of the branch to delete", required = true) String branchName,
            @ToolParam(name = "force", description = "If 'true', force-deletes even if the branch is not fully merged. Default: false", required = false) String force)
    {
        boolean forceDelete = Optional.ofNullable(force).map(Boolean::parseBoolean).orElse(false);
        return gitService.deleteBranch(projectName, branchName, forceDelete);
    }

    @Tool(name = "gitCheckout", description = "Checks out a branch, switching the working tree to that branch.", type = "object")
    public String gitCheckout(
            @ToolParam(name = "projectName", description = "The Eclipse project name", required = true) String projectName,
            @ToolParam(name = "branchName", description = "The branch name to checkout", required = true) String branchName)
    {
        return gitService.checkoutBranch(projectName, branchName);
    }

    @Tool(name = "gitReset", description = "Unstages files from the index (equivalent to 'git reset HEAD <file>'). Does not modify the working tree.", type = "object")
    public String gitReset(
            @ToolParam(name = "projectName", description = "The Eclipse project name", required = true) String projectName,
            @ToolParam(name = "filePattern", description = "File pattern to unstage (e.g., '.' for all, or a specific file path)", required = true) String filePattern)
    {
        return gitService.resetFiles(projectName, filePattern);
    }

    @Tool(name = "gitStash", description = "Stashes the current working directory and index changes, reverting the working tree to HEAD.", type = "object")
    public String gitStash(
            @ToolParam(name = "projectName", description = "The Eclipse project name", required = true) String projectName,
            @ToolParam(name = "message", description = "Optional message to describe the stash", required = false) String message)
    {
        return gitService.stash(projectName, message);
    }

    @Tool(name = "gitStashPop", description = "Applies and removes the most recent stash entry, restoring previously stashed changes.", type = "object")
    public String gitStashPop(
            @ToolParam(name = "projectName", description = "The Eclipse project name", required = true) String projectName)
    {
        return gitService.stashPop(projectName);
    }

    @Tool(name = "gitStashList", description = "Lists all stash entries.", type = "object")
    public String gitStashList(
            @ToolParam(name = "projectName", description = "The Eclipse project name", required = true) String projectName)
    {
        return gitService.stashList(projectName);
    }
}
