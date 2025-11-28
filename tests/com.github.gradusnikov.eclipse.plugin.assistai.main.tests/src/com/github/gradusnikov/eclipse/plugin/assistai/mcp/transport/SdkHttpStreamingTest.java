package com.github.gradusnikov.eclipse.plugin.assistai.mcp.transport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.Map;

import org.apache.catalina.Context;
import org.apache.catalina.LifecycleException;
import org.apache.catalina.startup.Tomcat;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.json.jackson.JacksonMcpJsonMapperSupplier;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.HttpServletStreamableServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;
import jakarta.servlet.Servlet;

/**
 * Quick test with Claude Code:
 * <code>
 * claude mcp add --transport http test http://172.24.208.1:8123/mcp/calculator
 * claude mcp list
 * claude mcp get
 * claude "Using test tool perform the following operation: 2+2"
 * claude mcp remove test
 * <p>
 * Reference: https://code.claude.com/docs/en/mcp
 */
@Timeout(15)
public class SdkHttpStreamingTest
{
    static Logger logger = LoggerFactory.getLogger( SdkHttpStreamingTest.class );
    
    private static final String MCP_ENDPOINT = "/mcp/calculator";
    // Bind to all interfaces (0.0.0.0) to allow WSL access
    private static final String HOST = "0.0.0.0";
    private static final int PORT = findAvailablePort(8123);
    
    private Tomcat tomcat;
    private McpSyncServer mcpServer;
    private HttpServletStreamableServerTransportProvider transportProvider;

    @BeforeEach
    public void beforeEach() throws Exception
    {
        // Sync tool specification
        var schema = """
                    {
                      "type" : "object",
                      "id" : "urn:jsonschema:Operation",
                      "properties" : {
                        "operation" : {
                          "type" : "string"
                        },
                        "a" : {
                          "type" : "number"
                        },
                        "b" : {
                          "type" : "number"
                        }
                      }
                    }
                    """;
        
        var jsonMapperSupplier = new JacksonMcpJsonMapperSupplier();
        var tool = McpSchema.Tool.builder()
                .name("calculator")
                .description("Basic calculator")
                .inputSchema(jsonMapperSupplier.get(), schema)
                .build();
        
        var syncToolSpecification = McpServerFeatures.SyncToolSpecification.builder()
                .tool(tool)
                .callHandler((exchange, request) -> {
                    var a = request.arguments().get("a");
                    var b = request.arguments().get("b");
                    var operation = request.arguments().get("operation");
                    
                    double numA = ((Number) a).doubleValue();
                    double numB = ((Number) b).doubleValue();
                    double result = 0;
                    
                    switch (String.valueOf(operation)) {
                        case "add":
                            result = numA + numB;
                            break;
                        case "subtract":
                            result = numA - numB;
                            break;
                        case "multiply":
                            result = numA * numB;
                            break;
                        case "divide":
                            result = numA / numB;
                            break;
                    }
                    
                    var resultText = String.format("Result: %.2f %s %.2f = %.2f", 
                            numA, operation, numB, result);
                    
                    return McpSchema.CallToolResult.builder()
                            .addTextContent(resultText)
                            .isError(false)
                            .build();
                })
                .build();
        
        var capabilities = McpSchema.ServerCapabilities.builder()
                .prompts( false )
                .resources( false, false )
                .tools(true)
                .logging()
                .build();
        
        transportProvider = HttpServletStreamableServerTransportProvider.builder()
                .jsonMapper(jsonMapperSupplier.get())
                .keepAliveInterval(Duration.ofSeconds(10))
                .mcpEndpoint(MCP_ENDPOINT)
                .build();
        
        // Create MCP server
        mcpServer = McpServer.sync(transportProvider)
                .serverInfo("calculator-server", "1.0.0")
                .capabilities(capabilities)
                .jsonMapper(jsonMapperSupplier.get())
                .tools(syncToolSpecification)
                .build();
        
        // Start Tomcat with the transport provider as servlet
        tomcat = createTomcatServer("", PORT, transportProvider);
        tomcat.start();
        assertTrue(tomcat.getServer().getState().isAvailable());
        logger.info( "Tomcat MCP Server started at http://{}:{}{}", HOST, PORT, MCP_ENDPOINT );
        logger.info( "Access from Windows: http://localhost:{}{}", PORT, MCP_ENDPOINT );
        logger.info( "Access from WSL: http://{}:{}{}", getWindowsIPForWSL(), PORT, MCP_ENDPOINT );
        System.in.read();
    }
    
    @AfterEach
    public void afterEach() throws LifecycleException
    {
        if (mcpServer != null) {
            mcpServer.close();
        }
        if (transportProvider != null) {
            transportProvider.closeGracefully().block();
        }
        if (tomcat != null) {
            tomcat.stop();
            tomcat.destroy();
        }
    }
    
    @Test
    public void testToolCall() throws Exception
    {
        // Create MCP client
        var clientTransport = HttpClientStreamableHttpTransport.builder("http://" + HOST + ":" + PORT)
                .endpoint(MCP_ENDPOINT)
                .build();
        
        var client = McpClient.sync(clientTransport)
                .requestTimeout(Duration.ofSeconds(10))
                .build();
        
        // Initialize the client
        var initResult = client.initialize();
        assertNotNull(initResult);
        assertNotNull(initResult.serverInfo());
        assertEquals("calculator-server", initResult.serverInfo().name());
        
        // List available tools
        var toolsList = client.listTools();
        assertNotNull(toolsList.tools());
        assertFalse(toolsList.tools().isEmpty());
        assertEquals(1, toolsList.tools().size());
        assertEquals("calculator", toolsList.tools().get(0).name());
        
        // Call the calculator tool
        var toolCallRequest = McpSchema.CallToolRequest.builder()
                .name("calculator")
                .arguments(Map.of(
                    "operation", "add",
                    "a", 10,
                    "b", 5
                ))
                .build();
        
        var toolResult = client.callTool(toolCallRequest);
        
        // Verify the result
        assertNotNull(toolResult);
        assertFalse(toolResult.isError());
        assertNotNull(toolResult.content());
        assertFalse(toolResult.content().isEmpty());
        assertTrue(toolResult.content().get(0) instanceof McpSchema.TextContent);
        
        var textContent = (McpSchema.TextContent) toolResult.content().get(0);
        assertTrue(textContent.text().contains("Result:"));
        assertTrue(textContent.text().contains("10"));
        assertTrue(textContent.text().contains("5"));
        assertTrue(textContent.text().contains("15"));
        
        // Close the client
        client.close();
    }
    
    private static Tomcat createTomcatServer(String contextPath, int port, Servlet servlet)
    {
        var tomcat = new Tomcat();
        tomcat.setPort(port);
        
        tomcat.setHostname(HOST);

        String baseDir = System.getProperty("java.io.tmpdir");
        tomcat.setBaseDir(baseDir);

        Context context = tomcat.addContext(contextPath, baseDir);

        // Add transport servlet to Tomcat
        org.apache.catalina.Wrapper wrapper = context.createWrapper();
        wrapper.setName("mcpServlet");
        wrapper.setServlet(servlet);
        wrapper.setLoadOnStartup(1);
        wrapper.setAsyncSupported(true);
        context.addChild(wrapper);
        context.addServletMappingDecoded("/*", "mcpServlet");

        var connector = tomcat.getConnector();
        connector.setAsyncTimeout(3000);

        return tomcat;
    }
    
    private static String getWindowsIPForWSL()
    {
        // try DNS resolution (hostname.local)
        try {
            String hostname = java.net.InetAddress.getLocalHost().getHostName();
            java.net.InetAddress[] addresses = java.net.InetAddress.getAllByName(hostname + ".local");
            
            // Prefer 172.x.x.x addresses (WSL bridge network range)
            for (java.net.InetAddress address : addresses) {
                if (address instanceof java.net.Inet4Address && !address.isLoopbackAddress()) {
                    String ip = address.getHostAddress();
                    if (ip.startsWith("172.")) {
                        logger.debug( "Found WSL preffered IP adress {}", ip );
                        return ip;
                    }
                }
            }
            
            // Return first non-loopback IPv4 (might be physical adapter)
            for (java.net.InetAddress address : addresses) {
                if (address instanceof java.net.Inet4Address && !address.isLoopbackAddress()) {
                    logger.debug( "Using physical address {}", address );
                    return address.getHostAddress();
                }
            }
        } catch (Exception e) {
            logger.info( "Unable to find the WSL address. Run: \"nslookup $(hostname).local\" in WSL to find IP" );
        }
        return "<Run: nslookup $(hostname).local in WSL to find IP>";
    }
    
    private static int findAvailablePort(int requiredPort )
    {
        try (final ServerSocket socket = new ServerSocket()) {
            try
            {
                socket.bind( new InetSocketAddress( requiredPort ) );
                return requiredPort;
            }
            catch ( IOException e )
            {
                socket.bind(new InetSocketAddress(0));
                return socket.getLocalPort();
            }
        }
        catch (final IOException e) {
            throw new IllegalStateException("Cannot bind to an available port!", e);
        }
    }
}
