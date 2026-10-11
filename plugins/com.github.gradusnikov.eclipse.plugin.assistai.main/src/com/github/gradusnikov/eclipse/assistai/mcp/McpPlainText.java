package com.github.gradusnikov.eclipse.assistai.mcp;

import java.util.List;
import java.util.Map;

/**
 * Renders structured MCP data as complete, human-readable text rather than JSON.
 */
public final class McpPlainText
{
    /**
     * Prevents instantiation of this utility class.
     */
    private McpPlainText()
    {
    }

    /**
     * Renders a structured MCP payload as readable indented text.
     *
     * @param payload The structured payload
     *
     * @return The plain-text representation
     */
    public static String render( Map<String, Object> payload )
    {
        StringBuilder text = new StringBuilder();
        Object summary = payload.get( "summaryText" );
        if ( summary instanceof String value && !value.isBlank() )
        {
            text.append( value ).append( "\n\n" );
        }
        appendMap( text, payload, 0, true );
        return text.toString().stripTrailing();
    }

    /**
     * Appends the entries of a map at the requested indentation level.
     *
     * @param text        The output buffer
     * @param map         The map to render
     * @param indent      The number of leading spaces
     * @param omitSummary Whether to omit the separately rendered summary field
     */
    private static void appendMap( StringBuilder text, Map<?, ?> map, int indent, boolean omitSummary )
    {
        for ( Map.Entry<?, ?> entry : map.entrySet() )
        {
            String name = String.valueOf( entry.getKey() );
            if ( omitSummary && "summaryText".equals( name ) )
            {
                continue;
            }
            appendEntry( text, entry, indent );
        }
    }

    /**
     * Appends a map entry and delegates rendering of its value.
     *
     * @param text   The output buffer
     * @param entry  The map entry to render
     * @param indent The number of leading spaces
     */
    private static void appendEntry( StringBuilder text, Map.Entry<?, ?> entry, int indent )
    {
        indent( text, indent ).append( String.valueOf( entry.getKey() ) ).append( ':' );
        appendValue( text, entry.getValue(), indent );
    }

    /**
     * Appends a scalar, map, list, or multiline string value.
     *
     * @param text   The output buffer
     * @param value  The value to render
     * @param indent The indentation of the owning entry
     */
    private static void appendValue( StringBuilder text, Object value, int indent )
    {
        if ( value instanceof Map<?, ?> map )
        {
            text.append( '\n' );
            appendMap( text, map, indent + 2, false );
        }
        else if ( value instanceof List<?> list )
        {
            if ( list.isEmpty() )
            {
                text.append( " []\n" );
            }
            else
            {
                text.append( '\n' );
                appendList( text, list, indent + 2 );
            }
        }
        else if ( value instanceof String string && string.indexOf( '\n' ) >= 0 )
        {
            text.append( '\n' );
            String fence = fence( string );
            indent( text, indent + 2 ).append( fence ).append( '\n' );
            text.append( string );
            if ( !string.endsWith( "\n" ) )
            {
                text.append( '\n' );
            }
            indent( text, indent + 2 ).append( fence ).append( '\n' );
        }
        else
        {
            text.append( ' ' ).append( String.valueOf( value ) ).append( '\n' );
        }
    }

    /**
     * Appends list items at the requested indentation level.
     *
     * @param text   The output buffer
     * @param list   The list to render
     * @param indent The number of leading spaces before each dash
     */
    private static void appendList( StringBuilder text, List<?> list, int indent )
    {
        for ( Object item : list )
        {
            indent( text, indent ).append( '-' );
            if ( item instanceof Map<?, ?> map )
            {
                if ( map.isEmpty() )
                {
                    text.append( " {}\n" );
                    continue;
                }

                var entries = map.entrySet().iterator();
                Map.Entry<?, ?> first = entries.next();
                text.append( ' ' ).append( String.valueOf( first.getKey() ) ).append( ':' );
                appendValue( text, first.getValue(), indent + 2 );
                while ( entries.hasNext() )
                {
                    appendEntry( text, entries.next(), indent + 2 );
                }
            }
            else if ( item instanceof List<?> nested )
            {
                text.append( '\n' );
                appendList( text, nested, indent + 2 );
            }
            else
            {
                text.append( ' ' ).append( String.valueOf( item ) ).append( '\n' );
            }
        }
    }

    /**
     * Appends indentation to the output buffer.
     *
     * @param text   The output buffer
     * @param spaces The number of spaces to append
     *
     * @return The supplied output buffer
     */
    private static StringBuilder indent( StringBuilder text, int spaces )
    {
        return text.append( " ".repeat( spaces ) );
    }

    /**
     * Selects a Markdown fence longer than any backtick run in the content.
     *
     * @param content The multiline content to fence
     *
     * @return A safe Markdown fence
     */
    private static String fence( String content )
    {
        int longest = 0;
        int run = 0;
        for ( int index = 0; index < content.length(); index++ )
        {
            run = content.charAt( index ) == '`' ? run + 1 : 0;
            longest = Math.max( longest, run );
        }
        return "`".repeat( Math.max( 3, longest + 1 ) );
    }
}
