package com.github.gradusnikov.eclipse.assistai.network.clients;

import static java.util.function.Predicate.not;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.concurrent.Flow;
import java.util.concurrent.SubmissionPublisher;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.eclipse.core.runtime.ILog;
import org.eclipse.e4.core.di.annotations.Creatable;
import org.eclipse.jface.preference.IPreferenceStore;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.gradusnikov.eclipse.assistai.Activator;
import com.github.gradusnikov.eclipse.assistai.chat.Attachment;
import com.github.gradusnikov.eclipse.assistai.chat.ChatMessage;
import com.github.gradusnikov.eclipse.assistai.chat.Conversation;
import com.github.gradusnikov.eclipse.assistai.chat.Incoming;
import com.github.gradusnikov.eclipse.assistai.mcp.McpClientRetistry;
import com.github.gradusnikov.eclipse.assistai.preferences.models.ModelApiDescriptor;
import com.github.gradusnikov.eclipse.assistai.prompt.Prompts;
import com.github.gradusnikov.eclipse.assistai.tools.ImageUtilities;
import com.github.gradusnikov.eclipse.assistai.tools.JsonUtils;

import io.modelcontextprotocol.client.McpSyncClient;
import jakarta.inject.Inject;

/**
 * A Java HTTP client for streaming requests to OpenAI Responses API.
 * This is the new recommended API for agentic applications with built-in tools,
 * stateful conversations, and improved reasoning capabilities.
 */
@Creatable
public class OpenAIResponsesJavaHttpClient implements LanguageModelClient
{
    private final State NULL_STATE = new NullState();
    private State state = NULL_STATE;
    private SubmissionPublisher<Incoming> publisher;
    
    private Supplier<Boolean> isCancelled = () -> false;
    
    @Inject
    private ILog logger;
    
    @Inject
    private LanguageModelClientConfiguration configuration;
    
    @Inject
    private McpClientRetistry mcpClientRegistry;
    
    private IPreferenceStore preferenceStore;
    
    private final ObjectMapper objectMapper = new ObjectMapper();
    
    public OpenAIResponsesJavaHttpClient()
    {
        publisher = new SubmissionPublisher<>();
        preferenceStore = Activator.getDefault().getPreferenceStore();
    }
    
    @Override
    public void setCancelProvider( Supplier<Boolean> isCancelled )
    {
        this.isCancelled = isCancelled;
    }
    
    @Override
    public synchronized void subscribe(Flow.Subscriber<Incoming> subscriber)
    {
        publisher.subscribe(subscriber);
    }
    
    
    /**
     * Creates a function call output object for the Responses API
     * This should be called after executing a function and getting the result
     */
    public Map<String, Object> createFunctionCallOutput(String callId, Object result)
    {
        var output = new LinkedHashMap<String, Object>();
        output.put("type", "function_call_output");
        output.put("call_id", callId);
        
        // Convert result to JSON string if it's not already a string
        String outputStr;
        if (result instanceof String) {
            outputStr = (String) result;
        } else {
            try {
                outputStr = objectMapper.writeValueAsString(result);
            } catch (JsonProcessingException e) {
                outputStr = result.toString();
            }
        }
        output.put("output", outputStr);
        
        return output;
    }
    
    /**
     * Creates a custom tool call output object for the Responses API
     */
    public Map<String, Object> createCustomToolCallOutput(String callId, String result)
    {
        var output = new LinkedHashMap<String, Object>();
        output.put("type", "custom_tool_call_output");
        output.put("call_id", callId);
        output.put("output", result);
        
        return output;
    }
    
    /**
     * Creates the request body for the Responses API
     */
    private String getRequestBody(Conversation prompt, ModelApiDescriptor model)
    {
        var requestBody = new LinkedHashMap<String, Object>();
        
        // Basic parameters
        requestBody.put("model", model.modelName());
        
        // Instructions (system prompt)
        var systemPrompt = preferenceStore.getString(Prompts.SYSTEM.preferenceName());
        if (!systemPrompt.isBlank()) {
            requestBody.put("instructions", systemPrompt);
        }
        
        // Input - can be string or array of messages
        var input = buildInput(prompt, model);
        requestBody.put("input", input);
        
        // Tools - both built-in and MCP tools
        var tools = buildTools(model);
        if (!tools.isEmpty()) {
            requestBody.put("tools", tools);
            
            // Tool choice configuration
            requestBody.put("tool_choice", "auto");
            
            // Parallel tool calls (default true)
            requestBody.put("parallel_tool_calls", false); // TODO: handle parallel calls
        }
        
        // Model-specific parameters
        if (!model.modelName().matches("^o1(-.*)?")) {
            requestBody.put("temperature", model.temperature() / 10.0);
        }
        
        // Streaming
        requestBody.put("stream", true);
        
        // Storage (default true for Responses API)
        // we don't want to store the conversation and use previous_response_id
        // each time a whole conversation context is sent
        requestBody.put("store", false);
        
        return JsonUtils.toJsonString(requestBody);
    }
    
    /**
     * Builds the input field - can be a string or array of messages
     */
    private List<Map<String, Object>> buildInput(Conversation prompt, ModelApiDescriptor model)
    {
        var messages = prompt.messages();
        if (messages.isEmpty()) {
            return new ArrayList<Map<String,Object>>();
        }
        
        // Otherwise use array format
        var inputMessages = messages.stream()
                                    .map( message -> toInputMessage(message, model) )
                                    .collect( Collectors.toList());
        return inputMessages;
    }
    
    /**
     * Converts ChatMessage to Responses API message format
     */
    private Map<String, Object> toInputMessage(ChatMessage message, ModelApiDescriptor model)
    {
        var inputMessage = new LinkedHashMap<String, Object>();
        
        if ( "assistant".equals( message.getRole() ) && Objects.nonNull( message.getFunctionCall() ) )
        {
            inputMessage.put("type", "function_call");
            inputMessage.put("call_id", message.getFunctionCall().id() );
            inputMessage.put( "name", message.getFunctionCall().name()  );
            inputMessage.put("arguments", JsonUtils.toJsonString( message.getFunctionCall().arguments() ) );
            
        }
        else if ( "function".equals( message.getRole() ) ) 
        {
            inputMessage.put("type", "function_call_output");
            inputMessage.put("call_id", message.getFunctionCall().id() );
            inputMessage.put("output", message.getContent() );
        }
        else
        {
            inputMessage.put("role", message.getRole());
            // Handle content based on message type
            var content = buildMessageContent(message, model);
            inputMessage.put("content", content);
        }
        
        
        return inputMessage;
    }
    
    /**
     * Builds message content handling text, images, and attachments
     */
    private Object buildMessageContent(ChatMessage message, ModelApiDescriptor model)
    {
        // Assemble text content
        List<String> textParts = message.getAttachments()
                .stream()
                .map(Attachment::toChatMessageContent)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
        
        var attachmentsString = String.join("\n", textParts);
        var textContent = attachmentsString.isBlank() 
                ? message.getContent() 
                : attachmentsString + "\n\n" + message.getContent();
        
        // Handle vision models with images
        if (model.vision() && message.getAttachments().stream().anyMatch(a -> a.getImageData() != null)) {
            var contentArray = new ArrayList<>();
            
            // Add text content
            var textObject = new LinkedHashMap<String, String>();
            textObject.put("type", "text");
            textObject.put("text", textContent);
            contentArray.add(textObject);
            
            // Add images
            message.getAttachments()
                    .stream()
                    .map(Attachment::getImageData)
                    .filter(Objects::nonNull)
                    .map(ImageUtilities::toBase64Jpeg)
                    .map(this::toImageContent)
                    .forEachOrdered(contentArray::add);
            
            return contentArray;
        }
        
        // Simple text content
        return textContent;
    }
    
    /**
     * Creates image content object for Responses API
     */
    private Map<String, Object> toImageContent(String base64Data)
    {
        var imageContent = new LinkedHashMap<String, Object>();
        imageContent.put("type", "image_url");
        
        var imageUrl = new LinkedHashMap<String, String>();
        imageUrl.put("url", "data:image/jpeg;base64," + base64Data);
        imageContent.put("image_url", imageUrl);
        
        return imageContent;
    }
    
    /**
     * Builds tools array including built-in and MCP tools
     */
    private List<Map<String, Object>> buildTools(ModelApiDescriptor model)
    {
        var tools = new ArrayList<Map<String, Object>>();
        
        // Add MCP tools if function calling is enabled
        if ( model.functionCalling() ) 
        {
            for (var client : mcpClientRegistry.listEnabledveClients().entrySet()) 
            {
                tools.addAll(convertMcpToolsToResponses(client.getKey(), client.getValue()));
            }
        }
        
        return tools;
    }
    
    /**
     * Converts MCP tools to Responses API format
     */
    private List<Map<String, Object>> convertMcpToolsToResponses(String clientName, McpSyncClient client)
    {
        var tools = new ArrayList<Map<String, Object>>();
        
        for (var tool : client.listTools().tools()) {
            tools.add(Map.of(
                    "type", "function",
                    "name", clientName + "__" + tool.name(),
                    "description", Optional.ofNullable(tool.description()).orElse(""),
                    "parameters", Map.of(
                            "type", tool.inputSchema().type(),
                            "properties", tool.inputSchema().properties()),
                    "required", Optional.ofNullable(tool.inputSchema().required()).orElse(List.of())
            ));
        }
        
        return tools;
    }
    
    @Override
    public Runnable run(Conversation prompt) 
    {
        return () -> {
            var model = configuration.getSelectedModel().orElseThrow();
            
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(configuration.getConnectionTimoutSeconds()))
                    .build();
            
            String requestBody = getRequestBody(prompt, model);
            
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(model.apiUrl()))
                    .timeout(Duration.ofSeconds(configuration.getRequestTimoutSeconds()))
                    .version(HttpClient.Version.HTTP_1_1)
                    .header("Authorization", "Bearer " + model.apiKey())
                    .header("Accept", "text/event-stream")
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                    .build();
            
            logger.info("Sending request to OpenAI Responses API.\n\n" + requestBody);
            
            try {
                HttpResponse<Stream<String>> response = client.send( request, HttpResponse.BodyHandlers.ofLines());
                
                if (response.statusCode() != 200) 
                {
                    var errorBody = response.body().collect( Collectors.joining() );
                    throw new Exception("HTTP " + response.statusCode() + ": " + errorBody);
                }
                // Process each line as it arrives
                response.body()
                    .filter( not(String::isBlank) )
                    .takeWhile(line -> !isCancelled.get())
                    .filter(line -> line.startsWith("data:"))
                    .map( line -> line.substring(5).trim() )
                    .takeWhile(line -> !"[DONE]".equals( line ))
                    .forEach(this::processResponseEvent );
                
                if (isCancelled.get())
                {
                    publisher.closeExceptionally(new CancellationException());
                }
            }
            catch (Exception e) 
            {
                logger.error(e.getMessage(), e);
                publisher.closeExceptionally(e);
            } 
            finally 
            {
                publisher.close();
            }
        };
    }
    
    /**
     * Processes individual response events from the stream
     */
    private void processResponseEvent(String data)
    {
        try {
            var jsonNode = objectMapper.readTree(data);
            
            String eventType = Optional.ofNullable( jsonNode.get( "type" ) )
                                       .map( JsonNode::asText )
                                       .orElse( "" );
            
            // this is a state machine
            // State 1: processing response created 
            // State A.1: new output - when eventType is response.output_item.added
            // State A.2: processing output -  process item, eg. response.output_text.delta response.output_text, or function_call_arguments.delta function_call_arguments.done
            // State A.3: finishing - when eventType is response.output_item.done
            // State 2: response.completed
            
//            System.out.println(  "processing event: " +  jsonNode.get( "type" ).asText() );
            
            if ( "response.output_item.added".equals(eventType) )
            {
                // output type
                String outputType = Optional.ofNullable( jsonNode.get( "item" ) )
                        .map( node -> node.get("type") )
                        .map( JsonNode::asText )
                        .orElse( "" );
                state = switch ( outputType )
                {
                    case "function_call" -> new FunctionOutputState();
                    case "reasoning" -> new ReasoningState();
                    case "message" -> new TextOutputState();
                    default -> NULL_STATE;
                };
                state = state.begin( jsonNode.get( "item" ) );
            }
            if ( "response.output_item.done".equals( eventType ) )
            {
                state = state.finish( jsonNode.get( "item" ) );
            }
            
            state = switch ( eventType )
            {
                // response.output_text.delta  or response.output_text, or function_call_arguments.delta
                case String s when s.contains( ".delta" ) -> state.update( jsonNode );
                default -> state;
            };
            
        } catch (Exception e) {
            logger.error("Error processing response event: " + data, e);
        }
    }

    private interface State
    {
        public State begin( JsonNode node );
        public State update( JsonNode node );
        public State finish( JsonNode node );
    }
    
    private class TextOutputState implements State
    {
        @Override
        public State begin( JsonNode node )
        {
            return this;
        }

        @Override
        public State update( JsonNode node )
        {
            Optional.ofNullable( node.get( "delta" ) )
                    .map( JsonNode::asText )
                    .filter( not( String::isEmpty ) )
                    .ifPresent( text -> publisher.submit(new Incoming(Incoming.Type.CONTENT, text) ) );
            return this;
        }

        @Override
        public State finish( JsonNode node )
        {
            return NULL_STATE;
        }
    }
    private class FunctionOutputState implements State
    {
        @Override
        public State begin( JsonNode node )
        {
            var name = node.get("name").asText();
            var callId = node.get("call_id").asText();

            var functionCallJsonHeaderFormat = """
                    "function_call": {
                        "id": "%s",
                        "name": "%s",
                        "arguments": """;
            
            // Start function call JSON
            var functionCallJson = String.format(
                    functionCallJsonHeaderFormat,
                    callId, 
                    name );
            publisher.submit(new Incoming(Incoming.Type.FUNCTION_CALL, functionCallJson));               
            return this;
        }

        @Override
        public State update( JsonNode node )
        {
            // Add arguments if present
            if (node.has("delta")) {
                var arguments = node.get("delta").asText();
                publisher.submit(new Incoming(Incoming.Type.FUNCTION_CALL, arguments));
            }
            return this;
        }

        @Override
        public State finish( JsonNode node )
        {
            // Close the JSON structure
            publisher.submit(new Incoming(Incoming.Type.FUNCTION_CALL, "\n"));
            return NULL_STATE;
        }
        
    }
    private class ReasoningState implements State
    {

        @Override
        public State begin( JsonNode node )
        {
            // Reasoning output contains model's internal reasoning
            // For now, we'll just log it, but you might want to expose it differently
            if (node.has("summary") && node.get("summary").isArray()) {
                var summary = node.get("summary");
                for (var summaryItem : summary) {
                    if (summaryItem.has("text")) {
                        logger.info("Reasoning: " + summaryItem.get("text").asText());
                    }
                }
            }            
            return this;
        }

        @Override
        public State update( JsonNode node )
        {
            return this;
        }

        @Override
        public State finish( JsonNode node )
        {
            return NULL_STATE;
        }
        
    }
    private class NullState implements State
    {

        @Override
        public State begin( JsonNode node )
        {
            return this;
        }

        @Override
        public State update( JsonNode node )
        {
            return this;
        }

        @Override
        public State finish( JsonNode node )
        {
            return NULL_STATE;
        }
    }
}
