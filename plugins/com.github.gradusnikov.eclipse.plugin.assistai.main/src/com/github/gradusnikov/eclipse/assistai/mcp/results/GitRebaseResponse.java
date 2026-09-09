package com.github.gradusnikov.eclipse.assistai.mcp.results;

import java.util.List;

import com.github.gradusnikov.eclipse.assistai.mcp.results.GitLogResponse.GitCommit;
import com.github.gradusnikov.eclipse.assistai.mcp.results.GitStatusResponse.GitFileChange;

/**
 * Where a {@code gitRebase} left the checked-out branch.
 * <p>
 * A rebase is a multi-step operation that can pause: on a conflict the repository
 * stays mid-rebase, with the commit being replayed named in {@link #stoppedAt()} and
 * the conflicting files listed, until the caller resolves them and continues, skips
 * that commit, or aborts. Each of those is a further call with a different
 * {@link RebaseOperation}, so the state a caller is in is reported as a status rather
 * than a sentence.
 *
 * @param operation the step that was asked for
 * @param upstream the ref the branch was rebased onto; null for a continue, skip or
 *            abort, which act on the rebase already in progress
 * @param stoppedAt the commit whose replay hit conflicts, when the rebase is paused
 * @param conflicting the files left with conflict markers
 * @param blockingFiles the local changes that stopped the rebase before it started,
 *            or would have been overwritten by its first checkout
 */
public record GitRebaseResponse(
    String projectName,
    RebaseOperation operation,
    RebaseStatus status,
    String upstream,
    String currentBranch,
    String headSha,
    GitCommit stoppedAt,
    List<GitFileChange> conflicting,
    List<GitFileChange> blockingFiles,
    List<String> refreshedProjects,
    List<Diagnostic> diagnostics,
    String summaryText
)
{
    public enum RebaseOperation
    {
        /** Start rebasing the checked-out branch onto the upstream. */
        BEGIN,
        /** Resume a paused rebase after resolving conflicts. */
        CONTINUE,
        /** Drop the commit the rebase is paused on and resume. */
        SKIP,
        /** Abandon the rebase and put the branch back where it started. */
        ABORT
    }

    public enum RebaseStatus
    {
        /** The rebase completed; the branch now sits on the upstream. */
        OK,
        /** The branch already sat on the upstream. Nothing changed. */
        UP_TO_DATE,
        /** The branch was moved forward without replaying anything. */
        FAST_FORWARD,
        /** Replaying a commit hit conflicts; the rebase is paused on that commit. */
        STOPPED,
        /** The rebase was abandoned; the branch is back where it started. */
        ABORTED,
        /** The commit being replayed became empty; skip it or abort. */
        NOTHING_TO_COMMIT,
        /** Uncommitted local changes stopped the rebase before it started. */
        UNCOMMITTED_CHANGES,
        /** The first checkout would overwrite local changes; nothing was rebased. */
        BLOCKED,
        /** The rebase failed for another reason; diagnostics say why. */
        FAILED
    }

    public static GitRebaseResponse of( String projectName, RebaseOperation operation, RebaseStatus status,
            String upstream, String currentBranch, String headSha, GitCommit stoppedAt, List<GitFileChange> conflicting,
            List<GitFileChange> blockingFiles, List<String> refreshedProjects, List<Diagnostic> diagnostics )
    {
        List<GitFileChange> conflicts = conflicting == null ? List.of() : List.copyOf( conflicting );
        List<GitFileChange> blocking = blockingFiles == null ? List.of() : List.copyOf( blockingFiles );
        List<String> refreshed = refreshedProjects == null ? List.of() : List.copyOf( refreshedProjects );
        List<Diagnostic> problems = diagnostics == null ? List.of() : List.copyOf( diagnostics );
        return new GitRebaseResponse( projectName, operation, status, upstream, currentBranch, headSha, stoppedAt,
                conflicts, blocking, refreshed, problems,
                summarize( operation, status, upstream, currentBranch, stoppedAt, conflicts.size(), blocking.size() ) );
    }

    private static String summarize( RebaseOperation operation, RebaseStatus status, String upstream,
            String currentBranch, GitCommit stoppedAt, int conflicts, int blocking )
    {
        String onto = upstream == null ? "" : " onto " + upstream;
        return switch ( status )
        {
            case OK -> ( operation == RebaseOperation.BEGIN ? "Rebased " + currentBranch + onto : "Rebase completed" ) + ".";
            case UP_TO_DATE -> currentBranch + " is already up to date with " + upstream + ".";
            case FAST_FORWARD -> "Fast-forwarded " + currentBranch + onto + ".";
            case STOPPED -> "Rebase paused on " + ( stoppedAt == null ? "a commit" : stoppedAt.shortSha() ) + " with "
                    + conflicts + " conflicting file(s); resolve them, then CONTINUE, SKIP or ABORT.";
            case ABORTED -> "Rebase aborted; " + currentBranch + " is back where it started.";
            case NOTHING_TO_COMMIT -> "The commit being replayed is now empty; SKIP it or ABORT.";
            case UNCOMMITTED_CHANGES -> blocking + " uncommitted change(s) stop the rebase; commit or stash them first.";
            case BLOCKED -> blocking + " local change(s) would be overwritten; nothing was rebased.";
            case FAILED -> "Rebase failed.";
        };
    }
}
