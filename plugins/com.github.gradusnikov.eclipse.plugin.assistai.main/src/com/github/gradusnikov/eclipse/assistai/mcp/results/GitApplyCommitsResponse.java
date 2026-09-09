package com.github.gradusnikov.eclipse.assistai.mcp.results;

import java.util.List;

import com.github.gradusnikov.eclipse.assistai.mcp.results.GitLogResponse.GitCommit;
import com.github.gradusnikov.eclipse.assistai.mcp.results.GitStatusResponse.GitFileChange;

/**
 * What a {@code gitCherryPick} or {@code gitRevert} did.
 * <p>
 * The two are one shape because they are one operation with the patch applied in
 * opposite directions: each replays existing commits onto the checked-out branch as
 * new commits, and each can stop on a conflict with the repository left mid-operation.
 * The commits that were actually created are listed in the shape {@code gitLog} uses,
 * so their shas can be cited or reset to without another lookup.
 *
 * @param requestedCommits the shas or refs the caller asked to apply, in order
 * @param previousHead where HEAD pointed before
 * @param newHead where HEAD points afterwards
 * @param createdCommits the new commits, oldest first; empty when nothing was applied
 * @param conflicting the files left with conflict markers
 * @param blockingFiles the local changes that stopped the operation before it changed
 *            anything
 */
public record GitApplyCommitsResponse(
    String projectName,
    ApplyOperation operation,
    ApplyStatus status,
    List<String> requestedCommits,
    String previousHead,
    String newHead,
    List<GitCommit> createdCommits,
    List<GitFileChange> conflicting,
    List<GitFileChange> blockingFiles,
    List<String> refreshedProjects,
    List<Diagnostic> diagnostics,
    String summaryText
)
{
    public enum ApplyOperation
    {
        /** The commits' changes were applied. */
        CHERRY_PICK,
        /** The commits' changes were undone. */
        REVERT
    }

    public enum ApplyStatus
    {
        /** Every requested commit was applied as a new commit. */
        APPLIED,
        /**
         * A commit did not apply cleanly; conflict markers are in the working tree and
         * the commits before it, if any, are already on the branch.
         */
        CONFLICTED,
        /** Local changes would have been overwritten; nothing was applied. */
        BLOCKED,
        /** The operation failed; diagnostics say why. */
        FAILED
    }

    public static GitApplyCommitsResponse of( String projectName, ApplyOperation operation, ApplyStatus status,
            List<String> requestedCommits, String previousHead, String newHead, List<GitCommit> createdCommits,
            List<GitFileChange> conflicting, List<GitFileChange> blockingFiles, List<String> refreshedProjects,
            List<Diagnostic> diagnostics )
    {
        List<String> requested = requestedCommits == null ? List.of() : List.copyOf( requestedCommits );
        List<GitCommit> created = createdCommits == null ? List.of() : List.copyOf( createdCommits );
        List<GitFileChange> conflicts = conflicting == null ? List.of() : List.copyOf( conflicting );
        List<GitFileChange> blocking = blockingFiles == null ? List.of() : List.copyOf( blockingFiles );
        List<String> refreshed = refreshedProjects == null ? List.of() : List.copyOf( refreshedProjects );
        List<Diagnostic> problems = diagnostics == null ? List.of() : List.copyOf( diagnostics );
        return new GitApplyCommitsResponse( projectName, operation, status, requested, previousHead, newHead, created,
                conflicts, blocking, refreshed, problems,
                summarize( operation, status, requested.size(), created.size(), conflicts.size(), blocking.size() ) );
    }

    private static String summarize( ApplyOperation operation, ApplyStatus status, int requested, int created,
            int conflicts, int blocking )
    {
        String verb = operation == ApplyOperation.CHERRY_PICK ? "Cherry-pick" : "Revert";
        return switch ( status )
        {
            case APPLIED -> verb + " applied " + requested + " commit(s), creating " + created + ".";
            case CONFLICTED -> verb + " stopped with " + conflicts + " conflicting file(s) after creating " + created
                    + " commit(s).";
            case BLOCKED -> blocking + " local change(s) block the " + verb.toLowerCase() + "; nothing was applied.";
            case FAILED -> verb + " failed.";
        };
    }
}
