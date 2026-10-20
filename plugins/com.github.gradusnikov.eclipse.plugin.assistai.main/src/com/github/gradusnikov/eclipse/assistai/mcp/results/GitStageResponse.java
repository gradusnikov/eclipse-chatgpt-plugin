package com.github.gradusnikov.eclipse.assistai.mcp.results;

import java.util.List;

import com.github.gradusnikov.eclipse.assistai.mcp.results.GitStatusResponse.GitFileChange;

/**
 * What staging or unstaging a pathspec actually did to the index.
 * <p>
 * {@code gitAdd} and {@code gitReset} used to echo the caller's own argument -
 * "Added: src/Typo.java", "Unstaged: src/Typo.java" - which contains no information the
 * caller did not already have and, worse, is returned unchanged when the pathspec
 * matched nothing at all. JGit succeeds against a pathspec that matches no file, so a
 * misspelled path reported a successful stage and the commit that followed was silently
 * wrong. {@link #files()} is what the index actually gained or lost, computed by
 * comparing the index before and after, so {@code totalFiles: 0} says outright that
 * nothing was staged.
 * <p>
 * Each entry's {@code changeType} is the staged change relative to HEAD - the same
 * meaning it has in {@code gitStatus}'s staged list. For an unstage that is what the
 * file <em>was</em> staged as before the entry was dropped. For an index-only removal
 * it is {@code DELETED} when the file is in HEAD, since the removal is now what is
 * staged, and otherwise what the dropped entry had been staged as.
 *
 * @param pathspec the pattern the caller passed, echoed so a result can be matched back
 *            to the request that produced it
 * @param files the files whose index entry changed, empty when the pathspec matched
 *            nothing or nothing had changed
 */
public record GitStageResponse(
    String projectName,
    StageOperation operation,
    String pathspec,
    int totalFiles,
    List<GitFileChange> files,
    String summaryText
)
{
    public enum StageOperation
    {
        /** Files were added to the index. */
        STAGE,
        /** Index entries were reset to HEAD. */
        UNSTAGE,
        /** Index entries were removed, leaving the working tree alone ('git rm --cached'). */
        REMOVE
    }

    public static GitStageResponse of( String projectName, StageOperation operation, String pathspec,
            List<GitFileChange> files )
    {
        List<GitFileChange> changed = files == null ? List.of() : List.copyOf( files );
        return new GitStageResponse( projectName, operation, pathspec, changed.size(), changed,
                summarize( operation, pathspec, changed.size() ) );
    }

    private static String summarize( StageOperation operation, String pathspec, int count )
    {
        String verb = switch ( operation )
        {
            case STAGE -> "staged";
            case UNSTAGE -> "unstaged";
            case REMOVE -> "removed from the index";
        };
        if ( count == 0 )
        {
            String matched = operation == StageOperation.REMOVE ? "matched no index entry" : "matched no changed file";
            return "Nothing was " + verb + ": '" + pathspec + "' " + matched + ".";
        }
        return count + ( count == 1 ? " file " : " files " ) + verb + ".";
    }
}
