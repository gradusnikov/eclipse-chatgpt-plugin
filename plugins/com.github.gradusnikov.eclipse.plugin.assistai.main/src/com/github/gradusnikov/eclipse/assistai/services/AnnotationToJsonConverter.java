
package com.github.gradusnikov.eclipse.assistai.services;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;

public class AnnotationToJsonConverter
{

    public static JsonNode convertToJson( Class<?> clazz )
    {
        List<Function> functionDetailsList = new ArrayList<>();
        for ( Method method : clazz.getDeclaredMethods() )
        {
            var functionAnnotation = method.getAnnotation( com.github.gradusnikov.eclipse.assistai.services.Function.class );
            if ( functionAnnotation != null )
            {
                var properties = new LinkedHashMap<String, Property>();
                var required   = new ArrayList<String>();
                for ( var param : method.getParameters() )
                {
                    FunctionParam functionParamAnnotation = param.getAnnotation( FunctionParam.class );
                    if ( functionParamAnnotation != null )
                    {
                        String name = Optional.ofNullable( functionParamAnnotation.name() ).orElse( param.getName() );
                        properties.put( name, 
                                        new Property( functionParamAnnotation.type(),
                                                      functionParamAnnotation.description() ) 
                                        );
                        if ( functionParamAnnotation.required() )
                        {
                            required.add( functionParamAnnotation.name() );
                        }
                    }
                }
                
                Parameters parameters = new Parameters( functionAnnotation.type(), properties, required );
                String name = Optional.ofNullable( functionAnnotation.name() ).filter( Predicate.not(String::isBlank)).orElse( method.getName() );
                functionDetailsList.add( new Function( name, 
                                                       functionAnnotation.description(), 
                                                       parameters ) );
            }
        }
        ObjectMapper objectMapper = new ObjectMapper();
        return objectMapper.valueToTree( functionDetailsList );
    }

    public record Function( String name, String description,  Parameters parameters ) {}
    public record Parameters(String type, Map<String, Property> properties, List<String> required ) {}
    public record Property( String type, String description ) {}

}
