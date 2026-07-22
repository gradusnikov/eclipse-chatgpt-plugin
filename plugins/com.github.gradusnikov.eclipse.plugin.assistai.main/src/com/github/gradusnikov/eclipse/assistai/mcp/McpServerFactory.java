package com.github.gradusnikov.eclipse.assistai.mcp;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Objects;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import org.apache.commons.lang3.exception.ExceptionUtils;
import org.eclipse.core.runtime.ILog;
import org.eclipse.e4.core.di.annotations.Creatable;
import org.osgi.framework.Bundle;
import org.osgi.framework.FrameworkUtil;

import com.github.gradusnikov.eclipse.assistai.mcp.annotations.ToolParam;
import com.github.gradusnikov.eclipse.assistai.mcp.operations.Operation;
import com.github.gradusnikov.eclipse.assistai.mcp.operations.OperationRegistry;

import io.modelcontextprotocol.json.jackson2.JacksonMcpJsonMapperSupplier;
import io.modelcontextprotocol.json.schema.JsonSchemaValidator;
import io.modelcontextprotocol.json.schema.jackson2.JacksonJsonSchemaValidatorSupplier;
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

    private final OperationRegistry operationRegistry;
    
    @Inject
    public McpServerFactory(ILog logger, OperationRegistry operationRegistry)
    {
        this.logger = logger;
        this.operationRegistry = operationRegistry;
    }
    
    private McpSchema.Implementation createImplementationInfo( Object serverImplementation )
    {
        var mcpServerAnnotation = serverImplementation.getClass().getAnnotation( com.github.gradusnikov.eclipse.assistai.mcp.annotations.McpServer.class );
        
        var serverName = mcpServerAnnotation.name();
        var version = getVersionNumber();
        McpSchema.Implementation info = McpSchema.Implementation.builder( serverName, version ).build(); 
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
            var annotation = executor.getToolAnnotation( tool.name() );
            boolean longExecution = annotation
                    .map( com.github.gradusnikov.eclipse.assistai.mcp.annotations.Tool::longExecution )
                    .orElse( Boolean.FALSE );
            if ( longExecution )
            {
                return executeLongTool( executor, tool, args, annotation.get() );
            }
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
     * Runs a tool that may outlive the client's tool call timeout.
     * <p>
     * The work is registered as an {@link Operation} before it starts, so that when
     * the inline wait runs out the caller can be handed its id rather than an error.
     * The tool keeps running and its result is kept for collection - which is the
     * whole difference from the old behaviour, where a slow tool was abandoned by the
     * client while still running invisibly, and its result was thrown away.
     */
    private CallToolResult executeLongTool( ToolExecutor executor, Tool tool, Map<String, Object> args,
            com.github.gradusnikov.eclipse.assistai.mcp.annotations.Tool annotation )
    {
        Operation operation = operationRegistry.register( tool.name(), describeArguments( args ) );
        var future = executor.call( tool.name(), args, operation );
        operationRegistry.attachFuture( operation, future );

        int inlineWait = resolveInlineWait( args, annotation );
        return createTextCallToolResult( operationRegistry.awaitOrHandOff( operation, inlineWait ) );
    }

    /**
     * How long to wait inline before handing back an operation id. A tool may let the
     * caller override the annotation's default through one of its own arguments,
     * unless that argument means something other than seconds.
     */
    private int resolveInlineWait( Map<String, Object> args,
            com.github.gradusnikov.eclipse.assistai.mcp.annotations.Tool annotation )
    {
        String param = annotation.inlineWaitParam();
        if ( param == null || param.isBlank() || args == null )
        {
            return annotation.inlineWait();
        }
        Object value = args.get( param );
        if ( value == null )
        {
            return annotation.inlineWait();
        }
        try
        {
            return Integer.parseInt( String.valueOf( value ).trim() );
        }
        catch ( NumberFormatException e )
        {
            return annotation.inlineWait();
        }
    }

    /** A short "what is this operation working on" line, built from the call's arguments. */
    private String describeArguments( Map<String, Object> args )
    {
        if ( args == null || args.isEmpty() )
        {
            return "";
        }
        String description = args.entrySet().stream()
                .filter( entry -> entry.getValue() != null && !String.valueOf( entry.getValue() ).isBlank() )
                .map( entry -> entry.getKey() + "=" + String.valueOf( entry.getValue() ) )
                .collect( Collectors.joining( ", " ) );
        return description.length() > 120 ? description.substring( 0, 117 ) + "..." : description;
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
        var content = McpSchema.TextContent.builder(Optional.ofNullable( result ).map( Object::toString ).orElse( "" ) )
                .annotations( McpSchema.Annotations.builder().audience( List.of( McpSchema.Role.ASSISTANT ) ).build() )
                .build();
        return McpSchema.CallToolResult.builder().addContent( content ).isError( false ).build();
    }
    
    public CallToolResult createErrorResult( Exception e )
    {
        var cause = ExceptionUtils.getRootCauseMessage( e );
        var content = McpSchema.TextContent.builder( "Error: " + cause ).build();
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
                Map<String, Object> schema = new LinkedHashMap<>();
                schema.put( "type", toolAnnotation.type() );
                schema.put( "properties", properties );
                schema.put( "required", required );
                schema.put( "additionalProperties", false );
                McpSchema.Tool tool = McpSchema.Tool.builder( toolAnnotation.name(), schema )
                        .title( toolAnnotation.name() )
                        .description( describeTool( toolAnnotation ) )
                        .build();
                tools.add( tool );
            }
        }
        return tools;
    }

    /**
     * The description a client sees. A long execution tool advertises its own protocol,
     * so the model learns from the schema that a slow call hands back an operation id
     * instead of failing.
     */
    private String describeTool( com.github.gradusnikov.eclipse.assistai.mcp.annotations.Tool toolAnnotation )
    {
        if ( !toolAnnotation.longExecution() )
        {
            return toolAnnotation.description();
        }
        return toolAnnotation.description()
                + " This tool can run longer than a tool call is allowed to take. If it has not finished after "
                + toolAnnotation.inlineWait()
                + "s you get an operationId instead of the result and the work carries on: poll it with"
                + " getOperationStatus (which can also page the output), or stop it with cancelOperation.";
    }

    /**
     * Adds the operation tools to any server that declares a long execution tool, so a
     * caller always has a way back to work it started. They are synthesized rather than
     * declared on each server class because {@code ToolExecutor} only discovers methods
     * declared directly on the server, so a shared base class would go unseen.
     */
    private List<SyncToolSpecification> createOperationToolSpecifications( String prefix )
    {
        Map<String, Object> statusSchema = Map.of(
                "type", "object",
                "properties", new LinkedHashMap<>( Map.of(
                        "operationId", Map.of( "type", "string",
                                "description", "The operationId handed back by the tool that started the work (e.g. 'op-3'). Use listOperations if you lost it." ),
                        "outputLimit", Map.of( "type", "string",
                                "description", "Number of output lines to return (default: 0, meaning none). The reply reports the total and the next offset." ),
                        "outputOffset", Map.of( "type", "string",
                                "description", "0-based index of the first output line to return. Negative counts back from the end, so '-100' is the last 100 lines. Default: 0." ),
                        "waitSeconds", Map.of( "type", "string",
                                "description", "Block up to this many seconds for the operation to finish before replying (default: 0, reply immediately)." ) ) ),
                "required", List.of( "operationId" ),
                "additionalProperties", false );
        var status = McpSchema.Tool.builder( prefix + "getOperationStatus", statusSchema )
                .title( prefix + "getOperationStatus" )
                .description( "Reports on a long running operation started by another tool: its state, how long it has been running,"
                        + " progress, the result once it finishes, and optionally a page of its output." )
                .build();

        Map<String, Object> listSchema = Map.of(
                "type", "object",
                "properties", new LinkedHashMap<>(),
                "required", List.of(),
                "additionalProperties", false );
        var list = McpSchema.Tool.builder( prefix + "listOperations", listSchema )
                .title( prefix + "listOperations" )
                .description( "Lists long running operations - those still running and the last few that finished - with their"
                        + " operationId, state and elapsed time." )
                .build();

        Map<String, Object> cancelSchema = Map.of(
                "type", "object",
                "properties", new LinkedHashMap<>( Map.of(
                        "operationId", Map.of( "type", "string",
                                "description", "The operationId to stop (e.g. 'op-3')." ) ) ),
                "required", List.of( "operationId" ),
                "additionalProperties", false );
        var cancel = McpSchema.Tool.builder( prefix + "cancelOperation", cancelSchema )
                .title( prefix + "cancelOperation" )
                .description( "Stops a running operation - terminating the test JVM, build or search behind it. Use when an"
                        + " operation is stuck or no longer needed." )
                .build();

        return List.of(
                SyncToolSpecification.builder().tool( status )
                        .callHandler( ( exchange, request ) -> createTextCallToolResult(
                                operationRegistry.getOperationStatus(
                                        stringArg( request.arguments(), "operationId", null ),
                                        intArg( request.arguments(), "outputOffset", 0 ),
                                        intArg( request.arguments(), "outputLimit", 0 ),
                                        intArg( request.arguments(), "waitSeconds", 0 ) ) ) )
                        .build(),
                SyncToolSpecification.builder().tool( list )
                        .callHandler( ( exchange, request ) -> createTextCallToolResult( operationRegistry.listOperations() ) )
                        .build(),
                SyncToolSpecification.builder().tool( cancel )
                        .callHandler( ( exchange, request ) -> createTextCallToolResult(
                                operationRegistry.cancelOperation(
                                        stringArg( request.arguments(), "operationId", null ) ) ) )
                        .build() );
    }

    private static String stringArg( Map<String, Object> args, String name, String fallback )
    {
        return Optional.ofNullable( args ).map( map -> map.get( name ) ).map( String::valueOf ).orElse( fallback );
    }

    private static int intArg( Map<String, Object> args, String name, int fallback )
    {
        String value = stringArg( args, name, null );
        if ( value == null || value.isBlank() )
        {
            return fallback;
        }
        try
        {
            return Integer.parseInt( value.trim() );
        }
        catch ( NumberFormatException e )
        {
            return fallback;
        }
    }

    public McpSyncServer createSyncServer( Object serverImplementation, McpServerTransportProvider transportProvider )
    {
        return createSyncServer( serverImplementation, transportProvider, Collections.emptySet(), "" );
    }

    public McpSyncServer createSyncServer( Object serverImplementation, McpServerTransportProvider transportProvider, Collection<String> excludedTools )
    {
        return createSyncServer( serverImplementation, transportProvider, excludedTools, "" );
    }

    public McpSyncServer createSyncServer( Object serverImplementation, McpServerTransportProvider transportProvider, Collection<String> excludedTools, String toolPrefix )
    {
        requireMcpServerAnnotation( serverImplementation );

        var info     = createImplementationInfo( serverImplementation );
        var toolSpecifications = createToolSpecifications( serverImplementation, excludedTools, toolPrefix );
        var capabilities = createCapabilities();
        return McpServer.sync( transportProvider )
                        .serverInfo( info )
                        .capabilities( capabilities )
                        .tools( toolSpecifications )
                        .jsonMapper( new JacksonMcpJsonMapperSupplier().get() )
                        .jsonSchemaValidator( createJsonSchemaValidator() )
                        .build();
    }

    public McpSyncServer createSyncServer( Object serverImplementation, HttpServletStreamableServerTransportProvider transportProvider )
    {
        return createSyncServer( serverImplementation, transportProvider, Collections.emptySet(), "" );
    }

    public McpSyncServer createSyncServer( Object serverImplementation, HttpServletStreamableServerTransportProvider transportProvider, Collection<String> excludedTools )
    {
        return createSyncServer( serverImplementation, transportProvider, excludedTools, "" );
    }

    public McpSyncServer createSyncServer( Object serverImplementation, HttpServletStreamableServerTransportProvider transportProvider, Collection<String> excludedTools, String toolPrefix )
    {
        requireMcpServerAnnotation( serverImplementation );

        var info     = createImplementationInfo( serverImplementation );
        var toolSpecifications = createToolSpecifications( serverImplementation, excludedTools, toolPrefix );
        var capabilities = createCapabilities();
        return McpServer.sync( transportProvider )
                .serverInfo( info )
                .capabilities( capabilities )
                .tools( toolSpecifications )
                .jsonMapper( new JacksonMcpJsonMapperSupplier().get() )
                .jsonSchemaValidator( createJsonSchemaValidator() )
                .build();
    }

    private JsonSchemaValidator createJsonSchemaValidator()
    {
        var thread = Thread.currentThread();
        var originalClassLoader = thread.getContextClassLoader();
        thread.setContextClassLoader( com.networknt.schema.SchemaRegistry.class.getClassLoader() );
        try
        {
            return new JacksonJsonSchemaValidatorSupplier().get();
        }
        finally
        {
            thread.setContextClassLoader( originalClassLoader );
        }
    }

    private List<SyncToolSpecification> createToolSpecifications( Object serverImplementation, Collection<String> excludedTools, String toolPrefix )
    {
        var excluded = Set.copyOf( excludedTools );
        var prefix = (toolPrefix != null && !toolPrefix.isBlank()) ? toolPrefix : "";
        var executor = new ToolExecutor( serverImplementation );
        var tools    = extractAnnotatedTools( executor.getFunctions() );
        if ( tools.isEmpty() )
        {
            logger.warn( "No tools found in " + serverImplementation.getClass() );
        }
        var toolSpecifications = tools.stream()
                .filter( tool -> !excluded.contains( tool.name() ) )
                .map( tool -> {
                    var prefixedTool = prefix.isEmpty() ? tool : McpSchema.Tool.builder( prefix + tool.name(), tool.inputSchema() )
                            .title( prefix + tool.name() )
                            .description( tool.description() )
                            .outputSchema( tool.outputSchema() )
                            .annotations( tool.annotations() )
                            .meta( tool.meta() )
                            .build();
                    return McpServerFeatures.SyncToolSpecification.builder()
                        .tool( prefixedTool )
                        .callHandler( (exchange, request) ->  executeCallTool( executor, tool, request.arguments() ) )
                        .build();
                }).collect( Collectors.toList() );
        
        boolean hasLongExecutionTool = Arrays.stream( executor.getFunctions() )
                .map( method -> method.getAnnotation( com.github.gradusnikov.eclipse.assistai.mcp.annotations.Tool.class ) )
                .filter( Objects::nonNull )
                .anyMatch( com.github.gradusnikov.eclipse.assistai.mcp.annotations.Tool::longExecution );
        if ( hasLongExecutionTool )
        {
            var withOperationTools = new ArrayList<>( toolSpecifications );
            createOperationToolSpecifications( prefix ).stream()
                    .filter( spec -> !excluded.contains( spec.tool().name() ) )
                    .forEach( withOperationTools::add );
            return withOperationTools;
        }
        return toolSpecifications;
    }
    
    public List<String> listToolNames( Object serverImplementation )
    {
        var executor = new ToolExecutor( serverImplementation );
        return extractAnnotatedTools( executor.getFunctions() ).stream()
                .map( McpSchema.Tool::name )
                .collect( Collectors.toList() );
    }

    private void requireMcpServerAnnotation( Object serverImplementation )
    {
        Optional.ofNullable( serverImplementation.getClass().getAnnotation( com.github.gradusnikov.eclipse.assistai.mcp.annotations.McpServer.class ) )
                .orElseThrow( () -> new IllegalArgumentException( "Not an MCP server" ) );
    }
    
}
