package com.github.gradusnikov.eclipse.assistai.mcp.servers;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import org.eclipse.e4.core.di.annotations.Creatable;

import com.github.gradusnikov.eclipse.assistai.mcp.annotations.McpServer;
import com.github.gradusnikov.eclipse.assistai.mcp.annotations.Tool;
import com.github.gradusnikov.eclipse.assistai.mcp.annotations.ToolParam;
import com.github.gradusnikov.eclipse.assistai.mcp.services.PDEService;
import com.github.gradusnikov.eclipse.assistai.mcp.services.RuntimeReloadService;

import jakarta.inject.Inject;

@Creatable
@McpServer(name = "eclipse-pde")
public class PDEMcpServer
{
    @Inject
    private PDEService pdeService;

    @Inject
    private RuntimeReloadService runtimeReloadService;

    @Tool(name = "getActiveTarget",
          description = "Gets information about the currently active Eclipse target platform.",
          type = "object")
    public String getActiveTarget()
    {
        return pdeService.getActiveTarget();
    }

    @Tool(name = "setActiveTarget",
          description = "Sets the active Eclipse target platform from a .target file. Loads and activates the target definition.",
          longExecution = true,
          type = "object")
    public String setActiveTarget(
            @ToolParam(name = "targetFilePath", description = "The workspace-relative or absolute path to the .target file (e.g., '/MyProject/myplatform.target')") String targetFilePath)
    {
        return pdeService.setActiveTarget(targetFilePath);
    }

    @Tool(name = "reloadTarget",
          description = "Reloads the currently active Eclipse target platform. Useful after target contents change on disk.",
          longExecution = true,
          type = "object")
    public String reloadTarget()
    {
        return pdeService.reloadTarget();
    }

    @Tool(name = "restartMcpServers",
          description = "Safely rebuilds the AssistAI HTTP MCP servers after the current response completes. Existing MCP connections are interrupted and may need to reconnect.",
          type = "object")
    public String restartMcpServers(
            @ToolParam(name = "delayMillis", description = "Delay before restart, from 500 to 30000 ms. Default: 1500", required = false) String delayMillis)
    {
        Integer delay = Optional.ofNullable(delayMillis).filter(value -> !value.isBlank()).map(Integer::valueOf).orElse(null);
        return runtimeReloadService.restartMcpServers(delay);
    }

    @Tool(name = "reloadWorkspaceBundle",
          description = "Schedules an OSGi update of a bundle backed by an open Eclipse workspace project. The reload starts after the current response completes; MCP clients may need to reconnect when reloading AssistAI itself.",
          type = "object")
    public String reloadWorkspaceBundle(
            @ToolParam(name = "symbolicName", description = "Bundle symbolic name; it must also name an open workspace project") String symbolicName,
            @ToolParam(name = "delayMillis", description = "Delay before reload, from 500 to 30000 ms. Default: 1500", required = false) String delayMillis)
    {
        Integer delay = Optional.ofNullable(delayMillis).filter(value -> !value.isBlank()).map(Integer::valueOf).orElse(null);
        return runtimeReloadService.reloadWorkspaceBundle(symbolicName, delay);
    }

    @Tool(name = "runJUnitPluginTests",
          description = "Runs all JUnit Plug-in Tests in the specified project using the PDE launcher. Returns test results including pass/fail counts.",
          longExecution = true,
          type = "object")
    public String runJUnitPluginTests(
            @ToolParam(name = "projectName", description = "The exact Eclipse project name containing the plug-in test classes") String projectName,
            @ToolParam(name = "timeout", description = "Maximum time in seconds to wait for test completion (default: 60)", required = false) String timeout,
            @ToolParam(name = "withCoverage", description = "If 'true', runs tests with code coverage (requires EclEmma/JaCoCo installed). Default: false", required = false) String withCoverage,
            @ToolParam(name = "includeAllPlugins", description = "If 'true', launches with all workspace and target platform plug-ins (USE_DEFAULT mode). If 'false' (default), only includes the test plug-in and lets Eclipse auto-resolve required dependencies.", required = false) String includeAllPlugins,
            @ToolParam(name = "additionalBundles", description = "Comma-separated list of additional bundle/plug-in symbolic names to include in the launch (only used when includeAllPlugins is false). Use this when auto-resolved dependencies are insufficient.", required = false) String additionalBundles)
    {
        int timeoutSeconds = Optional.ofNullable(timeout).map(Integer::parseInt).orElse(60);
        boolean coverage = Optional.ofNullable(withCoverage).map(Boolean::parseBoolean).orElse(false);
        boolean allPlugins = Optional.ofNullable(includeAllPlugins).map(Boolean::parseBoolean).orElse(false);
        List<String> extras = parseAdditionalBundles(additionalBundles);
        return pdeService.runJUnitPluginTests(projectName, timeoutSeconds, coverage, allPlugins, extras);
    }

    @Tool(name = "runJUnitPluginTestClass",
          description = "Runs all JUnit Plug-in Tests in a specific class using the PDE launcher. Returns test results.",
          longExecution = true,
          type = "object")
    public String runJUnitPluginTestClass(
            @ToolParam(name = "projectName", description = "The exact Eclipse project name containing the test class") String projectName,
            @ToolParam(name = "className", description = "The fully qualified class name (e.g., 'com.example.MyPluginTest')") String className,
            @ToolParam(name = "timeout", description = "Maximum time in seconds to wait for test completion (default: 60)", required = false) String timeout,
            @ToolParam(name = "withCoverage", description = "If 'true', runs tests with code coverage (requires EclEmma/JaCoCo installed). Default: false", required = false) String withCoverage,
            @ToolParam(name = "includeAllPlugins", description = "If 'true', launches with all workspace and target platform plug-ins (USE_DEFAULT mode). If 'false' (default), only includes the test plug-in and lets Eclipse auto-resolve required dependencies.", required = false) String includeAllPlugins,
            @ToolParam(name = "additionalBundles", description = "Comma-separated list of additional bundle/plug-in symbolic names to include in the launch (only used when includeAllPlugins is false). Use this when auto-resolved dependencies are insufficient.", required = false) String additionalBundles)
    {
        int timeoutSeconds = Optional.ofNullable(timeout).map(Integer::parseInt).orElse(60);
        boolean coverage = Optional.ofNullable(withCoverage).map(Boolean::parseBoolean).orElse(false);
        boolean allPlugins = Optional.ofNullable(includeAllPlugins).map(Boolean::parseBoolean).orElse(false);
        List<String> extras = parseAdditionalBundles(additionalBundles);
        return pdeService.runJUnitPluginTestClass(projectName, className, timeoutSeconds, coverage, allPlugins, extras);
    }

    private List<String> parseAdditionalBundles(String additionalBundles)
    {
        if (additionalBundles == null || additionalBundles.isBlank())
        {
            return Collections.emptyList();
        }
        return Arrays.stream(additionalBundles.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }
}
