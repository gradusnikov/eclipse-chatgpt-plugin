package com.github.gradusnikov.eclipse.assistai.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Plain JUnit tests for the JGit-backed differ. JGit needs no Eclipse runtime.
 */
public class UnifiedDiffsTest
{
    @Test
    public void reportsNothingWhenContentIsUnchanged()
    {
        assertEquals( "", UnifiedDiffs.diff( "alpha\nbravo\n", "alpha\nbravo\n", 3 ) );
    }

    @Test
    public void reportsAChangedLine()
    {
        String diff = UnifiedDiffs.diff( "alpha\nbravo\ncharlie\n", "alpha\nBRAVO\ncharlie\n", 3 );

        assertTrue( diff.contains( "-bravo" ), diff );
        assertTrue( diff.contains( "+BRAVO" ), diff );
    }

    @Test
    public void emitsHunkHeaders()
    {
        String diff = UnifiedDiffs.diff( "a\nb\nc\nd\ne\nf\ng\nh\n", "a\nb\nc\nd\ne\nf\ng\nZ\n", 1 );

        assertTrue( diff.contains( "@@" ), "a unified diff needs hunk headers: " + diff );
    }

    @Test
    public void limitsContextToTheRequestedNumberOfLines()
    {
        String before = "1\n2\n3\n4\n5\n6\n7\n8\n9\n10\nTARGET\n11\n12\n13\n14\n15\n";
        String after = before.replace( "TARGET", "CHANGED" );

        String narrow = UnifiedDiffs.diff( before, after, 1 );
        String wide = UnifiedDiffs.diff( before, after, 5 );

        assertTrue( wide.length() > narrow.length(), "more context should produce a longer diff" );
    }

    @Test
    public void addsFileHeadersWhenLabelsAreGiven()
    {
        String diff = UnifiedDiffs.diff( "alpha\n", "old.txt", "bravo\n", "new.txt", 3 );

        assertTrue( diff.startsWith( "--- old.txt\n+++ new.txt\n" ), diff );
    }

    @Test
    public void omitsHeadersWhenThereIsNoChange()
    {
        assertEquals( "", UnifiedDiffs.diff( "same\n", "old.txt", "same\n", "new.txt", 3 ) );
    }

    @Test
    public void handlesAnAddedFile()
    {
        String diff = UnifiedDiffs.diff( "", "alpha\nbravo\n", 3 );

        assertTrue( diff.contains( "+alpha" ), diff );
        assertTrue( diff.contains( "+bravo" ), diff );
    }

    @Test
    public void handlesADeletedBody()
    {
        String diff = UnifiedDiffs.diff( "alpha\nbravo\n", "", 3 );

        assertTrue( diff.contains( "-alpha" ), diff );
    }

    @Test
    public void toleratesNullContent()
    {
        assertFalse( UnifiedDiffs.diff( null, "alpha\n", 3 ).isEmpty() );
        assertEquals( "", UnifiedDiffs.diff( null, null, 3 ) );
    }

    @Test
    public void treatsCrlfAndLfContentAsDifferentText()
    {
        // Not a normalisation helper: it must report what is actually in the file, so a
        // line-ending change is a real change.
        String diff = UnifiedDiffs.diff( "alpha\nbravo\n", "alpha\r\nbravo\r\n", 3 );

        assertFalse( diff.isEmpty(), "a line-ending change is a change" );
    }

    @Test
    public void omittedIsTheCountsAndTheReason()
    {
        UnifiedDiffs.Unified unified = UnifiedDiffs.compare( "a\nb\n", "a\nc\nd\n", 3 );

        assertEquals( "\\ diff omitted (+2 -1 lines): because\n", UnifiedDiffs.omitted( unified, "because" ) );
    }

    @Test
    public void omittedIsEmptyForIdenticalSides()
    {
        UnifiedDiffs.Unified unified = UnifiedDiffs.compare( "same\n", "same\n", 3 );

        assertEquals( "", UnifiedDiffs.omitted( unified, "because" ) );
    }
}
