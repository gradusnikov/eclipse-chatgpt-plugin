package com.github.gradusnikov.eclipse.assistai.jobs;

import java.util.Collections;
import java.util.Optional;

import com.github.gradusnikov.eclipse.assistai.mcp.McpJson;
import com.github.gradusnikov.eclipse.assistai.models.ToolResponseFormat;

import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;

/**
 * Selects the representation of a tool result that is added to the conversation.
 */
final class ToolResultTextFormatter
{
    /**
     * Prevents instantiation of this utility class.
     */
    private ToolResultTextFormatter()
    {
    }

    /**
     * Formats an MCP tool result for insertion into the model conversation.
     *
     * @param result          The MCP tool result
     * @param format          The requested response representation
     * @param cachedReference Replacement text for cached content, or {@code null}
     *
     * @return The formatted conversation text
     */
    static String format( CallToolResult result, ToolResponseFormat format, String cachedReference )
    {
        ToolResponseFormat selectedFormat = format == null ? ToolResponseFormat.PLAIN : format;
        String plain = plainText( result, cachedReference );
        String json = result.structuredContent() == null ? "" : McpJson.toJson( result.structuredContent() );

        String text = switch ( selectedFormat )
        {
            case PLAIN -> plain;
            case JSON -> json.isBlank() ? plain : json;
            case BOTH -> combine( plain, json );
        };

        return result.isError() ? "Error: " + text : text;
    }

    /**
     * Collects the textual content of a tool result unless cached content supplies
     * a replacement reference.
     *
     * @param result          The MCP tool result
     * @param cachedReference Replacement text for cached content, or {@code null}
     *
     * @return The plain-text representation
     */
    private static String plainText( CallToolResult result, String cachedReference )
    {
        if ( cachedReference != null )
        {
            return cachedReference;
        }

        StringBuilder text = new StringBuilder();
        for ( McpSchema.Content content : Optional.ofNullable( result.content() ).orElse( Collections.emptyList() ) )
        {
            if ( content instanceof McpSchema.TextContent textContent )
            {
                if ( !text.isEmpty() )
                {
                    text.append( '\n' );
                }
                text.append( textContent.text() );
            }
        }
        return text.toString();
    }

    /**
     * Combines plain and structured representations without adding empty sections.
     *
     * @param plain The plain-text representation
     * @param json  The JSON representation
     *
     * @return Both representations, or the non-empty one
     */
    private static String combine( String plain, String json )
    {
        if ( plain.isBlank() )
        {
            return json;
        }
        if ( json.isBlank() )
        {
            return plain;
        }
        return plain + "\n\nStructured content:\n" + json;
    }
}
