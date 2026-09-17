package com.github.gradusnikov.eclipse.assistai.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * The text side of {@link Javadocs}: HTML to Markdown and the summary sentence. Rendering
 * a real element needs JDT and is covered by the PDE tests of the services that call it.
 */
public class JavadocsTest
{
    @Test
    public void summaryIsTheFirstSentenceOfTheMainDescription()
    {
        String html = "Handles complex multi-step transactions. This is the second sentence\n"
                + "which should not appear.<dl><dt>Since:</dt><dd>1.0</dd></dl>";

        assertEquals( "Handles complex multi-step transactions.", Javadocs.summary( html ) );
    }

    @Test
    public void summaryStopsBeforeTheSuperMethodReferences()
    {
        String html = "Says hello.\n<div><b>Specified by:</b> <a href='eclipse-javadoc:x'>greet()</a> in Greeter</div>";

        assertEquals( "Says hello.", Javadocs.summary( html ) );
    }

    @Test
    public void summaryIsNullForBlockTagsAlone()
    {
        assertNull( Javadocs.summary( "<dl><dt>Author:</dt><dd>someone</dd></dl>" ) );
    }

    @Test
    public void firstSentenceKeepsAbbreviationsAndQualifiedNamesWhole()
    {
        assertEquals( "See e.g. the java.util.List docs.",
                Javadocs.firstSentence( "See e.g. the java.util.List docs. More follows." ) );
    }

    @Test
    public void firstSentenceJoinsAWrappedCommentOntoOneLine()
    {
        assertEquals( "A utility without a period", Javadocs.firstSentence( "A utility\n   without a period" ) );
    }

    @Test
    public void firstSentenceCutsARunOnDescription()
    {
        String sentence = Javadocs.firstSentence( "word ".repeat( 100 ) );

        assertTrue( sentence.endsWith( "..." ), sentence );
        assertTrue( sentence.length() <= Javadocs.SUMMARY_MAX_LENGTH + 3, sentence );
    }

    @Test
    public void firstSentenceIsNullForNothing()
    {
        assertNull( Javadocs.firstSentence( null ) );
        assertNull( Javadocs.firstSentence( "  \n " ) );
    }

    @Test
    public void markdownDropsWorkbenchLinksButKeepsTheirText()
    {
        String markdown = Javadocs.toMarkdown( "<!-- baseURL=\"file:/x\" -->See <a href='eclipse-javadoc:%E2%98%82=p/x'>Greeter</a> too." );

        assertEquals( "See Greeter too.", markdown );
    }

    @Test
    public void markdownKeepsCodeAndParagraphs()
    {
        String markdown = Javadocs.toMarkdown( "<p>Returns <code>null</code> when unset.</p><dl><dt>Returns:</dt><dd>the value</dd></dl>" );

        assertTrue( markdown.contains( "`null`" ), markdown );
        assertTrue( markdown.contains( "Returns:" ), markdown );
        assertFalse( markdown.contains( "<" ), markdown );
    }

    @Test
    public void markdownTidiesTheArtifactsJdkCommentsProduce()
    {
        // A line break after {@code List} in the source lands inside the code span; an empty anchor becomes {#id}.
        String markdown = Javadocs.toMarkdown( "of the <code>List\n</code> interface.<a id=\"fail-fast\"></a>\n<p>Fail-fast means..." );

        assertTrue( markdown.contains( "the `List` interface." ), markdown );
        assertFalse( markdown.contains( "{#" ), markdown );
        assertTrue( markdown.contains( "Fail-fast means" ), markdown );
    }

    @Test
    public void detailParsesCaseInsensitivelyWithADefault()
    {
        assertEquals( Javadocs.Detail.SUMMARY, Javadocs.Detail.parse( null, Javadocs.Detail.SUMMARY ) );
        assertEquals( Javadocs.Detail.NONE, Javadocs.Detail.parse( " ", Javadocs.Detail.NONE ) );
        assertEquals( Javadocs.Detail.FULL, Javadocs.Detail.parse( "full", Javadocs.Detail.NONE ) );
        assertThrows( IllegalArgumentException.class, () -> Javadocs.Detail.parse( "brief", Javadocs.Detail.NONE ) );
    }
}
