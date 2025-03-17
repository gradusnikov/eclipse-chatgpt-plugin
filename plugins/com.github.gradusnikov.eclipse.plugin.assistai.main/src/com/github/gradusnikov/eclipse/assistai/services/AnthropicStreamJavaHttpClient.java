package com.github.gradusnikov.eclipse.assistai.services;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.Flow;
import java.util.concurrent.SubmissionPublisher;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import org.eclipse.core.runtime.ILog;
import org.eclipse.e4.core.di.annotations.Creatable;
import org.eclipse.jface.preference.IPreferenceStore;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.github.gradusnikov.eclipse.assistai.Activator;
import com.github.gradusnikov.eclipse.assistai.mcp.McpClientRetistry;
import com.github.gradusnikov.eclipse.assistai.model.ChatMessage;
import com.github.gradusnikov.eclipse.assistai.model.Conversation;
import com.github.gradusnikov.eclipse.assistai.model.Incoming;
import com.github.gradusnikov.eclipse.assistai.model.ModelApiDescriptor;
import com.github.gradusnikov.eclipse.assistai.part.Attachment;
import com.github.gradusnikov.eclipse.assistai.prompt.Prompts;
import com.github.gradusnikov.eclipse.assistai.tools.ImageUtilities;

import jakarta.inject.Inject;

/**
 * A Java HTTP client for streaming requests to Anthropic API.
 * This class allows subscribing to responses received from the Anthropic API and processes the chat completions.
 */
@Creatable
public class AnthropicStreamJavaHttpClient implements LanguageModelClient
{
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

    public AnthropicStreamJavaHttpClient()
    {
        publisher = new SubmissionPublisher<>();
        preferenceStore = Activator.getDefault().getPreferenceStore();
    }
    
    @Override
    public void setCancelProvider(Supplier<Boolean> isCancelled)
    {
        this.isCancelled = isCancelled;
    }
    @Override
    public synchronized void subscribe(Flow.Subscriber<Incoming> subscriber)
    {
        publisher.subscribe(subscriber);
    }

    private String getRequestBody(Conversation prompt, ModelApiDescriptor model)
    {
        try
        {
            var requestBody = new LinkedHashMap<String, Object>();
            var messages = new ArrayList<Map<String, Object>>();

            // System message should be placed in system key, not in messages array for Anthropic
            String systemPrompt = preferenceStore.getString(Prompts.SYSTEM.preferenceName());
            requestBody.put("system", systemPrompt);

            // Add all messages from prompt
            prompt.messages().stream()
                             .filter( Predicate.not(ChatMessage::isEmpty) )
                             .map(message -> toJsonPayload(message, model)).forEach(messages::add);

            // Add required fields for Anthropic API
            requestBody.put("model", model.modelName());
            requestBody.put("messages", messages);
            requestBody.put("temperature", model.temperature() / 10.0);
            requestBody.put("stream", true);
            requestBody.put("max_tokens", 4096); // Configurable limit
            
            // Add tools if function calling is enabled
            if (model.functionCalling())
            {
                ArrayNode tools = objectMapper.createArrayNode();
                for (var client : mcpClientRegistry.listClients().entrySet())
                {
                    tools.addAll(AnnotationToJsonConverter.clientToolsToJsonAnthropic(client.getKey(), client.getValue()));
                }
                if (!tools.isEmpty())
                {
                    requestBody.put("tools", tools);
                }
            }

            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(requestBody);
        }
        catch (JsonProcessingException e)
        {
            throw new RuntimeException(e);
        }
    }

    private LinkedHashMap<String, Object> toJsonPayload(ChatMessage message, ModelApiDescriptor model)
    {
        try
        {
            var userMessage = new LinkedHashMap<String, Object>();
            // Convert role from OpenAI format to Anthropic format if needed
            String role = message.getRole();
            // In Anthropic API, roles are limited to "user" and "assistant"
            role = role.equals( "assistant" ) ? "assistant" : "user";
            userMessage.put("role", role);
            
            // Handle function calls/tool use
            if ( model.functionCalling() && Objects.nonNull( message.getFunctionCall() ) )
            {
                if ( "function".equals( message.getRole() ) )
                {
                    var functionCall = message.getFunctionCall();
                    var toolUseContent = new LinkedHashMap<String, Object>();
                    toolUseContent.put("type", "tool_result");
                    toolUseContent.put("tool_use_id", functionCall.id() );
                    toolUseContent.put("content", List.of( Map.of(
                                                         "type", "text", 
                                                         "text", message.getContent() ) ) );
                    toolUseContent.put( "is_error", false );
                    userMessage.put("content", List.of(toolUseContent));
                }
                else
                {
                    var functionCall = message.getFunctionCall();
                    var toolUseContent = new LinkedHashMap<String, Object>();
                    toolUseContent.put("type", "tool_use");
                    toolUseContent.put("id", functionCall.id() );
                    toolUseContent.put("name", functionCall.name() );
                    // Parse arguments into a Map instead of a JSON string for Anthropic
                    toolUseContent.put("input", functionCall.arguments() );
                    
                    userMessage.put("content", List.of(toolUseContent));
                }
            }
            // Handle text and attachments
            else
            {
                List<String> textParts = message.getAttachments()
                        .stream()
                        .map(Attachment::toChatMessageContent)
                        .filter(Objects::nonNull)
                        .collect(Collectors.toList());
                String textContent = String.join("\n", textParts) + "\n\n" + message.getContent();

                // Handle content format based on whether there are images (vision capability)
                if (model.vision())
                {
                    var contentList = new ArrayList<>();
                    
                    // Add text content
                    var textObject = new LinkedHashMap<String, String>();
                    textObject.put("type", "text");
                    textObject.put("text", textContent);
                    contentList.add(textObject);
                    
                    // Add image content if available
                    message.getAttachments()
                            .stream()
                            .map(Attachment::getImageData)
                            .filter(Objects::nonNull)
                            .map(ImageUtilities::toBase64Jpeg)
                            .map(this::toImageContent)
                            .forEachOrdered(contentList::add);
                    
                    userMessage.put("content", contentList);
                }
                else
                {
                    userMessage.put("content", textContent);
                }
            }
            return userMessage;
        }
        catch (Exception e)
        {
            throw new RuntimeException(e);
        }
    }

    private LinkedHashMap<String, Object> toImageContent(String data)
    {
        var imageObject = new LinkedHashMap<String, Object>();
        imageObject.put("type", "image");
        var imageSource = new LinkedHashMap<String, Object>();
        
        // Anthropic uses media_type instead of content type
        imageSource.put("type", "base64");
        imageSource.put("media_type", "image/jpeg");
        imageSource.put("data", data);
        
        imageObject.put("source", imageSource);
        return imageObject;
    }
    public Runnable run(Conversation prompt)
    {
        return () -> {
            var model = configuration.getSelectedModel().orElseThrow();

            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(configuration.getConnectionTimoutSeconds()))
                    .build();

            String requestBody = getRequestBody(prompt, model);
            HttpRequest request = HttpRequest.newBuilder().uri(URI.create(model.apiUrl()))
                    .timeout(Duration.ofSeconds(configuration.getRequestTimoutSeconds()))
                    .version(HttpClient.Version.HTTP_1_1)
                    .header("x-api-key", model.apiKey())
                    .header("anthropic-version", "2023-06-01") // Update to latest API version if needed
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                    .build();

            logger.info("Sending request to Anthropic API.\n\n" + requestBody);

            try
            {
                HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());

                if (response.statusCode() != 200)
                {
                    String responseBody = new String(response.body().readAllBytes(), StandardCharsets.UTF_8);
                    logger.error("Request failed with status code: " + response.statusCode() + " and response body: " + responseBody);
                    return;
                }
                
                try (var inputStream = response.body();
                     var inputStreamReader = new InputStreamReader(inputStream, StandardCharsets.UTF_8);
                     var reader = new BufferedReader(inputStreamReader))
                {
                    String line;
                    Incoming.Type incomingType = null;
                    
                    while ((line = reader.readLine()) != null && !isCancelled.get())
                    {
                        // Skip empty lines and ping messages
                        if (line.isEmpty()) 
                        {
                            continue;
                        }
                        // Handle data: prefix (SSE format)
                        if (line.startsWith("data:")) 
                        {
                            line = line.substring(5).trim();
                            // Skip [DONE] marker
                            if ("[DONE]".equals(line)) {
                                continue;
                            }
                            try 
                            {
                                var node = objectMapper.readTree(line);
                                var type = node.has("type") ? node.get("type").asText() : "";
                                
                                // ignore pings
                                if ( "ping".equals( type ) )
                                {
                                    continue;
                                }
                                
                                if ( "content_block_start".equals( type ) )
                                {
                                    incomingType =  switch ( node.get( "content_block" ).get( "type" ).asText() )
                                    {
                                        case "text" -> Incoming.Type.CONTENT;
                                        case "tool_use" -> Incoming.Type.FUNCTION_CALL;
                                        default -> null;
                                    };
                                }
                                // Handle tool use events (function calls)
                                if ( "content_block_start".equals(type) && Incoming.Type.FUNCTION_CALL.equals(incomingType) ) 
                                {
                                    JsonNode toolUseNode = node.get("content_block");
                                    String toolName = toolUseNode.get("name").asText();
                                    String toolId = toolUseNode.get("id").asText();
                                    
                                    JsonNode inputNode = toolUseNode.get("input");
                                    String arguments = inputNode.toString();
                                    
                                    publisher.submit( new Incoming(Incoming.Type.FUNCTION_CALL, 
                                            String.format( "\"function_call\" : { \n \"name\": \"%s\",\n \"id\": \"%s\",\n \"arguments\" :", toolName, toolId ) ) );
                                }
                                // Handle content blocks
                                if ("content_block_delta".equals(type) && Objects.nonNull( incomingType ) ) 
                                {
                                    var delta = node.get( "delta" );
                                    if ( Objects.nonNull( delta ) )
                                    {
                                        if ( delta.has("text") )
                                        {
                                            publisher.submit( new Incoming( incomingType, delta.get("text").asText() ) );
                                        }
                                        else if ( delta.has("partial_json") )
                                        {
                                            publisher.submit( new Incoming( incomingType, delta.get("partial_json").asText() ) );
                                        }
                                    }
                                } 

                            } catch (Exception e) {
                                // Handle parsing errors but continue processing
                                logger.error("Error parsing response line: " + line, e);
                            }
                        }
                    }
                }
                
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
    
}