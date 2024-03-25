package com.github.gradusnikov.eclipse.assistai.services;

import jakarta.inject.Singleton;

import org.eclipse.e4.core.di.annotations.Creatable;
import org.eclipse.jface.preference.IPreferenceStore;

import com.github.gradusnikov.eclipse.assistai.Activator;
import com.github.gradusnikov.eclipse.assistai.preferences.PreferenceConstants;

@Creatable
@Singleton
public class OpenAIClientConfiguration 
{

    public String getApiBase()
    {
        IPreferenceStore prefernceStore = Activator.getDefault().getPreferenceStore();
        return prefernceStore.getString(PreferenceConstants.OPENAI_API_BASE);
    }
    
    public String getApiEndPoint()
    {
        IPreferenceStore prefernceStore = Activator.getDefault().getPreferenceStore();
        return prefernceStore.getString(PreferenceConstants.OPENAI_API_END_POINT);
    }

    public String getApiKey()
    {
        IPreferenceStore prefernceStore = Activator.getDefault().getPreferenceStore();
        return prefernceStore.getString(PreferenceConstants.OPENAI_API_KEY);
    }

    public String getChatModelName()
    {
        IPreferenceStore prefernceStore = Activator.getDefault().getPreferenceStore();
        return prefernceStore.getString(PreferenceConstants.OPENAI_CHAT_MODEL_NAME);
    }
    public String getVisionModelName()
    {
        IPreferenceStore prefernceStore = Activator.getDefault().getPreferenceStore();
        return prefernceStore.getString(PreferenceConstants.OPENAI_VISION_MODEL_NAME);
    }

    public String getApiUrl()
    {
    	if (getApiEndPoint().startsWith("/"))
    	{
    		return getApiBase() + getApiEndPoint();
    	}
    	else
    	{
    		return getApiBase() + "/" + getApiEndPoint();
    	}
    }
    
    public int getConnectionTimoutSeconds()
    {
        IPreferenceStore prefernceStore = Activator.getDefault().getPreferenceStore();
        return Integer.parseInt( prefernceStore.getString(PreferenceConstants.OPENAI_CONNECTION_TIMEOUT_SECONDS) );
        
    }
    
    public int getRequestTimoutSeconds()
    {
        IPreferenceStore prefernceStore = Activator.getDefault().getPreferenceStore();
        return Integer.parseInt( prefernceStore.getString(PreferenceConstants.OPENAI_REQUEST_TIMEOUT_SECONDS) );
        
    }
    
    public double getModelTemperature()
    {
        IPreferenceStore prefernceStore = Activator.getDefault().getPreferenceStore();
        double temperatureInt = Integer.parseInt( prefernceStore.getString(PreferenceConstants.OPENAI_MODEL_TEMPERATURE) );
        return temperatureInt/10.0;
        
    }
    
}
