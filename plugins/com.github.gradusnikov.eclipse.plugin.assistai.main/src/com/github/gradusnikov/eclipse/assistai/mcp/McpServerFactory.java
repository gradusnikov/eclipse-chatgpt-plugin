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

import com.github.gradusnikov.eclipse.assistai.mcp.annotations.Tool;
import com.github.gradusnikov.eclipse.assistai.mcp.annotations.ToolParam;
import com.github.gradusnikov.eclipse.assistai.mcp.operations.Operation;
import com.github.gradusnikov.eclipse.assistai.mcp.operations.OperationRegistry;
import com.github.gradusnikov.eclipse.assistai.mcp.results.OperationStatusResponse;
import com.github.gradusnikov.eclipse.assistai.models.ModelApiDescriptor;
import com.github.gradusnikov.eclipse.assistai.models.ModelApiDescriptorRepository;
import com.github.gradusnikov.eclipse.assistai.models.ToolResponseFormat;

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
import io.modelcontextprotocol.spec.McpServerTransportProvider;
import jakarta.inject.Inject;

@Creatable
public class McpServerFactory
{
    private final ILog              logger;

    private final OperationRegistry operationRegistry;

    private final ModelApiDescriptorRepository modelApiDescriptorRepository;

    private final ToolResponseFormat fallbackResponseFormat;

    /**
     * Creates a factory that obtains the response format from the active model.
     *
     * @param logger                       The plug-in logger
     * @param operationRegistry            The registry for long-running operations
     * @param modelApiDescriptorRepository The repository containing the active model configuration
     */
    @Inject
    public McpServerFactory( ILog logger, OperationRegistry operationRegistry, ModelApiDescriptorRepository modelApiDescriptorRepository )
    {
        this.logger = logger;
        this.operationRegistry = operationRegistry;
        this.modelApiDescriptorRepository = modelApiDescriptorRepository;
        this.fallbackResponseFormat = ToolResponseFormat.PLAIN;
    }

    /**
     * Creates a factory with plain-text responses as its fallback format.
     *
     * @param logger            The plug-in logger
     * @param operationRegistry The registry for long-running operations
     */
    public McpServerFactory( ILog logger, OperationRegistry operationRegistry )
    {
        this( logger, operationRegistry, ToolResponseFormat.PLAIN );
    }

    /**
     * Creates a factory with a fixed response format, primarily for tests.
     *
     * @param logger            The plug-in logger
     * @param operationRegistry The registry for long-running operations
     * @param responseFormat    The response format to use
     */
    McpServerFactory( ILog logger, OperationRegistry operationRegistry, ToolResponseFormat responseFormat )
    {
        this.logger = logger;
        this.operationRegistry = operationRegistry;
        this.modelApiDescriptorRepository = null;
        this.fallbackResponseFormat = responseFormat == null ? ToolResponseFormat.PLAIN : responseFormat;
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
        return Optional.ofNullable( FrameworkUtil.getBundle( McpServerFactory.class ) )
        		.map( Bundle::getVersion )
        		.map( Object::toString )
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
     * @param executor       The tool executor
     * @param tool           The tool definition
     * @param args           The arguments for the tool call
     * @param responseFormat The representation to include in the result
     *
     * @return The result of the tool call
     */
    private CallToolResult executeCallTool( ToolExecutor executor, McpSchema.Tool tool, Map<String, Object> args, ToolResponseFormat responseFormat )
    {
        try
        {
            executor.validateArguments( tool.name(), args );
            var annotation = executor.getToolAnnotation( tool.name() );
            boolean longExecution = annotation.map( com.github.gradusnikov.eclipse.assistai.mcp.annotations.Tool::longExecution ).orElse( Boolean.FALSE );
            if ( longExecution )
            {
                return executeLongTool( executor, tool, args, annotation.get(), responseFormat );
            }
            var result = executor.call( tool.name(), args ).get();
            return createCallToolResult( result, responseFormat );
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
     * The work is registered as an {@link Operation} before it starts, so that
     * when the inline wait runs out the caller can be handed its id rather than
     * an error. The tool keeps running and its result is kept for collection -
     * which is the whole difference from the old behaviour, where a slow tool
     * was abandoned by the client while still running invisibly, and its result
     * was thrown away.
     *
     * @param executor       The tool executor
     * @param tool           The tool definition
     * @param args           The arguments for the tool call
     * @param annotation     The annotation defining the long-running behavior
     * @param responseFormat The representation to include in the result
     *
     * @return The inline result or an operation hand-off
     */
    private CallToolResult executeLongTool( ToolExecutor executor, McpSchema.Tool tool, Map<String, Object> args,
            com.github.gradusnikov.eclipse.assistai.mcp.annotations.Tool annotation, ToolResponseFormat responseFormat )
    {
        Operation operation = operationRegistry.register( tool.name(), describeArguments( args ) );
        var future = executor.call( tool.name(), args, operation );
        operationRegistry.attachFuture( operation, future );

        int inlineWait = resolveInlineWait( args, annotation );
        return createCallToolResult( operationRegistry.awaitOrHandOff( operation, inlineWait ), responseFormat );
    }

    /**
     * How long to wait inline before handing back an operation id. A tool may
     * let the caller override the annotation's default through one of its own
     * arguments, unless that argument means something other than seconds.
     */
    private int resolveInlineWait( Map<String, Object> args, com.github.gradusnikov.eclipse.assistai.mcp.annotations.Tool annotation )
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

    /**
     * A short "what is this operation working on" line, built from the call's
     * arguments.
     */
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
     * Adapts a tool's Java return value to an MCP tool result. MCP results and
     * content objects are preserved; ordinary values retain the historical text
     * conversion. Collections allow a tool to return mixed text, image and
     * resource content in a stable order.
     *
     * @param result The result object from tool execution
     *
     * @return A content-aware MCP tool result
     */
    CallToolResult createCallToolResult( Object result )
    {
        return createCallToolResult( result, currentResponseFormat() );
    }

    /**
     * Adapts a Java return value to the requested MCP result representation.
     *
     * @param result         The result object from tool execution
     * @param responseFormat The representation to include in the result
     *
     * @return The content-aware MCP tool result
     */
    private CallToolResult createCallToolResult( Object result, ToolResponseFormat responseFormat )
    {
        if ( result instanceof CallToolResult callToolResult )
        {
            return callToolResult;
        }

        var builder = McpSchema.CallToolResult.builder().isError( false );

        if ( result instanceof StructuredToolResult structured )
        {
            // A tool only wraps its payload when the text has to say something the
            // payload does not - paged console output, say.
            addStructuredResult( builder, McpJson.toMap( structured.data() ), structured.text(), responseFormat );
        } 
        else if ( result instanceof Record payload )
        {
            // When text is requested it is rendered rather than escaped: a multi-line
            // string is fenced instead of collapsed into one line of \n. McpPlainText
            // is applied to the same map used for structuredContent, so Both cannot
            // describe two different outcomes.
            Map<String, Object> content = McpJson.toMap( payload );
            addStructuredResult( builder, content, McpPlainText.render( content ), responseFormat );
        } 
        else {        	
        	addResultContent( builder, result );
        }

        return builder.build();
    }

    /**
     * Adds plain text, structured JSON, or both representations to a result builder.
     *
     * @param builder           The result builder to update
     * @param structuredContent The structured result payload
     * @param text              The plain-text result payload
     * @param responseFormat    The representation to include
     */
    private void addStructuredResult( McpSchema.CallToolResult.Builder builder,
            Map<String, Object> structuredContent,
            String text,
            ToolResponseFormat responseFormat )
    {
    	// PLAIN & BOTH
        if ( responseFormat != ToolResponseFormat.JSON )
        {
            builder.addContent( createTextContent( text ) );
        }
        // Keep the canonical payload available for in-process consumers such as the
        // resource cache. ToolResultTextFormatter controls what enters the model
        // conversation, so retaining it here does not duplicate conversation tokens.
        builder.structuredContent( structuredContent );
    }

    /**
     * Resolves the response format configured for the active model.
     *
     * @return The active model's format, or the configured fallback
     */
    private ToolResponseFormat currentResponseFormat()
    {
        if ( modelApiDescriptorRepository == null )
        {
            return fallbackResponseFormat;
        }
        ModelApiDescriptor model = modelApiDescriptorRepository.getChatModelInUse();
        return model == null ? fallbackResponseFormat : model.toolResponseFormat();
    }

    private void addResultContent( McpSchema.CallToolResult.Builder builder, Object result )
    {
        switch ( result )
        {
            case null -> builder.addContent( createTextContent( "" ) );
            case McpSchema.Content content -> builder.addContent( content );
            case Collection<?> items -> items.forEach( item -> addResultContent( builder, item ) );
            default -> builder.addContent( createTextContent( result.toString() ) );
        }
    }

    private McpSchema.TextContent createTextContent( String text )
    {
        return McpSchema.TextContent.builder( text )
        		.annotations( McpSchema.Annotations.builder().audience( List.of( McpSchema.Role.ASSISTANT ) ).build() )
                .build();
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
     * @param responseFormat The representation advertised and returned by the tools
     * @param methods        The methods to extract tool definitions from
     *
     * @return List of tool definitions
     */
    List<McpSchema.Tool> extractAnnotatedTools( ToolResponseFormat responseFormat, Method... methods )
    {
        var tools = new ArrayList<McpSchema.Tool>();

        for ( Method method : methods )
        {
            var toolAnnotation = method.getAnnotation( com.github.gradusnikov.eclipse.assistai.mcp.annotations.Tool.class );
            if ( toolAnnotation != null )
            {
                var properties = new LinkedHashMap<String, Object>();
                var required = new ArrayList<String>();

                for ( var param : method.getParameters() )
                {
                    ToolParam toolParamAnnotation = param.getAnnotation( ToolParam.class );
                    if ( toolParamAnnotation != null )
                    {
                        String name = ToolExecutor.toParamName( param );
                        properties.put( name, Map.of( "type", toolParamAnnotation.type(), "description", toolParamAnnotation.description() ) );
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

                var toolBuilder = McpSchema.Tool.builder( toolAnnotation.name(), schema ).title( toolAnnotation.name() )
                        .description( describeTool( toolAnnotation ) );

                // The MCP SDK automatically adds serialized structuredContent as text
                // whenever outputSchema is present and content is empty. Therefore JSON
                // deliberately omits the schema so it remains JSON-only on the wire.
                // Both advertises the schema and already supplies explicit text.
                Map<String, Object> outputSchema = McpOutputSchemas.forType( toolAnnotation.outputType() );
                if ( outputSchema != null && responseFormat == ToolResponseFormat.BOTH )
                {
                    toolBuilder.outputSchema( outputSchema );
                }

                tools.add( toolBuilder.build() );
            }
        }
        return tools;
    }

    /**
     * The description a client sees. A long execution tool advertises its own
     * protocol, so the model learns from the schema that a slow call hands back
     * an operation id instead of failing.
     */
    private String describeTool( com.github.gradusnikov.eclipse.assistai.mcp.annotations.Tool toolAnnotation )
    {
        if ( !toolAnnotation.longExecution() )
        {
            return toolAnnotation.description();
        }
        return toolAnnotation.description() + " This tool can run longer than a tool call is allowed to take. If it has not finished after "
                + toolAnnotation.inlineWait() + "s you get an operationId instead of the result and the work carries on: poll it with"
                + " getOperationStatus (which can also page the output), or stop it with cancelOperation.";
    }

    /**
     * Adds the shared operation tools to any server that declares a long
     * execution tool. The handler uses the same annotation-driven discovery and
     * schema generation as regular MCP server tools.
     *
     * @param prefix         The optional tool-name prefix
     * @param responseFormat The representation returned by the operation tools
     *
     * @return The shared operation tool specifications
     */
    private List<SyncToolSpecification> createOperationToolSpecifications( String prefix, ToolResponseFormat responseFormat )
    {
        return createToolSpecifications( new OperationTools( operationRegistry ), Collections.emptySet(), prefix, responseFormat );
    }

    static final class OperationTools
    {
        private final OperationRegistry operationRegistry;

        OperationTools( OperationRegistry operationRegistry )
        {
            this.operationRegistry = operationRegistry;
        }

        @Tool( name = "getOperationStatus",
               description = "Reports on a long running operation: its state, elapsed time, progress, "
                   + "result once finished, and optionally process output and/or typed intermediate results "
                   + "(e.g. JUnit 'summary' or 'results' published per test while the run is still going). "
                   + "When waitSeconds is omitted, an auto-increasing backoff is used per operation: "
                   + "2 s, 3 s, 5 s, 10 s, 15 s (capped). Pass an explicit value to override. "
                   + "While the operation is RUNNING, all published intermediate results are always shown. "
                   + "After it finishes, pass includeResults to retrieve specific types.",
               type = "object",
               outputType = OperationStatusResponse.class )
        public StructuredToolResult getOperationStatus(
                @ToolParam( name = "operationId",
                            description = "The operationId handed back by the tool that started "
                                + "the work (e.g. 'op-3'). Use listOperations if you lost it." )
                String operationId,
                @ToolParam( name = "outputOffset",
                            description = "0-based index of the first output line to return. "
                                + "Negative counts back from the end, so '-100' is the last 100 lines. Default: 0.",
                            required = false )
                String outputOffset,
                @ToolParam( name = "outputLimit",
                            description = "Number of output lines to return (default: 0, meaning none). "
                                + "The reply reports the total and the next offset.",
                            required = false )
                String outputLimit,
                @ToolParam( name = "waitSeconds",
                            description = "Seconds to block for the operation to finish before replying. "
                                + "Omit (or pass null) to use the automatic backoff sequence "
                                + "(2 s → 3 s → 5 s → 10 s → 15 s). Pass 0 to reply immediately.",
                            required = false )
                String waitSeconds,
                @ToolParam( name = "includeResults",
                            description = "Comma-separated result-type keys to include in the reply "
                                + "(e.g. 'summary', 'results', 'summary,results'), or 'all' for every type. "
                                + "When omitted, only a hint is shown listing the available types and their "
                                + "qualifier ('intermediate' if still running, 'final' if finished), "
                                + "similar to how console output is hinted when outputLimit is not set.",
                            required = false )
                String includeResults )
        {
            // null/blank waitSeconds → -1 → auto-backoff in the registry
            int wait = ( waitSeconds == null || waitSeconds.isBlank() ) ? -1 : intArg( waitSeconds, 0 );
            String text = operationRegistry.getOperationStatus(
                operationId,
                intArg( outputOffset, 0 ),
                intArg( outputLimit, 0 ),
                wait,
                ( includeResults == null || includeResults.isBlank() ) ? null : includeResults );

            // Described after the wait, so a caller that blocked until the operation
            // finished gets its structured result rather than a snapshot of it running.
            OperationStatusResponse status = operationRegistry.find( operationId )
                    .map( OperationStatusResponse::from )
                    .orElseGet( () -> OperationStatusResponse.unknown( operationId ) );

            return StructuredToolResult.of( status, text );
        }


        @Tool( name = "listOperations", description = "Lists long running operations - those still running and the last few that finished - with their operationId, state and elapsed time.", type = "object" )
        public String listOperations()
        {
            return operationRegistry.listOperations();
        }

        @Tool( name = "cancelOperation", description = "Stops a running operation - terminating the test JVM, build or search behind it. Use when an operation is stuck or no longer needed.", type = "object" )
        public String cancelOperation( @ToolParam( name = "operationId", description = "The operationId to stop (e.g. 'op-3')." )
        String operationId )
        {
            return operationRegistry.cancelOperation( operationId );
        }
    }

    private static int intArg( String value, int fallback )
    {
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

    public McpSyncServer createSyncServer( Object serverImplementation, McpServerTransportProvider transportProvider, Collection<String> excludedTools,
            String toolPrefix )
    {
        requireMcpServerAnnotation( serverImplementation );

        var info = createImplementationInfo( serverImplementation );
        var toolSpecifications = createToolSpecifications( serverImplementation, excludedTools, toolPrefix );
        var capabilities = createCapabilities();
        return McpServer.sync( transportProvider ).serverInfo( info ).capabilities( capabilities ).tools( toolSpecifications )
                .jsonMapper( new JacksonMcpJsonMapperSupplier().get() ).jsonSchemaValidator( createJsonSchemaValidator() ).build();
    }

    public McpSyncServer createSyncServer( Object serverImplementation, HttpServletStreamableServerTransportProvider transportProvider )
    {
        return createSyncServer( serverImplementation, transportProvider, Collections.emptySet(), "" );
    }

    public McpSyncServer createSyncServer( Object serverImplementation, HttpServletStreamableServerTransportProvider transportProvider,
            Collection<String> excludedTools )
    {
        return createSyncServer( serverImplementation, transportProvider, excludedTools, "" );
    }

    public McpSyncServer createSyncServer( Object serverImplementation, HttpServletStreamableServerTransportProvider transportProvider,
            Collection<String> excludedTools, String toolPrefix )
    {
        requireMcpServerAnnotation( serverImplementation );

        var info = createImplementationInfo( serverImplementation );
        var toolSpecifications = createToolSpecifications( serverImplementation, excludedTools, toolPrefix );
        var capabilities = createCapabilities();
        return McpServer.sync( transportProvider ).serverInfo( info ).capabilities( capabilities ).tools( toolSpecifications )
                .jsonMapper( new JacksonMcpJsonMapperSupplier().get() ).jsonSchemaValidator( createJsonSchemaValidator() ).build();
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
        return createToolSpecifications( serverImplementation, excludedTools, toolPrefix, currentResponseFormat() );
    }

    /**
     * Creates MCP tool specifications using a fixed response representation.
     *
     * @param serverImplementation The object containing annotated tool methods
     * @param excludedTools        Tool names that must not be registered
     * @param toolPrefix           The optional prefix added to registered tool names
     * @param responseFormat       The representation returned by the registered handlers
     *
     * @return The tool specifications to register with the server
     */
    private List<SyncToolSpecification> createToolSpecifications( Object serverImplementation, Collection<String> excludedTools, String toolPrefix,
            ToolResponseFormat responseFormat )
    {
        var excluded = Set.copyOf( excludedTools );
        var prefix = ( toolPrefix != null && !toolPrefix.isBlank() ) ? toolPrefix : "";
        var executor = new ToolExecutor( serverImplementation );
        var tools = extractAnnotatedTools( responseFormat, executor.getFunctions() );
        if ( tools.isEmpty() )
        {
            logger.warn( "No tools found in " + serverImplementation.getClass() );
        }
        var toolSpecifications = tools.stream().filter( tool -> !excluded.contains( tool.name() ) ).map( tool -> {
            var prefixedTool = prefix.isEmpty() ? tool
                    : McpSchema.Tool.builder( prefix + tool.name(), tool.inputSchema() ).title( prefix + tool.name() ).description( tool.description() )
                            .outputSchema( tool.outputSchema() ).annotations( tool.annotations() ).meta( tool.meta() ).build();
            return McpServerFeatures.SyncToolSpecification.builder().tool( prefixedTool )
                    .callHandler( ( exchange, request ) -> executeCallTool( executor, tool, request.arguments(), responseFormat ) ).build();
        } ).collect( Collectors.toList() );

        boolean hasLongExecutionTool = Arrays.stream( executor.getFunctions() )
                .map( method -> method.getAnnotation( com.github.gradusnikov.eclipse.assistai.mcp.annotations.Tool.class ) ).filter( Objects::nonNull )
                .anyMatch( com.github.gradusnikov.eclipse.assistai.mcp.annotations.Tool::longExecution );
        if ( hasLongExecutionTool )
        {
            var withOperationTools = new ArrayList<>( toolSpecifications );
            createOperationToolSpecifications( prefix, responseFormat ).stream().filter( spec -> !excluded.contains( spec.tool().name() ) )
                    .forEach( withOperationTools::add );
            return withOperationTools;
        }
        return toolSpecifications;
    }

    public List<String> listToolNames( Object serverImplementation )
    {
        var executor = new ToolExecutor( serverImplementation );
        return extractAnnotatedTools( currentResponseFormat(), executor.getFunctions() ).stream().map( McpSchema.Tool::name ).collect( Collectors.toList() );
    }

    private void requireMcpServerAnnotation( Object serverImplementation )
    {
        Optional.ofNullable( serverImplementation.getClass().getAnnotation( com.github.gradusnikov.eclipse.assistai.mcp.annotations.McpServer.class ) )
                .orElseThrow( () -> new IllegalArgumentException( "Not an MCP server" ) );
    }

}
