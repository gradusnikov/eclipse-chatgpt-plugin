package com.github.gradusnikov.eclipse.assistai.mcp.results;

import java.util.List;

/**
 * What a {@code gitPush} did on the remote.
 * <p>
 * A push is refused per ref, and the reason - most often that the remote has commits
 * the local branch does not - decides the next step: pull first, or force. So each
 * ref's outcome is listed, and a refusal is {@link PushStatus#REJECTED} with a
 * diagnostic naming the remedy rather than an exception with the remote's wording.
 *
 * @param remote the remote pushed to
 * @param updates one entry per ref the push addressed
 */
public record GitPushResponse(
    String projectName,
    String remote,
    PushStatus status,
    List<GitPushUpdate> updates,
    List<Diagnostic> diagnostics,
    String summaryText
)
{
    public enum PushStatus
    {
        /** Every ref was updated on the remote. */
        PUSHED,
        /** The remote already had everything. */
        UP_TO_DATE,
        /** The remote refused at least one ref; the updates say which and why. */
        REJECTED,
        /** The remote could not be pushed to; diagnostics say why. */
        FAILED
    }

    /**
     * One ref's outcome.
     *
     * @param localRef the local ref that was pushed
     * @param remoteRef the ref on the remote it went to
     * @param status Git's own word: OK, UP_TO_DATE, REJECTED_NONFASTFORWARD, ...
     * @param message the remote's explanation, when it gave one
     */
    public record GitPushUpdate(
        String localRef,
        String remoteRef,
        String status,
        String message
    )
    {
    }

    public static GitPushResponse of( String projectName, String remote, PushStatus status, List<GitPushUpdate> updates,
            List<Diagnostic> diagnostics )
    {
        List<GitPushUpdate> entries = updates == null ? List.of() : List.copyOf( updates );
        List<Diagnostic> problems = diagnostics == null ? List.of() : List.copyOf( diagnostics );
        String summary = switch ( status )
        {
            case PUSHED -> "Pushed " + entries.size() + " ref(s) to " + remote + ".";
            case UP_TO_DATE -> remote + " is already up to date.";
            case REJECTED -> remote + " rejected the push.";
            case FAILED -> "Pushing to " + remote + " failed.";
        };
        return new GitPushResponse( projectName, remote, status, entries, problems, summary );
    }
}
