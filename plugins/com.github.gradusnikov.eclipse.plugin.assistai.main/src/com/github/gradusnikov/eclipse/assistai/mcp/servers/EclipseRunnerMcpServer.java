package com.github.gradusnikov.eclipse.assistai.mcp.servers;

import java.util.Optional;

import org.eclipse.e4.core.di.annotations.Creatable;

import com.github.gradusnikov.eclipse.assistai.mcp.annotations.McpServer;
import com.github.gradusnikov.eclipse.assistai.mcp.annotations.Tool;
import com.github.gradusnikov.eclipse.assistai.mcp.annotations.ToolParam;
import com.github.gradusnikov.eclipse.assistai.mcp.results.ActiveLaunchesResponse;
import com.github.gradusnikov.eclipse.assistai.mcp.results.BreakpointResponse;
import com.github.gradusnikov.eclipse.assistai.mcp.results.BreakpointsResponse;
import com.github.gradusnikov.eclipse.assistai.mcp.results.EvaluationResponse;
import com.github.gradusnikov.eclipse.assistai.mcp.results.HotCodeReplaceResponse;
import com.github.gradusnikov.eclipse.assistai.mcp.results.LaunchConfigurationsResponse;
import com.github.gradusnikov.eclipse.assistai.mcp.results.LaunchResponse;
import com.github.gradusnikov.eclipse.assistai.mcp.results.StackTraceResponse;
import com.github.gradusnikov.eclipse.assistai.mcp.results.StepResponse;
import com.github.gradusnikov.eclipse.assistai.mcp.results.StopApplicationResponse;
import com.github.gradusnikov.eclipse.assistai.mcp.results.TestNGLaunchConfigurationResponse;
import com.github.gradusnikov.eclipse.assistai.mcp.services.JavaLaunchService;
import com.github.gradusnikov.eclipse.assistai.mcp.services.TestNGLaunchService;

import jakarta.inject.Inject;

@Creatable
@McpServer(name = "eclipse-runner")
public class EclipseRunnerMcpServer
{
    @Inject
    private JavaLaunchService javaLaunchService;

    @Inject
    private TestNGLaunchService testNGLaunchService;

    @Tool(name = "runJavaApplication", longExecution = true,
          description = "Launches a Java application in run mode. Specify the project and fully qualified main class. "
              + "If timeout > 0, waits for the process to finish; if timeout = 0, launches in background and returns immediately. "
              + "exitCode is the one fact that says whether the program worked and is a field of its own, null when the process is still running or the VM reported none - never a sentinel. "
              + "timedOut says the wait ran out rather than the program finishing. stdout and stderr are separate, each with its own truncated flag and pre-truncation totalChars.",
          type = "object", outputType = LaunchResponse.class)
    public LaunchResponse runJavaApplication(
            @ToolParam(name = "projectName", description = "The name of the project containing the main class") String projectName,
            @ToolParam(name = "mainClass", description = "The fully qualified name of the main class (e.g., 'com.example.Main')") String mainClass,
            @ToolParam(name = "programArgs", description = "Optional program arguments passed to the main method", required = false) String programArgs,
            @ToolParam(name = "vmArgs", description = "Optional JVM arguments (e.g., '-Xmx512m -Dfoo=bar')", required = false) String vmArgs,
            @ToolParam(name = "timeout", description = "Timeout in seconds to wait for completion. Use '0' to launch in background without waiting. Default: '30'", required = false) String timeout)
    {
        int timeoutSeconds = Optional.ofNullable(timeout).map(Integer::parseInt).orElse(30);
        return javaLaunchService.runJavaApplication(projectName, mainClass, programArgs, vmArgs, timeoutSeconds);
    }

    @Tool(name = "debugJavaApplication", longExecution = true,
          description = "Launches a Java application in debug mode. The application will stop at breakpoints. Use toggleBreakpoint to set breakpoints before launching. "
              + "Same result shape as runJavaApplication: status, exitCode (null when still running), timedOut, and separate stdout/stderr with truncation counts.",
          type = "object", outputType = LaunchResponse.class)
    public LaunchResponse debugJavaApplication(
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
          description = "Stops the running or debugging Java applications matching the launch configuration name or main class name (substring match, case-insensitive). "
              + "status is NO_MATCH when nothing was running that matched - a state, not a failure - OK when at least one was stopped, FAILED when matches existed and none could be. "
              + "terminated is a list of launches, so a name containing a comma is still one entry; totalMatched beside it shows a partial stop.",
          type = "object", outputType = StopApplicationResponse.class)
    public StopApplicationResponse stopApplication(
            @ToolParam(name = "nameOrClass", description = "A substring to match against the application name or main class (e.g., 'Main' or 'com.example')") String nameOrClass)
    {
        return javaLaunchService.stopApplication(nameOrClass);
    }

    @Tool(name = "listActiveLaunches",
          description = "Lists the applications Eclipse is currently running or debugging. "
              + "Each launch reports name, mode (run/debug), mainType, projectName, a terminated flag, and its processes with the operating system pid where the debug plug-in recorded one. "
              + "Nothing running is an empty launches list with totalLaunches = 0, not a message.",
          type = "object", outputType = ActiveLaunchesResponse.class)
    public ActiveLaunchesResponse listActiveLaunches()
    {
        return javaLaunchService.listActiveLaunches();
    }

    @Tool(name = "listLaunchConfigurations",
          description = "Lists all saved launch configurations in the workspace (name, type, and for Java applications the project and main class). "
              + "Each entry has: name, typeId, typeName, projectName, mainClass. "
              + "Use this to discover the exact name to pass to launchConfiguration, (eclipse-ide MCP server).runJUnitTests (launcherName), or (eclipse-pde MCP server).runJUnitPluginTests (launcherName). "
              + "Use typeFilter to narrow results: 'junit' for plain JUnit runs, 'junit-plugin' for PDE plug-in tests, "
              + "'testng' for TestNG runs (such as those createTestNGLaunchConfiguration saves), "
              + "or any substring of the type ID for other types.",
          type = "object", outputType = LaunchConfigurationsResponse.class)
    public LaunchConfigurationsResponse listLaunchConfigurations(
            @ToolParam(name = "typeFilter",
                       description = "Optional filter: 'junit' (org.eclipse.jdt.junit.launchconfig), "
                           + "'junit-plugin' (org.eclipse.pde.ui.JunitLaunchConfig), "
                           + "'testng' (org.testng.eclipse.launchconfig), "
                           + "'all' or omit for everything, or any substring of the type ID.",
                       required = false) String typeFilter)
    {
        return javaLaunchService.listLaunchConfigurations( typeFilter );
    }

    @Tool(name = "createTestNGLaunchConfiguration",
          description = "Creates and saves a TestNG launch configuration - the one Eclipse's 'Run As > TestNG Test' saves - without launching it. "
              + "Requires the TestNG for Eclipse plug-in; without it nothing is saved and diagnostics carry TESTNG_LAUNCH_TYPE_MISSING. "
              + "Give exactly one target: className (optionally with methodName), packageName, suiteFile, or groups. "
              + "For groups, the test classes declaring them in @Test(groups=...) are found in the project and the projects it requires, and reported as groupClasses. "
              + "If a TestNG configuration of the same name exists it is updated in place (created=false); a configuration of any other type with that name is never overwritten (RESOURCE_ALREADY_EXISTS). "
              + "Run the result with launchConfiguration(configurationName=...) and find it later with listLaunchConfigurations(typeFilter='testng'). "
              + "Unlike the IDE action, dependsOnGroups/dependsOnMethods are not followed to add further classes.",
          type = "object", outputType = TestNGLaunchConfigurationResponse.class)
    public TestNGLaunchConfigurationResponse createTestNGLaunchConfiguration(
            @ToolParam(name = "projectName", description = "The Eclipse project containing the tests (use listProjects to find it)") String projectName,
            @ToolParam(name = "className", description = "Fully qualified test class name (e.g. 'com.example.FooTest')", required = false) String className,
            @ToolParam(name = "methodName", description = "Test method name within className (e.g. 'testCreate'). Requires className", required = false) String methodName,
            @ToolParam(name = "packageName", description = "Fully qualified package whose tests to run (e.g. 'com.example.service')", required = false) String packageName,
            @ToolParam(name = "suiteFile", description = "Project-relative path of a TestNG suite file (e.g. 'src/test/resources/testng.xml')", required = false) String suiteFile,
            @ToolParam(name = "groups", description = "Comma-separated TestNG group names (e.g. 'smoke,fast')", required = false) String groups,
            @ToolParam(name = "configurationName", description = "Optional name for the configuration. Default follows the IDE: the simple class name, 'Class.method', 'package <name>', or '<project>_<suite path with dots>' for a suite; '<project> groups <names>' for groups", required = false) String configurationName,
            @ToolParam(name = "vmArgs", description = "Optional JVM arguments (e.g. '-Xmx512m -Dfoo=bar')", required = false) String vmArgs,
            @ToolParam(name = "programArgs", description = "Optional program arguments passed to TestNG", required = false) String programArgs)
    {
        return testNGLaunchService.createLaunchConfiguration(projectName, className, methodName, packageName,
                suiteFile, groups, configurationName, vmArgs, programArgs);
    }

    @Tool(name = "launchConfiguration", longExecution = true,
          description = "Launches an existing saved launch configuration by name, exactly as it would run from Eclipse's Run/Debug Configurations dialog (reusing its classpath, program/VM arguments, environment variables, working directory, and agent settings such as JRebel). Use listLaunchConfigurations to find the name. Unlike runJavaApplication/debugJavaApplication, this does NOT create a throwaway configuration. If timeout > 0, waits for the process to finish; if timeout = 0, launches in background and returns immediately. Reports status, exitCode (null when still running), timedOut, and separate stdout/stderr with truncation counts. For JUnit test launches (plain tests or plug-in tests), use the dedicated runJUnitTests (eclipse-ide) or runJUnitPluginTests (eclipse-pde) tools instead — they provide structured test results, per-test status, and polling support that this generic launcher does not. TestNG configurations, including those saved by createTestNGLaunchConfiguration, are run with this tool.",
          type = "object", outputType = LaunchResponse.class)
    public LaunchResponse launchConfiguration(
            @ToolParam(name = "configurationName", description = "The exact name of the launch configuration to launch (e.g., 'Run Snapshot App No Data Compass Local')") String configurationName,
            @ToolParam(name = "mode", description = "Launch mode: 'run' or 'debug'. Default: 'run'", required = false) String mode,
            @ToolParam(name = "timeout", description = "Timeout in seconds to wait for completion. Use '0' to launch in background without waiting. Default: '0'", required = false) String timeout)
    {
        int timeoutSeconds = Optional.ofNullable(timeout).map(Integer::parseInt).orElse(0);
        String launchMode = Optional.ofNullable(mode).filter(m -> !m.isBlank()).orElse("run");
        return javaLaunchService.launchConfiguration(configurationName, launchMode, timeoutSeconds);
    }

    @Tool(name = "toggleBreakpoint",
          description = "Sets a line breakpoint at the given location, or removes the one already there. "
              + "action says which way it went - SET, REMOVED or NONE - so a caller never has to read that out of a sentence. "
              + "The location is validated first: status is TYPE_NOT_FOUND when the project resolves no such type (a breakpoint there would never bind) and INVALID_LINE when the line is past the end of the file; in both cases nothing is created. "
              + "The resulting breakpoint is reported in the same shape listBreakpoints returns, with projectName and a project-relative filePath.",
          type = "object", outputType = BreakpointResponse.class)
    public BreakpointResponse toggleBreakpoint(
            @ToolParam(name = "projectName", description = "The name of the project containing the source file") String projectName,
            @ToolParam(name = "typeName", description = "The fully qualified type name (e.g., 'com.example.Main')") String typeName,
            @ToolParam(name = "lineNumber", description = "The 1-based line number where the breakpoint should be set") String lineNumber)
    {
        return javaLaunchService.toggleBreakpoint(projectName, typeName, Integer.parseInt(lineNumber));
    }

    @Tool(name = "listBreakpoints",
          description = "Lists all breakpoints currently set in the workspace. "
              + "Each breakpoint reports projectName and a project-relative filePath, which the reading and editing tools take directly, plus typeName, a 1-based lineNumber, enabled, condition and hitCount. "
              + "No breakpoints is an empty breakpoints list with totalBreakpoints = 0, not a message.",
          type = "object", outputType = BreakpointsResponse.class)
    public BreakpointsResponse listBreakpoints()
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
          description = "Gets the stack trace of every thread of a debug session, plus the local variables of the top frame. "
              + "Each frame reports declaringType, methodName, projectName and a project-relative filePath with a 1-based lineNumber, so it can be opened with the reading tools; a frame outside the workspace, such as a JRE or library frame, reports no path. "
              + "sessionFound says whether any debug session matched and anyThreadSuspended whether the program is stopped at a breakpoint - neither is an error.",
          type = "object", outputType = StackTraceResponse.class)
    public StackTraceResponse getStackTrace(
            @ToolParam(name = "nameOrClass", description = "A substring to match against the debug session name or main class") String nameOrClass)
    {
        return javaLaunchService.getStackTrace(nameOrClass);
    }

    /** The wait applied when the caller names none, for the four tools that move a program. */
    private static final int DEFAULT_STEP_TIMEOUT_SECONDS = 10;

    private static final String STEP_RESULT_DESCRIPTION =
            "Returns the location the program is at afterwards, not a confirmation: frame carries declaringType, methodName, "
          + "projectName, a project-relative filePath, a 1-based lineNumber and the frame's local variables, so no follow-up getStackTrace is needed. "
          + "status is SUSPENDED when it stopped again, TERMINATED when the program ended, TIMED_OUT when it had not stopped within the timeout (retryable), "
          + "RUNNING when timeout was 0 and nothing was waited for, NO_SUSPENDED_THREAD / THREAD_NOT_FOUND when there was nothing to act on, "
          + "and SESSION_NOT_FOUND when no debug session matched - which a caller must never read as success. threadName names the thread acted on.";

    @Tool(name = "resumeDebug", longExecution = true, inlineWaitParam = "",
          description = "Resumes a suspended debug session and waits for it to stop at the next breakpoint. " + STEP_RESULT_DESCRIPTION,
          type = "object", outputType = StepResponse.class)
    public StepResponse resumeDebug(
            @ToolParam(name = "nameOrClass", description = "A substring to match against the debug session name or main class") String nameOrClass,
            @ToolParam(name = "threadName", description = "Optional: the thread to resume. Omit to resume the whole session", required = false) String threadName,
            @ToolParam(name = "timeout", description = "Seconds to wait for the next suspend. Use '0' to resume without waiting. Default: '10'", required = false) String timeout)
    {
        return javaLaunchService.resumeDebug(nameOrClass, threadName, stepTimeout(timeout));
    }

    @Tool(name = "stepOver", longExecution = true, inlineWaitParam = "",
          description = "Steps over the current line in a suspended debug session, executing it without entering method calls. " + STEP_RESULT_DESCRIPTION,
          type = "object", outputType = StepResponse.class)
    public StepResponse stepOver(
            @ToolParam(name = "nameOrClass", description = "A substring to match against the debug session name or main class") String nameOrClass,
            @ToolParam(name = "threadName", description = "Optional: the thread to step. Omit to take the first suspended thread", required = false) String threadName,
            @ToolParam(name = "timeout", description = "Seconds to wait for the step to complete. Use '0' not to wait. Default: '10'", required = false) String timeout)
    {
        return javaLaunchService.stepOver(nameOrClass, threadName, stepTimeout(timeout));
    }

    @Tool(name = "stepInto", longExecution = true, inlineWaitParam = "",
          description = "Steps into the method call at the current line in a suspended debug session. " + STEP_RESULT_DESCRIPTION,
          type = "object", outputType = StepResponse.class)
    public StepResponse stepInto(
            @ToolParam(name = "nameOrClass", description = "A substring to match against the debug session name or main class") String nameOrClass,
            @ToolParam(name = "threadName", description = "Optional: the thread to step. Omit to take the first suspended thread", required = false) String threadName,
            @ToolParam(name = "timeout", description = "Seconds to wait for the step to complete. Use '0' not to wait. Default: '10'", required = false) String timeout)
    {
        return javaLaunchService.stepInto(nameOrClass, threadName, stepTimeout(timeout));
    }

    @Tool(name = "stepReturn", longExecution = true, inlineWaitParam = "",
          description = "Runs until the current method returns to its caller, in a suspended debug session. " + STEP_RESULT_DESCRIPTION,
          type = "object", outputType = StepResponse.class)
    public StepResponse stepReturn(
            @ToolParam(name = "nameOrClass", description = "A substring to match against the debug session name or main class") String nameOrClass,
            @ToolParam(name = "threadName", description = "Optional: the thread to step. Omit to take the first suspended thread", required = false) String threadName,
            @ToolParam(name = "timeout", description = "Seconds to wait for the step to complete. Use '0' not to wait. Default: '10'", required = false) String timeout)
    {
        return javaLaunchService.stepReturn(nameOrClass, threadName, stepTimeout(timeout));
    }

    private int stepTimeout( String timeout )
    {
        return Optional.ofNullable( timeout ).filter( t -> !t.isBlank() )
                .map( Integer::parseInt ).orElse( DEFAULT_STEP_TIMEOUT_SECONDS );
    }

    @Tool(name = "evaluateExpression",
          description = "Evaluates a Java expression in a suspended debug frame. The application must be stopped at a breakpoint. "
              + "value and declaredType are separate fields, so a result whose toString() contains a parenthesis is still readable, and nullResult distinguishes the null reference from a String holding \"null\". "
              + "status is OK only when there is a value: COMPILE_ERROR puts the compiler's own messages in errorMessages, EVALUATION_FAILED means the expression threw, "
              + "and TIMED_OUT / NO_SUSPENDED_THREAD / THREAD_NOT_FOUND / SESSION_NOT_FOUND each say why there is none. "
              + "threadName and frame name the context the expression was evaluated in.",
          type = "object", outputType = EvaluationResponse.class)
    public EvaluationResponse evaluateExpression(
            @ToolParam(name = "nameOrClass", description = "A substring to match against the debug session name or main class") String nameOrClass,
            @ToolParam(name = "expression", description = "The Java expression to evaluate (e.g., 'myList.size()', 'x + y', 'this.toString()')") String expression,
            @ToolParam(name = "threadName", description = "Optional: the suspended thread whose top frame to evaluate in. Omit to take the first suspended thread", required = false) String threadName)
    {
        return javaLaunchService.evaluateExpression(nameOrClass, expression, threadName);
    }

    @Tool(name = "setConditionalBreakpoint",
          description = "Sets a breakpoint that only triggers when a condition evaluates to true, replacing any breakpoint already at that location. "
              + "The condition comes back in its own field of the reported breakpoint, so a condition containing ':' no longer has to be recovered by splitting a sentence. "
              + "action is SET or REPLACED. The location is validated first: TYPE_NOT_FOUND or INVALID_LINE means nothing was created.",
          type = "object", outputType = BreakpointResponse.class)
    public BreakpointResponse setConditionalBreakpoint(
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
          description = "Rebuilds the debugged project and reports whether the new bytecode actually reached the running JVM - the observed outcome, not that a build was triggered. "
              + "status is SUCCEEDED when the VM took it, OBSOLETE_METHODS when it did but frames already on the stack still run the old code, FAILED when the VM refused (a schema change: the running code is unchanged), "
              + "NOT_SUPPORTED when the VM cannot hot swap at all, IN_SYNC when nothing needed replacing, and TIMED_OUT when the VM is out of sync and reported nothing. "
              + "projectName is the project that was rebuilt; null means the launch named none and the whole workspace was built.",
          type = "object", outputType = HotCodeReplaceResponse.class)
    public HotCodeReplaceResponse hotCodeReplace(
            @ToolParam(name = "nameOrClass", description = "A substring to match against the debug session name or main class") String nameOrClass)
    {
        return javaLaunchService.hotCodeReplace(nameOrClass);
    }
}
