package com.github.gradusnikov.eclipse.assistai.tools;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import org.eclipse.jgit.diff.DiffFormatter;
import org.eclipse.jgit.diff.Edit;
import org.eclipse.jgit.diff.EditList;
import org.eclipse.jgit.diff.HistogramDiff;
import org.eclipse.jgit.diff.RawText;
import org.eclipse.jgit.diff.RawTextComparator;

/**
 * Unified diffs, produced by JGit rather than by hand.
 * <p>
 * JGit is already a dependency of this plugin, and its differ handles the parts that
 * are tedious to get right: hunk grouping, context merging, correct {@code @@} ranges
 * and no-newline-at-end-of-file. Hand-rolled line walkers in this codebase produced
 * output that only resembled a unified diff.
 */
public final class UnifiedDiffs
{
    /** Lines of context around each change, matching the usual diff default. */
    public static final int DEFAULT_CONTEXT_LINES = 3;

    private UnifiedDiffs()
    {
    }

    /**
     * Produces the hunks of a unified diff, without file headers.
     *
     * @return the diff body, or an empty string when the two sides are identical
     */
    public static String diff( String oldContent, String newContent, int contextLines )
    {
        return compare( oldContent, newContent, contextLines ).body();
    }

    /**
     * A unified diff together with the line counts behind it.
     * <p>
     * The counts come from the {@link EditList} JGit built in order to render the
     * diff, so they agree with it by construction. Counting {@code +}/{@code -}
     * prefixes in the rendered text instead would miscount the {@code ---}/{@code +++}
     * headers and every context line that happens to begin with a minus.
     */
    public record Unified( String body, int addedLines, int removedLines )
    {
        /** Whether the two sides were identical. */
        public boolean isEmpty()
        {
            return body.isEmpty();
        }
    }

    /**
     * Diffs two contents once, returning both the rendered hunks and their statistics.
     */
    public static Unified compare( String oldContent, String newContent, int contextLines )
    {
        RawText oldText = new RawText( toBytes( oldContent ) );
        RawText newText = new RawText( toBytes( newContent ) );

        EditList edits = new HistogramDiff().diff( RawTextComparator.DEFAULT, oldText, newText );
        if ( edits.isEmpty() )
        {
            return new Unified( "", 0, 0 );
        }

        int removed = 0;
        int added = 0;
        for ( Edit edit : edits )
        {
            removed += edit.getEndA() - edit.getBeginA();
            added += edit.getEndB() - edit.getBeginB();
        }

        try ( ByteArrayOutputStream out = new ByteArrayOutputStream();
              DiffFormatter formatter = new DiffFormatter( out ) )
        {
            formatter.setContext( Math.max( 0, contextLines ) );
            formatter.format( edits, oldText, newText );
            return new Unified( out.toString( StandardCharsets.UTF_8 ), added, removed );
        }
        catch ( IOException e )
        {
            // Writing to a ByteArrayOutputStream cannot fail; a diff is a report, so
            // an unexpected failure must not take the surrounding operation down.
            return new Unified( "", added, removed );
        }
    }

    /**
     * Produces a complete unified diff, including the {@code ---}/{@code +++} header
     * lines that name each side.
     */
    public static String diff( String oldContent, String oldLabel, String newContent, String newLabel, int contextLines )
    {
        String body = diff( oldContent, newContent, contextLines );
        if ( body.isEmpty() )
        {
            return "";
        }
        return "--- " + oldLabel + "\n+++ " + newLabel + "\n" + body;
    }

    /**
     * The line counts of a change in place of its diff, for a result whose caller already
     * holds the text - repeating it back would only cost tokens. Identical sides give an
     * empty string, like {@link #diff}.
     */
    public static String omitted( Unified unified, String reason )
    {
        if ( unified.isEmpty() )
        {
            return "";
        }
        return "\\ diff omitted (+" + unified.addedLines() + " -" + unified.removedLines() + " lines): " + reason + "\n";
    }

    private static byte[] toBytes( String content )
    {
        return content == null ? new byte[0] : content.getBytes( StandardCharsets.UTF_8 );
    }
}
