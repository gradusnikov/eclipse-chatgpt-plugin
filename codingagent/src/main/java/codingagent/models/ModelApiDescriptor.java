package codingagent.models;

/**
 * Api Descriptor
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