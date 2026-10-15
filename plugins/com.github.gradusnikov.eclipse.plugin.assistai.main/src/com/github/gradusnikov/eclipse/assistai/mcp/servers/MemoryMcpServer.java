package com.github.gradusnikov.eclipse.assistai.mcp.servers;

import java.util.Map;

import org.eclipse.e4.core.di.annotations.Creatable;

import com.github.gradusnikov.eclipse.assistai.mcp.annotations.McpServer;
import com.github.gradusnikov.eclipse.assistai.mcp.annotations.Tool;
import com.github.gradusnikov.eclipse.assistai.mcp.annotations.ToolParam;
import com.github.gradusnikov.eclipse.assistai.mcp.services.MemoryStoreService;

import jakarta.inject.Inject;

@Creatable
@McpServer(name="memory")
public class MemoryMcpServer
{
    @Inject
    private MemoryStoreService memoryStore;

    @Tool(name = "think", description = "Use this tool to think about something. It will not obtain new information or perform changes, but will put your thought into a log, so that it is accessible to you. Use it for complex reasoning or as memory cache when you need to store some temporary information that you may consider useful to complete the task.", type = "object")
    public String think( @ToolParam(name="thought", description = "A thought or information worth using in solving a task", required=true) String thought )
    {
        memoryStore.logThought( thought );
        return thought;
    }

    @Tool(name = "remember", description = "Persist a key-value pair to long-term memory. The value is stored permanently across all sessions and projects in the workspace — similar to a global ~/.config/opencode/AGENTS.md but editable by the assistant at runtime. Use a descriptive, unique key so you can recall or list it later. Use this for cross-project knowledge, user preferences, and learned conventions that don't belong in a project-specific configuration file.", type = "object")
    public String remember( @ToolParam(name="key", description = "A descriptive identifier for the memory entry", required=true) String key,
                            @ToolParam(name="value", description = "The information to store", required=true) String value )
    {
        memoryStore.remember( key, value );
        return "Stored: " + key + " = " + value;
    }

    @Tool(name = "recall", description = "Retrieve a previously stored memory entry by its exact key. Returns the stored value, or a message if the key does not exist. Use after listMemories to fetch the full value of a specific entry.", type = "object")
    public String recall( @ToolParam(name="key", description = "The key to look up", required=true) String key )
    {
        String value = memoryStore.recall( key );
        if ( value == null )
        {
            return "No memory found for key: " + key;
        }
        return value;
    }

    @Tool(name = "listMemories", description = "List all stored memory keys with a short preview of each value (max 80 chars). Use recall(key) to retrieve the full value. Call this at the start of every session to load cross-project, cross-session context that is not captured in project-specific configuration files. This is the persistent, global instruction store — equivalent to reading a global AGENTS.md but dynamic and editable at runtime.", type = "object")
    public String listMemories()
    {
        Map<String, String> memories = memoryStore.listMemories();
        if ( memories.isEmpty() )
        {
            return "No memories stored.";
        }
        StringBuilder sb = new StringBuilder();
        for ( Map.Entry<String, String> entry : memories.entrySet() )
        {
            String preview = entry.getValue();
            if ( preview.length() > 80 )
            {
                preview = preview.substring( 0, 77 ) + "...";
            }
            sb.append( entry.getKey() ).append( " = " ).append( preview ).append( "\n" );
        }
        return sb.toString().trim();
    }

    @Tool(name = "forget", description = "Remove a memory entry by key. Returns whether the key existed. Use to clean up outdated or incorrect entries from persistent memory.", type = "object")
    public String forget( @ToolParam(name="key", description = "The key to remove", required=true) String key )
    {
        boolean removed = memoryStore.forget( key );
        return removed ? "Forgotten: " + key : "Key not found: " + key;
    }

    @Tool(name = "completion_meta", description = "Internal sink for code completion. Use this tool to output any non-code text (markdown, explanations, reasoning, meta commentary) instead of writing it into the completion CONTENT stream. The code completion CONTENT stream must contain ONLY the exact source code to insert.", type = "object")
    public String completionMeta( @ToolParam(name="text", description = "Non-code meta text that should not appear in the completion output", required=true) String text )
    {
        return text;
    }
}
