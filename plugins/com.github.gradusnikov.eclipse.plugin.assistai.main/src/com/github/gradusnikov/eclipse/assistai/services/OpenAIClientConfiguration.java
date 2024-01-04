package com.github.gradusnikov.eclipse.assistai.services;

import javax.inject.Singleton;

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

    public String getModelName()
    {
        IPreferenceStore prefernceStore = Activator.getDefault().getPreferenceStore();
        return prefernceStore.getString(PreferenceConstants.OPENAI_MODEL_NAME);
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
    
}
