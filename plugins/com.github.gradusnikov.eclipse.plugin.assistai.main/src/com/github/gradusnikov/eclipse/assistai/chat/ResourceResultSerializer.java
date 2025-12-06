package com.github.gradusnikov.eclipse.assistai.chat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Serializes/deserializes ResourceToolResult to/from a special JSON format
 * that can be detected in tool output.
 * 
 * Format:
 * {"__resourceCache__":true,"uri":"...","type":"...","displayName":"...","workspacePath":"...","toolName":"...","content":"..."}
 */
public class ResourceResultSerializer {
    
    private static final String MARKER = "__resourceCache__";
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    
    /**
     * Serializes a ResourceToolResult to a JSON string that can be detected and parsed.
     */
    public static String serialize(ResourceToolResult result) {
        if (result == null || !result.isCacheable()) {
            return result != null ? result.content() : "";
        }
        
        try {
            ObjectNode json = OBJECT_MAPPER.createObjectNode();
            json.put(MARKER, true);
            json.put("uri", result.descriptor().uri().toString());
            json.put("type", result.descriptor().type().name());
            json.put("displayName", result.descriptor().displayName());
            json.put("workspacePath", 
                result.descriptor().workspacePath() != null 
                    ? result.descriptor().workspacePath().toString() 
                    : null);
            json.put("toolName", result.descriptor().toolName());
            json.put("content", result.content());
            
            return OBJECT_MAPPER.writeValueAsString(json);
        } catch (Exception e) {
            // If serialization fails, return original content
            return result.content();
        }
    }
    
    /**
     * Checks if the given text is a serialized ResourceToolResult.
     */
    public static boolean isResourceResult(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        String trimmed = text.trim();
        return trimmed.startsWith("{\"" + MARKER + "\":");
    }
    
    /**
     * Deserializes a ResourceToolResult from JSON string.
     * Returns null if the text is not a valid resource result.
     */
    public static ResourceToolResult deserialize(String text) {
        if (!isResourceResult(text)) {
            return null;
        }
        
        try {
            JsonNode json = OBJECT_MAPPER.readTree(text.trim());
            
            String uriStr = json.get("uri").asText();
            String typeStr = json.get("type").asText();
            String displayName = json.get("displayName").asText();
            JsonNode workspacePathNode = json.get("workspacePath");
            String workspacePathStr = (workspacePathNode != null && !workspacePathNode.isNull()) 
                ? workspacePathNode.asText() 
                : null;
            String toolName = json.get("toolName").asText();
            String content = json.get("content").asText();
            
            ResourceDescriptor.ResourceType type = ResourceDescriptor.ResourceType.valueOf(typeStr);
            java.net.URI uri = java.net.URI.create(uriStr);
            org.eclipse.core.runtime.IPath workspacePath = workspacePathStr != null 
                ? org.eclipse.core.runtime.IPath.fromOSString(workspacePathStr) 
                : null;
            
            ResourceDescriptor descriptor = new ResourceDescriptor(
                uri, type, displayName, workspacePath, toolName
            );
            
            return new ResourceToolResult(descriptor, content);
            
        } catch (Exception e) {
            // If parsing fails, return null (treat as regular content)
            return null;
        }
    }
}
