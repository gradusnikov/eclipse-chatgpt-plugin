package com.github.gradusnikov.eclipse.assistai.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.github.gradusnikov.eclipse.assistai.mcp.operations.OperationRegistry;
import com.github.gradusnikov.eclipse.assistai.mcp.results.OperationStatusResponse;
import com.github.gradusnikov.eclipse.assistai.mcp.servers.EclipseIntegrationsMcpServer;
import com.github.gradusnikov.eclipse.assistai.mcp.servers.PDEMcpServer;
import com.github.gradusnikov.eclipse.assistai.models.ToolResponseFormat;

import io.modelcontextprotocol.spec.McpSchema;

public class McpServerFactoryPDETest
{
    static final class StructuredTools
    {
        @com.github.gradusnikov.eclipse.assistai.mcp.annotations.Tool(
                name = "structured", description = "Returns structured data.", outputType = OperationStatusResponse.class )
        public OperationStatusResponse structured()
        {
            return null;
        }
    }

    @Test
    public void operationToolsUseAnnotationDrivenDiscoveryAndExecution() throws Exception
    {
        OperationRegistry registry = new OperationRegistry();
        McpServerFactory.OperationTools tools = new McpServerFactory.OperationTools( registry );
        McpServerFactory factory = new McpServerFactory( null, registry );

        assertEquals( Set.of( "getOperationStatus", "listOperations", "cancelOperation" ), Set.copyOf( factory.listToolNames( tools ) ) );

        ToolExecutor executor = new ToolExecutor( tools );
        String listResult = (String) executor.call( "listOperations", Map.of() ).get();
        assertEquals( "No operations have been started.", listResult );

        // getOperationStatus returns a StructuredToolResult: the payload a caller
        // branches on, plus polling text that says more than the payload does.
        StructuredToolResult statusResult = (StructuredToolResult) executor
                .call( "getOperationStatus", Map.of( "operationId", "op-does-not-exist", "outputOffset", "-100", "outputLimit", "50", "waitSeconds", "0" ) )
                .get();

        assertTrue( statusResult.text().contains( "no operation with id 'op-does-not-exist'" ) );

        OperationStatusResponse status = (OperationStatusResponse) statusResult.data();
        assertEquals( "op-does-not-exist", status.operationId() );
        assertEquals( "UNKNOWN", status.state(), "an unknown id is a state, not only a sentence" );
        assertNull( status.result() );
    }

    @Test
    public void preservesMcpContentAndMixedContentCollections()
    {
        McpServerFactory factory = new McpServerFactory( null, new OperationRegistry() );
        McpSchema.TextContent text = McpSchema.TextContent.builder( "caption" ).build();
        McpSchema.ImageContent image = new McpSchema.ImageContent( null, "AQ==", "image/png", null );

        McpSchema.CallToolResult result = factory.createCallToolResult( List.of( text, image ) );

        assertEquals( 2, result.content().size() );
        assertSame( text, result.content().get( 0 ) );
        assertSame( image, result.content().get( 1 ) );
    }

    @Test
    public void exposesTheImageResourceTool()
    {
        McpServerFactory factory = new McpServerFactory( null, new OperationRegistry() );

        List<String> toolNames = factory.listToolNames( new EclipseIntegrationsMcpServer() );
        assertTrue( toolNames.contains( "readImageResource" ) );
        assertTrue( toolNames.contains( "explainTypeResolution" ) );
    }

    @Test
    public void exposesDeferredRuntimeReloadTools()
    {
        McpServerFactory factory = new McpServerFactory( null, new OperationRegistry() );
        List<String> toolNames = factory.listToolNames( new PDEMcpServer() );

        assertTrue( toolNames.contains( "restartMcpServers" ) );
        assertTrue( toolNames.contains( "reloadWorkspaceBundle" ) );
    }

    @Test
    public void preservesCompleteCallToolResults()
    {
        McpServerFactory factory = new McpServerFactory( null, new OperationRegistry() );
        McpSchema.CallToolResult original = McpSchema.CallToolResult.builder().addTextContent( "failure" ).isError( true ).build();

        assertSame( original, factory.createCallToolResult( original ) );
    }

    @Test
    public void onlyBothAdvertisesAnOutputSchema()
    {
        McpServerFactory factory = new McpServerFactory( null, new OperationRegistry() );
        var methods = StructuredTools.class.getDeclaredMethods();

        var plain = factory.extractAnnotatedTools( ToolResponseFormat.PLAIN, methods ).get( 0 );
        var json = factory.extractAnnotatedTools( ToolResponseFormat.JSON, methods ).get( 0 );
        var both = factory.extractAnnotatedTools( ToolResponseFormat.BOTH, methods ).get( 0 );

        assertNull( plain.outputSchema() );
        assertNull( json.outputSchema(), "Otherwise the MCP SDK adds compatibility text to JSON-only responses." );
        assertNotNull( both.outputSchema() );
    }
}
