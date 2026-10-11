package com.github.gradusnikov.eclipse.assistai.jobs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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

    @Test
    public void cachedContentIsReplacedInEveryFormat()
    {
        String fileContent = "large file content that must not be repeated";
        String cachedReference = "[Resource cached: workspace:///Demo/File.java]";
        CallToolResult cached = CallToolResult.builder()
                .addTextContent( fileContent )
                .structuredContent( Map.of( "filePath", "File.java", "content", fileContent ) )
                .isError( false )
                .build();

        for ( ToolResponseFormat format : ToolResponseFormat.values() )
        {
            String formatted = ToolResultTextFormatter.format( cached, format, cachedReference );

            assertTrue( formatted.contains( cachedReference ), format.name() );
            assertFalse( formatted.contains( fileContent ), format.name() );
        }
    }

    @Test
    public void cachedJsonKeepsStructuredMetadata()
    {
        String cachedReference = "[Resource cached: workspace:///Demo/File.java]";

        String formatted = ToolResultTextFormatter.format( result, ToolResponseFormat.JSON, cachedReference );

        assertTrue( formatted.contains( "\"value\" : 42" ) );
        assertTrue( formatted.contains( "\"content\" : \"[Resource cached:" ) );
    }
}
