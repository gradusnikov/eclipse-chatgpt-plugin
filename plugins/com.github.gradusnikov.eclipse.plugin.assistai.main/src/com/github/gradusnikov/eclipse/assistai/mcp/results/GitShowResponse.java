package com.github.gradusnikov.eclipse.assistai.mcp.results;

import java.util.List;

import com.github.gradusnikov.eclipse.assistai.mcp.results.GitDiffResponse.GitFileDiff;
import com.github.gradusnikov.eclipse.assistai.mcp.results.GitLogResponse.GitCommit;

/**
 * One commit and the change it introduced - what {@code git show} prints.
 * <p>
 * The commit is in the shape {@code gitLog} uses and the change is in the shape
 * {@code gitDiff} uses, so a caller that renders either renders this. The diff is
 * against the commit's first parent, or against the empty tree for a root commit,
 * which is what {@code git show} does too.
 *
 * @param revision the revision the caller asked for, as given
 * @param parentShas the commit's parents; more than one for a merge commit, none for
 *            a root commit
 * @param files each changed file, resolved to the project and project-relative path
 *            the reading and editing tools take
 * @param unifiedDiff the hunks, with the repository-relative paths Git writes
 */
public record GitShowResponse(
    String projectName,
    String revision,
    GitCommit commit,
    List<String> parentShas,
    int totalFiles,
    int addedLines,
    int removedLines,
    List<GitFileDiff> files,
    String unifiedDiff,
    String summaryText
)
{
    public static GitShowResponse of( String projectName, String revision, GitCommit commit, List<String> parentShas,
            List<GitFileDiff> files, String unifiedDiff )
    {
        List<String> parents = parentShas == null ? List.of() : List.copyOf( parentShas );
        List<GitFileDiff> entries = files == null ? List.of() : List.copyOf( files );

        int added = 0;
        int removed = 0;
        for ( GitFileDiff file : entries )
        {
            added += file.addedLines();
            removed += file.removedLines();
        }

        return new GitShowResponse( projectName, revision, commit, parents, entries.size(), added, removed, entries,
                unifiedDiff == null ? "" : unifiedDiff,
                commit.shortSha() + " " + commit.shortMessage() + ": " + entries.size()
                        + ( entries.size() == 1 ? " file changed, " : " files changed, " ) + added + " insertions, "
                        + removed + " deletions." );
    }
}
