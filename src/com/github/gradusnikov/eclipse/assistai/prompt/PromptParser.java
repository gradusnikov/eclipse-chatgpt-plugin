package com.github.gradusnikov.eclipse.assistai.prompt;

import java.util.Scanner;
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
        StringBuilder out = new StringBuilder();
        
        try( Scanner scanner = new Scanner(prompt) )
        {
            scanner.useDelimiter( "\n" );
            Pattern codeBlockPattern = Pattern.compile( "^```([a-z]*)$" );
            while ( scanner.hasNext() )
            {
                String  line    = scanner.next();
                Matcher codeBlockMatcher = codeBlockPattern.matcher( line );
                
                if ( codeBlockMatcher.find() )
                {
                    if( (state & CODE_BLOCK_STATE) != CODE_BLOCK_STATE )
                    {
                        out.append( String.format( "<pre><code lang=\"%s\">\n",  codeBlockMatcher.group(1) ) );
                        state ^= CODE_BLOCK_STATE;
                    }
                    else
                    {
                        out.append( "</code></pre>\n" );
                        state ^= CODE_BLOCK_STATE;
                    }
                }
                else
                {
                    if ( (state & CODE_BLOCK_STATE) == CODE_BLOCK_STATE  )
                    {
                        
                        out.append(  StringEscapeUtils.escapeHtml4(escapeBackSlashes(line)) );
                    }
                    else
                    {
                        out.append( markdown( StringEscapeUtils.escapeHtml4(line) ) );
                    }
                    
                    // handle new lines
                    if ( scanner.hasNext() )
                    {
                        if ( (state & CODE_BLOCK_STATE) == CODE_BLOCK_STATE  )
                        {
                            out.append( "\n" );
                        }
                        else
                        {
                            out.append( "<br/>" );
                        }
                        
                    }
                    else if ( (state & CODE_BLOCK_STATE) == CODE_BLOCK_STATE  ) // close opened code blocks
                    {
                        out.append( "</code></pre>\n" );
                    }
                }
            }
        }
        return out.toString();
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
