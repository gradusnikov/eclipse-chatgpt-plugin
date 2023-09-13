package com.example.handlers.services;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.gradusnikov.eclipse.assistai.services.AnnotationToJsonConverter;
import com.github.gradusnikov.eclipse.assistai.services.Function;
import com.github.gradusnikov.eclipse.assistai.services.FunctionExecutor;
import com.github.gradusnikov.eclipse.assistai.services.FunctionParam;

class AnnotationToJsonConverterTest
{


    public static class HelloFunction 
    {
        @Function(name="getCurrentWeather", description="Get the current weather in a given location", type="object")
        public WeatherReport getCurrentWeather( 
                @FunctionParam(name="location", description="The city and state, e.g. San Francisco, CA", required=true) String location, 
                @FunctionParam(name="unit", description="The temperature unit, e.g. celsius or fahrenheit" ) String unit )
        {
            return new WeatherReport ( location, 72, unit, new String[]{"sunny", "windy"});
        }
    }

    String expectedJson = """
        [ {
          "name" : "getCurrentWeather",
          "description" : "Get the current weather in a given location",
          "parameters" : {
            "type" : "object",
            "properties" : {
              "location" : {
                "type" : "string",
                "description" : "The city and state, e.g. San Francisco, CA"
              },
              "unit" : {
                "type" : "string",
                "description" : "The temperature unit, e.g. celsius or fahrenheit"
              }
            },
            "required" : [ "location" ]
          }
        } ]  
            """;
    
    String functionCall = """
            "function_call" : {
                "arguments" : {
                    "location" : "San Francisco, CA",
                    "unit" : "celsius" 
                },
                "name" : "getCurrentWeather"
            }
            """;
    
    public record WeatherReport( String location, int temperature, String unit, String[] forecast  ) {};
    
    
    @Test
    void test() throws Exception
    {
        JsonNode actual = AnnotationToJsonConverter.convertToJson( HelloFunction.class );
        ObjectMapper mapper = new ObjectMapper();
        System.out.println( mapper.writerWithDefaultPrettyPrinter().writeValueAsString( actual ) );        
        JsonNode expected = mapper.readTree( expectedJson );
        
//        assertThat( actual ).isEqualTo( expected );
        
        
        FunctionExecutor executor = new FunctionExecutor( new HelloFunction() );
        String[] args = {"location", "San Francisco, CA", "unit", "celsius"}; 
        CompletableFuture<Object> future = executor.call("getCurrentWeather", args);
        future = future.thenApply( t -> {
            try
            {
                String str = mapper.writerWithDefaultPrettyPrinter().writeValueAsString( t ); 
                return str;
            }
            catch ( JsonProcessingException e )
            {
                throw new RuntimeException();
            }
        } );
        System.out.println( future.get() );
//        System.out.println( mapper.writerWithDefaultPrettyPrinter().writeValueAsString( result.get() ) );        
        
    }

}
