package com.github.gradusnikov.eclipse.assistai.models;

import java.time.Duration;
import java.util.Optional;

/**
 * 
 */
public record ModelApiDescriptor(
         String uid,
         String apiType,
         String apiUrl,
         String apiKey,
         int connectionTimeoutSeconds,
         int requestTimeoutSeconds,
         String modelName,
         int temperature,
         boolean vision,
         boolean functionCalling,
         ToolResponseFormat toolResponseFormat
         ) {
   
    public static final int TEMPERATURE_NOT_SUPPORTED = -1;

    /**
     * Normalizes model definitions loaded from preferences that predate the
     * response-format setting.
     */
    public ModelApiDescriptor
    {
        // Existing persisted model definitions predate this property.
        toolResponseFormat = toolResponseFormat == null ? ToolResponseFormat.PLAIN : toolResponseFormat;
    }
    
    public static ModelApiDescriptor copyWithUid( String uid, ModelApiDescriptor stub) {
        return new ModelApiDescriptor(
                    uid,
                    stub.apiType(),
                    stub.apiUrl(),
                    stub.apiKey(),
                    stub.connectionTimeoutSeconds(),
                    stub.requestTimeoutSeconds(),
                    stub.modelName(),
                    stub.temperature(),
                    stub.vision(),
                    stub.functionCalling(),
                    stub.toolResponseFormat()
                );
    };

    /** Returns the connection timeout as a Duration, defaulting to 10s if unset (0). */
    public Duration connectionTimeout()
    {
        return Duration.ofSeconds(connectionTimeoutSeconds > 0 ? connectionTimeoutSeconds : 10);
    }

    /** Returns the request timeout as a Duration, defaulting to 30s if unset (0). */
    public Duration requestTimeout()
    {
        return Duration.ofSeconds(requestTimeoutSeconds > 0 ? requestTimeoutSeconds : 30);
    }
    
    public Optional<Float> scaledTemperature()
    {
        return temperature >= 0 ? Optional.empty() : Optional.of( Float.valueOf( (float)temperature/10.0f ) );
        
    }
    
} 
