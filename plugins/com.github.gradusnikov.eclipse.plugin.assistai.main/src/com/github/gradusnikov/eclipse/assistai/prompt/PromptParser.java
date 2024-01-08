package com.github.gradusnikov.eclipse.assistai.prompt;

import java.util.Scanner;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.commons.lang3.StringEscapeUtils;

/**
 * A utility class for parsing and converting a text prompt to an HTML formatted string.
 */
public class PromptParser
{
    
    private static final int DEFAULT_STATE = 0;
    private static final int CODE_BLOCK_STATE = 1;
    private static final int FUNCION_CALL_STATE = 2;
    
    
    private int state = DEFAULT_STATE;
    
    private final String prompt;
    
    public PromptParser( String prompt )
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
        
        try( var scanner = new Scanner(prompt) )
        {
            scanner.useDelimiter( "\n" );
            var codeBlockPattern = Pattern.compile( "^```([aA-zZ]*)$" );
            var functionCallPattern = Pattern.compile( "^\"function_call\".*" );
            while ( scanner.hasNext() )
            {
                var  line    = scanner.next();
                var codeBlockMatcher = codeBlockPattern.matcher( line );
                var functionBlockMatcher = functionCallPattern.matcher( line );
                
                if ( codeBlockMatcher.find() )
                {
                    var lang = codeBlockMatcher.group(1);
                    handleCodeBlock( out, lang );
                }
                else if ( functionBlockMatcher.find() )
                {
                    handleFunctionCall( out, line );
                }
                else
                {
                    handleNonCodeBlock( out, line, !scanner.hasNext() );
                }
            }
        }
        return out.toString();
    }

    private void handleFunctionCall( StringBuilder out, String line )
    {
        if( (state & FUNCION_CALL_STATE) != FUNCION_CALL_STATE )
        {
            out.append( """
                    <div class="function-call">
                    <details><summary>Function call</summary>
                    <pre>
                    """ + line
                    
            );
            state ^= FUNCION_CALL_STATE;
            
        }
    }

    private void handleNonCodeBlock( StringBuilder out,  String line, boolean lastLine )
    {
        if ( (state & CODE_BLOCK_STATE) == CODE_BLOCK_STATE  )
        {
            
            out.append(  StringEscapeUtils.escapeHtml4(escapeBackSlashes(line)) );
        }
        else
        {
            out.append( markdown( StringEscapeUtils.escapeHtml4(line) ) );
        }
        
        if ( lastLine && (state & CODE_BLOCK_STATE) == CODE_BLOCK_STATE  ) // close opened code blocks
        {
        	out.append( "</code></pre>\n" );
        }
        if ( lastLine && (state & FUNCION_CALL_STATE) == FUNCION_CALL_STATE  ) // close opened code blocks
        {
            out.append( "</pre></div>\n" );
        }
        else if ( (state & CODE_BLOCK_STATE) == CODE_BLOCK_STATE  )
        {
                out.append( "\n" );
        }
        else
        {
            out.append( "<br/>" );
        }
    }

    private void handleCodeBlock( StringBuilder out, String lang )
    {
        if( (state & CODE_BLOCK_STATE) != CODE_BLOCK_STATE )
        {
            String codeBlockId = UUID.randomUUID().toString();
            out.append( """ 
                    <input type="button" onClick="eclipseCopyCode(document.getElementById('${codeBlockId}').innerText)" value="Copy Code" />
                    <input type="${showApplyPatch}" onClick="eclipseApplyPatch(document.getElementById('${codeBlockId}').innerText)" value="ApplyPatch"/>
                    <pre><code lang="${lang}" id="${codeBlockId}">
                    """
                    .replace( "${lang}", lang )
                    .replace( "${codeBlockId}", codeBlockId )
                    .replace( "${showApplyPatch}", "diff".equals(lang) ? "button" : "hidden" ) // show "Apply Patch" button for diffs
            );
            state ^= CODE_BLOCK_STATE;
        }
        else
        {
            out.append( "</code></pre>\n" );
            state ^= CODE_BLOCK_STATE;
        }
    }
    
    public static String escapeBackSlashes( String input )
    {
        input = input.replace( "\\", "\\\\" );
        return input;
    }
    
    public static String markdown(String input) 
    {
        // Replace headers
        input = input.replaceAll("^# (.*?)$", "<h1>$1</h1>");
        input = input.replaceAll("^## (.*?)$", "<h2>$1</h2>");
        input = input.replaceAll("^### (.*?)$", "<h3>$1</h3>");
        input = input.replaceAll("^#### (.*?)$", "<h4>$1</h4>");
        input = input.replaceAll("^##### (.*?)$", "<h5>$1</h5>");
        input = input.replaceAll("^###### (.*?)$", "<h6>$1</h6>");
        
        // Replace **text** with <strong>text</strong>
        input = input.replaceAll("\\*\\*(.*?)\\*\\*", "<strong>$1</strong>");

        // Replace *text* with <em>text</em>
        input = input.replaceAll("\\*(.*?)\\*", "<em>$1</em>");

        // Replace `text` with <i>text</i>
        input = input.replaceAll("`(.*?)`", "<i>$1</i>");
        
        // Replace [text](url) with <a href="url">text</a>
        input = input.replaceAll("\\[(.*?)\\]\\((.*?)\\)", "<a href=\"$2\">$1</a>");

        // Inline code
        input = input.replaceAll("`([^`]+)`", "<code>$1</code>");
        
        // Links
        input = input.replaceAll("\\[(.*?)\\]\\((.*?)\\)", "<a href=\"$2\" target=\"_blank\">$1</a>");

        
        // Blockquotes
        input = input.replaceAll("^> (.*?)$", "<blockquote>$1</blockquote>");
        
        // Unordered lists
        input = input.replaceAll("^\\* (.*?)$", "<li>$1</li>");
        input = input.replaceAll("^- (.*?)$", "<li>$1</li>");
        input = input.replaceAll("^\\+ (.*?)$", "<li>$1</li>");
        
        // Ordered lists
//        input = input.replaceAll("^\\d+\\. (.*?)$", "<li>$1</li>");
        
        // Horizontal Rule
        input = input.replaceAll("^(\\*\\*\\*|---)$", "<hr>");
        
        return input;
    }
}
