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

    @Test
    public void attachedJavadocLosesThePageHeadingAndSignature()
    {
        // A member read from a Javadoc archive is that member's section of the generated page, which
        // opens with its name and signature; the description a summary wants comes after them.
        String page = "### setPrettyPrinting\n"
                + "\n"
                + "[@CanIgnoreReturnValue](https://errorprone.info/x.html) public [GsonBuilder](GsonBuilder.html) setPrettyPrinting()  \n"
                + "Configures Gson to output JSON that fits in a page for pretty printing."
                + " This option only affects JSON serialization.";

        assertEquals( "Configures Gson to output JSON that fits in a page for pretty printing.",
                Javadocs.firstSentence( Javadocs.descriptionOf( page ) ) );
    }

    @Test
    public void aSourceCommentIsLeftAlone()
    {
        String comment = "Says hello. And then some more.";

        assertEquals( comment, Javadocs.descriptionOf( comment ) );
        assertNull( Javadocs.descriptionOf( null ) );
    }

    @Test
    public void aPageWithNoSignatureBreakKeepsWhatItHas()
    {
        // The signature goes only when the hard break that ends it is found, so a differently shaped
        // page loses its heading and keeps its text rather than coming back empty.
        assertEquals( "Some description.", Javadocs.descriptionOf( "### name\n\nSome description." ) );
    }
}
