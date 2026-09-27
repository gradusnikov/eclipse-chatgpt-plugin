package com.github.gradusnikov.eclipse.assistai.completion;

/**
 * Sanitizes an accumulated code-completion response before it is displayed or
 * inserted into an editor.
 * <p>
 * Streaming transport boundaries are arbitrary. A Markdown opening fence and
 * its language identifier can therefore arrive in different chunks. Sanitizing
 * each chunk independently can leak identifiers such as {@code java} into the
 * editor. This sanitizer operates on the accumulated response instead.
 */
public final class CompletionResponseSanitizer
{
    private static final String BACKTICK_FENCE = "```";
    private static final String TILDE_FENCE = "~~~";

    private CompletionResponseSanitizer()
    {
    }

    /**
     * @param response accumulated model response
     * @return source text suitable for insertion, or {@code null} when no source
     *         text is available yet
     */
    public static String sanitize( String response )
    {
        if ( response == null || response.isBlank() )
        {
            return null;
        }

        String result = response.stripLeading();

        if ( isPartialOpeningFence( result ) )
        {
            return null;
        }

        String openingFence = openingFence( result );
        if ( openingFence != null )
        {
            int firstLineEnd = firstLineEnd( result );
            if ( firstLineEnd < 0 )
            {
                // Still receiving the opening fence and optional language identifier.
                return null;
            }

            int contentStart = firstLineEnd + 1;
            if ( result.charAt( firstLineEnd ) == '\r'
                    && contentStart < result.length()
                    && result.charAt( contentStart ) == '\n' )
            {
                contentStart++;
            }
            result = result.substring( contentStart );

            int closingFence = result.indexOf( openingFence );
            if ( closingFence >= 0 )
            {
                result = result.substring( 0, closingFence );
            }
            else
            {
                result = stripPartialClosingFence( result, openingFence );
            }
        }
        else
        {
            // Preserve the previous behavior for Markdown that follows raw source.
            int fenceIndex = firstFenceIndex( result );
            if ( fenceIndex >= 0 )
            {
                result = result.substring( 0, fenceIndex );
            }
        }

        result = result.trim();
        return result.isBlank() ? null : result;
    }

    private static boolean isPartialOpeningFence( String text )
    {
        return BACKTICK_FENCE.startsWith( text ) || TILDE_FENCE.startsWith( text );
    }

    private static String openingFence( String text )
    {
        if ( text.startsWith( BACKTICK_FENCE ) )
        {
            return BACKTICK_FENCE;
        }
        if ( text.startsWith( TILDE_FENCE ) )
        {
            return TILDE_FENCE;
        }
        return null;
    }

    private static int firstLineEnd( String text )
    {
        int lf = text.indexOf( '\n' );
        int cr = text.indexOf( '\r' );

        if ( lf < 0 )
        {
            return cr;
        }
        if ( cr < 0 )
        {
            return lf;
        }
        return Math.min( lf, cr );
    }

    private static int firstFenceIndex( String text )
    {
        int backtickIndex = text.indexOf( BACKTICK_FENCE );
        int tildeIndex = text.indexOf( TILDE_FENCE );

        if ( backtickIndex < 0 )
        {
            return tildeIndex;
        }
        if ( tildeIndex < 0 )
        {
            return backtickIndex;
        }
        return Math.min( backtickIndex, tildeIndex );
    }

    private static String stripPartialClosingFence( String text, String fence )
    {
        for ( int length = fence.length() - 1; length > 0; length-- )
        {
            String prefix = fence.substring( 0, length );
            if ( text.endsWith( prefix ) )
            {
                return text.substring( 0, text.length() - length );
            }
        }
        return text;
    }
}
