package com.github.gradusnikov.eclipse.assistai.jobs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import org.junit.jupiter.api.Test;

import com.github.gradusnikov.eclipse.assistai.models.ToolResponseFormat;

import io.modelcontextprotocol.spec.McpSchema.CallToolResult;

public class ToolResultTextFormatterPDETest
{
    private final CallToolResult result = CallToolResult.builder()
            .addTextContent( "plain result" )
            .structuredContent( Map.of( "value", 42 ) )
            .isError( false )
            .build();

    @Test
    public void plainSelectsTextContent()
    {
        assertEquals( "plain result", ToolResultTextFormatter.format( result, ToolResponseFormat.PLAIN, null ) );
    }

    @Test
    public void jsonSelectsStructuredContent()
    {
        String formatted = ToolResultTextFormatter.format( result, ToolResponseFormat.JSON, null );

        assertTrue( formatted.contains( "\"value\" : 42" ) );
        assertTrue( !formatted.contains( "plain result" ) );
    }

    @Test
    public void bothContainsBothRepresentations()
    {
        String formatted = ToolResultTextFormatter.format( result, ToolResponseFormat.BOTH, null );

        assertTrue( formatted.startsWith( "plain result\n\nStructured content:\n" ) );
        assertTrue( formatted.contains( "\"value\" : 42" ) );
    }

    @Test
    public void jsonFallsBackToPlainWhenStructuredContentIsMissing()
    {
        CallToolResult plainOnly = CallToolResult.builder()
                .addTextContent( "plain only" )
                .isError( false )
                .build();

        assertEquals( "plain only",
                ToolResultTextFormatter.format( plainOnly, ToolResponseFormat.JSON, null ) );
    }
}
