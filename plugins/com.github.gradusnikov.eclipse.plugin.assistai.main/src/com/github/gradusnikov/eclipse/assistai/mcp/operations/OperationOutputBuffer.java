package com.github.gradusnikov.eclipse.assistai.mcp.operations;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * Line oriented, bounded buffer holding the output produced by an
 * {@link Operation} - typically the stdout/stderr of a launched process.
 * <p>
 * Lines are addressed by an absolute index that stays stable for the lifetime of
 * the operation: when the buffer overflows, the oldest lines are dropped but the
 * indices of the surviving lines do not shift. A caller paging through the output
 * can therefore keep asking for the next offset without ever silently re-reading
 * or skipping a line.
 * <p>
 * Thread safe: producers are stream reader threads, consumers are MCP tool
 * threads.
 */
public class OperationOutputBuffer
{
    /** Lines retained before the oldest are dropped. */
    public static final int MAX_LINES = 5000;

    /** Characters retained before the oldest lines are dropped. */
    static final int MAX_CHARS = 512 * 1024;

    /** A single line longer than this is truncated rather than allowed to blow the budget. */
    public static final int MAX_LINE_CHARS = 2000;

    private static final String TRUNCATED = " ...[truncated]";

    private final Deque<String> lines = new ArrayDeque<>();

    private final StringBuilder pendingOut = new StringBuilder();

    private final StringBuilder pendingErr = new StringBuilder();

    private int droppedLines;

    private int chars;

    /**
     * A page of output.
     *
     * @param lines        the lines in this page, in order
     * @param firstIndex   absolute index of the first returned line
     * @param nextOffset   absolute index to ask for to continue paging
     * @param totalLines   total lines produced so far, including dropped ones
     * @param droppedLines lines already evicted from the front of the buffer
     */
    public record Page( List<String> lines, int firstIndex, int nextOffset, int totalLines, int droppedLines )
    {
    }

    /**
     * Appends a chunk of stream output. Chunks arrive at arbitrary boundaries, so
     * an incomplete trailing line is held back until its newline shows up.
     */
    public synchronized void append( String text, boolean error )
    {
        if ( text == null || text.isEmpty() )
        {
            return;
        }
        StringBuilder pending = error ? pendingErr : pendingOut;
        pending.append( text );

        int newline;
        while ( ( newline = indexOfNewline( pending ) ) >= 0 )
        {
            String line = pending.substring( 0, newline );
            pending.delete( 0, newline + 1 );
            addLine( line, error );
        }
    }

    /**
     * Flushes any trailing partial line. Call when the producing stream ends,
     * otherwise a final line without a newline would never become visible.
     */
    public synchronized void flush()
    {
        if ( pendingOut.length() > 0 )
        {
            addLine( pendingOut.toString(), false );
            pendingOut.setLength( 0 );
        }
        if ( pendingErr.length() > 0 )
        {
            addLine( pendingErr.toString(), true );
            pendingErr.setLength( 0 );
        }
    }

    /**
     * Returns a page of lines.
     *
     * @param offset absolute index of the first line wanted. A negative value counts
     *               back from the end, so -200 asks for the last 200 lines. An offset
     *               pointing at already dropped lines is clamped to the oldest line
     *               still held.
     * @param limit  maximum number of lines to return; a value below 1 yields an empty
     *               page that still reports the totals.
     */
    public synchronized Page page( int offset, int limit )
    {
        int total = droppedLines + lines.size();
        if ( limit < 1 )
        {
            return new Page( List.of(), total, total, total, droppedLines );
        }

        int from = offset < 0 ? Math.max( 0, total + offset ) : offset;
        from = Math.max( from, droppedLines );
        from = Math.min( from, total );

        int skip = from - droppedLines;
        List<String> page = new ArrayList<>( Math.min( limit, lines.size() ) );
        int index = 0;
        for ( String line : lines )
        {
            if ( index >= skip && page.size() < limit )
            {
                page.add( line );
            }
            index++;
        }
        return new Page( page, from, from + page.size(), total, droppedLines );
    }

    /** Total lines produced so far, including any that have been dropped. */
    public synchronized int totalLines()
    {
        return droppedLines + lines.size();
    }

    /** True when nothing has ever been written. */
    public synchronized boolean isEmpty()
    {
        return totalLines() == 0 && pendingOut.length() == 0 && pendingErr.length() == 0;
    }

    public synchronized void clear()
    {
        lines.clear();
        pendingOut.setLength( 0 );
        pendingErr.setLength( 0 );
        chars = 0;
        // droppedLines is deliberately kept: absolute indices must never rewind.
    }

    private void addLine( String rawLine, boolean error )
    {
        String line = rawLine;
        if ( line.endsWith( "\r" ) )
        {
            line = line.substring( 0, line.length() - 1 );
        }
        if ( line.length() > MAX_LINE_CHARS )
        {
            line = line.substring( 0, MAX_LINE_CHARS ) + TRUNCATED;
        }
        if ( error )
        {
            line = "[err] " + line;
        }
        lines.addLast( line );
        chars += line.length() + 1;
        evict();
    }

    private void evict()
    {
        while ( lines.size() > MAX_LINES || chars > MAX_CHARS )
        {
            String dropped = lines.pollFirst();
            if ( dropped == null )
            {
                chars = 0;
                return;
            }
            chars -= dropped.length() + 1;
            droppedLines++;
        }
    }

    private static int indexOfNewline( StringBuilder builder )
    {
        for ( int i = 0; i < builder.length(); i++ )
        {
            if ( builder.charAt( i ) == '\n' )
            {
                return i;
            }
        }
        return -1;
    }
}
