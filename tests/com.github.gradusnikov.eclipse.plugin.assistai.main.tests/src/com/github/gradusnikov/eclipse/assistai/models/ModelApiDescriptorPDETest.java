package com.github.gradusnikov.eclipse.assistai.models;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

public class ModelApiDescriptorPDETest
{
    @Test
    public void unsupportedTemperatureIsOmitted()
    {
        assertTrue( descriptor( ModelApiDescriptor.TEMPERATURE_NOT_SUPPORTED )
                .scaledTemperature()
                .isEmpty() );
    }

    @Test
    public void zeroTemperatureScalesToZero()
    {
        assertEquals( 0.0f, descriptor( 0 ).scaledTemperature().orElseThrow(), 0.0001f );
    }

    @Test
    public void midpointTemperatureScalesToPointFive()
    {
        assertEquals( 0.5f, descriptor( 5 ).scaledTemperature().orElseThrow(), 0.0001f );
    }

    @Test
    public void maximumTemperatureScalesToOne()
    {
        assertEquals( 1.0f, descriptor( 10 ).scaledTemperature().orElseThrow(), 0.0001f );
    }

    private static ModelApiDescriptor descriptor( int temperature )
    {
        return new ModelApiDescriptor(
                "test",
                "openai",
                "http://localhost",
                "key",
                10,
                30,
                "test-model",
                temperature,
                false,
                false );
    }
}
