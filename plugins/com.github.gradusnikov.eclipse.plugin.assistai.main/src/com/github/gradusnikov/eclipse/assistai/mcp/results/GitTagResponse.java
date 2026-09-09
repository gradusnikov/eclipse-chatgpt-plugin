package com.github.gradusnikov.eclipse.assistai.mcp.results;

import com.github.gradusnikov.eclipse.assistai.mcp.results.GitTagListResponse.GitTag;

/**
 * A tag that {@code gitTag} created or {@code gitDeleteTag} removed.
 * <p>
 * The tag is reported in exactly the shape {@code gitTagList} lists tags in, so the
 * sha of a tag just created can be used the same way as one looked up.
 *
 * @param tag the tag; for a deletion, what was known about it before it was removed
 */
public record GitTagResponse(
    String projectName,
    TagOperation operation,
    GitTag tag,
    String summaryText
)
{
    public enum TagOperation
    {
        CREATED,
        DELETED
    }

    public static GitTagResponse created( String projectName, GitTag tag )
    {
        return new GitTagResponse( projectName, TagOperation.CREATED, tag,
                "Created " + ( tag.annotated() ? "annotated" : "lightweight" ) + " tag " + tag.name() + " at "
                        + shortSha( tag.targetSha() ) + "." );
    }

    public static GitTagResponse deleted( String projectName, GitTag tag )
    {
        return new GitTagResponse( projectName, TagOperation.DELETED, tag, "Deleted tag " + tag.name() + "." );
    }

    private static String shortSha( String sha )
    {
        return sha == null ? "?" : sha.substring( 0, Math.min( 7, sha.length() ) );
    }
}
