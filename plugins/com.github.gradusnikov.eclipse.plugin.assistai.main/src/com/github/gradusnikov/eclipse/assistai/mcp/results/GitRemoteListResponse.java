package com.github.gradusnikov.eclipse.assistai.mcp.results;

import java.util.List;

/**
 * The remotes configured for the repository a project is mapped into.
 *
 * @param totalRemotes how many remotes are configured
 */
public record GitRemoteListResponse(
    String projectName,
    int totalRemotes,
    List<GitRemote> remotes,
    String summaryText
)
{
    /**
     * One remote.
     *
     * @param name what gitFetch, gitPull and gitPush take as the remote
     * @param fetchUrl the URL fetched from
     * @param pushUrl the URL pushed to; the same as fetchUrl unless configured apart
     */
    public record GitRemote(
        String name,
        String fetchUrl,
        String pushUrl
    )
    {
    }

    public static GitRemoteListResponse of( String projectName, List<GitRemote> remotes )
    {
        List<GitRemote> entries = remotes == null ? List.of() : List.copyOf( remotes );
        String summary = entries.isEmpty()
                ? "No remotes configured."
                : entries.size() + ( entries.size() == 1 ? " remote." : " remotes." );
        return new GitRemoteListResponse( projectName, entries.size(), entries, summary );
    }
}
