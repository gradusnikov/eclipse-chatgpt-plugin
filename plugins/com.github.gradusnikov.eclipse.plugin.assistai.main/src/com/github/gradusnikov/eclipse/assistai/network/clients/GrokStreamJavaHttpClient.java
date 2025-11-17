package com.github.gradusnikov.eclipse.assistai.network.clients;


import java.io.BufferedReader;
import java.io.IOException;
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
import java.util.concurrent.TimeUnit;
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

@Creatable
public class GrokStreamJavaHttpClient implements LanguageModelClient {
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

    public GrokStreamJavaHttpClient() {
        publisher = new SubmissionPublisher<>();
        preferenceStore = Activator.getDefault().getPreferenceStore();
    }

    @Override
    public void setCancelProvider(Supplier<Boolean> isCancelled) {
        this.isCancelled = isCancelled;
    }

    @Override
    public synchronized void subscribe(Flow.Subscriber<Incoming> subscriber) {
        publisher.subscribe(subscriber);
    }

    static ArrayNode clientToolsToJson(String clientName, McpSyncClient client) {
        List<Map<String, Object>> tools = new ArrayList<>();

        for (var tool : client.listTools().tools()) {
            var toolObj = new LinkedHashMap<String, Object>();
            var functionObj = new LinkedHashMap<String, Object>();

            functionObj.put("name", clientName + "__" + tool.name());
            functionObj.put("description", tool.description() != null ? tool.description() : "");
            functionObj.put("parameters", Map.of(
                "type", tool.inputSchema().type(),
                "properties", tool.inputSchema().properties().isEmpty() ? new LinkedHashMap<>() : tool.inputSchema().properties(),
                "required", tool.inputSchema().required() != null ? tool.inputSchema().required() : new ArrayList<>()
            ));

            toolObj.put("type", "function");
            toolObj.put("function", functionObj);
            tools.add(toolObj);
        }

        var objectMapper = new ObjectMapper();
        return (ArrayNode) objectMapper.valueToTree(tools);
    }

    private String getRequestBody(Conversation prompt, ModelApiDescriptor model) {
        try {
            var requestBody = new LinkedHashMap<String, Object>();
            var messages = new ArrayList<Map<String, Object>>();

            // System message
            String systemPrompt = preferenceStore.getString(Prompts.SYSTEM.preferenceName());
            if (!systemPrompt.isBlank()) {
                messages.add(Map.of(
                    "role", "system",
                    "content", systemPrompt
                ));
            }

            // Add user and assistant messages
            prompt.messages().stream()
                .filter(Predicate.not(ChatMessage::isEmpty))
                .map(message -> toJsonPayload(message, model))
                .forEach(messages::add);

            requestBody.put("model", model.modelName());
            requestBody.put("messages", messages);
            requestBody.put("temperature", model.temperature() / 10.0);
            requestBody.put("stream", true);
            requestBody.put("max_tokens", 10000);

            // Add tools if function calling is enabled
            if (model.functionCalling()) {
                ArrayNode tools = objectMapper.createArrayNode();
                for (var client : mcpClientRegistry.listEnabledveClients().entrySet()) {
                    tools.addAll(clientToolsToJson(client.getKey(), client.getValue()));
                }
                if (!tools.isEmpty()) {
                    requestBody.put("tools", tools);
                    requestBody.put("tool_choice", "auto"); // Default as per xAI docs
                }
            }

            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(requestBody);
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }
    }

    private LinkedHashMap<String, Object> toJsonPayload(ChatMessage message, ModelApiDescriptor model) {
        try {
            var userMessage = new LinkedHashMap<String, Object>();
            String role = message.getRole();

            // Map roles to Grok API: user, assistant, system, or tool
            if ("function".equals(role)) {
                role = "tool"; // Grok uses "tool" for function call results
            } else if (!"user".equals(role) && !"assistant".equals(role) && !"system".equals(role)) {
                role = "user"; // Default to user for unrecognized roles
            }
            userMessage.put("role", role);

            // Handle tool calls/results
            if (model.functionCalling() && Objects.nonNull(message.getFunctionCall())) {
                if ("tool".equals(role)) {
                    var functionCall = message.getFunctionCall();
                    userMessage.put("tool_call_id", functionCall.id());
                    userMessage.put("content", message.getContent());
                } else {
                    var functionCall = message.getFunctionCall();
                    var toolCall = new LinkedHashMap<String, Object>();
                    toolCall.put("id", functionCall.id());
                    toolCall.put("type", "function");
                    
                    // Convert arguments Map to JSON string as required by Grok API
                    String argumentsJson;
                    try {
                        argumentsJson = objectMapper.writeValueAsString(functionCall.arguments());
                    } catch (JsonProcessingException e) {
                        argumentsJson = "{}";
                    }
                    
                    toolCall.put("function", Map.of(
                        "name", functionCall.name(),
                        "arguments", argumentsJson
                    ));
                    userMessage.put("content", "");
                    userMessage.put("tool_calls", List.of(toolCall));
                }
            } else {
                // Handle text and attachments
                List<String> textParts = message.getAttachments()
                    .stream()
                    .map(Attachment::toChatMessageContent)
                    .filter(Objects::nonNull)
                    .collect(Collectors.toList());
                var attachmentsString = String.join("\n", textParts);
                var textContent = attachmentsString.isBlank()
                    ? message.getContent()
                    : attachmentsString + "\n\n" + message.getContent();

                // Check if we have images for vision models
                boolean hasImages = model.vision() && message.getAttachments()
                    .stream()
                    .anyMatch(attachment -> attachment.getImageData() != null);

                if (hasImages) {
                    // Use array-based content for vision models with images
                    var contentList = new ArrayList<>();

                    // Add text content first (as per Grok API specs)
                    if (!textContent.isEmpty()) {
                        contentList.add(Map.of(
                            "type", "text",
                            "text", textContent
                        ));
                    }

                    // Add image content with proper Grok API format
                    message.getAttachments()
                        .stream()
                        .map(Attachment::getImageData)
                        .filter(Objects::nonNull)
                        .map(ImageUtilities::toBase64Jpeg)
                        .map(this::toGrokImageContent)
                        .forEachOrdered(contentList::add);

                    userMessage.put("content", contentList);
                } else {
                    // Use string content when no images are present
                    userMessage.put("content", textContent);
                }
            }
            return userMessage;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private LinkedHashMap<String, Object> toGrokImageContent(String data) {
        var imageObject = new LinkedHashMap<String, Object>();
        imageObject.put("type", "image_url");
        
        var imageUrl = new LinkedHashMap<String, Object>();
        imageUrl.put("url", "data:image/jpeg;base64," + data);
        imageUrl.put("detail", "auto"); // Use "auto" as default as per Grok API specs
        
        imageObject.put("image_url", imageUrl);
        return imageObject;
    }

    @Override
    public Runnable run(Conversation prompt) {
        return () -> {
            var model = configuration.getSelectedModel().orElseThrow();

            HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(configuration.getConnectionTimeoutSeconds()))
                .build();
            String requestBody = getRequestBody(prompt, model);
            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(model.apiUrl()))
                .timeout(Duration.ofSeconds(configuration.getRequestTimeoutSeconds()))
                .version(HttpClient.Version.HTTP_1_1)
                .header("Authorization", "Bearer " + model.apiKey())
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                .build();
            logger.info("Sending request to Grok API.\n\n" + requestBody);

            int maxRetries = 3;
            int retryCount = 0;
            boolean shouldRetry = false;

            do {
                shouldRetry = false;

                try {
                    HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
                    if (response.statusCode() == 429) {
                        String retryAfter = response.headers().firstValue("retry-after").orElse("60");
                        int waitSeconds = Integer.parseInt(retryAfter);

                        String responseBody = new String(response.body().readAllBytes(), StandardCharsets.UTF_8);
                        JsonNode errorNode = objectMapper.readTree(responseBody);
                        String errorType = errorNode.path("error").path("type").asText();
                        String errorMessage = errorNode.path("error").path("message").asText();

                        retryCount++;
                        if (retryCount <= maxRetries) {
                            String rateLimitMessage = String.format(
                                "Rate limit exceeded (%s). Waiting %d seconds before retry %d of %d. %s",
                                errorType, waitSeconds, retryCount, maxRetries, errorMessage);
                            logger.warn(rateLimitMessage);
                            Thread.sleep(TimeUnit.SECONDS.toMillis(waitSeconds));
                            shouldRetry = true;
                        } else {
                            throw new IOException("Max retries exceeded for rate limit. Error: " + errorMessage);
                        }
                    } else if (response.statusCode() != 200) {
                        String responseBody = new String(response.body().readAllBytes(), StandardCharsets.UTF_8);
                        throw new IOException("Request failed with status code: " + response.statusCode() + " and response body: " + responseBody);
                    } else {
                        try (var inputStream = response.body();
                             var inputStreamReader = new InputStreamReader(inputStream, StandardCharsets.UTF_8);
                             var reader = new BufferedReader(inputStreamReader)) {
                            String line;

                            while ((line = reader.readLine()) != null && !isCancelled.get()) {
                                if (line.isEmpty()) {
                                    continue;
                                }
                                if (line.startsWith("data:")) {
                                    line = line.substring(5).trim();
                                    if ("[DONE]".equals(line)) {
                                        continue;
                                    }
                                    processResponseEvent(line);
                                }
                            }
                        }
                    }
                } catch (Exception e) {
                    logger.error(e.getMessage(), e);
                    publisher.closeExceptionally(e);
                } finally {
                    if (!shouldRetry) {
                        if (isCancelled.get()) {
                            publisher.closeExceptionally(new CancellationException());
                        } else {
                            publisher.close();
                        }
                    }
                }
            } while (shouldRetry && !isCancelled.get());
        };
    }

    /**
     * Processes individual response events from the Grok API stream
     */
    private void processResponseEvent(String data) {
        try {
            var jsonNode = objectMapper.readTree(data);
            var choices = jsonNode.path("choices");
            
            if (choices.isArray() && !choices.isEmpty()) {
                var choice = choices.get(0);
                var delta = choice.path("delta");
                
                // According to Grok API docs, function calls are returned in whole in a single chunk
                // Handle complete tool calls
                if (delta.has("tool_calls")) {
                    var toolCalls = delta.get("tool_calls");
                    if (toolCalls.isArray()) {
                        for (JsonNode toolCall : toolCalls) {
                            processToolCall(toolCall);
                        }
                    }
                }
                // Handle content deltas
                else if (delta.has("content")) {
                    var content = delta.get("content").asText();
                    if (!content.isEmpty()) {
                        publisher.submit(new Incoming(Incoming.Type.CONTENT, content));
                    }
                }
            }
        } catch (Exception e) {
            logger.error("Error processing response event: " + data, e);
        }
    }

    /**
     * Processes a complete tool call from Grok API
     */
    private void processToolCall(JsonNode toolCall) {
        try {
            String toolId = toolCall.path("id").asText();
            var function = toolCall.path("function");
            String toolName = function.path("name").asText();
            String arguments = function.path("arguments").asText();
            
            // Create function call JSON in the same format as OpenAI client
            var functionCallJson = String.format(
                "\"function_call\": {\n    \"id\": \"%s\",\n    \"name\": \"%s\",\n    \"arguments\": %s\n}",
                toolId, toolName, arguments
            );
            
            publisher.submit(new Incoming(Incoming.Type.FUNCTION_CALL, functionCallJson));
        } catch (Exception e) {
            logger.error("Error processing tool call: " + toolCall, e);
        }
    }


}
