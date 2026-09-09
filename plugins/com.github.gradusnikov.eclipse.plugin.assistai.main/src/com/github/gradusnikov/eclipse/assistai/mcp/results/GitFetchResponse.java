package com.github.gradusnikov.eclipse.assistai.mcp.results;

import java.util.List;

/**
 * What a {@code gitFetch} brought in.
 * <p>
 * A fetch that changed nothing and a fetch that could not reach the remote both print
 * nothing on the command line. Here the first is {@link FetchStatus#UP_TO_DATE} and
 * the second is {@link FetchStatus#FAILED} with a diagnostic, and every
 * remote-tracking ref that moved is listed with where it moved from and to.
 *
 * @param remote the remote that was fetched
 * @param updates the remote-tracking refs that changed
 * @param messages what the remote said, when it said anything
 */
public record GitFetchResponse(
    String projectName,
    String remote,
    FetchStatus status,
    int totalUpdates,
    List<GitRefUpdate> updates,
    String messages,
    List<Diagnostic> diagnostics,
    String summaryText
)
{
    public enum FetchStatus
    {
        /** At least one remote-tracking ref moved. */
        UPDATED,
        /** The remote had nothing new. */
        UP_TO_DATE,
        /** The remote could not be fetched from; diagnostics say why. */
        FAILED
    }

    /**
     * One ref a fetch changed.
     *
     * @param localRef the remote-tracking ref, {@code refs/remotes/origin/main}
     * @param remoteRef the ref on the remote it mirrors
     * @param oldSha what the ref pointed at before, null when it was created
     * @param newSha what it points at now, null when it was deleted
     * @param result Git's own word for the update: NEW, FAST_FORWARD, FORCED, ...
     */
    public record GitRefUpdate(
        String localRef,
        String remoteRef,
        String oldSha,
        String newSha,
        String result
    )
    {
    }

    public static GitFetchResponse of( String projectName, String remote, List<GitRefUpdate> updates, String messages )
    {
        List<GitRefUpdate> changed = updates == null ? List.of() : List.copyOf( updates );
        FetchStatus status = changed.isEmpty() ? FetchStatus.UP_TO_DATE : FetchStatus.UPDATED;
        String summary = changed.isEmpty()
                ? "Already up to date with " + remote + "."
                : "Fetched " + changed.size() + " ref update(s) from " + remote + ".";
        return new GitFetchResponse( projectName, remote, status, changed.size(), changed,
                messages == null ? "" : messages.strip(), List.of(), summary );
    }

    public static GitFetchResponse failed( String projectName, String remote, Diagnostic diagnostic )
    {
        return new GitFetchResponse( projectName, remote, FetchStatus.FAILED, 0, List.of(), "", List.of( diagnostic ),
                "Fetching from " + remote + " failed." );
    }
}
