package com.github.gradusnikov.eclipse.assistai.prompt;

import java.util.Base64;
import java.util.EnumSet;
import java.util.Scanner;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.commons.text.StringEscapeUtils;

/**
 * A utility class for parsing and converting a text prompt to an HTML formatted
 * string.
 */
public class MarkdownParser
{

    /**
     * Enum representing the different parsing states
     */
    private enum ParserState
    {
        CODE_BLOCK, FUNCTION_CALL, TEXT_ATTACHMENT, LATEX_BLOCK
    }

    private static final String  TATT_CONTEXTSTART                   = "<|ContextStart|>";
    private static final String  TATT_FILEPREFIX                     = "File: ";
    private static final String  TATT_LINESPREFIX                    = "Lines: ";
    private static final String  TATT_CONTENTSTART                   = "<|ContentStart|>";
    private static final String  TATT_CONTENTEND                     = "<|ContentEnd|>";
    private static final String  TATT_CONTEXTEND                     = "<|ContextEnd|>";

    // LaTeX pattern definitions
    private static final Pattern LATEX_INLINE_PATTERN                = Pattern.compile( "\\$(.*?)\\$|\\\\\\((.*?)\\\\\\)" );
    private static final Pattern LATEX_MULTILINE_BLOCK_OPEN_PATTERN  = Pattern.compile( "^[ \\t]*(?:\\$\\$(?!.*\\$\\$)|\\\\\\[(?!.*\\\\\\])).*$" );
    private static final Pattern LATEX_SINGLELINE_BLOCK_OPEN_PATTERN = Pattern.compile( "^[ \\t]*(?:\\$\\$(?:.*\\$\\$)|\\\\\\[(?:.*\\\\\\])).*$" );
    private static final Pattern LATEX_BLOCK_CLOSE_PATTERN           = Pattern.compile( "^.*?(\\$\\$|\\\\\\])[ \\t]*$" );
    private static final Pattern LATEX_LINE_START_PATTERN            = Pattern.compile( "^\\s*(\\$\\$|\\\\\\[)\\s*" );
    private static final Pattern LATEX_LINE_END_PATTERN              = Pattern.compile( "\\s*(\\$\\$|\\\\\\])$" );
    
    // Code and function call patterns
    private static final Pattern CODE_INLINE_PATTERN                 = Pattern.compile("`(.*?)`");
    private static final Pattern CODE_BLOCK_PATTERN                  = Pattern.compile( "^\\s*```([a-zA-Z0-9]*)\\s*$" );
    private static final Pattern FUNCTION_CALL_PATTERN               = Pattern.compile( "^\"function_call\".*" );
    
    // Markdown patterns
    private static final Pattern HEADER_1_PATTERN                    = Pattern.compile( "^# (.*?)$" );
    private static final Pattern HEADER_2_PATTERN                    = Pattern.compile( "^## (.*?)$" );
    private static final Pattern HEADER_3_PATTERN                    = Pattern.compile( "^### (.*?)$" );
    private static final Pattern HEADER_4_PATTERN                    = Pattern.compile( "^#### (.*?)$" );
    private static final Pattern HEADER_5_PATTERN                    = Pattern.compile( "^##### (.*?)$" );
    private static final Pattern HEADER_6_PATTERN                    = Pattern.compile( "^###### (.*?)$" );
    private static final Pattern BOLD_PATTERN                        = Pattern.compile( "\\*\\*(.*?)\\*\\*" );
    private static final Pattern ITALIC_PATTERN                      = Pattern.compile( "\\*(.*?)\\*" );
    private static final Pattern STRIKETHROUGH_PATTERN               = Pattern.compile( "~~(.*?)~~" );
    private static final Pattern INLINE_CODE_PATTERN                 = Pattern.compile( "`(.*?)`" );
    private static final Pattern IMAGE_PATTERN                       = Pattern.compile( "!\\[(.*?)\\]\\((.*?)\\)" );
    private static final Pattern LINK_PATTERN                        = Pattern.compile( "\\[(.*?)\\]\\((.*?)\\)" );
    private static final Pattern BLOCKQUOTE_PATTERN                  = Pattern.compile( "^> (.*?)$" );
    private static final Pattern UNORDERED_LIST_STAR_PATTERN         = Pattern.compile( "^\\* (.*?)$" );
    private static final Pattern UNORDERED_LIST_DASH_PATTERN         = Pattern.compile( "^- (.*?)$" );
    private static final Pattern UNORDERED_LIST_PLUS_PATTERN         = Pattern.compile( "^\\+ (.*?)$" );
    private static final Pattern TASK_LIST_INCOMPLETE_PATTERN        = Pattern.compile( "^- \\[ \\] (.*?)$" );
    private static final Pattern TASK_LIST_COMPLETE_PATTERN          = Pattern.compile( "^- \\[x\\] (.*?)$" );
    private static final Pattern HORIZONTAL_RULE_PATTERN             = Pattern.compile( "^(\\*\\*\\*|---)$" );

    // Using EnumSet for clearer state management
    private EnumSet<ParserState> state                               = EnumSet.noneOf( ParserState.class );

    private final String         prompt;

    public MarkdownParser( String prompt )
    {
        this.prompt = prompt;
    }

    /**
     * Converts the prompt text to an HTML formatted string.
     *
     * @return An HTML formatted string representation of the prompt text.
     */
    public String parseToHtml()
    {
        var out = new StringBuilder();
        var latexBlockBuffer = new StringBuilder();
        
        try (var scanner = new Scanner( prompt ))
        {
            scanner.useDelimiter( "\n" );

            while ( scanner.hasNext() )
            {
                var line = scanner.next();
                var codeBlockMatcher = CODE_BLOCK_PATTERN.matcher( line );
                var functionBlockMatcher = FUNCTION_CALL_PATTERN.matcher( line );
                var latexMultilineBlockOpenMatcher = LATEX_MULTILINE_BLOCK_OPEN_PATTERN.matcher( line );
                var latexSinglelineBlockOpenMatcher = LATEX_SINGLELINE_BLOCK_OPEN_PATTERN.matcher( line );
                var latexCloseMatcher = LATEX_BLOCK_CLOSE_PATTERN.matcher( line );

                if ( state.contains( ParserState.LATEX_BLOCK ) )
                {
                    if ( latexCloseMatcher.find() )
                    {
                        String latexLine = replaceFirstPattern( line, LATEX_LINE_END_PATTERN, "" );
                        latexBlockBuffer.append( latexLine );
                        flushLatexBlockBuffer( latexBlockBuffer, out );
                        state.remove( ParserState.LATEX_BLOCK );
                    }
                    else
                    {
                        latexBlockBuffer.append( line ).append( "\n" );
                    }
                }
                else if ( codeBlockMatcher.find() )
                {
                    var lang = codeBlockMatcher.group( 1 );
                    handleCodeBlock( out, lang );
                }
                else if ( functionBlockMatcher.find() )
                {
                    handleFunctionCall( out, line );
                }
                else if ( line.startsWith( TATT_CONTEXTSTART ) )
                {
                    handleTextAttachmentStart( out, line );
                }
                else if ( latexMultilineBlockOpenMatcher.find() )
                {
                    String latexLine = replaceFirstPattern( line, LATEX_LINE_START_PATTERN, "" );
                    latexBlockBuffer.append( latexLine );
                    state.add( ParserState.LATEX_BLOCK );
                }
                else if ( latexSinglelineBlockOpenMatcher.find() )
                {
                    String latexLine = replaceFirstPattern( line, LATEX_LINE_START_PATTERN, "" );
                    latexLine = replaceFirstPattern( latexLine, LATEX_LINE_END_PATTERN, "" );
                    latexBlockBuffer.append( latexLine );
                    flushLatexBlockBuffer( latexBlockBuffer, out );
                }
                else
                {
                    handleContent( out, line, !scanner.hasNext() );
                }
            }

            // Handle any remaining LaTeX buffer content
            if ( latexBlockBuffer.length() > 0 )
            {
                flushLatexBlockBuffer( latexBlockBuffer, out );
            }
        }
        catch ( Exception e )
        {
            // Add error handling
            out.append( "<div class=\"error\">Error parsing content: " ).append( e.getMessage() ).append( "</div>" );
        }
        return out.toString();
    }

    private void handleTextAttachmentStart( StringBuilder out, String line )
    {
        if ( !state.contains( ParserState.TEXT_ATTACHMENT ) )
        {
            out.append( """
                    <div class="function-call">
                    <details><summary>""" );
            state.add( ParserState.TEXT_ATTACHMENT );
        }
    }

    private void handleFunctionCall( StringBuilder out, String line )
    {
        if ( !state.contains( ParserState.FUNCTION_CALL ) )
        {
            out.append( """
                    <div class="function-call">
                    <details><summary>Function call</summary>
                    <pre>""" ).append( line );
            state.add( ParserState.FUNCTION_CALL );
        }
    }

    private void handleContent( StringBuilder out, String line, boolean lastLine )
    {
        if ( state.contains( ParserState.CODE_BLOCK ) )
        {
            out.append( StringEscapeUtils.escapeHtml4( escapeBackSlashes( line ) ) );
        }
        else if ( state.contains( ParserState.TEXT_ATTACHMENT ) )
        {
            handleTextAttachmentLine( out, line );
            return;
        }
        else
        {
            out.append( convertLineToHtml( StringEscapeUtils.escapeHtml4( line ) ) );
        }

        if ( lastLine )
        {
            // Close any open blocks on the last line
            if ( state.contains( ParserState.CODE_BLOCK ) )
            {
                out.append( "</code></pre>\n" );
                state.remove( ParserState.CODE_BLOCK );
            }
            else if ( state.contains( ParserState.FUNCTION_CALL ) )
            {
                out.append( "</pre></details></div>\n" );
                state.remove( ParserState.FUNCTION_CALL );
            }
        }
        else if ( state.contains( ParserState.CODE_BLOCK ) )
        {
            out.append( "\n" );
        }
        else
        {
            out.append( "<br/>" );
        }
    }

    private void handleTextAttachmentLine( StringBuilder out, String line )
    {
        if ( line.startsWith( TATT_FILEPREFIX ) )
        {
            out.append( "Context: " ).append( line.substring( TATT_FILEPREFIX.length() ) ).append( ", " );
        }
        else if ( line.startsWith( TATT_LINESPREFIX ) )
        {
            out.append( line ).append( "</summary>" );
        }
        else if ( line.startsWith( TATT_CONTENTSTART ) )
        {
            out.append( "<pre>" );
        }
        else if ( line.startsWith( TATT_CONTENTEND ) )
        {
            out.append( "</pre>" );
        }
        else if ( line.startsWith( TATT_CONTEXTEND ) )
        {
            out.append( "</details></div>\n" );
            state.remove( ParserState.TEXT_ATTACHMENT );
        }
        else
        {
            out.append( StringEscapeUtils.escapeHtml4( line ) ).append( "<br/>" );
        }
    }

    private void handleCodeBlock( StringBuilder out, String lang )
    {
        if ( !state.contains( ParserState.CODE_BLOCK ) )
        {
            String codeBlockId = UUID.randomUUID().toString();
            String blockClass = "diff".equals( lang ) ? "diff-block" : "code-block";

            // Removed newline after <pre><code> tag to fix the extra line issue
            out.append( """
                    <div class="codeBlock %s">
                    <div class="codeBlockButtons">
                    <input type="button" onClick="eclipseCopyCode(document.getElementById('%s').innerText)" value="Copy" />
                    <input class="code-only" type="button" onClick="eclipseInsertCode(document.getElementById('%s').innerText)" value="Insert" />
                    <input class="code-only" type="button" onClick="eclipseNewFile(document.getElementById('%s').innerText, '%s')" value="New File" />
                    <input class="code-only" type="button" onClick="eclipseDiffCode(document.getElementById('%s').innerText)" value="Diff" />
                    <input class="diff-only" type="button" onClick="eclipseApplyPatch(document.getElementById('%s').innerText)" value="Apply"/>
                    </div>
                    <pre><code lang="%s" id="%s">""".formatted( blockClass, codeBlockId, codeBlockId, codeBlockId, lang, codeBlockId, codeBlockId, lang,
                    codeBlockId ) );
            state.add( ParserState.CODE_BLOCK );
        }
        else
        {
            out.append( "</code></pre></div>\n" );
            state.remove( ParserState.CODE_BLOCK );
        }
    }

    /**
     * Flushes the accumulated LaTeX content from the buffer into the HTML
     * output. This method wraps the LaTeX content in a {@code <span>} element
     * with a class for styling. The content is Base64 encoded to ensure that
     * any special characters are preserved and do not interfere with the HTML
     * structure.
     *
     * @param latexBlockBuffer
     *            The buffer containing the accumulated LaTeX content.
     * @param htmlOutput
     *            The StringBuilder to which the HTML content is appended.
     */
    private void flushLatexBlockBuffer( StringBuilder latexBlockBuffer, StringBuilder htmlOutput )
    {
        if ( latexBlockBuffer.length() > 0 )
        {
            htmlOutput.append( "<span class=\"block-latex\">" );
            htmlOutput.append( Base64.getEncoder().encodeToString( latexBlockBuffer.toString().getBytes() ) );
            htmlOutput.append( "</span><br/>\n" );
            latexBlockBuffer.setLength( 0 ); // Clear the buffer after
                                              // processing to avoid duplicate
                                              // content.
        }
    }

    /**
     * Replaces the first occurrence of a specified pattern in the input string
     * with the given replacement.
     *
     * @param input
     *            The original string where the replacement is to be made.
     * @param pattern
     *            The regular expression pattern to search for in the input
     *            string.
     * @param replacement
     *            The string to replace the first match of the pattern.
     * @return A new string with the first occurrence of the pattern replaced by
     *         the replacement string.
     */
    private static String replaceFirstPattern( String input, Pattern pattern, String replacement )
    {
        Matcher matcher = pattern.matcher( input );
        return matcher.replaceFirst( replacement );
    }

    /**
     * Escapes backslashes in the input string to prevent issues in HTML
     * rendering.
     * 
     * @param input
     *            The input string
     * @return The input string with backslashes escaped
     */
    public static String escapeBackSlashes( String input )
    {
        return input.replace( "\\", "\\\\" );
    }

    /**
     * Converts markdown syntax to HTML.
     * 
     * @param input
     *            The input string containing markdown
     * @return The HTML representation of the markdown
     */
    public static String convertMarkdownLineToHtml( String input )
    {
        // Headers
        input = replaceAllPattern( input, HEADER_1_PATTERN, "<h1>$1</h1>" );
        input = replaceAllPattern( input, HEADER_2_PATTERN, "<h2>$1</h2>" );
        input = replaceAllPattern( input, HEADER_3_PATTERN, "<h3>$1</h3>" );
        input = replaceAllPattern( input, HEADER_4_PATTERN, "<h4>$1</h4>" );
        input = replaceAllPattern( input, HEADER_5_PATTERN, "<h5>$1</h5>" );
        input = replaceAllPattern( input, HEADER_6_PATTERN, "<h6>$1</h6>" );

        // Bold and italic
        input = replaceAllPattern( input, BOLD_PATTERN, "<strong>$1</strong>" );
        input = replaceAllPattern( input, ITALIC_PATTERN, "<em>$1</em>" );

        // Strikethrough
        input = replaceAllPattern( input, STRIKETHROUGH_PATTERN, "<del>$1</del>" );

        // Inline code
        input = replaceAllPattern( input, INLINE_CODE_PATTERN, "<code>$1</code>" );

        // Images
        input = replaceAllPattern( input, IMAGE_PATTERN, "<img src=\"$2\" alt=\"$1\" />" );

        // Links
        input = replaceAllPattern( input, LINK_PATTERN, "<a href=\"$2\" target=\"_blank\">$1</a>" );

        // Blockquotes
        input = replaceAllPattern( input, BLOCKQUOTE_PATTERN, "<blockquote>$1</blockquote>" );

        // Unordered lists
        input = replaceAllPattern( input, UNORDERED_LIST_STAR_PATTERN, "<li>$1</li>" );
        input = replaceAllPattern( input, UNORDERED_LIST_DASH_PATTERN, "<li>$1</li>" );
        input = replaceAllPattern( input, UNORDERED_LIST_PLUS_PATTERN, "<li>$1</li>" );

        // Task lists
        input = replaceAllPattern( input, TASK_LIST_INCOMPLETE_PATTERN, "<li><input type=\"checkbox\" disabled> $1</li>" );
        input = replaceAllPattern( input, TASK_LIST_COMPLETE_PATTERN, "<li><input type=\"checkbox\" checked disabled> $1</li>" );

        // Horizontal Rule
        input = replaceAllPattern( input, HORIZONTAL_RULE_PATTERN, "<hr>" );

        return input;
    }

    /**
     * Helper method to replace all occurrences of a pattern in a string.
     * 
     * @param input The input string
     * @param pattern The pattern to match
     * @param replacement The replacement string
     * @return The string with all matches replaced
     */
    private static String replaceAllPattern( String input, Pattern pattern, String replacement )
    {
        return pattern.matcher( input ).replaceAll( replacement );
    }

    
    /**
     * Converts a single line of text to HTML, processing inline elements in a specific order:
     * inline code first, then LaTeX expressions, and finally Markdown formatting. This order
     * prevents interference between different syntax patterns and ensures proper escaping.
     *
     * @param line The input line containing any combination of inline code (`code`),
     *             LaTeX ($math$), and Markdown formatting
     * @return The HTML-formatted line with all inline elements converted to appropriate
     *         HTML spans with base64 encoded content
     */
    private static String convertLineToHtml(String line) {
        return convertMarkdownLineToHtml(convertInLineLatexToHtml(convertInlineCodeToHtml(line)));
    }

    /**
     * Converts Markdown inline code segments to HTML spans with base64 encoded content.
     * Processes text enclosed in single backticks (`code`) and transforms them into
     * HTML spans with the content base64 encoded to preserve special characters.
     *
     * @param line Text line potentially containing inline code segments
     * @return Line with inline code converted to HTML spans containing base64 encoded content
     */
    private static String convertInlineCodeToHtml(String line) {
        return CODE_INLINE_PATTERN.matcher(line).replaceAll(match -> {
            String content = match.group(1);
            String base64Content = content;
            return "<span class=\"inline-code\">" + base64Content + "</span>";
        });
    }

    /**
     * Converts inline LaTeX expressions to HTML spans with base64 encoded content.
     * Handles both $...$ and \(...\) syntax for inline math.
     *
     * @param line Text line potentially containing inline LaTeX
     * @return Line with LaTeX expressions converted to HTML spans
     */
    private static String convertInLineLatexToHtml(String line) {
        return LATEX_INLINE_PATTERN.matcher(line).replaceAll(match -> {
            // Check each capture group since we don't know which pattern matched
            for (int i = 1; i <= match.groupCount(); i++) {
                String content = match.group(i);
                if (content != null) 
                {
                    String base64Content = Base64.getEncoder().encodeToString(content.getBytes());
                    return "<span class=\"inline-latex\">" + base64Content + "</span>";
                }
            }
            return match.group(); // fallback, shouldn't happen
        });
    }    
}
