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
         Duration connectionTimeout,
         Duration requestTimeout,
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
                    stub.connectionTimeout(),
                    stub.requestTimeout(),
                    stub.modelName(),
                    stub.temperature(),
                    stub.vision(),
                    stub.functionCalling()
                );
    };
            
    
} 