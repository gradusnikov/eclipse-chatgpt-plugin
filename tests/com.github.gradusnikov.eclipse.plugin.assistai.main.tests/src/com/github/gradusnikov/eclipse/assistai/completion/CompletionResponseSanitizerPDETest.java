package com.github.gradusnikov.eclipse.assistai.completion;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

public class CompletionResponseSanitizerPDETest
{
    @Test
    public void rawSourceIsUnchanged()
    {
        assertEquals(
                "System.out.println(name);",
                CompletionResponseSanitizer.sanitize( "System.out.println(name);" ) );
    }

    @Test
    public void backtickFenceAndLanguageIdentifierAreRemoved()
    {
        assertEquals(
                "System.out.println(name);",
                CompletionResponseSanitizer.sanitize(
                        "```java\nSystem.out.println(name);\n```" ) );
    }

    @Test
    public void tildeFenceAndLanguageIdentifierAreRemoved()
    {
        assertEquals(
                "System.out.println(name);",
                CompletionResponseSanitizer.sanitize(
                        "~~~java\nSystem.out.println(name);\n~~~" ) );
    }

    @Test
    public void languageIdentifierNeverLeaksWhileOpeningFenceStreams()
    {
        String[] chunks = {
                "```",
                "ja",
                "va",
                "\n",
                "System.out.",
                "println(name);",
                "\n",
                "```"
        };

        StringBuilder accumulated = new StringBuilder();
        for ( int i = 0; i < chunks.length; i++ )
        {
            accumulated.append( chunks[i] );
            String sanitized = CompletionResponseSanitizer.sanitize( accumulated.toString() );

            if ( i < 3 )
            {
                assertNull( sanitized );
            }
            else if ( sanitized != null )
            {
                assertFalse( sanitized.startsWith( "java" ),
                        "language identifier must never be exposed as source code" );
            }
        }

        assertEquals(
                "System.out.println(name);",
                CompletionResponseSanitizer.sanitize( accumulated.toString() ) );
    }

    @Test
    public void partialClosingFenceIsNotDisplayed()
    {
        assertEquals(
                "return value;",
                CompletionResponseSanitizer.sanitize( "```java\nreturn value;\n``" ) );
    }

    @Test
    public void markdownAfterRawSourceIsTruncated()
    {
        assertEquals(
                "return value;",
                CompletionResponseSanitizer.sanitize(
                        "return value;\n```\nExplanation that must not be inserted" ) );
    }

    @Test
    public void blankResponseProducesNoCompletion()
    {
        assertNull( CompletionResponseSanitizer.sanitize( null ) );
        assertNull( CompletionResponseSanitizer.sanitize( "" ) );
        assertNull( CompletionResponseSanitizer.sanitize( "   \n\t" ) );
    }
}
