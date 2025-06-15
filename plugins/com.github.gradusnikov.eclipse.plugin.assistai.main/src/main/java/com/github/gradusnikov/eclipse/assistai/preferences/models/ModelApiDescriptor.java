package com.github.gradusnikov.eclipse.assistai.preferences.models;

/**
 * 
 */
public record ModelApiDescriptor(
         String uid,
         String apiType,
         String apiUrl,
         String apiKey,
         String modelName,
         int temperature,
         boolean vision,
         boolean functionCalling
         ) {} 