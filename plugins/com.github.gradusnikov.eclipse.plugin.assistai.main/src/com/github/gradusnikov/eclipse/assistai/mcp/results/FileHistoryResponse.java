package com.github.gradusnikov.eclipse.assistai.mcp.results;

import java.io.InputStream;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

import org.eclipse.core.resources.IFileState;

/**
 * The stored versions of a file in Eclipse local history.
 * <p>
 * Exists chiefly so {@code historyTimestamp} arrives as a number rather than as a
 * column an agent has to pick out of a formatted table. The other history tools take
 * that timestamp as their selector, so making it machine-readable is what lets a
 * caller list versions and act on one without parsing anything.
 */
public record FileHistoryResponse(
    String projectName,
    String filePath,
    int totalVersions,
    List<HistoryEntry> versions,
    boolean truncated,
    String summaryText,
    List<Diagnostic> diagnostics
)
{
    /**
     * @param historyTimestamp the identifier to pass to getFileHistoryContent,
     *            restoreFileVersion or compareWithHistory. Stable for this content,
     *            unlike a position in the list
     * @param sizeBytes size of the stored content, or -1 when the state records a
     *            deletion
     */
    public record HistoryEntry(
        long historyTimestamp,
        String storedAt,
        long sizeBytes,
        boolean exists
    )
    {
    }

    private static final DateTimeFormatter TIMESTAMP_FMT = DateTimeFormatter
            .ofPattern( "yyyy-MM-dd HH:mm:ss" )
            .withZone( ZoneId.systemDefault() );

    /**
     * A listing that could not be produced, carrying the reason as a code.
     * <p>
     * Without this the record advertised a diagnostics field it could never fill,
     * because every failure was thrown - so a client reading {@code structuredContent},
     * which is what the advertised schema tells it to do, received nothing at all.
     */
    public static FileHistoryResponse failed( String projectName, String filePath, Diagnostic diagnostic )
    {
        return new FileHistoryResponse( projectName, filePath, 0, List.of(), false,
                diagnostic.message(), List.of( diagnostic ) );
    }

    public static FileHistoryResponse from( String projectName, String filePath, IFileState[] history, int limit )
    {
        List<HistoryEntry> versions = new ArrayList<>();
        int total = history == null ? 0 : history.length;
        int shown = limit > 0 ? Math.min( total, limit ) : total;

        for ( int i = 0; i < shown; i++ )
        {
            IFileState state = history[i];
            long size = -1L;
            boolean exists = state.exists();
            if ( exists )
            {
                try ( InputStream contents = state.getContents() )
                {
                    size = contents.available();
                }
                catch ( Exception e )
                {
                    // Size is decoration; a state that cannot be measured is still
                    // addressable by its timestamp, which is the part that matters.
                    size = -1L;
                }
            }
            versions.add( new HistoryEntry(
                    state.getModificationTime(),
                    TIMESTAMP_FMT.format( Instant.ofEpochMilli( state.getModificationTime() ) ),
                    size,
                    exists ) );
        }

        boolean truncated = total > shown;
        String summary = total == 0
                ? "No local history for " + filePath + "."
                : total + ( total == 1 ? " stored version." : " stored versions." )
                        + ( truncated ? " Showing the most recent " + shown + "." : "" );

        return new FileHistoryResponse( projectName, filePath, total, versions, truncated, summary,
                Diagnostic.none() );
    }
}
