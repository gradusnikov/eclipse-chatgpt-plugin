package com.github.gradusnikov.eclipse.assistai.mcp.servers;

import com.github.gradusnikov.eclipse.assistai.mcp.annotations.McpServer;
import com.github.gradusnikov.eclipse.assistai.mcp.annotations.Tool;
import com.github.gradusnikov.eclipse.assistai.mcp.annotations.ToolParam;

@McpServer(name="memory")
public class MemoryMcpServer
{
    @Tool(name = "think", description = "Use this tool to think about something. It will not obtain new information or perform changes, but will put your thought into a log, so that it is accessible to you. Use it for complex reasoning or as memory cache when you need to store some temporary information that you may consider useful to complete the task.", type = "object")
    public String think( @ToolParam(name="thought", description = "A thought or information worth using in solving a task", required=true) String thought )
    {
        return thought;
    }

    @Tool(name = "completion_meta", description = "Internal sink for code completion. Use this tool to output any non-code text (markdown, explanations, reasoning, meta commentary) instead of writing it into the completion CONTENT stream. The code completion CONTENT stream must contain ONLY the exact source code to insert.", type = "object")
    public String completionMeta( @ToolParam(name="text", description = "Non-code meta text that should not appear in the completion output", required=true) String text )
    {
        return text;
    }
}
