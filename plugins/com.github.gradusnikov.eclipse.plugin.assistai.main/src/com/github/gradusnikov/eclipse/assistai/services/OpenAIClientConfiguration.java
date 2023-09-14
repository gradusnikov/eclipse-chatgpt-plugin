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
    private final String CHAT_URL = "/v1/chat/completions";

    public String getApiBase()
    {
        IPreferenceStore prefernceStore = Activator.getDefault().getPreferenceStore();
        return prefernceStore.getString(PreferenceConstants.OPENAI_API_BASE);
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
        return getApiBase() + CHAT_URL;
    }

}
