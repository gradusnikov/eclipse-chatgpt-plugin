package com.github.gradusnikov.eclipse.assistai.preferences.mcp;

import java.util.Objects;
import java.util.UUID;

import org.eclipse.core.runtime.ILog;
import org.eclipse.e4.core.di.annotations.Creatable;

import com.github.gradusnikov.eclipse.assistai.mcp.http.HttpMcpServerPreferences;
import com.github.gradusnikov.eclipse.assistai.mcp.http.HttpMcpServerPreferencesProvider;
import com.github.gradusnikov.eclipse.assistai.mcp.http.HttpMcpServerRegistry;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;

/**
 * Presenter for HTTP MCP Server preferences
 */
@Creatable
@Singleton
public class McpHttpServerPreferencePresenter
{
    private final HttpMcpServerPreferencesProvider preferencesProvider;
    private final HttpMcpServerRegistry httpServerRegistry;
    private final ILog logger;

    private McpHttpServerPreferencePage view;

    @Inject
    public McpHttpServerPreferencePresenter(
            HttpMcpServerPreferencesProvider preferencesProvider,
            HttpMcpServerRegistry httpServerRegistry,
            ILog logger)
    {
        Objects.requireNonNull(preferencesProvider);
        Objects.requireNonNull(httpServerRegistry);
        Objects.requireNonNull(logger);

        this.preferencesProvider = preferencesProvider;
        this.httpServerRegistry = httpServerRegistry;
        this.logger = logger;
    }

    /**
     * Save the HTTP MCP server preferences
     *
     * @param port the port number
     * @param token the authentication token
     * @param enabled whether the server is enabled
     */
    public void savePreferences(int port, String hostname, String token, boolean enabled)
    {
        HttpMcpServerPreferences preferences = new HttpMcpServerPreferences(port, hostname, token);
        preferencesProvider.save(preferences);
        preferencesProvider.setEnabled(enabled);
        
        logger.info( "MCP Http server preferences updated" );
        initializeView( view );
        
        httpServerRegistry.restart();
        
    }

    /**
     * Generate a new authentication token
     *
     * @return the generated token
     */
    public String generateToken()
    {
        return UUID.randomUUID().toString();
    }

    /**
     * Register the view
     *
     * @param view the view to register
     */
    public void registerView(McpHttpServerPreferencePage view)
    {
        this.view = view;
        
        initializeView( view );
    }

    private void initializeView( McpHttpServerPreferencePage view )
    {
        if ( view != null )
        {
            // initialize view
            view.updateHttpMcpPreferences( preferencesProvider.get() );
            view.updateHttpMcpEnbled( preferencesProvider.isEnabled() );
            view.updateServerStatus( httpServerRegistry.isRunning() );
            view.updateEnabledEndpoints( httpServerRegistry.listEndpoints() );
        }
    }

    /**
     * Reset to default values
     */
    public void onPerformDefaults()
    {
        // Set default values
        savePreferences(8881, "localhost", generateToken(), false);
        initializeView( view );
    }

    public void onGenerateNewToken()
    {
        var old =  preferencesProvider.get();
        HttpMcpServerPreferences preferences = new HttpMcpServerPreferences(old.port(), old.hostname(), generateToken());
        preferencesProvider.save(preferences);
        
        logger.info( "MCP Http server preferences updated" );
        
        initializeView( view );
    }
}
