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

    @Tool(name = "runJavaApplication",
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
}
