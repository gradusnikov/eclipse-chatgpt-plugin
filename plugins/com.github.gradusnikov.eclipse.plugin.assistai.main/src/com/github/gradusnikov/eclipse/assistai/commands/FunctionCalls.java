package com.github.gradusnikov.eclipse.assistai.commands;

import java.util.Arrays;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;

import org.eclipse.e4.core.di.annotations.Creatable;

@Creatable
@Singleton
public class FunctionCalls
{
    
    @Inject
    private OpenWeatherCommand openWeatherCommand;
    @Inject
    private ReadJavaDocCommand readJavaDocCommand;
    
    @Function(name="getCurrentWeather", description="Get the current weather in a given location", type="object")
    public String getCurrentWeather( 
            @FunctionParam(name="location", description="The city and state or country, e.g. San Francisco, CA", required=true) String location, 
            @FunctionParam(name="unit", description="The temperature unit, e.g. metric or imperial. Default value: metric" ) String unit )
    {
        var userUnit = unit;
        unit = Arrays.stream( new String[]{"imperial", "metric", "standard"} )
                     .filter( a -> a.equals( userUnit ) )
                     .findAny()
                     .orElse( "metric" );
            
        return openWeatherCommand.getCurrentWeather( location, unit );
    }
    
    public record WeatherReport( String location, int degrees, String unit, String[] forecast ) {};
    
    
    @Function(name="getJavaDoc", description="Get the JavaDoc for the given class.", type="object")
    public String getJavaDoc(
            @FunctionParam(name="fullyQualifiedClassName", description="A fully qualified class name of the Java class", required=true) String fullyQualifiedClassName)
    {
        return readJavaDocCommand.getClassAttachedJavadoc( fullyQualifiedClassName );
    }
    @Function(name="getSource", description="Get the source for the given class.", type="object")
    public String getSource(
            @FunctionParam(name="fullyQualifiedClassName", description="A fully qualified class name of the Java class", required=true) String fullyQualifiedClassName)
    {
        return readJavaDocCommand.getClassAttachedSource( fullyQualifiedClassName );
    }

}
