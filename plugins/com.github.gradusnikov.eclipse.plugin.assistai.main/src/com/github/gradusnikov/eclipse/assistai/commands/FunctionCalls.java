package com.github.gradusnikov.eclipse.assistai.commands;

import java.util.Arrays;



import org.eclipse.e4.core.di.annotations.Creatable;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;

@Creatable
@Singleton
public class FunctionCalls
{
    @Inject
    private OpenWeatherCommand openWeatherCommand;
    @Inject
    private ReadJavaDocCommand readJavaDocCommand;
    @Inject
    private WebSearchCommand   webSearchCommand;
    @Inject
    private ReadWebPageCommand readWebPageCommand;
    
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
    
    
    @Function(name="getJavaDoc", description="Get the JavaDoc for the given compilation unit.  For example,a class B defined as a member type of a class A in package x.y should have athe fully qualified name \"x.y.A.B\".Note that in order to be found, a type name (or its top level enclosingtype name) must match its corresponding compilation unit name.", type="object")
    public String getJavaDoc(
            @FunctionParam(name="fullyQualifiedName", description="A fully qualified name of the compilation unit", required=true) String fullyQualifiedClassName)
    {
        return readJavaDocCommand.getClassAttachedJavadoc( fullyQualifiedClassName );
    }
    @Function(name="getSource", description="Get the source for the given class.", type="object")
    public String getSource(
            @FunctionParam(name="fullyQualifiedClassName", description="A fully qualified class name of the Java class", required=true) String fullyQualifiedClassName)
    {
        return readJavaDocCommand.getClassAttachedSource( fullyQualifiedClassName );
    }

    @Function(name="webSearch", description="Performs a search using a Duck Duck Go search engine and returns the search result json.", type="object")
    public String webSearch(
            @FunctionParam(name="query", description="A search query", required=true) String query)
    {
        return webSearchCommand.search( query );
    }

    @Function(name="readWebPage", description="Reads the content of the given web site and returns its content as a markdown text.", type="object")
    public String readWebPage(
            @FunctionParam(name="url", description="A web site URL", required=true) String url)
    {
        return readWebPageCommand.readWebPage( url );
    }
    
    

}
