package com.github.gradusnikov.eclipse.assistai.mcp.servers;

import java.time.Instant;

import org.eclipse.e4.core.di.annotations.Creatable;

import com.github.gradusnikov.eclipse.assistai.mcp.McpServer;
import com.github.gradusnikov.eclipse.assistai.mcp.Tool;

@Creatable
@McpServer(name = "time")
public class TimeMcpServer
{
    @Tool(name = "currentTime", description = "returns the current date and time in ISO-8601 representation", type = "object")
    public String getCurrentTime()
    {
        return Instant.now().toString();
    }
}
