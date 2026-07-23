package com.github.gradusnikov.eclipse.assistai.mcp.servers;

import java.util.Optional;

import org.eclipse.e4.core.di.annotations.Creatable;

import com.github.gradusnikov.eclipse.assistai.mcp.annotations.McpServer;
import com.github.gradusnikov.eclipse.assistai.mcp.annotations.Tool;
import com.github.gradusnikov.eclipse.assistai.mcp.annotations.ToolParam;
import com.github.gradusnikov.eclipse.assistai.mcp.services.JavaLaunchService;

import jakarta.inject.Inject;

@Creatable
@McpServer(name = "eclipse-runner")
public class EclipseRunnerMcpServer
{
    @Inject
    private JavaLaunchService javaLaunchService;

    @Tool(name = "runJavaApplication", longExecution = true,
          description = "Launches a Java application in run mode. Specify the project and fully qualified main class. If timeout > 0, waits for the process to finish and returns stdout/stderr. If timeout = 0, launches in background and returns immediately.",
          type = "object")
    public String runJavaApplication(
            @ToolParam(name = "projectName", description = "The name of the project containing the main class") String projectName,
            @ToolParam(name = "mainClass", description = "The fully qualified name of the main class (e.g., 'com.example.Main')") String mainClass,
            @ToolParam(name = "programArgs", description = "Optional program arguments passed to the main method", required = false) String programArgs,
            @ToolParam(name = "vmArgs", description = "Optional JVM arguments (e.g., '-Xmx512m -Dfoo=bar')", required = false) String vmArgs,
            @ToolParam(name = "timeout", description = "Timeout in seconds to wait for completion. Use '0' to launch in background without waiting. Default: '30'", required = false) String timeout)
    {
        int timeoutSeconds = Optional.ofNullable(timeout).map(Integer::parseInt).orElse(30);
        return javaLaunchService.runJavaApplication(projectName, mainClass, programArgs, vmArgs, timeoutSeconds);
    }

    @Tool(name = "debugJavaApplication",
          description = "Launches a Java application in debug mode. The application will stop at breakpoints. Use toggleBreakpoint to set breakpoints before launching.",
          type = "object")
    public String debugJavaApplication(
            @ToolParam(name = "projectName", description = "The name of the project containing the main class") String projectName,
            @ToolParam(name = "mainClass", description = "The fully qualified name of the main class (e.g., 'com.example.Main')") String mainClass,
            @ToolParam(name = "programArgs", description = "Optional program arguments passed to the main method", required = false) String programArgs,
            @ToolParam(name = "vmArgs", description = "Optional JVM arguments (e.g., '-Xmx512m -Dfoo=bar')", required = false) String vmArgs,
            @ToolParam(name = "timeout", description = "Timeout in seconds to wait for completion. Use '0' to launch in background without waiting. Default: '0'", required = false) String timeout)
    {
        int timeoutSeconds = Optional.ofNullable(timeout).map(Integer::parseInt).orElse(0);
        return javaLaunchService.debugJavaApplication(projectName, mainClass, programArgs, vmArgs, timeoutSeconds);
    }

    @Tool(name = "stopApplication",
          description = "Stops a running or debugging Java application. Matches against the launch configuration name or main class name (substring match, case-insensitive).",
          type = "object")
    public String stopApplication(
            @ToolParam(name = "nameOrClass", description = "A substring to match against the application name or main class (e.g., 'Main' or 'com.example')") String nameOrClass)
    {
        return javaLaunchService.stopApplication(nameOrClass);
    }

    @Tool(name = "listActiveLaunches",
          description = "Lists all currently running or debugging applications with their status, mode (run/debug), and process information.",
          type = "object")
    public String listActiveLaunches()
    {
        return javaLaunchService.listActiveLaunches();
    }

    @Tool(name = "listLaunchConfigurations",
          description = "Lists all saved launch configurations in the workspace (name, type, and for Java applications the project and main class). "
              + "Returns a JSON array where each entry has: name, typeId, typeName, projectName, mainClass. "
              + "Use this to discover the exact name to pass to launchConfiguration, (eclipse-ide MCP server).runJUnitTests (launcherName), or (eclipse-pde MCP server).runJUnitPluginTests (launcherName). "
              + "Use typeFilter to narrow results: 'junit' for plain JUnit runs, 'junit-plugin' for PDE plug-in tests, "
              + "or any substring of the type ID for other types.",
          type = "object")
    public String listLaunchConfigurations(
            @ToolParam(name = "typeFilter",
                       description = "Optional filter: 'junit' (org.eclipse.jdt.junit.launchconfig), "
                           + "'junit-plugin' (org.eclipse.pde.ui.JunitLaunchConfig), "
                           + "'all' or omit for everything, or any substring of the type ID.",
                       required = false) String typeFilter)
    {
        return javaLaunchService.listLaunchConfigurations( typeFilter );
    }

    @Tool(name = "launchConfiguration",
          description = "Launches an existing saved launch configuration by name, exactly as it would run from Eclipse's Run/Debug Configurations dialog (reusing its classpath, program/VM arguments, environment variables, working directory, and agent settings such as JRebel). Use listLaunchConfigurations to find the name. Unlike runJavaApplication/debugJavaApplication, this does NOT create a throwaway configuration. If timeout > 0, waits for the process to finish and returns stdout/stderr; if timeout = 0, launches in background and returns immediately. For JUnit test launches (plain tests or plug-in tests), use the dedicated runJUnitTests (eclipse-ide) or runJUnitPluginTests (eclipse-pde) tools instead — they provide structured test results, per-test status, and polling support that this generic launcher does not.",
          type = "object")
    public String launchConfiguration(
            @ToolParam(name = "configurationName", description = "The exact name of the launch configuration to launch (e.g., 'Run Snapshot App No Data Compass Local')") String configurationName,
            @ToolParam(name = "mode", description = "Launch mode: 'run' or 'debug'. Default: 'run'", required = false) String mode,
            @ToolParam(name = "timeout", description = "Timeout in seconds to wait for completion. Use '0' to launch in background without waiting. Default: '0'", required = false) String timeout)
    {
        int timeoutSeconds = Optional.ofNullable(timeout).map(Integer::parseInt).orElse(0);
        String launchMode = Optional.ofNullable(mode).filter(m -> !m.isBlank()).orElse("run");
        return javaLaunchService.launchConfiguration(configurationName, launchMode, timeoutSeconds);
    }

    @Tool(name = "toggleBreakpoint",
          description = "Sets or removes a line breakpoint at the specified location. If a breakpoint already exists at the line, it is removed. Otherwise, a new breakpoint is created.",
          type = "object")
    public String toggleBreakpoint(
            @ToolParam(name = "projectName", description = "The name of the project containing the source file") String projectName,
            @ToolParam(name = "typeName", description = "The fully qualified type name (e.g., 'com.example.Main')") String typeName,
            @ToolParam(name = "lineNumber", description = "The 1-based line number where the breakpoint should be set") String lineNumber)
    {
        return javaLaunchService.toggleBreakpoint(projectName, typeName, Integer.parseInt(lineNumber));
    }

    @Tool(name = "listBreakpoints",
          description = "Lists all breakpoints currently set in the workspace, showing their location, enabled status, and any conditions.",
          type = "object")
    public String listBreakpoints()
    {
        return javaLaunchService.listBreakpoints();
    }

    @Tool(name = "removeAllBreakpoints",
          description = "Removes all breakpoints from the workspace.",
          type = "object")
    public String removeAllBreakpoints()
    {
        return javaLaunchService.removeAllBreakpoints();
    }

    @Tool(name = "getStackTrace",
          description = "Gets the stack trace of all threads for a suspended debug session. Shows the call stack, and local variables for the top frame. The application must be stopped at a breakpoint.",
          type = "object")
    public String getStackTrace(
            @ToolParam(name = "nameOrClass", description = "A substring to match against the debug session name or main class") String nameOrClass)
    {
        return javaLaunchService.getStackTrace(nameOrClass);
    }

    @Tool(name = "resumeDebug",
          description = "Resumes execution of a suspended debug session. The application will continue running until it hits the next breakpoint or terminates.",
          type = "object")
    public String resumeDebug(
            @ToolParam(name = "nameOrClass", description = "A substring to match against the debug session name or main class") String nameOrClass)
    {
        return javaLaunchService.resumeDebug(nameOrClass);
    }

    @Tool(name = "stepOver",
          description = "Steps over the current line in a suspended debug session. Executes the current line without entering method calls.",
          type = "object")
    public String stepOver(
            @ToolParam(name = "nameOrClass", description = "A substring to match against the debug session name or main class") String nameOrClass)
    {
        return javaLaunchService.stepOver(nameOrClass);
    }

    @Tool(name = "stepInto",
          description = "Steps into the method call at the current line in a suspended debug session. Enters the called method.",
          type = "object")
    public String stepInto(
            @ToolParam(name = "nameOrClass", description = "A substring to match against the debug session name or main class") String nameOrClass)
    {
        return javaLaunchService.stepInto(nameOrClass);
    }

    @Tool(name = "stepReturn",
          description = "Steps out of the current method in a suspended debug session. Runs until the current method returns to its caller.",
          type = "object")
    public String stepReturn(
            @ToolParam(name = "nameOrClass", description = "A substring to match against the debug session name or main class") String nameOrClass)
    {
        return javaLaunchService.stepReturn(nameOrClass);
    }

    @Tool(name = "evaluateExpression",
          description = "Evaluates a Java expression in the context of a suspended debug frame. The application must be stopped at a breakpoint. Can evaluate any valid Java expression including method calls, field access, arithmetic, etc.",
          type = "object")
    public String evaluateExpression(
            @ToolParam(name = "nameOrClass", description = "A substring to match against the debug session name or main class") String nameOrClass,
            @ToolParam(name = "expression", description = "The Java expression to evaluate (e.g., 'myList.size()', 'x + y', 'this.toString()')") String expression)
    {
        return javaLaunchService.evaluateExpression(nameOrClass, expression);
    }

    @Tool(name = "setConditionalBreakpoint",
          description = "Sets a breakpoint with a condition expression. The breakpoint only triggers when the condition evaluates to true. Replaces any existing breakpoint at the same location.",
          type = "object")
    public String setConditionalBreakpoint(
            @ToolParam(name = "projectName", description = "The name of the project containing the source file") String projectName,
            @ToolParam(name = "typeName", description = "The fully qualified type name (e.g., 'com.example.Main')") String typeName,
            @ToolParam(name = "lineNumber", description = "The 1-based line number where the breakpoint should be set") String lineNumber,
            @ToolParam(name = "condition", description = "A Java boolean expression (e.g., 'i > 100', 'name.equals(\"test\")')") String condition,
            @ToolParam(name = "hitCount", description = "Optional: breakpoint triggers only after being hit N times. Default: '0' (disabled)", required = false) String hitCount)
    {
        int hitCountInt = Optional.ofNullable(hitCount).map(Integer::parseInt).orElse(0);
        return javaLaunchService.setConditionalBreakpoint(projectName, typeName,
                Integer.parseInt(lineNumber), condition, hitCountInt);
    }

    @Tool(name = "hotCodeReplace", longExecution = true,
          description = "Triggers hot code replace (HCR) in an active debug session. Compiles the latest code changes and pushes them into the running JVM without restarting the application. The JVM must support HCR (most standard JVMs do).",
          type = "object")
    public String hotCodeReplace(
            @ToolParam(name = "nameOrClass", description = "A substring to match against the debug session name or main class") String nameOrClass)
    {
        return javaLaunchService.hotCodeReplace(nameOrClass);
    }
}
