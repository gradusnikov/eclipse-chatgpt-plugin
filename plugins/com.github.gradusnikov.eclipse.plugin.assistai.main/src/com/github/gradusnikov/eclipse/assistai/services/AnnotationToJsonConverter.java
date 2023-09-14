
package com.github.gradusnikov.eclipse.assistai.services;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.gradusnikov.eclipse.assistai.commands.FunctionExecutor;
import com.github.gradusnikov.eclipse.assistai.commands.FunctionParam;

public class AnnotationToJsonConverter
{
    public static JsonNode convertDeclaredFunctionsToJson( Method ... methods )
    {
        var functionDetailsList = new ArrayList<>();
        
        for ( Method method : methods )
        {
            var functionAnnotation = method.getAnnotation( com.github.gradusnikov.eclipse.assistai.commands.Function.class );
            if ( functionAnnotation != null )
            {
                var properties = new LinkedHashMap<String, Property>();
                var required   = new ArrayList<String>();
                
                for ( var param : method.getParameters() )
                {
                    FunctionParam functionParamAnnotation = param.getAnnotation( FunctionParam.class );
                    if ( functionParamAnnotation != null )
                    {
                        String name = FunctionExecutor.toParamName( param ); 
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
                var name = FunctionExecutor.toFunctionName( method );
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

}
