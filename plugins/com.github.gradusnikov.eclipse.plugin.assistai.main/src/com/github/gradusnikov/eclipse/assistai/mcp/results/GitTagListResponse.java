package com.github.gradusnikov.eclipse.assistai.mcp.results;

import java.util.List;

/**
 * The tags of the repository a project is mapped into.
 *
 * @param totalTags how many tags the repository has
 */
public record GitTagListResponse(
    String projectName,
    int totalTags,
    List<GitTag> tags,
    String summaryText
)
{
    /**
     * One tag.
     *
     * @param name the short name, which is what the tag tools and every revision
     *            parameter take
     * @param fullName the ref, {@code refs/tags/v1.0}
     * @param sha for an annotated tag the tag object's own sha; for a lightweight tag
     *            the commit it points at, the same as {@code targetSha}
     * @param targetSha the commit the tag ultimately points at
     * @param annotated whether the tag carries its own message and author
     * @param message the annotated tag's message, null for a lightweight tag
     */
    public record GitTag(
        String name,
        String fullName,
        String sha,
        String targetSha,
        boolean annotated,
        String message
    )
    {
    }

    public static GitTagListResponse of( String projectName, List<GitTag> tags )
    {
        List<GitTag> entries = tags == null ? List.of() : List.copyOf( tags );
        String summary = entries.isEmpty()
                ? "No tags."
                : entries.size() + ( entries.size() == 1 ? " tag." : " tags." );
        return new GitTagListResponse( projectName, entries.size(), entries, summary );
    }
}
