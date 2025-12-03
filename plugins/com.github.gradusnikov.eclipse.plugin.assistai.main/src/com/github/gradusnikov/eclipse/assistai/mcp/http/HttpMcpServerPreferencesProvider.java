package com.github.gradusnikov.eclipse.assistai.mcp.http;

import org.eclipse.e4.core.di.annotations.Creatable;
import org.eclipse.jface.preference.IPreferenceStore;

import com.github.gradusnikov.eclipse.assistai.Activator;

import jakarta.inject.Singleton;

@Creatable
@Singleton
public class HttpMcpServerPreferencesProvider
{
    public HttpMcpServerPreferences get()
    {
        IPreferenceStore preferenceStore = Activator.getDefault().getPreferenceStore();

        String hostname = preferenceStore.getString(com.github.gradusnikov.eclipse.assistai.preferences.PreferenceConstants.ASSISTAI_MCP_HTTP_HOSTNAME);
        int port = preferenceStore.getInt(com.github.gradusnikov.eclipse.assistai.preferences.PreferenceConstants.ASSISTAI_MCP_HTTP_PORT);
        String token = preferenceStore.getString(com.github.gradusnikov.eclipse.assistai.preferences.PreferenceConstants.ASSISTAI_MCP_HTTP_AUTH_TOKEN);

        return new HttpMcpServerPreferences(port, hostname, token);
    }

    public void save(HttpMcpServerPreferences preferences)
    {
        IPreferenceStore preferenceStore = Activator.getDefault().getPreferenceStore();

        preferenceStore.setValue(com.github.gradusnikov.eclipse.assistai.preferences.PreferenceConstants.ASSISTAI_MCP_HTTP_HOSTNAME, preferences.hostname());
        preferenceStore.setValue(com.github.gradusnikov.eclipse.assistai.preferences.PreferenceConstants.ASSISTAI_MCP_HTTP_PORT, preferences.port());
        preferenceStore.setValue(com.github.gradusnikov.eclipse.assistai.preferences.PreferenceConstants.ASSISTAI_MCP_HTTP_AUTH_TOKEN, preferences.token());
    }

    public boolean isEnabled()
    {
        IPreferenceStore preferenceStore = Activator.getDefault().getPreferenceStore();
        return preferenceStore.getBoolean(com.github.gradusnikov.eclipse.assistai.preferences.PreferenceConstants.ASSISTAI_MCP_HTTP_ENABLED);
    }

    public void setEnabled(boolean enabled)
    {
        IPreferenceStore preferenceStore = Activator.getDefault().getPreferenceStore();
        preferenceStore.setValue(com.github.gradusnikov.eclipse.assistai.preferences.PreferenceConstants.ASSISTAI_MCP_HTTP_ENABLED, enabled);
    }

    public void resetToDefaults()
    {
        IPreferenceStore preferenceStore = Activator.getDefault().getPreferenceStore();
        
        preferenceStore.setToDefault(com.github.gradusnikov.eclipse.assistai.preferences.PreferenceConstants.ASSISTAI_MCP_HTTP_HOSTNAME);
        preferenceStore.setToDefault(com.github.gradusnikov.eclipse.assistai.preferences.PreferenceConstants.ASSISTAI_MCP_HTTP_PORT);
        preferenceStore.setToDefault(com.github.gradusnikov.eclipse.assistai.preferences.PreferenceConstants.ASSISTAI_MCP_HTTP_AUTH_TOKEN);
        preferenceStore.setToDefault(com.github.gradusnikov.eclipse.assistai.preferences.PreferenceConstants.ASSISTAI_MCP_HTTP_ENABLED);
    }

}
