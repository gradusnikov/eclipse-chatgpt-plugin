
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
    public static JsonNode convertDeclaredFunctionsToJson( Method ... methods )
    {
        var functionDetailsList = new ArrayList<>();
        
        for ( Method method : methods )
        {
            var functionAnnotation = method.getAnnotation( com.github.gradusnikov.eclipse.assistai.mcp.Tool.class );
            if ( functionAnnotation != null )
            {
                var properties = new LinkedHashMap<String, Property>();
                var required   = new ArrayList<String>();
                
                for ( var param : method.getParameters() )
                {
                    ToolParam functionParamAnnotation = param.getAnnotation( ToolParam.class );
                    if ( functionParamAnnotation != null )
                    {
                        String name = ToolExecutor.toParamName( param ); 
                        properties.put( name, 
                                        new Property( functionParamAnnotation.type(),
                                                      functionParamAnnotation.description() ) 
                                        );
                        if ( functionParamAnnotation.required() )
                        {
                            required.add( name );
                        }
                    }
                }
                
                var parameters = new Parameters( functionAnnotation.type(), properties, required );
                var name = ToolExecutor.toFunctionName( method );
                functionDetailsList.add( new Function( name, 
                                                       functionAnnotation.description(), 
                                                       parameters ) );
            }
        }
        var objectMapper = new ObjectMapper();
        return objectMapper.valueToTree( functionDetailsList );

    }

    /**
     * Converts methods of the class that have {@link Function} annotations into
     * a JSON structure.
     * <p>
     * See <a href=
     * "https://platform.openai.com/docs/guides/gpt/function-calling">OpenAI API
     * docs</a> for details.
     *
     * 
     * @param clazz
     *            a {@link Class} where we expect to find {@link Function}
     *            annotated methods.
     * @return a {@link JsonNode} in OpenAI API format
     */
    public static JsonNode convertDeclaredFunctionsToJson( Class<?> clazz )
    {
        return convertDeclaredFunctionsToJson( clazz.getDeclaredMethods() );
    }

    public record Function( String name, String description,  Parameters parameters ) {}
    public record Parameters(String type, Map<String, Property> properties, List<String> required ) {}
    public record Property( String type, String description ) {}
    
    
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

}
