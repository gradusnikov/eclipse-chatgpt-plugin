package com.github.gradusnikov.eclipse.assistai.preferences.models;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

public class ModelListPreferencePagePDETest
{
    @Test
    public void temperatureLabelShowsProviderDefault()
    {
        assertEquals( "Temperature: Default", ModelListPreferencePage.temperatureLabelText( -1 ) );
    }

    @Test
    public void temperatureLabelShowsScaledValue()
    {
        assertEquals( "Temperature: 0.0", ModelListPreferencePage.temperatureLabelText( 0 ) );
        assertEquals( "Temperature: 0.5", ModelListPreferencePage.temperatureLabelText( 5 ) );
        assertEquals( "Temperature: 1.0", ModelListPreferencePage.temperatureLabelText( 10 ) );
    }
}
