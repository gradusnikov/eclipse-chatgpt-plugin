package com.github.gradusnikov.eclipse.assistai.models;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

public class ModelApiDescriptorRepositoryPDETest
{
    @Test
    public void oldPersistedModelsDefaultToPlainToolResponses()
    {
        String legacyJson = """
                [ {
                  "uid" : "legacy",
                  "apiType" : "openai",
                  "apiUrl" : "https://example.test/v1",
                  "apiKey" : "",
                  "connectionTimeoutSeconds" : 10,
                  "requestTimeoutSeconds" : 30,
                  "modelName" : "legacy-model",
                  "temperature" : -1,
                  "vision" : false,
                  "functionCalling" : true
                } ]
                """;

        ModelApiDescriptor descriptor = ModelApiDescriptorRepository.fromJson( legacyJson ).getFirst();

        assertEquals( ToolResponseFormat.PLAIN, descriptor.toolResponseFormat() );
    }
}
