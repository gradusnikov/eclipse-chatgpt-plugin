package com.github.gradusnikov.eclipse.assistai.mcp.results;

import java.util.List;

/**
 * Where a {@code gitResetToRevision} moved the checked-out branch.
 *
 * @param mode SOFT moves the branch only, MIXED also resets the index, HARD also
 *            rewrites the working tree
 * @param revision the revision the caller asked for, as given
 * @param previousHead where HEAD pointed before - the handle for undoing this
 * @param newHead where HEAD points now
 */
public record GitResetResponse(
    String projectName,
    ResetMode mode,
    String revision,
    String previousHead,
    String newHead,
    List<String> refreshedProjects,
    String summaryText
)
{
    public enum ResetMode
    {
        SOFT,
        MIXED,
        HARD
    }

    public static GitResetResponse of( String projectName, ResetMode mode, String revision, String previousHead,
            String newHead, List<String> refreshedProjects )
    {
        List<String> refreshed = refreshedProjects == null ? List.of() : List.copyOf( refreshedProjects );
        String shortHead = newHead == null ? "?" : newHead.substring( 0, Math.min( 7, newHead.length() ) );
        return new GitResetResponse( projectName, mode, revision, previousHead, newHead, refreshed,
                mode + " reset to " + revision + " (" + shortHead + ")." );
    }
}
