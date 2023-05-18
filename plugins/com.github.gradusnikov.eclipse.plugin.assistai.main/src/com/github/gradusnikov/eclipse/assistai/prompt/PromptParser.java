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
            var codeBlockPattern = Pattern.compile( "^```([a-z]*)$" );
            while ( scanner.hasNext() )
            {
                var  line    = scanner.next();
                var codeBlockMatcher = codeBlockPattern.matcher( line );
                
                if ( codeBlockMatcher.find() )
                {
                    var lang = codeBlockMatcher.group(1);
                    handleCodeBlock( out, lang );
                }
                else
                {
                    handleNonCodeBlock( out, line, !scanner.hasNext() );
                }
            }
        }
        return out.toString();
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
    
    public static String markdown(String input) {
        // Replace **text** with <strong>text</strong>
        input = input.replaceAll("\\*\\*(.*?)\\*\\*", "<strong>$1</strong>");

        // Replace *text* with <em>text</em>
        input = input.replaceAll("\\*(.*?)\\*", "<em>$1</em>");

        // Replace `text` with <i>text</i>
        input = input.replaceAll("`(.*?)`", "<i>$1</i>");

        
        // Replace [text](url) with <a href="url">text</a>
        input = input.replaceAll("\\[(.*?)\\]\\((.*?)\\)", "<a href=\"$2\">$1</a>");

        return input;
    }
}
