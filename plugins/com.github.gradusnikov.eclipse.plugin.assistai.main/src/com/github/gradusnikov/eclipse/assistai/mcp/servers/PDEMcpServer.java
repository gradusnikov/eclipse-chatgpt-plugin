package com.github.gradusnikov.eclipse.assistai.mcp.servers;

import java.util.Optional;

import org.eclipse.e4.core.di.annotations.Creatable;

import com.github.gradusnikov.eclipse.assistai.mcp.annotations.McpServer;
import com.github.gradusnikov.eclipse.assistai.mcp.annotations.Tool;
import com.github.gradusnikov.eclipse.assistai.mcp.annotations.ToolParam;
import com.github.gradusnikov.eclipse.assistai.mcp.services.PDEService;

import jakarta.inject.Inject;

@Creatable
@McpServer(name = "eclipse-pde")
public class PDEMcpServer
{
    @Inject
    private PDEService pdeService;

    @Tool(name = "getActiveTarget",
          description = "Gets information about the currently active Eclipse target platform.",
          type = "object")
    public String getActiveTarget()
    {
        return pdeService.getActiveTarget();
    }

    @Tool(name = "setActiveTarget",
          description = "Sets the active Eclipse target platform from a .target file. Loads and activates the target definition.",
          type = "object")
    public String setActiveTarget(
            @ToolParam(name = "targetFilePath", description = "The workspace-relative or absolute path to the .target file (e.g., '/MyProject/myplatform.target')") String targetFilePath)
    {
        return pdeService.setActiveTarget(targetFilePath);
    }

    @Tool(name = "reloadTarget",
          description = "Reloads the currently active Eclipse target platform. Useful after target contents change on disk.",
          type = "object")
    public String reloadTarget()
    {
        return pdeService.reloadTarget();
    }

    @Tool(name = "runJUnitPluginTests",
          description = "Runs all JUnit Plug-in Tests in the specified project using the PDE launcher. Returns test results including pass/fail counts.",
          type = "object")
    public String runJUnitPluginTests(
            @ToolParam(name = "projectName", description = "The exact Eclipse project name containing the plug-in test classes") String projectName,
            @ToolParam(name = "timeout", description = "Maximum time in seconds to wait for test completion (default: 60)", required = false) String timeout)
    {
        int timeoutSeconds = Optional.ofNullable(timeout).map(Integer::parseInt).orElse(60);
        return pdeService.runJUnitPluginTests(projectName, timeoutSeconds);
    }

    @Tool(name = "runJUnitPluginTestClass",
          description = "Runs all JUnit Plug-in Tests in a specific class using the PDE launcher. Returns test results.",
          type = "object")
    public String runJUnitPluginTestClass(
            @ToolParam(name = "projectName", description = "The exact Eclipse project name containing the test class") String projectName,
            @ToolParam(name = "className", description = "The fully qualified class name (e.g., 'com.example.MyPluginTest')") String className,
            @ToolParam(name = "timeout", description = "Maximum time in seconds to wait for test completion (default: 60)", required = false) String timeout)
    {
        int timeoutSeconds = Optional.ofNullable(timeout).map(Integer::parseInt).orElse(60);
        return pdeService.runJUnitPluginTestClass(projectName, className, timeoutSeconds);
    }
}
