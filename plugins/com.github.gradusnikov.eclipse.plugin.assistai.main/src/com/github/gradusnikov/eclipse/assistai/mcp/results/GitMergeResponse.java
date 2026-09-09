package com.github.gradusnikov.eclipse.assistai.mcp.results;

import java.util.List;

import com.github.gradusnikov.eclipse.assistai.mcp.results.GitLogResponse.GitCommit;
import com.github.gradusnikov.eclipse.assistai.mcp.results.GitStatusResponse.GitFileChange;

/**
 * What a {@code gitMerge} did to the checked-out branch.
 * <p>
 * A merge has more outcomes than "done" and "failed", and the ones a caller must act
 * on - conflict markers left in the tree, local changes that stopped it before it
 * touched anything, a squash that left the result staged but not committed - each
 * need a different next step. They arrive as a status plus the files involved, every
 * file named by the project and project-relative path the editing tools take.
 *
 * @param mergedRef the branch, tag or commit that was merged in
 * @param previousHead where HEAD pointed before the merge
 * @param newHead where HEAD points afterwards; unchanged when nothing was committed
 * @param commit the merge commit that was created, or null when the merge
 *            fast-forwarded, was already up to date, stopped on conflicts or was left
 *            uncommitted
 * @param conflicting the files left with conflict markers
 * @param blockingFiles the local changes a blocked merge would have overwritten
 * @param refreshedProjects every Eclipse project that was refreshed afterwards - a
 *            merge rewrites the working tree of the whole repository, not of one project
 */
public record GitMergeResponse(
    String projectName,
    MergeStatus status,
    String mergedRef,
    String previousHead,
    String newHead,
    GitCommit commit,
    List<GitFileChange> conflicting,
    List<GitFileChange> blockingFiles,
    List<String> refreshedProjects,
    List<Diagnostic> diagnostics,
    String summaryText
)
{
    public enum MergeStatus
    {
        /** HEAD moved forward to the merged ref; no merge commit was needed. */
        FAST_FORWARD,
        /** A merge commit was created. */
        MERGED,
        /** The merged ref was already contained in HEAD. Nothing changed. */
        ALREADY_UP_TO_DATE,
        /**
         * The merged result is in the index and working tree but no commit was made -
         * a squash merge, which leaves committing to the caller.
         */
        MERGED_NOT_COMMITTED,
        /**
         * Conflict markers are in the working tree and the repository is mid-merge
         * until they are resolved and committed, or the merge is reset away.
         */
        CONFLICTED,
        /** Local changes would have been overwritten; nothing was merged. */
        BLOCKED,
        /** The merge could not be performed; diagnostics say why. */
        FAILED
    }

    public static GitMergeResponse of( String projectName, MergeStatus status, String mergedRef, String previousHead,
            String newHead, GitCommit commit, List<GitFileChange> conflicting, List<GitFileChange> blockingFiles,
            List<String> refreshedProjects, List<Diagnostic> diagnostics )
    {
        List<GitFileChange> conflicts = conflicting == null ? List.of() : List.copyOf( conflicting );
        List<GitFileChange> blocking = blockingFiles == null ? List.of() : List.copyOf( blockingFiles );
        List<String> refreshed = refreshedProjects == null ? List.of() : List.copyOf( refreshedProjects );
        List<Diagnostic> problems = diagnostics == null ? List.of() : List.copyOf( diagnostics );
        return new GitMergeResponse( projectName, status, mergedRef, previousHead, newHead, commit, conflicts, blocking,
                refreshed, problems, summarize( status, mergedRef, commit, conflicts.size(), blocking.size() ) );
    }

    private static String summarize( MergeStatus status, String mergedRef, GitCommit commit, int conflicts, int blocking )
    {
        return switch ( status )
        {
            case FAST_FORWARD -> "Fast-forwarded to " + mergedRef + ".";
            case MERGED -> "Merged " + mergedRef + ( commit == null ? "." : " as " + commit.shortSha() + "." );
            case ALREADY_UP_TO_DATE -> "Already up to date with " + mergedRef + ".";
            case MERGED_NOT_COMMITTED -> "Merged " + mergedRef + " into the index and working tree; nothing was committed.";
            case CONFLICTED -> "Merging " + mergedRef + " left " + conflicts + " conflicting file(s).";
            case BLOCKED -> blocking + " local change(s) block merging " + mergedRef + "; nothing was merged.";
            case FAILED -> "Merging " + mergedRef + " failed.";
        };
    }
}
