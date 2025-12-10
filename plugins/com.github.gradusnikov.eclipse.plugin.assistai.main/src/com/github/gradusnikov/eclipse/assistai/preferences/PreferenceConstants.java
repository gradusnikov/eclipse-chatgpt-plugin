package com.github.gradusnikov.eclipse.assistai.preferences;

/**
 * Constant definitions for plug-in preferences
 */
public class PreferenceConstants
{
    public static final String ASSISTAI_CONNECTION_TIMEOUT_SECONDS = "AssistAIConnectionTimeoutSeconds";
    public static final String ASSISTAI_REQUEST_TIMEOUT_SECONDS = "AssistAIRequestTimeoutSeconds";
    public static final String ASSISTAI_CHAT_MODEL = "AssistaAISelectedModel";
    public static final String ASSISTAI_DEFINED_MODELS = "AssistAIDefinedModels";
    
    // MCP Server preferences
    public static final String ASSISTAI_DEFINED_MCP_SERVERS = "AssistAIDefinedMCPServers";
    public static final String ASSISTAI_SELECTED_MCP_SERVER = "AssistAISelectedMCPServer";
    
    // MCP Http
    public static final String ASSISTAI_MCP_HTTP_HOSTNAME = "AssistAIMcpHttpHostname";
    public static final String ASSISTAI_MCP_HTTP_PORT = "AssistAIMcpHttpPort";
    public static final String ASSISTAI_MCP_HTTP_AUTH_TOKEN = "AssistAIMcpHttpToken";
    public static final String ASSISTAI_MCP_HTTP_ENABLED = "AssistAIMcpHttpEnabled";
    
    // Code Completion preferences
    public static final String ASSISTAI_COMPLETION_ENABLED = "AssistAICompletionEnabled";
    public static final String ASSISTAI_COMPLETION_MODEL = "AssistAICompletionModel";
    public static final String ASSISTAI_COMPLETION_TIMEOUT_SECONDS = "AssistAICompletionTimeoutSeconds";
    public static final String ASSISTAI_COMPLETION_HOTKEY = "AssistAICompletionHotkey";
    
    // Default hotkey: Alt+/ (cross-platform friendly)
    public static final String ASSISTAI_COMPLETION_HOTKEY_DEFAULT = "Alt+/";
}