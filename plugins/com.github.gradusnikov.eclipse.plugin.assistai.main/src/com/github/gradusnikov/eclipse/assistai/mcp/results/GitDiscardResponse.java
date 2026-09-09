package com.github.gradusnikov.eclipse.assistai.mcp.results;

import java.util.List;

import com.github.gradusnikov.eclipse.assistai.mcp.results.GitStatusResponse.GitFileChange;

/**
 * What a {@code gitDiscardChanges} put back.
 * <p>
 * The pathspec is echoed and the files that actually changed are listed, for the
 * same reason {@code gitAdd} lists them: Git succeeds against a pathspec matching
 * nothing, so the count is the only thing that distinguishes a discard from a typo.
 *
 * @param files the files whose working-tree content was restored from the index,
 *            each with what its uncommitted change had been
 */
public record GitDiscardResponse(
    String projectName,
    String pathspec,
    int totalFiles,
    List<GitFileChange> files,
    List<String> refreshedProjects,
    String summaryText
)
{
    public static GitDiscardResponse of( String projectName, String pathspec, List<GitFileChange> files,
            List<String> refreshedProjects )
    {
        List<GitFileChange> restored = files == null ? List.of() : List.copyOf( files );
        List<String> refreshed = refreshedProjects == null ? List.of() : List.copyOf( refreshedProjects );
        String summary = restored.isEmpty()
                ? "Nothing was discarded: '" + pathspec + "' matched no changed tracked file."
                : "Discarded the changes of " + restored.size() + ( restored.size() == 1 ? " file." : " files." );
        return new GitDiscardResponse( projectName, pathspec, restored.size(), restored, refreshed, summary );
    }
}
