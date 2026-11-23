package com.github.gradusnikov.eclipse.assistai.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.github.gradusnikov.eclipse.assistai.mcp.operations.OperationRegistry;
import com.github.gradusnikov.eclipse.assistai.mcp.results.Diagnostic;
import com.github.gradusnikov.eclipse.assistai.mcp.results.DiagnosticCode;
import com.github.gradusnikov.eclipse.assistai.mcp.results.OperationStatusResponse;
import com.github.gradusnikov.eclipse.assistai.mcp.results.TestNGLaunchConfigurationResponse;
import com.github.gradusnikov.eclipse.assistai.mcp.servers.EclipseRunnerMcpServer;
import com.github.gradusnikov.eclipse.plugin.assistai.mcp.operations.LongExecutionToolPDETest;
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

    // --- typed parameters ---

    private static Map<String, Object> inputSchema( Object server, String toolName )
    {
        McpServerFactory factory = new McpServerFactory( null, new OperationRegistry() );
        return factory.extractAnnotatedTools( ToolResponseFormat.PLAIN, new ToolExecutor( server ).getFunctions() ).stream()
                .filter( tool -> tool.name().equals( toolName ) ).findFirst().orElseThrow().inputSchema();
    }

    @Test
    public void advertisesTimeoutAsAnInteger()
    {
        @SuppressWarnings( "unchecked" )
        var properties = (Map<String, Map<String, Object>>) inputSchema( new EclipseRunnerMcpServer(), "launchConfiguration" ).get( "properties" );

        assertEquals( "integer", properties.get( "timeout" ).get( "type" ) );
        assertEquals( "string", properties.get( "configurationName" ).get( "type" ) );
    }

    @Test
    public void acceptsATimeoutSentAsANumberOrAsAString()
    {
        var schema = inputSchema( new EclipseRunnerMcpServer(), "launchConfiguration" );
        var validator = McpServerFactory.createJsonSchemaValidator();

        assertTrue( validator.validate( schema, Map.of( "configurationName", "X", "timeout", 120 ) ).valid(),
                "a client following the schema sends a number" );
        assertTrue( validator.validate( schema, Map.of( "configurationName", "X", "timeout", "120" ) ).valid(),
                "a client that learnt the old all-string schema sends a string" );
        assertFalse( validator.validate( schema, Map.of( "configurationName", "X", "timeout", "soon" ) ).valid() );
        assertFalse( validator.validate( schema, Map.of( "configurationName", "X", "timeout", 1.5 ) ).valid() );
    }

    @Test
    public void acceptsABooleanSentAsABooleanOrAsAString()
    {
        Map<String, Object> schema = Map.of( "type", "object", "properties", Map.of( "preview", Map.of( "type", "boolean" ) ) );
        var validator = McpServerFactory.createJsonSchemaValidator();

        assertTrue( validator.validate( schema, Map.of( "preview", true ) ).valid() );
        assertTrue( validator.validate( schema, Map.of( "preview", "false" ) ).valid() );
        assertFalse( validator.validate( schema, Map.of( "preview", "maybe" ) ).valid() );
    }

    // --- isError ---

    @Test
    public void aResponseWithAFailingDiagnosticIsAnError()
    {
        McpServerFactory factory = new McpServerFactory( null, new OperationRegistry() );
        var missing = TestNGLaunchConfigurationResponse.failure( "Demo",
                Diagnostic.fatal( DiagnosticCode.TESTNG_LAUNCH_TYPE_MISSING, "TestNG for Eclipse is not installed." ) );

        McpSchema.CallToolResult result = factory.createCallToolResult( missing );

        assertTrue( result.isError() );
        assertNotNull( result.structuredContent(), "the diagnostic is still there to branch on" );
    }

    @Test
    public void aSuccessfulResponseIsNotAnError()
    {
        McpServerFactory factory = new McpServerFactory( null, new OperationRegistry() );
        var saved = TestNGLaunchConfigurationResponse.success( "CalculatorTest", true, "Demo", "CLASS",
                List.of( "com.example.CalculatorTest" ), List.of(), List.of(), List.of(), List.of(), List.of() );

        assertFalse( factory.createCallToolResult( saved ).isError() );
    }

    @Test
    public void warningDiagnosticsDoNotMakeACallAnError()
    {
        assertFalse( McpServerFactory.reportsFailure( Map.of( "diagnostics",
                List.of( Map.of( "code", DiagnosticCode.COVERAGE_UNAVAILABLE.name() ),
                        Map.of( "code", DiagnosticCode.EDITOR_REVEAL_FAILED.name() ) ) ) ) );
        assertTrue( McpServerFactory.reportsFailure( Map.of( "diagnostics",
                List.of( Map.of( "code", DiagnosticCode.COVERAGE_UNAVAILABLE.name() ),
                        Map.of( "code", DiagnosticCode.PROJECT_NOT_FOUND.name() ) ) ) ) );
    }

    @Test
    public void aFailedOrUnknownOperationIsAnErrorButACancelledOneIsNot()
    {
        assertTrue( McpServerFactory.reportsFailure( Map.of( "operationId", "op-1", "state", "FAILED" ) ) );
        assertTrue( McpServerFactory.reportsFailure( Map.of( "operationId", "op-1", "state", "UNKNOWN" ) ) );
        assertFalse( McpServerFactory.reportsFailure( Map.of( "operationId", "op-1", "state", "CANCELLED" ) ) );
        assertTrue( McpServerFactory.reportsFailure( Map.of( "operationId", "op-1", "state", "COMPLETED",
                "result", Map.of( "diagnostics", List.of( Map.of( "code", "LAUNCH_FAILED" ) ) ) ) ),
                "a finished operation's result is judged as if it had been returned inline" );
    }

    @Test
    public void anErrorStringFromAProseToolIsAnError()
    {
        McpServerFactory factory = new McpServerFactory( null, new OperationRegistry() );

        assertTrue( factory.createCallToolResult( "Error: no project named 'X'." ).isError() );
        assertFalse( factory.createCallToolResult( "Opened project 'X'." ).isError() );
    }

    @Test
    public void aLongExecutionToolThatThrowsIsAnError()
    {
        McpServerFactory factory = new McpServerFactory( null, new OperationRegistry(), ToolResponseFormat.PLAIN );
        var boom = factory.createToolSpecifications( new LongExecutionToolPDETest.FailingServer(), Set.of(), "", ToolResponseFormat.PLAIN )
                .stream().filter( spec -> spec.tool().name().equals( "boom" ) ).findFirst().orElseThrow();

        McpSchema.CallToolResult result = boom.callHandler().apply( null, new McpSchema.CallToolRequest( "boom", Map.of() ) );

        assertTrue( result.isError() );
        assertTrue( ( (McpSchema.TextContent) result.content().get( 0 ) ).text().contains( "kaboom" ) );
    }
}
