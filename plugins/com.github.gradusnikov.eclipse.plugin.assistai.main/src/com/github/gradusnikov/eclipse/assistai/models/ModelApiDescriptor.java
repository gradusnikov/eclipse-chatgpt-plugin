package com.github.gradusnikov.eclipse.assistai.models;

import java.time.Duration;

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
         boolean functionCalling
         ) {
       
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
                    stub.functionCalling()
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
} 
