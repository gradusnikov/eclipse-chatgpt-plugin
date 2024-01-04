package com.github.gradusnikov.eclipse.assistai.commands;

import java.io.IOException;
import java.net.URL;

import jakarta.inject.Inject;

import org.eclipse.core.runtime.ILog;
import org.eclipse.e4.core.di.annotations.Creatable;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Class responsible for getting weather information from OpenWeather API
 */
@Creatable
public class OpenWeatherCommand
{
    @Inject
    private ILog logger;
    /**
     * Get the current weather for the provided location and unit.
     * @param location The location to get the weather for.
     * @param unit The unit of the weather data.
     * @return The weather data.
     */
    public String getCurrentWeather( String location, String unit )
    {
        String appid = "bd5e378503939ddaee76f12ad7a97608";// FOR TESTING ONLY!
        // get lat, long by city name
        String directURLTemplate = "https://api.openweathermap.org/geo/1.0/direct?q=${location}&limit=1&appid=${key}";
        
        // get current wether for lat long
        ObjectMapper mapper = new ObjectMapper();
        try
        {
            URL directURL = new URL(directURLTemplate.replace("${key}", appid)
                                                     .replace("${location}", location));
            
            logger.info( "Query OpenWeather: " + directURL );
            
            JsonNode direct = mapper.readTree( directURL );
            String weatherURLTemplate =  "https://api.openweathermap.org/data/2.5/weather?lat=${lat}&lon=${lon}&appid=${key}&units=${unit}";
            String lat = direct.get( 0 ).get( "lat" ).asText();
            String lon = direct.get( 0 ).get( "lon" ).asText();
            
            URL weatherURL = new URL( weatherURLTemplate.replace( "${lat}", lat )
                                                       .replace( "${lon}", lon )
                                                       .replace( "${unit}", unit )
                                                       .replace("${key}", appid));
            
            logger.info( "Query OpenWeather: " + weatherURL );
            
            JsonNode weather = mapper.readTree( weatherURL );
            return mapper.writerWithDefaultPrettyPrinter().writeValueAsString( weather ); 

        }
        catch ( IOException e )
        {
            throw new RuntimeException(e);
        }
        
    }
}
