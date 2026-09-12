package com.github.gradusnikov.eclipse.assistai.tools;

import java.text.BreakIterator;
import java.util.Locale;
import java.util.regex.Pattern;

import org.eclipse.core.runtime.CoreException;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.IMethod;
import org.eclipse.jdt.core.JavaModelException;
import org.eclipse.jdt.internal.ui.text.javadoc.JavadocContentAccess2;

import com.vladsch.flexmark.html2md.converter.FlexmarkHtmlConverter;

/**
 * Javadoc as Markdown, rendered by JDT rather than read off the source.
 * <p>
 * A raw comment does not say what a reader of the code sees on hover: an undocumented
 * override carries its supertype's text, {@code {@inheritDoc}} is expanded section by
 * section, and a missing {@code @param} or {@code @return} is taken from the supertype.
 * JDT's hover engine resolves all of that, so every tool that reports documentation goes
 * through it and reports the same text the developer sees.
 * <p>
 * {@link JavadocContentAccess2} is x-friends API, like the call-hierarchy classes this
 * plugin already depends on. The public {@code org.eclipse.jdt.ui.JavadocContentAccess}
 * inherits only whole comments and leaves {@code {@inheritDoc}} unexpanded.
 */
public final class Javadocs
{
    /** How much of an element's documentation a result carries. */
    public enum Detail
    {
        /** No documentation; the field is null. */
        NONE,
        /** The first sentence of the main description, one line per element. */
        SUMMARY,
        /** The whole comment as Markdown: description, parameters, return, exceptions and other tags. */
        FULL;

        /**
         * Parses a tool argument, case-insensitively.
         *
         * @param fallback the value for a null or blank argument
         * @throws IllegalArgumentException for a value that names no detail level, so the
         *             caller learns the accepted spellings instead of silently getting
         *             the default
         */
        public static Detail parse( String value, Detail fallback )
        {
            if ( value == null || value.isBlank() )
            {
                return fallback;
            }
            try
            {
                return valueOf( value.trim().toUpperCase( Locale.ROOT ) );
            }
            catch ( IllegalArgumentException e )
            {
                throw new IllegalArgumentException( "javadoc must be NONE, SUMMARY or FULL, not '" + value + "'", e );
            }
        }
    }

    /**
     * Rendered documentation.
     *
     * @param markdown never blank
     * @param inherited the element has no comment of its own; the text is its supertype's.
     *            Only a method can inherit, and one whose own comment is just
     *            {@code {@inheritDoc}} counts as documented
     */
    public record Rendered( String markdown, boolean inherited )
    {
    }

    /** Characters a summary keeps before it is cut, so a description written as one long sentence still summarises. */
    public static final int SUMMARY_MAX_LENGTH = 200;

    private static final FlexmarkHtmlConverter CONVERTER = FlexmarkHtmlConverter.builder().build();

    private static final Pattern HTML_COMMENT = Pattern.compile( "<!--.*?-->", Pattern.DOTALL );

    /** A link into the workbench, which means nothing to an MCP client; its text is kept. */
    private static final Pattern WORKBENCH_LINK = Pattern.compile( "\\[([^\\]]*)\\]\\(eclipse-javadoc:[^)]*\\)" );

    private static final Pattern WHITESPACE = Pattern.compile( "\\s+" );

    private Javadocs()
    {
    }

    /**
     * Renders an element's documentation at the requested level of detail.
     *
     * @param useAttachedJavadoc whether a binary element without source may be looked up
     *            at its project's Javadoc location, which can be a URL - fine for one
     *            type a caller asked about, not for every type in a search result
     * @return null for {@link Detail#NONE}, and for an element that has no documentation
     *         of its own or to inherit
     */
    public static Rendered render( IJavaElement element, Detail detail, boolean useAttachedJavadoc )
    {
        if ( detail == Detail.NONE )
        {
            return null;
        }
        String html;
        try
        {
            html = JavadocContentAccess2.getHTMLContent( element, useAttachedJavadoc );
        }
        catch ( CoreException e )
        {
            // Documentation is a report on the element; failing to render it must not take the outline down.
            return null;
        }
        if ( html == null || html.isBlank() )
        {
            return null;
        }
        String markdown = detail == Detail.SUMMARY ? summary( html ) : toMarkdown( html );
        if ( markdown == null || markdown.isBlank() )
        {
            return null;
        }
        return new Rendered( markdown, isInherited( element ) );
    }

    /**
     * Converts the HTML JDT renders to Markdown, dropping the base-URL comment and the
     * workbench links that only mean something inside Eclipse.
     */
    public static String toMarkdown( String html )
    {
        String markdown = CONVERTER.convert( HTML_COMMENT.matcher( html ).replaceAll( "" ) );
        return WORKBENCH_LINK.matcher( markdown ).replaceAll( "$1" ).strip();
    }

    /**
     * The first sentence of the main description, or null when the comment has no main
     * description - block tags alone, such as {@code @author}, are not a summary.
     */
    public static String summary( String html )
    {
        return firstSentence( toMarkdown( mainDescription( html ) ) );
    }

    /**
     * The HTML before the parts JDT appends to a main description: the super-method
     * references ({@code <div><b>Overrides:</b>...}) and the block-tag list ({@code <dl>}).
     */
    static String mainDescription( String html )
    {
        int end = html.length();
        for ( String appendix : new String[] { "<dl>", "<div><b>" } )
        {
            int at = html.indexOf( appendix );
            if ( at >= 0 && at < end )
            {
                end = at;
            }
        }
        return html.substring( 0, end );
    }

    /**
     * The first sentence, on one line.
     * <p>
     * Sentence ends are found by {@link BreakIterator}, which unlike a plain "period then
     * space" rule does not break inside {@code e.g. the} or {@code java.util.List}. A
     * sentence longer than {@link #SUMMARY_MAX_LENGTH} is cut and marked with an ellipsis.
     *
     * @return null for null or blank text
     */
    public static String firstSentence( String markdown )
    {
        if ( markdown == null )
        {
            return null;
        }
        String text = WHITESPACE.matcher( markdown.strip() ).replaceAll( " " );
        if ( text.isEmpty() )
        {
            return null;
        }
        BreakIterator sentences = BreakIterator.getSentenceInstance( Locale.ENGLISH );
        sentences.setText( text );
        int end = sentences.next();
        if ( end != BreakIterator.DONE && end < text.length() )
        {
            text = text.substring( 0, end ).strip();
        }
        if ( text.length() > SUMMARY_MAX_LENGTH )
        {
            text = text.substring( 0, SUMMARY_MAX_LENGTH ).strip() + "...";
        }
        return text;
    }

    /** Whether a method has no comment of its own, so whatever rendered was its supertype's. */
    private static boolean isInherited( IJavaElement element )
    {
        if ( !( element instanceof IMethod method ) )
        {
            return false;
        }
        try
        {
            return method.getOpenable().getBuffer() != null && method.getJavadocRange() == null;
        }
        catch ( JavaModelException e )
        {
            return false;
        }
    }
}
