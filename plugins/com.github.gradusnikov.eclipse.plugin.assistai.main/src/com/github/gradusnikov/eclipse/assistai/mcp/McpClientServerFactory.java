
package com.github.gradusnikov.eclipse.assistai.mcp;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import org.apache.commons.lang3.exception.ExceptionUtils;
import org.eclipse.core.runtime.ILog;
import org.eclipse.e4.core.di.annotations.Creatable;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.gradusnikov.eclipse.assistai.mcp.InMemoryTransport.TransportPair;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.spec.McpClientTransport;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

/**
 * Factory for creating Model Context Protocol (MCP) client-server pairs that communicate
 * through an in-memory transport mechanism. This class facilitates the integration of
 * AI assistants with Eclipse IDE tools by providing a communication bridge between them.
 * <p>
 * The factory extracts tool definitions from annotated methods, configures the MCP server
 * with these tools, and creates a client that can communicate with the server.
 */
@Creatable
@Singleton
public class McpClientServerFactory
{
    /** Eclipse logging service injected by the framework */
    @Inject
    ILog logger;
    
    /** Jackson object mapper for JSON serialization and deserialization */
    ObjectMapper objectMapper = new ObjectMapper();
    
    /**
     * Record representing a synchronized MCP client-server pair that communicate
     * through an in-memory transport.
     */
    public record InMemorySyncClientServer( McpSyncClient client, McpSyncServer server ) {};
    
    /**
     * Creates a synchronized MCP client-server pair that communicate through an
     * in-memory transport. The server implementation must be annotated with
     * {@link com.github.gradusnikov.eclipse.assistai.mcp.McpServer}.
     * 
     * @param serverImplementation An object whose class is annotated with {@link com.github.gradusnikov.eclipse.assistai.mcp.McpServer}
     *                            and contains methods annotated with {@link com.github.gradusnikov.eclipse.assistai.mcp.Tool}
     * @return A record containing the synchronized client and server
     * @throws IllegalArgumentException If the serverImplementation is not annotated with {@link com.github.gradusnikov.eclipse.assistai.mcp.McpServer}
     */
    public InMemorySyncClientServer creteInMemorySyncClientServer( Object serverImplementation )
    {
        McpSchema.Implementation info = createImplementationInfo( serverImplementation );

        // create built-in MCP client server
        var transports =  InMemoryTransport.createTransportPair();

        
        ToolExecutor executor = new ToolExecutor( serverImplementation );
        List<McpSchema.Tool> tools = extractAnnotatedTools( executor.getFunctions() );
        
        if ( tools.isEmpty() )
        {
            logger.warn( "No tools found in " + serverImplementation.getClass() );
        }
        
        var toolRegistrations = createToolSpecifications( executor, tools );

        McpSyncServer server = buildServer( info, transports, toolRegistrations );
        
        McpSyncClient client = buildClient( info, transports );
        
        return new InMemorySyncClientServer( client, server );
        
    }

    private McpSchema.Implementation createImplementationInfo( Object serverImplementation )
    {
        var mcpServerAnnotation = Optional.ofNullable( serverImplementation.getClass().getAnnotation( com.github.gradusnikov.eclipse.assistai.mcp.McpServer.class ) )
                                          .orElseThrow( () -> new IllegalArgumentException( "Not MCP server" ) );
        
        var serverName = mcpServerAnnotation.name();
        McpSchema.Implementation info = new McpSchema.Implementation( serverName, "1.0.0" );
        return info;
    }

    /**
     * Builds an MCP client that communicates through the provided transport.
     * 
     * @param info Information about the client implementation
     * @param transports The transport pair containing client and server transports
     * @return A synchronized MCP client
     */
    private McpSyncClient buildClient( McpSchema.Implementation info, TransportPair transports )
    {
        McpClientTransport clientTransport = transports.getClientTransport();
        McpSyncClient client = McpClient.sync( clientTransport ).clientInfo( info ).build();
        return client;
    }

    /**
     * Builds an MCP server with the provided tool registrations.
     * 
     * @param info Information about the server implementation
     * @param transports The transport pair containing client and server transports
     * @param toolRegistrations List of tool registrations to be exposed by the server
     * @return A synchronized MCP server
     */
    private McpSyncServer buildServer( McpSchema.Implementation info, TransportPair transports, List<SyncToolSpecification> toolRegistrations )
    {
        McpSchema.ServerCapabilities capabilities = McpSchema.ServerCapabilities.builder()
                .logging()
                .prompts( false )
                .resources( false, false )
                .tools( true )
                .build();

        McpSyncServer server = McpServer.sync( transports.getServerTransport() )
                                        .serverInfo( info )
                                        .capabilities( capabilities )
                                        .tools( toolRegistrations )
                                        .build();
        return server;
    }

    /**
     * Creates tool registrations from the list of tools. Each registration
     * links a tool definition with its executor.
     * 
     * @param executor The tool executor that will execute tool calls
     * @param tools List of tool definitions
     * @return List of tool registrations
     */
    private List<SyncToolSpecification> createToolSpecifications( ToolExecutor executor, List<McpSchema.Tool> tools )
    {
        return tools.stream().map( tool -> 
                McpServerFeatures.SyncToolSpecification.builder()
                    .tool( tool )
                    .callHandler( (exchange, request) ->  executeCallTool( executor, tool, request.arguments() ) )
                    .build()
            ).collect( Collectors.toList() );
    }


    
    /**
     * Executes a tool call with the provided arguments.
     * 
     * @param executor The tool executor
     * @param tool The tool definition
     * @param args The arguments for the tool call
     * @return The result of the tool call
     */
    private CallToolResult executeCallTool( ToolExecutor executor, Tool tool, Map<String, Object> args )
    {
        try
        {
            var result = executor.call( tool.name(), args ).get();
            return createTextCallToolResult( result );
        }
        catch ( Exception e )
        {
            logger.error( e.getMessage(), e );
            return createErrorResult( e );
        }
    }

    /**
     * Creates a text-based tool call result from the provided object.
     * 
     * @param result The result object from tool execution
     * @return A CallToolResult containing the text representation of the result
     */
    private CallToolResult createTextCallToolResult( Object result )
    {
        // TODO: support other content types
        var content = new McpSchema.TextContent( 
                new McpSchema.Annotations( List.of( McpSchema.Role.ASSISTANT ), 0.0 ),
                Optional.ofNullable( result ).map( Object::toString ).orElse( "" ) );
        return McpSchema.CallToolResult.builder().addContent( content ).isError( false ).build();
    }
    
    public CallToolResult createErrorResult( Exception e )
    {
        var cause = ExceptionUtils.getRootCauseMessage( e );
        var content = new McpSchema.TextContent( "Error: " + cause );
        return McpSchema.CallToolResult.builder().addContent( content ).isError( true ).build();
    }
    
    /**
     * Extracts tool definitions from methods annotated with
     * {@link com.github.gradusnikov.eclipse.assistai.mcp.Tool}.
     * <p>
     * This method processes the annotations on methods and their parameters to
     * create MCP tool definitions that can be registered with an MCP server.
     * 
     * @param methods The methods to extract tool definitions from
     * @return List of tool definitions
     */
    public static List<McpSchema.Tool> extractAnnotatedTools( Method ... methods )
    {
        var tools = new ArrayList<McpSchema.Tool>();
        
        for ( Method method : methods )
        {
            var toolAnnotation = method.getAnnotation( com.github.gradusnikov.eclipse.assistai.mcp.Tool.class );
            if ( toolAnnotation != null )
            {
                var properties = new LinkedHashMap<String, Object>();
                var required   = new ArrayList<String>();
                
                for ( var param : method.getParameters() )
                {
                    ToolParam toolParamAnnotation = param.getAnnotation( ToolParam.class );
                    if ( toolParamAnnotation != null )
                    {
                        String name = ToolExecutor.toParamName( param ); 
                        properties.put( name, 
                                        Map.of("type",        toolParamAnnotation.type(),
                                               "description", toolParamAnnotation.description() ) 
                                        );
                        if ( toolParamAnnotation.required() )
                        {
                            required.add( name );
                        }
                    }
                }
                McpSchema.JsonSchema schema = new McpSchema.JsonSchema( toolAnnotation.type(), properties, required, false, null, null );
                McpSchema.Tool tool = new McpSchema.Tool( toolAnnotation.name(), 
                                                          toolAnnotation.name(), // title
                                                          toolAnnotation.description(), 
                                                          schema, 
                                                          null, //outputSchema
                                                          null, //tool annotations
                                                          null // tool meta 
                                                          );
//                McpSchema.Tool tool = new McpSchema.Tool( toolAnnotation.name(), toolAnnotation.description(), schema, null, null, null );
                tools.add( tool );
            }
        }
        return tools;
    }
}
