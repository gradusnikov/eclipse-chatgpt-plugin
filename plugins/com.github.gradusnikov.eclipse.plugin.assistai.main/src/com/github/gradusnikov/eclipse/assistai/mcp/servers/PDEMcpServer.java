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
import com.github.gradusnikov.eclipse.assistai.mcp.services.TestRunManager;
import com.github.gradusnikov.eclipse.assistai.mcp.services.TestRunSession;

import jakarta.inject.Inject;

@Creatable
@McpServer(name = "eclipse-pde")
public class PDEMcpServer
{
    @Inject
    private PDEService pdeService;

    @Inject
    private TestRunManager testRunManager;

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
            @ToolParam(name = "targetFilePath",
                       description = "The workspace-relative or absolute path to the .target file (e.g., '/MyProject/myplatform.target')") String targetFilePath)
    {
        return pdeService.setActiveTarget( targetFilePath );
    }

    @Tool(name = "reloadTarget",
          description = "Reloads the currently active Eclipse target platform. Useful after target contents change on disk.",
          type = "object")
    public String reloadTarget()
    {
        return pdeService.reloadTarget();
    }

    @Tool(name = "startJUnitPluginTestRun",
          description = "Starts a JUnit Plug-in Test run asynchronously using the PDE launcher and returns a run ID for polling. "
              + "Scope is inferred from parameters: className+methodName=single method, "
              + "className=single class, packageName=package, none=all tests. "
              + "Use getTestRunStatus (in the eclipse-ide server) to poll progress and stopTestRun to cancel. "
              + "If a previous run gave an idea about how long the tests take, use that to set your "
              + "polling interval - avoid polling too frequently to save tokens.",
          type = "object")
    public String startJUnitPluginTestRun(
            @ToolParam(name = "projectName",
                       description = "The exact Eclipse project name containing the plug-in test classes",
                       required = true) String projectName,
            @ToolParam(name = "className",
                       description = "The fully qualified class name (e.g., 'com.example.MyPluginTest'). If omitted, runs all tests or package tests.",
                       required = false) String className,
            @ToolParam(name = "packageName",
                       description = "The fully qualified package name (e.g. 'com.example.service'). Ignored if className is set.",
                       required = false) String packageName,
            @ToolParam(name = "withCoverage",
                       description = "If 'true', runs tests with code coverage (requires EclEmma/JaCoCo). Default: false",
                       required = false) String withCoverage,
            @ToolParam(name = "includeAllPlugins",
                       description = "If 'true', launches with all workspace and target platform plug-ins. If 'false' (default), auto-resolves dependencies.",
                       required = false) String includeAllPlugins,
            @ToolParam(name = "additionalBundles",
                       description = "Comma-separated list of additional bundle/plug-in symbolic names to include (only when includeAllPlugins is false).",
                       required = false) String additionalBundles,
            @ToolParam(name = "launcherName",
                       description = "Optional name of a saved launch configuration to use as the base "
                           + "(use listLaunchConfigurations with typeFilter='junit-plugin' to find it). "
                           + "When set, all settings from that config are reused (VM args, program args, bundle selection, etc.) "
                           + "and only the test target (project/class) is overridden. "
                           + "The includeAllPlugins and additionalBundles params are ignored when launcherName is set.",
                       required = false) String launcherName)
    {
        boolean coverage = Optional.ofNullable( withCoverage ).map( Boolean::parseBoolean ).orElse( false );
        boolean allPlugins = Optional.ofNullable( includeAllPlugins ).map( Boolean::parseBoolean ).orElse( false );
        List<String> extras = parseAdditionalBundles( additionalBundles );

        try
        {
            String runId;
            String desc;
            if ( className != null && !className.isBlank() )
            {
                runId = pdeService.startJUnitPluginTestClass( projectName, className, coverage,
                    allPlugins, extras, launcherName );
                desc = "Running " + className + " in " + projectName;
            }
            else if ( packageName != null && !packageName.isBlank() )
            {
                runId = pdeService.startJUnitPluginTestPackage( projectName, packageName, coverage,
                    allPlugins, extras, launcherName );
                desc = "Running package " + packageName + " in " + projectName;
            }
            else
            {
                runId = pdeService.startJUnitPluginTests( projectName, coverage, allPlugins, extras, launcherName );
                desc = "Running all plug-in tests in " + projectName;
            }
            return "Run ID: " + runId + "\nStatus: RUNNING\nDescription: " + desc + "\n";
        }
        catch ( Exception e )
        {
            return "Error: " + e.getMessage();
        }
    }

    private List<String> parseAdditionalBundles( String additionalBundles )
    {
        if ( additionalBundles == null || additionalBundles.isBlank() )
        {
            return Collections.emptyList();
        }
        return Arrays.stream( additionalBundles.split( "," ) )
                     .map( String::trim )
                     .filter( s -> !s.isEmpty() )
                     .toList();
    }
}
