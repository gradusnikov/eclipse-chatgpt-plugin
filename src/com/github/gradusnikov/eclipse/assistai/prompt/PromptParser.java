package com.github.gradusnikov.eclipse.assistai.prompt;

import java.util.Scanner;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.commons.lang3.StringEscapeUtils;

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
                    out.append( StringEscapeUtils.escapeHtml4(line));
                    
                    // handle new lines
                    if ( scanner.hasNext() )
                    {
                        out.append( "\n" );
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
}
