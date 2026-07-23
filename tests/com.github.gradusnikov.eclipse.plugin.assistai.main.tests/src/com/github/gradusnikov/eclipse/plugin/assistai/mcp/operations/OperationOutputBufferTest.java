package com.github.gradusnikov.eclipse.plugin.assistai.mcp.operations;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.github.gradusnikov.eclipse.assistai.mcp.operations.OperationOutputBuffer;

/**
 * The output buffer is what lets a caller page through a long operation's output, so
 * its line indices have to stay honest even after the buffer overflows and starts
 * dropping the oldest lines.
 */
public class OperationOutputBufferTest
{
    @Test
    public void splitsChunksIntoLinesAcrossArbitraryBoundaries()
    {
        OperationOutputBuffer buffer = new OperationOutputBuffer();
        // Stream output does not arrive on line boundaries.
        buffer.append( "hel", false );
        buffer.append( "lo\nwor", false );
        buffer.append( "ld\n", false );

        OperationOutputBuffer.Page page = buffer.page( 0, 10 );
        assertEquals( 2, page.totalLines() );
        assertEquals( "hello", page.lines().get( 0 ) );
        assertEquals( "world", page.lines().get( 1 ) );
    }

    @Test
    public void holdsBackAPartialLineUntilFlushed()
    {
        OperationOutputBuffer buffer = new OperationOutputBuffer();
        buffer.append( "no newline yet", false );
        assertEquals( 0, buffer.totalLines() );

        buffer.flush();
        assertEquals( 1, buffer.totalLines() );
        assertEquals( "no newline yet", buffer.page( 0, 1 ).lines().get( 0 ) );
    }

    @Test
    public void marksErrorLines()
    {
        OperationOutputBuffer buffer = new OperationOutputBuffer();
        buffer.append( "boom\n", true );
        assertEquals( "[err] boom", buffer.page( 0, 1 ).lines().get( 0 ) );
    }

    @Test
    public void pagesForwardWithNextOffset()
    {
        OperationOutputBuffer buffer = new OperationOutputBuffer();
        for ( int i = 0; i < 10; i++ )
        {
            buffer.append( "line" + i + "\n", false );
        }

        OperationOutputBuffer.Page first = buffer.page( 0, 4 );
        assertEquals( 0, first.firstIndex() );
        assertEquals( 4, first.nextOffset() );
        assertEquals( "line0", first.lines().get( 0 ) );

        OperationOutputBuffer.Page second = buffer.page( first.nextOffset(), 4 );
        assertEquals( 4, second.firstIndex() );
        assertEquals( "line4", second.lines().get( 0 ) );
        assertEquals( 10, second.totalLines() );
    }

    @Test
    public void negativeOffsetReadsTheTail()
    {
        OperationOutputBuffer buffer = new OperationOutputBuffer();
        for ( int i = 0; i < 10; i++ )
        {
            buffer.append( "line" + i + "\n", false );
        }

        OperationOutputBuffer.Page tail = buffer.page( -3, 3 );
        assertEquals( 7, tail.firstIndex() );
        assertEquals( 3, tail.lines().size() );
        assertEquals( "line7", tail.lines().get( 0 ) );
        assertEquals( "line9", tail.lines().get( 2 ) );
        assertEquals( 10, tail.nextOffset() );
    }

    @Test
    public void keepsAbsoluteIndicesStableAfterDroppingOldLines()
    {
        OperationOutputBuffer buffer = new OperationOutputBuffer();
        int written = OperationOutputBuffer.MAX_LINES + 500;
        for ( int i = 0; i < written; i++ )
        {
            buffer.append( "line" + i + "\n", false );
        }

        assertEquals( written, buffer.totalLines() );

        // Asking for a line that has been evicted must not silently return a different
        // line: it clamps to the oldest one still held and says how many were dropped.
        OperationOutputBuffer.Page page = buffer.page( 0, 5 );
        assertTrue( page.droppedLines() > 0 );
        assertEquals( page.droppedLines(), page.firstIndex() );
        assertEquals( "line" + page.droppedLines(), page.lines().get( 0 ) );

        // The newest line is still addressable at its original absolute index.
        OperationOutputBuffer.Page last = buffer.page( written - 1, 1 );
        assertEquals( "line" + ( written - 1 ), last.lines().get( 0 ) );
    }

    @Test
    public void truncatesAnAbsurdlyLongLine()
    {
        OperationOutputBuffer buffer = new OperationOutputBuffer();
        buffer.append( "x".repeat( OperationOutputBuffer.MAX_LINE_CHARS + 100 ) + "\n", false );

        String line = buffer.page( 0, 1 ).lines().get( 0 );
        assertTrue( line.length() < OperationOutputBuffer.MAX_LINE_CHARS + 50 );
        assertTrue( line.endsWith( "[truncated]" ) );
    }

    @Test
    public void anEmptyPageStillReportsTheTotals()
    {
        OperationOutputBuffer buffer = new OperationOutputBuffer();
        buffer.append( "a\nb\n", false );

        OperationOutputBuffer.Page page = buffer.page( 0, 0 );
        assertTrue( page.lines().isEmpty() );
        assertEquals( 2, page.totalLines() );
    }
}
