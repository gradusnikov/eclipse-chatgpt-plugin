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
import org.osgi.framework.Bundle;
import org.osgi.framework.FrameworkUtil;

import com.github.gradusnikov.eclipse.assistai.mcp.annotations.ToolParam;

import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.HttpServletStreamableServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import io.modelcontextprotocol.spec.McpServerTransportProvider;
import jakarta.inject.Inject;

@Creatable
public class McpServerFactory
{
    private final ILog logger;
    
    @Inject
    public McpServerFactory(ILog logger)
    {
        this.logger = logger;
    }
    
    private McpSchema.Implementation createImplementationInfo( Object serverImplementation )
    {
        var mcpServerAnnotation = serverImplementation.getClass().getAnnotation( com.github.gradusnikov.eclipse.assistai.mcp.annotations.McpServer.class );
        
        var serverName = mcpServerAnnotation.name();
        var version = getVersionNumber();
        McpSchema.Implementation info = new McpSchema.Implementation( serverName, version ); 
        return info;
    }
    
    public String getVersionNumber()
    {
        return Optional.ofNullable( FrameworkUtil.getBundle(McpServerFactory.class) )
                        .map( Bundle::getVersion ).map( Object::toString )
                        .orElse( "1.0.0" );
    }
    
    
    private McpSchema.ServerCapabilities createCapabilities()
    {
        McpSchema.ServerCapabilities capabilities = McpSchema.ServerCapabilities.builder()
                .logging()
                .prompts( false )
                .resources( false, false )
                .tools( true )
                .build();
        return capabilities;
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
     * {@link com.github.gradusnikov.eclipse.assistai.mcp.annotations.Tool}.
     * <p>
     * This method processes the annotations on methods and their parameters to
     * create MCP tool definitions that can be registered with an MCP server.
     * 
     * @param methods The methods to extract tool definitions from
     * @return List of tool definitions
     */
    private List<McpSchema.Tool> extractAnnotatedTools( Method ... methods )
    {
        var tools = new ArrayList<McpSchema.Tool>();
        
        for ( Method method : methods )
        {
            var toolAnnotation = method.getAnnotation( com.github.gradusnikov.eclipse.assistai.mcp.annotations.Tool.class );
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
                tools.add( tool );
            }
        }
        return tools;
    }

    public McpSyncServer createSyncServer( Object serverImplementation, McpServerTransportProvider transportProvider )
    {
        requireMcpServerAnnotation( serverImplementation );

        var info     = createImplementationInfo( serverImplementation );
        var toolSpecifications = createToolSpecifications( serverImplementation );
        var capabilities = createCapabilities();
        return McpServer.sync( transportProvider )
                        .serverInfo( info )
                        .capabilities( capabilities )
                        .tools( toolSpecifications )
                        .build();
    }
    public McpSyncServer createSyncServer( Object serverImplementation, HttpServletStreamableServerTransportProvider transportProvider )
    {
        requireMcpServerAnnotation( serverImplementation );

        var info     = createImplementationInfo( serverImplementation );
        var toolSpecifications = createToolSpecifications( serverImplementation );
        var capabilities = createCapabilities();
        return McpServer.sync( transportProvider )
                .serverInfo( info )
                .capabilities( capabilities )
                .tools( toolSpecifications )
                .build();
    }

    private List<SyncToolSpecification> createToolSpecifications( Object serverImplementation )
    {
        var executor = new ToolExecutor( serverImplementation );
        var tools    = extractAnnotatedTools( executor.getFunctions() );
        if ( tools.isEmpty() )
        {
            logger.warn( "No tools found in " + serverImplementation.getClass() );
        }
        // map all tools to SyncToolSpecification
        var toolSpecifications = tools.stream().map( tool -> 
                    McpServerFeatures.SyncToolSpecification.builder()
                    .tool( tool )
                    .callHandler( (exchange, request) ->  executeCallTool( executor, tool, request.arguments() ) )
                    .build()
                ).collect( Collectors.toList() );
        
        return toolSpecifications;
    }

    private void requireMcpServerAnnotation( Object serverImplementation )
    {
        Optional.ofNullable( serverImplementation.getClass().getAnnotation( com.github.gradusnikov.eclipse.assistai.mcp.annotations.McpServer.class ) )
                .orElseThrow( () -> new IllegalArgumentException( "Not an MCP server" ) );
    }
    
}
