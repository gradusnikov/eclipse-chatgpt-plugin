
package com.github.gradusnikov.eclipse.assistai.network.clients;

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
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.Flow;
import java.util.concurrent.SubmissionPublisher;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.eclipse.core.runtime.ILog;
import org.eclipse.e4.core.di.annotations.Creatable;
import org.eclipse.jface.preference.IPreferenceStore;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.github.gradusnikov.eclipse.assistai.Activator;
import com.github.gradusnikov.eclipse.assistai.chat.Attachment;
import com.github.gradusnikov.eclipse.assistai.chat.ChatMessage;
import com.github.gradusnikov.eclipse.assistai.chat.Conversation;
import com.github.gradusnikov.eclipse.assistai.chat.Incoming;
import com.github.gradusnikov.eclipse.assistai.mcp.McpClientRetistry;
import com.github.gradusnikov.eclipse.assistai.preferences.models.ModelApiDescriptor;
import com.github.gradusnikov.eclipse.assistai.prompt.Prompts;
import com.github.gradusnikov.eclipse.assistai.tools.ImageUtilities;

import io.modelcontextprotocol.client.McpSyncClient;
import jakarta.inject.Inject;

/**
 * A Java HTTP client for streaming requests to Google Gemini API.
 * This class allows subscribing to responses received from the Gemini API and processes the chat completions.
 */
@Creatable
public class GeminiStreamJavaHttpClient implements LanguageModelClient
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

    public GeminiStreamJavaHttpClient()
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

    static ArrayNode clientToolsToJson(String clientName, McpSyncClient client) {
        List<Map<String, Object>> tools = new ArrayList<>();
        
        for (var tool : client.listTools().tools()) {
            try {
                // Create the main tool object
                var toolObj = new LinkedHashMap<String, Object>();
                
                // Create the function definition
                toolObj.put("name", clientName + "__" + tool.name());
                toolObj.put("description", tool.description() != null ? tool.description() : "");
                
                // Create parameters object in the format Gemini expects
                var inputSchema = new LinkedHashMap<String, Object>();
                inputSchema.put("type", "OBJECT"); // Always use OBJECT type for Gemini
                
                // Handle properties
                Map<String, Object> properties = new LinkedHashMap<>();
                if (tool.inputSchema().properties() != null && !tool.inputSchema().properties().isEmpty()) {
                    // Copy existing properties
                    properties.putAll(tool.inputSchema().properties());
                }
                
                // Ensure required properties exist in properties map
                List<String> validRequiredProps = new ArrayList<>();
                if (tool.inputSchema().required() != null) {
                    for (String reqProp : tool.inputSchema().required()) {
                        // If a required property doesn't exist in properties, add it with a dummy definition
                        if (!properties.containsKey(reqProp)) {
                            properties.put(reqProp, Map.of(
                                "type", "string",
                                "description", "Parameter " + reqProp
                            ));
                        }
                        validRequiredProps.add(reqProp);
                    }
                }
                
                // If properties is still empty, add a dummy property
                if (properties.isEmpty()) {
                    properties.put("dummy", Map.of(
                        "type", "string",
                        "description", "Dummy parameter"
                    ));
                }
                
                // Add properties to the schema
                inputSchema.put("properties", properties);
                
                // Add validated required fields if present
                if (!validRequiredProps.isEmpty()) {
                    inputSchema.put("required", validRequiredProps);
                }
                
                toolObj.put("parameters", inputSchema);
                tools.add(toolObj);
            } catch (Exception e) {
                // Log and skip problematic tools
                System.err.println("Error processing tool " + tool.name() + ": " + e.getMessage());
            }
        }
        
        var objectMapper = new ObjectMapper();
        var functionsJsonNode = objectMapper.valueToTree(tools);
        return (ArrayNode) functionsJsonNode; 
    }

    private String getRequestBody(Conversation prompt, ModelApiDescriptor model)
    {
        try
        {
            var requestBody = new LinkedHashMap<String, Object>();
            var messages = new ArrayList<Map<String, Object>>();
    
            // Add system message if provided
            String systemPrompt = preferenceStore.getString(Prompts.SYSTEM.preferenceName());
            if (!systemPrompt.isEmpty()) {
                // note gemini does not support system messages
                ChatMessage systemMessage = new ChatMessage( UUID.randomUUID().toString(), "user");
                systemMessage.setContent(systemPrompt);
                messages.add(toJsonPayload(systemMessage, model));
            }
            
            // Add all messages from prompt
            prompt.messages().stream()
                .filter(Predicate.not(ChatMessage::isEmpty))
                .map(message -> toJsonPayload(message, model))
                .forEach(messages::add);
    
            // Add required fields for Gemini API
            requestBody.put("model", model.modelName());
            requestBody.put("contents", messages);
            
            // Add generation configuration
            var generationConfig = new LinkedHashMap<String, Object>();
            
            // Add temperature configuration if applicable
            if (!model.modelName().matches("^o\\d{1}(-.*)?"))
            {
                generationConfig.put("temperature", model.temperature() / 10.0);
            }
            
            // Gemini doesn't use 'stream' directly in the body but in the URL or as a query parameter
            // So we're removing it from the body
            
            if (!generationConfig.isEmpty()) {
                requestBody.put("generationConfig", generationConfig);
            }

            // Add function calling if enabled
            if (model.functionCalling())
            {
                List<Map<String, Object>> allFunctionDeclarations = new ArrayList<>();
                
                for (var client : mcpClientRegistry.listEnabledClients().entrySet())
                {
                    try {
                        var functionDeclarations = clientToolsToJson(client.getKey(), client.getValue());
                        if (functionDeclarations != null && functionDeclarations.size() > 0) {
                            // Convert ArrayNode to List of Maps
                            for (JsonNode node : functionDeclarations) {
                                // Convert each JsonNode to a Map
                                Map<String, Object> declarationMap = objectMapper.convertValue(node, Map.class);
                                allFunctionDeclarations.add(declarationMap);
                            }
                        }
                    } catch (Exception e) {
                        logger.error("Error processing client " + client.getKey() + ": " + e.getMessage(), e);
                    }
                }
                
                if (!allFunctionDeclarations.isEmpty())
                {
                    // Add all function declarations as a single tool
                    List<Map<String, Object>> tools = new ArrayList<>();
                    tools.add(Map.of("functionDeclarations", allFunctionDeclarations));
                    requestBody.put("tools", tools);
                    
                    // Configure function calling mode
                    var toolConfig = new LinkedHashMap<String, Object>();
                    var functionCallingConfig = new LinkedHashMap<String, Object>();
                    functionCallingConfig.put("mode", "AUTO"); // AUTO, ANY, or NONE
                    toolConfig.put("functionCallingConfig", functionCallingConfig);
                    requestBody.put("toolConfig", toolConfig);
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
            // in Gemini API, it's not "assistant" but "model" role
            var role = message.getRole();
            if ( role.contentEquals( "assistant" ) )
            {
                role = "model";
            }
            userMessage.put("role", role );
            
            // Handle function calls
            if (model.functionCalling() && Objects.nonNull(message.getFunctionCall()))
            {
                if ("function".equals(message.getRole()))
                {
                    // Function response
                    var functionCall = message.getFunctionCall();
                    
                    // FIXME: google is precise about the declared function return type and the actual value
                    // if function was declared an object, and it returns just a string, then it has to 
                    // be converted into an object. Normally it should be parsed with  objectMapper.readTree(message.getContent())
                    userMessage.put("parts", List.of(Map.of("functionResponse", Map.of(
                            "name", functionCall.name(),
                            "response", Map.of("result", message.getContent().toString() ) // for the moment treat everything as text  
                    ))));
                }
                else if ("assistant".equals(message.getRole()) && Objects.nonNull(message.getFunctionCall()))
                {
                    // Function call from assistant
                    var functionCall = message.getFunctionCall();
                    userMessage.put("parts", List.of(Map.of("functionCall", Map.of(
                            "name", functionCall.name(),
                            "args", functionCall.arguments()
                    ))));
                }
                else {
                    // Regular content
                    handleContent(message, userMessage, model);
                }
            }
            else {
                // Regular content
                handleContent(message, userMessage, model);
            }
            
            return userMessage;
        }
        catch (Exception e)
        {
            throw new RuntimeException(e);
        }
    }
    
    private void handleContent(ChatMessage message, LinkedHashMap<String, Object> userMessage, ModelApiDescriptor model) {
        List<String> textParts = message.getAttachments()
                .stream()
                .map(Attachment::toChatMessageContent)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
        
        var attachmentsString = String.join("\n", textParts);
        
        var textContent = attachmentsString.isBlank() 
                ? message.getContent() 
                : attachmentsString + "\n\n" + message.getContent();
        
        // Handle content format based on whether there are images (vision capability)
        if (model.vision())
        {
            var parts = new ArrayList<>();
            
            // Add text content
            if (!textContent.isBlank()) {
                parts.add(Map.of("text", textContent));
            }
            
            // Add image content if available
            message.getAttachments()
                    .stream()
                    .map(Attachment::getImageData)
                    .filter(Objects::nonNull)
                    .map(ImageUtilities::toBase64Jpeg)
                    .map(this::toImagePart)
                    .forEach(parts::add);
            
            userMessage.put("parts", parts);
        }
        else
        {
            userMessage.put("parts", List.of(Map.of("text", textContent)));
        }
    }

    private Map<String, Object> toImagePart(String data)
    {
        var inlineData = new LinkedHashMap<String, Object>();
        inlineData.put("mime_type", "image/jpeg");
        inlineData.put("data", data);
        
        return Map.of("inline_data", inlineData);
    }
    
    /**
     * Constructs the proper URL for streaming based on the model configuration.
     * 
     * @param model The model configuration
     * @return A properly formatted URL for streaming content generation
     */
    private String constructStreamingUrl(ModelApiDescriptor model) {
        StringBuilder urlBuilder = new StringBuilder();
        
        // Start with the base API URL
        String baseUrl = model.apiUrl();
        if (baseUrl.endsWith("/")) {
            baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
        }
        
        urlBuilder.append(baseUrl);
        
        // Check if the URL already includes the model and endpoint
        if (!baseUrl.contains("/models/")) {
            // Add the models prefix if needed
            if (!baseUrl.endsWith("/models")) {
                urlBuilder.append("/models");
            }
            
            // Add the model name
            urlBuilder.append("/").append(model.modelName());
        }
        
        // Add the streaming endpoint
        urlBuilder.append(":streamGenerateContent");
        
        // Add the SSE parameter
        urlBuilder.append("?alt=sse");
        
        return urlBuilder.toString();
    }

    public Runnable run(Conversation prompt)
    {
        return () -> {
            var model = configuration.getSelectedModel().orElseThrow();
            
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(configuration.getConnectionTimoutSeconds()))
                    .build();

            String requestBody = getRequestBody(prompt, model);
            
            // Construct the proper URL for streaming
            String apiUrl = constructStreamingUrl(model);
            
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(apiUrl))
                    .timeout(Duration.ofSeconds(configuration.getRequestTimoutSeconds()))
                    .header("Content-Type", "application/json")
                    .header("x-goog-api-key", model.apiKey())
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                    .build();

            logger.info("Sending request to Gemini API.\n\n" + requestBody);

            try
            {
                // Use ofLines() for line-by-line processing of the streaming response
                HttpResponse<Stream<String>> response = client.send(request, HttpResponse.BodyHandlers.ofLines());

                if (response.statusCode() != 200)
                {
                    String responseBody = response.body().collect(Collectors.joining("\n"));
                    logger.error("Request failed with status code: " + response.statusCode() + " and response body: " + responseBody);
                    publisher.closeExceptionally(new RuntimeException("API request failed: " + response.statusCode()));
                    return;
                }
                
                // Process each line as it arrives
                response.body()
                    .filter(line -> !line.isEmpty())
                    .filter(line -> line.startsWith("data:"))
                    .takeWhile(line -> !isCancelled.get())
                    .forEach(line -> {
                        // Extract the data part after "data:" prefix
                        line = line.substring(5).trim();
                        
                        // Skip [DONE] marker
                        if ("[DONE]".equals(line)) {
                            return;
                        }
                        
                        try {
                            var node = objectMapper.readTree(line);
                            
                            // Process candidates if present
                            if (node.has("candidates") && !node.get("candidates").isEmpty()) {
                                var candidate = node.get("candidates").get(0);
                                
                                if (candidate.has("content") && candidate.get("content").has("parts")) {
                                    var parts = candidate.get("content").get("parts");
                                    
                                    for (JsonNode part : parts) {
                                        // Handle text content
                                        if (part.has("text")) {
                                            publisher.submit(new Incoming(Incoming.Type.CONTENT, part.get("text").asText()));
                                        }
                                        
                                        // Handle function calls
                                        if (part.has("functionCall")) {
                                            var functionCall = part.get("functionCall");
                                            String functionName = functionCall.get("name").asText();
                                            
                                            // Submit function call name
                                            publisher.submit(new Incoming(Incoming.Type.FUNCTION_CALL, 
                                                    String.format("\"function_call\" : { \n \"name\": \"%s\",\n \"arguments\" :", functionName)));
                                            
                                            // Submit function call arguments
                                            if (functionCall.has("args")) {
                                                publisher.submit(new Incoming(Incoming.Type.FUNCTION_CALL, 
                                                        functionCall.get("args").toString()));
                                            }
                                        }
                                    }
                                }
                            }
                        } 
                        catch (Exception e) {
                            logger.error("Error parsing response line: " + line, e);
                        }
                    });
                    
                if (isCancelled.get())
                {
                    publisher.closeExceptionally(new CancellationException());
                }
                else
                {
                    publisher.close();
                }
            }
            catch (Exception e)
            {
                logger.error(e.getMessage(), e);
                publisher.closeExceptionally(e);
            }
        };
    }
}
