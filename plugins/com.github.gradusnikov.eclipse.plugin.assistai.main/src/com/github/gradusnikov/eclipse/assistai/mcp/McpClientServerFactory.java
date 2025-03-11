package com.github.gradusnikov.eclipse.assistai.mcp;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import org.eclipse.core.runtime.ILog;
import org.eclipse.e4.core.di.annotations.Creatable;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.spec.ClientMcpTransport;
import io.modelcontextprotocol.spec.McpSchema;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;


@Creatable
@Singleton
public class McpClientServerFactory
{
    @Inject
    ILog logger;
    
    ObjectMapper objectMapper = new ObjectMapper();
    
    public record InMemorySyncClientServer( McpSyncClient client, McpSyncServer server ) {};
    
    /**
     * Converts the given
     * {@link com.github.gradusnikov.eclipse.assistai.mcp.McpServer}
     * implementation into a {@link McpClient} and {@link McpServer} that share
     * the common in-memory transport.
     * 
     * @param serverImplementation a {@link McpServer} annotated class
     * @return
     */
    public InMemorySyncClientServer creteInMemorySyncClientServer( Object serverImplementation )
    {
        var mcpServerAnnotation = Optional.ofNullable( serverImplementation.getClass().getAnnotation( com.github.gradusnikov.eclipse.assistai.mcp.McpServer.class ) )
                                          .orElseThrow( () -> new IllegalArgumentException( "Not MCP server" ) );
        
        var serverName = mcpServerAnnotation.name();
        McpSchema.Implementation info = new McpSchema.Implementation( serverName, "1.0.0" );

        // create buil-in MCP client server
        var transports =  InMemoryTransport.createTransportPair();

        
        ToolExecutor executor = new ToolExecutor( serverImplementation );
        List<McpSchema.Tool> tools = extractAnnotatedTools( executor.getFunctions() );
        if ( tools.isEmpty() )
        {
            logger.warn( "No tools found in " + serverImplementation.getClass() );
        }
        
        var toolRegistrations = tools.stream().map( tool -> {
            return new McpServerFeatures.SyncToolRegistration( tool, args -> {
                // call the tool 
                try
                {
                    var result = executor.call( tool.name(), args ).get();
                    // TODO: support other content types
                    var content = new McpSchema.TextContent( 
                            List.of( McpSchema.Role.ASSISTANT ), 
                            0.0,
                            Optional.ofNullable( result ).map( Object::toString ).orElse( "" ) );
                    return new McpSchema.CallToolResult( List.of( content ), false );
                }
                catch ( Exception e )
                {
                    logger.error( e.getMessage() );
                    return new McpSchema.CallToolResult( null, true );
                }
            } );
        } ).collect( Collectors.toList() );

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
        
        ClientMcpTransport clientTransport = transports.getClientTransport();
        McpSyncClient client = McpClient.sync( clientTransport ).clientInfo( info ).build();
        
        return new InMemorySyncClientServer( client, server );
        
    }
    
    
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
                McpSchema.JsonSchema schema = new McpSchema.JsonSchema( toolAnnotation.type(), properties, required, false );
                McpSchema.Tool tool = new McpSchema.Tool( toolAnnotation.name(), toolAnnotation.description(), schema );
                tools.add( tool );
            }
        }
        return tools;
    }
    
}
