
package com.github.gradusnikov.eclipse.assistai.services;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.github.gradusnikov.eclipse.assistai.mcp.ToolExecutor;
import com.github.gradusnikov.eclipse.assistai.mcp.ToolParam;

import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;

public class AnnotationToJsonConverter
{
    public static ArrayNode clientToolsToJson( String clientName, McpSyncClient client )
    {
        List<Map<String, Object>> toolObject = new ArrayList<>();
        for (McpSchema.Tool tool : client.listTools().tools() )
        {
            toolObject.add( Map.of( "name", clientName + "__" +  tool.name(), // tool name is a combination of client name and the tool name
                    "type", tool.inputSchema().type(),
                    "description", Optional.ofNullable( tool.description() ).orElse( "" ),
                    "parameters", Map.of("type", tool.inputSchema().type(), "properties", tool.inputSchema().properties()), // in reference implementation this method is not visible!
                    "required", Optional.ofNullable(tool.inputSchema().required()).orElse( List.of() ) 
                    ));
        }
        var objectMapper = new ObjectMapper();
        var functionsJsonNode= objectMapper.valueToTree( toolObject );
        return (ArrayNode) functionsJsonNode; 
    }

    public static ArrayNode clientToolsToJsonAnthropic(String clientName, McpSyncClient client) {
        List<Map<String, Object>> tools = new ArrayList<>();
        
        for (McpSchema.Tool tool : client.listTools().tools()) {
            // Create the main tool object
            var toolObj = new LinkedHashMap<String, Object>();
            
            // Create the function definition
            toolObj.put("name", clientName + "__" + tool.name());
            toolObj.put("description", tool.description() != null ? tool.description() : "");
            
            // Create parameters object in the format Anthropic expects
            var inputSchema = new LinkedHashMap<String, Object>();
            inputSchema.put("type", "object");
            
            // Add properties
            inputSchema.put("properties", tool.inputSchema().properties());
            
            // Add required fields if present
            if (tool.inputSchema().required() != null && !tool.inputSchema().required().isEmpty()) {
                inputSchema.put("required", tool.inputSchema().required());
            }
            
            toolObj.put("input_schema", inputSchema);
            tools.add(toolObj);
        }
        
        var objectMapper = new ObjectMapper();
        var functionsJsonNode= objectMapper.valueToTree( tools );
        return (ArrayNode) functionsJsonNode; 
    }
}
