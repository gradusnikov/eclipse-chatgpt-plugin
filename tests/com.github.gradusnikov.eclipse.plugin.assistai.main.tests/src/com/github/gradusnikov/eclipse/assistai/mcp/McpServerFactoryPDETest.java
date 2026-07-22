package com.github.gradusnikov.eclipse.assistai.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.github.gradusnikov.eclipse.assistai.mcp.operations.OperationRegistry;
import com.github.gradusnikov.eclipse.assistai.mcp.servers.EclipseIntegrationsMcpServer;

import io.modelcontextprotocol.spec.McpSchema;

public class McpServerFactoryPDETest
{
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

        String statusResult = (String) executor
                .call( "getOperationStatus", Map.of( "operationId", "op-does-not-exist", "outputOffset", "-100", "outputLimit", "50", "waitSeconds", "0" ) )
                .get();
        assertTrue( statusResult.contains( "no operation with id 'op-does-not-exist'" ) );
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

        assertTrue( factory.listToolNames( new EclipseIntegrationsMcpServer() ).contains( "readImageResource" ) );
    }

    @Test
    public void preservesCompleteCallToolResults()
    {
        McpServerFactory factory = new McpServerFactory( null, new OperationRegistry() );
        McpSchema.CallToolResult original = McpSchema.CallToolResult.builder().addTextContent( "failure" ).isError( true ).build();

        assertSame( original, factory.createCallToolResult( original ) );
    }
}
