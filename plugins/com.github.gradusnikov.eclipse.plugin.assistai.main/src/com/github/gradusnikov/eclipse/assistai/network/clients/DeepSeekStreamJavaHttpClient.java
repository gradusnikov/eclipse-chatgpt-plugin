
package com.github.gradusnikov.eclipse.assistai.network.clients;

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
 * A Java HTTP client for streaming requests to DeepSeek API.
 * This class allows subscribing to responses received from the DeepSeek API and processes the chat completions.
 */
@Creatable
public class DeepSeekStreamJavaHttpClient implements LanguageModelClient
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

    public DeepSeekStreamJavaHttpClient()
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

    private ArrayNode clientToolsToJson(String clientName, McpSyncClient client) {
        List<Map<String, Object>> tools = new ArrayList<>();
        
        for (var tool : client.listTools().tools()) {
            // Create the main tool object
            var toolObj = new LinkedHashMap<String, Object>();
            var functionObj = new LinkedHashMap<String, Object>();
            
            // Create the function definition
            functionObj.put("name", clientName + "__" + tool.name());
            functionObj.put("description", tool.description() != null ? tool.description() : "");
            
            // Create parameters object in the format DeepSeek expects
            var parametersObj = new LinkedHashMap<String, Object>();
            parametersObj.put("type", tool.inputSchema().type());
            
            // Add properties
            if (!tool.inputSchema().properties().isEmpty()) {
                parametersObj.put("properties", tool.inputSchema().properties());
            }
            
            // Add required fields if present
            if (tool.inputSchema().required() != null && !tool.inputSchema().required().isEmpty()) {
                parametersObj.put("required", tool.inputSchema().required());
            }
            
            functionObj.put("parameters", parametersObj);
            
            // Add type and function to the tool object
            toolObj.put("type", "function");
            toolObj.put("function", functionObj);
            
            tools.add(toolObj);
        }
        
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
            if (systemPrompt != null && !systemPrompt.isEmpty()) {
                var systemMessage = new LinkedHashMap<String, Object>();
                systemMessage.put("role", "system");
                systemMessage.put("content", systemPrompt);
                messages.add(systemMessage);
            }

            // Add all messages from prompt
            prompt.messages().stream()
                  .filter(Predicate.not(ChatMessage::isEmpty))
                  .map(message -> toJsonPayload(message, model))
                  .forEach(messages::add);

            // Add required fields for DeepSeek API
            requestBody.put("model", model.modelName());
            requestBody.put("messages", messages);
            requestBody.put("temperature", model.temperature() / 10.0);
            requestBody.put("stream", true);
            requestBody.put("max_tokens", 4096); // Configurable limit
            
            // Add tools if function calling is enabled
            if (model.functionCalling())
            {
                ArrayNode tools = objectMapper.createArrayNode();
                for (var client : mcpClientRegistry.listEnabledveClients().entrySet())
                {
                    tools.addAll(clientToolsToJson(client.getKey(), client.getValue()));
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
            var messagePayload = new LinkedHashMap<String, Object>();
            // Set role (DeepSeek supports system, user, assistant, tool)
            String role = message.getRole();
            
            // Convert 'function' role to 'tool' for DeepSeek
            if ("function".equals(role)) {
                role = "tool";
            }
            messagePayload.put("role", role);
            
            // Handle function calls/tool responses
            if ("tool".equals(role) && Objects.nonNull(message.getFunctionCall())) {
                // For tool (function) responses
                var functionCall = message.getFunctionCall();
                messagePayload.put("tool_call_id", functionCall.id());
                messagePayload.put("content", message.getContent());
                return messagePayload;
            }
            
            // Handle assistant messages with function calls
            if ("assistant".equals(role) && Objects.nonNull(message.getFunctionCall())) {
                var functionCall = message.getFunctionCall();
                
                // Create tool_calls array
                List<Map<String, Object>> toolCalls = new ArrayList<>();
                Map<String, Object> toolCall = new LinkedHashMap<>();
                toolCall.put("id", functionCall.id());
                toolCall.put("type", "function");
                
                // Create function object
                Map<String, Object> function = new LinkedHashMap<>();
                function.put("name", functionCall.name());
                
                // Parse arguments - DeepSeek expects a JSON object, not a string
                try {
                    JsonNode argumentsNode = objectMapper.valueToTree(functionCall.arguments());
                    function.put("arguments", objectMapper.writeValueAsString(argumentsNode));
                } catch (Exception e) {
                    // If parsing fails, use the arguments as a string
                    function.put("arguments", functionCall.arguments());
                }
                
                toolCall.put("function", function);
                toolCalls.add(toolCall);
                
                messagePayload.put("tool_calls", toolCalls);
                
                // If there's also content, add it
                if (message.getContent() != null && !message.getContent().isEmpty()) {
                    messagePayload.put("content", message.getContent());
                }
                
                return messagePayload;
            }
            
            // Handle regular messages with text and attachments
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
            if (model.vision() && message.getAttachments().stream().anyMatch(a -> a.getImageData() != null))
            {
                var contentList = new ArrayList<>();
                
                // Add text content
                if (textContent != null && !textContent.isBlank()) {
                    var textObject = new LinkedHashMap<String, Object>();
                    textObject.put("type", "text");
                    textObject.put("text", textContent);
                    contentList.add(textObject);
                }
                
                // Add image content if available
                message.getAttachments()
                        .stream()
                        .map(Attachment::getImageData)
                        .filter(Objects::nonNull)
                        .map(ImageUtilities::toBase64Jpeg)
                        .map(this::toImageContent)
                        .forEachOrdered(contentList::add);
                
                messagePayload.put("content", contentList);
            }
            else
            {
                // Ensure content is never empty for assistant messages
                if ("assistant".equals(role) && (textContent == null || textContent.isBlank())) {
                    messagePayload.put("content", "");
                } else {
                    messagePayload.put("content", textContent);
                }
            }
            return messagePayload;
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
        
        // DeepSeek uses base64 format for images
        imageObject.put("image_url", Map.of(
            "url", "data:image/jpeg;base64," + data
        ));
        
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
                    .header("Authorization", "Bearer " + model.apiKey())
                    .header("Content-Type", "application/json")
                    .header("Accept", "text/event-stream")
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                    .build();

            logger.info("Sending request to DeepSeek API.\n\n" + requestBody);

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
                    String currentToolCallId = null;
                    
                    while ((line = reader.readLine()) != null && !isCancelled.get())
                    {
                        // Skip empty lines
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
                                var choices = node.get("choices");
                                
                                if (choices != null && choices.isArray() && choices.size() > 0) {
                                    var choice = choices.get(0);
                                    var delta = choice.get("delta");
                                    
                                    if (delta != null) {
                                        // Handle content (regular text response)
                                        if (delta.has("content")) {
                                            String content = delta.get("content").asText();
                                            if (!content.isEmpty()) {
                                                publisher.submit(new Incoming(Incoming.Type.CONTENT, content));
                                            }
                                        }
                                        
										// handle function calls
                                        if (delta.has("tool_calls") && delta.get("tool_calls").isArray()) {
										    var toolCalls = delta.get("tool_calls");
										    for (var toolCall : toolCalls) {
										        // Publish the function call name and ID (first chunk)
										        if (toolCall.has("index") && toolCall.has("id") && toolCall.has("type") && 
										            "function".equals(toolCall.get("type").asText())) {
										            
										            currentToolCallId = toolCall.get("id").asText();
										            var function = toolCall.get("function");
										            
										            if (function != null && function.has("name")) {
										                String functionName = function.get("name").asText();
										                // Publish the initial function call structure
										                
										                publisher.submit(new Incoming(Incoming.Type.FUNCTION_CALL,
					                                        String.format( "\"function_call\" : { \n \"name\": \"%s\",\n \"id\": \"%s\",\n \"arguments\" :", functionName, currentToolCallId ) 
										                ));
										            }
										        }
										        // Publish argument chunks (raw JSON strings)
										        if (toolCall.has("function") && toolCall.get("function").has("arguments")) {
										            publisher.submit(new Incoming(Incoming.Type.FUNCTION_CALL, toolCall.get("function").get("arguments").asText()));
										        }
										    }
										}
										
										// Publish the closing brace when the function call is complete
										var finishReason = choice.get("finish_reason");
										if (finishReason != null && "tool_calls".equals(finishReason.asText())) {
										    publisher.submit(new Incoming(Incoming.Type.FUNCTION_CALL, "}"));
										}
                                    }
                                }
                            } 
                            catch (Exception e) {
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
