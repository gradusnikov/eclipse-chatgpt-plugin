package com.github.gradusnikov.eclipse.assistai.preferences;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.gradusnikov.eclipse.assistai.mcp.McpServerDescriptor;

/**
 * Utilities for serializing and deserializing MCP Server descriptors
 */
public class McpServerDescriptorUtilities {
    
    private static final ObjectMapper objectMapper = new ObjectMapper();
    
    /**
     * Convert a list of MCP Server descriptors to JSON
     * 
     * @param descriptors the descriptors to convert
     * @return JSON string
     */
    public static String toJson(List<McpServerDescriptor> descriptors) {
        try {
            return objectMapper.writeValueAsString(descriptors);
        } catch (JsonProcessingException e) {
            // In case of error, return empty JSON array
            return "[]";
        }
    }
    
    /**
     * Convert a list of MCP Server descriptors to JSON
     * 
     * @param descriptors the descriptors to convert
     * @return JSON string
     */
    public static String toJson(McpServerDescriptor... descriptors) {
        return toJson(Arrays.asList(descriptors));
    }
    
    /**
     * Convert JSON to a list of MCP Server descriptors
     * 
     * @param json the JSON to convert
     * @return list of descriptors
     */
    public static List<McpServerDescriptor> fromJson(String json) {
        if (json == null || json.isEmpty()) {
            return new ArrayList<>();
        }
        
        try {
            return objectMapper.readValue(json, new TypeReference<List<McpServerDescriptor>>() {});
        } catch (Exception e) {
            // In case of parsing error, return empty list
            return new ArrayList<>();
        }
    }
}