package com.github.gradusnikov.eclipse.assistai.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.github.gradusnikov.eclipse.assistai.mcp.results.Diagnostic;
import com.github.gradusnikov.eclipse.assistai.mcp.results.DiagnosticCode;
import com.github.gradusnikov.eclipse.assistai.models.ToolResponseFormat;
import com.github.gradusnikov.eclipse.assistai.resources.EditResult;
import com.github.gradusnikov.eclipse.assistai.resources.ResourceVersion;

import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;

/**
 * End-to-end check that a structured tool result reaches the wire in the selected
 * response format.
 * <p>
 * This is the guarantee the whole structured-output change rests on: before it, every
 * non-String return value went through {@code toString()}, so a record arrived as
 * {@code EditResult[status=APPLIED, ...]} - readable by nobody and parseable by
 * nothing. Runs under the PDE harness because it touches the MCP schema types.
 */
public class StructuredToolResultPDETest
{
    private static final String TEXT = "Rejected: no change was made to 'Service.java'.";

    private static CallToolResult convert( Object toolReturnValue )
    {
        // operationRegistry is only consulted for long-running tools, so this path
        // needs neither it nor a logger.
        return new McpServerFactory( null, null, ToolResponseFormat.BOTH ).createCallToolResult( toolReturnValue );
    }

    private static CallToolResult convert( Object toolReturnValue, ToolResponseFormat responseFormat )
    {
        return new McpServerFactory( null, null, responseFormat ).createCallToolResult( toolReturnValue );
    }

    private static EditResult conflictingEdit()
    {
        return EditResult.versionConflict( "Demo", "src/Service.java", ResourceVersion.UNKNOWN, 4711L );
    }

    @SuppressWarnings( "unchecked" )
    private static Map<String, Object> structured( CallToolResult result )
    {
        return (Map<String, Object>) result.structuredContent();
    }

    private static String text( CallToolResult result )
    {
        return result.content().stream()
                .filter( TextContent.class::isInstance )
                .map( content -> ( (TextContent) content ).text() )
                .findFirst()
                .orElse( null );
    }

    // ---- structured results ---------------------------------------------

    @Test
    public void sendsThePayloadAsStructuredContent()
    {
        CallToolResult result = convert( StructuredToolResult.of( conflictingEdit(), TEXT ) );

        Map<String, Object> content = structured( result );
        assertNotNull( content, "a structured tool result must populate structuredContent" );
        assertEquals( "REJECTED", content.get( "status" ) );
    }

    @Test
    public void sendsTheFormattedTextAlongsideThePayload()
    {
        CallToolResult result = convert( StructuredToolResult.of( conflictingEdit(), TEXT ) );

        assertEquals( TEXT, text( result ),
                "a text-only client must still receive the formatter's output" );
    }

    @Test
    public void neverSendsARecordToString()
    {
        CallToolResult result = convert( StructuredToolResult.of( conflictingEdit(), TEXT ) );

        assertFalse( text( result ).contains( "EditResult[" ),
                "the text block must come from the formatter, not from the record" );
    }

    @Test
    public void exposesNestedDiagnosticsAsObjects()
    {
        CallToolResult result = convert( StructuredToolResult.of( conflictingEdit(), TEXT ) );

        List<Map<String, Object>> diagnostics =
                (List<Map<String, Object>>) structured( result ).get( "diagnostics" );

        assertEquals( 1, diagnostics.size() );
        assertEquals( DiagnosticCode.VERSION_CONFLICT.name(), diagnostics.get( 0 ).get( "code" ) );
        assertEquals( Boolean.TRUE, diagnostics.get( 0 ).get( "retryable" ) );
    }

    @Test
    public void reportsTheConflictingStampInTheMessage()
    {
        Map<String, Object> content = structured( convert( StructuredToolResult.of( conflictingEdit(), TEXT ) ) );

        List<Map<String, Object>> diagnostics = (List<Map<String, Object>>) content.get( "diagnostics" );
        assertTrue( diagnostics.get( 0 ).get( "message" ).toString().contains( "4711" ) );
    }

    @Test
    public void isNotMarkedAsAnError()
    {
        // A rejected edit is a normal outcome the caller acts on, not a protocol error.
        assertFalse( convert( StructuredToolResult.of( conflictingEdit(), TEXT ) ).isError() );
    }

    @Test
    public void plainFormatKeepsStructuredContentForInternalConsumers()
    {
        CallToolResult result = convert( StructuredToolResult.of( conflictingEdit(), TEXT ), ToolResponseFormat.PLAIN );

        assertEquals( TEXT, text( result ) );
        assertNotNull( result.structuredContent() );
    }

    @Test
    public void jsonFormatOnlySendsStructuredContent()
    {
        CallToolResult result = convert( StructuredToolResult.of( conflictingEdit(), TEXT ), ToolResponseFormat.JSON );

        assertNotNull( result.structuredContent() );
        assertTrue( result.content().isEmpty() );
    }

    @Test
    public void bothFormatSendsTextAndStructuredContent()
    {
        CallToolResult result = convert( StructuredToolResult.of( conflictingEdit(), TEXT ), ToolResponseFormat.BOTH );

        assertEquals( TEXT, text( result ) );
        assertNotNull( result.structuredContent() );
    }

    // ---- unchanged behaviour for text tools ------------------------------

    @Test
    public void leavesPlainStringResultsAlone()
    {
        CallToolResult result = convert( "just some prose" );

        assertEquals( "just some prose", text( result ) );
        assertNull( result.structuredContent(),
                "a tool returning prose must not claim to have structured content" );
    }

    @Test
    public void stillHandlesANullResult()
    {
        CallToolResult result = convert( null );

        assertEquals( "", text( result ) );
        assertNull( result.structuredContent() );
    }

}
