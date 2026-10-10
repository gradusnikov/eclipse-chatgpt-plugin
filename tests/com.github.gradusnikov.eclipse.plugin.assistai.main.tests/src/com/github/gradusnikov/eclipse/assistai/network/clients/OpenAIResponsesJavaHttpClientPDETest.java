package com.github.gradusnikov.eclipse.assistai.network.clients;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

class OpenAIResponsesJavaHttpClientPDETest
{
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void turnsFailedResponseEventIntoException() throws Exception
    {
        var event = mapper.readTree( """
                {
                  "type": "response.failed",
                  "response": {
                    "id": "resp_test",
                    "error": {
                      "code": "credit_balance_exhausted",
                      "message": "You have no credits remaining."
                    }
                  }
                }
                """ );

        var exception = assertThrows(
                OpenAIResponsesJavaHttpClient.OpenAIResponseException.class,
                () -> OpenAIResponsesJavaHttpClient.throwIfFailedResponse( event ) );

        assertEquals( "resp_test", exception.responseId() );
        assertEquals( "credit_balance_exhausted", exception.code() );
        assertEquals(
                "You have no credits remaining. (credit_balance_exhausted)",
                exception.getMessage() );
    }

    @Test
    void acceptsNormalStreamingEvent() throws Exception
    {
        var event = mapper.readTree( """
                { "type": "response.output_text.delta", "delta": "ok" }
                """ );

        assertDoesNotThrow( () -> OpenAIResponsesJavaHttpClient.throwIfFailedResponse( event ) );
    }
}
