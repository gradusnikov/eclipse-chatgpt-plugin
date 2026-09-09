package com.github.gradusnikov.eclipse.assistai.mcp.results;

import java.util.List;

import com.github.gradusnikov.eclipse.assistai.mcp.results.GitStatusResponse.GitFileChange;

/**
 * What a {@code gitPull} did: a fetch followed by a merge or a rebase.
 * <p>
 * The second half is where the caller's next step is decided, so its outcome is the
 * status here. A pull that fetched fine and then stopped on conflicts is
 * {@link PullStatus#CONFLICTED} with the files listed, not a success with a footnote.
 *
 * @param remote the remote pulled from
 * @param remoteBranch the branch on that remote, as configured for the current branch
 * @param rebase whether the local commits were rebased onto the fetched ones rather
 *            than merged with them
 * @param fetchedUpdates how many remote-tracking refs the fetch moved
 * @param previousHead where HEAD pointed before
 * @param newHead where HEAD points afterwards
 * @param conflicting the files left with conflict markers
 * @param blockingFiles the local changes that stopped the merge or rebase
 */
public record GitPullResponse(
    String projectName,
    String remote,
    String remoteBranch,
    boolean rebase,
    PullStatus status,
    int fetchedUpdates,
    String previousHead,
    String newHead,
    List<GitFileChange> conflicting,
    List<GitFileChange> blockingFiles,
    List<String> refreshedProjects,
    List<Diagnostic> diagnostics,
    String summaryText
)
{
    public enum PullStatus
    {
        /** The branch moved forward to the fetched commits without a merge commit. */
        FAST_FORWARD,
        /** A merge commit joined the local and fetched commits. */
        MERGED,
        /** The local commits were replayed on top of the fetched ones. */
        REBASED,
        /** Nothing new on the remote. */
        ALREADY_UP_TO_DATE,
        /** The merge or rebase stopped on conflicts; the repository is mid-operation. */
        CONFLICTED,
        /** Local changes would have been overwritten; the fetch happened, nothing else. */
        BLOCKED,
        /** The pull failed; diagnostics say why. */
        FAILED
    }

    public static GitPullResponse of( String projectName, String remote, String remoteBranch, boolean rebase,
            PullStatus status, int fetchedUpdates, String previousHead, String newHead, List<GitFileChange> conflicting,
            List<GitFileChange> blockingFiles, List<String> refreshedProjects, List<Diagnostic> diagnostics )
    {
        List<GitFileChange> conflicts = conflicting == null ? List.of() : List.copyOf( conflicting );
        List<GitFileChange> blocking = blockingFiles == null ? List.of() : List.copyOf( blockingFiles );
        List<String> refreshed = refreshedProjects == null ? List.of() : List.copyOf( refreshedProjects );
        List<Diagnostic> problems = diagnostics == null ? List.of() : List.copyOf( diagnostics );
        return new GitPullResponse( projectName, remote, remoteBranch, rebase, status, fetchedUpdates, previousHead,
                newHead, conflicts, blocking, refreshed, problems,
                summarize( status, remote, remoteBranch, conflicts.size(), blocking.size() ) );
    }

    private static String summarize( PullStatus status, String remote, String remoteBranch, int conflicts, int blocking )
    {
        String from = remote + ( remoteBranch == null ? "" : "/" + remoteBranch );
        return switch ( status )
        {
            case FAST_FORWARD -> "Fast-forwarded to " + from + ".";
            case MERGED -> "Merged " + from + ".";
            case REBASED -> "Rebased onto " + from + ".";
            case ALREADY_UP_TO_DATE -> "Already up to date with " + from + ".";
            case CONFLICTED -> "Pulling " + from + " left " + conflicts + " conflicting file(s).";
            case BLOCKED -> blocking + " local change(s) block pulling " + from + "; only the fetch happened.";
            case FAILED -> "Pulling " + from + " failed.";
        };
    }
}
