package com.github.gradusnikov.eclipse.assistai.mcp.services;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.ILog;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.debug.core.DebugException;
import org.eclipse.debug.core.DebugPlugin;
import org.eclipse.debug.core.ILaunch;
import org.eclipse.debug.core.ILaunchConfiguration;
import org.eclipse.debug.core.ILaunchConfigurationType;
import org.eclipse.debug.core.ILaunchConfigurationWorkingCopy;
import org.eclipse.debug.core.ILaunchManager;
import org.eclipse.debug.core.IStreamListener;
import org.eclipse.debug.core.model.IBreakpoint;
import org.eclipse.debug.core.model.IDebugTarget;
import org.eclipse.debug.core.model.IProcess;
import org.eclipse.debug.core.model.IStackFrame;
import org.eclipse.debug.core.model.IStreamMonitor;
import org.eclipse.debug.core.model.IThread;
import org.eclipse.debug.core.model.IVariable;
import org.eclipse.e4.core.di.annotations.Creatable;
import org.eclipse.e4.ui.di.UISynchronize;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.debug.core.IJavaBreakpoint;
import org.eclipse.jdt.debug.core.IJavaLineBreakpoint;
import org.eclipse.jdt.debug.core.JDIDebugModel;
import org.eclipse.jdt.launching.IJavaLaunchConfigurationConstants;

import jakarta.inject.Inject;

@Creatable
public class JavaLaunchService
{
    @Inject
    ILog logger;

    @Inject
    UISynchronize sync;

    /**
     * Launches a Java application in run mode.
     *
     * @param projectName The name of the project containing the main class
     * @param mainClass The fully qualified name of the main class
     * @param programArgs Optional program arguments
     * @param vmArgs Optional VM arguments
     * @param timeout Timeout in seconds to wait for the process to finish (0 = don't wait)
     * @return A status message with launch info or captured output
     */
    public String runJavaApplication(String projectName, String mainClass,
                                     String programArgs, String vmArgs, int timeout)
    {
        return launchJavaApplication(projectName, mainClass, programArgs, vmArgs,
                ILaunchManager.RUN_MODE, timeout);
    }

    /**
     * Launches a Java application in debug mode.
     *
     * @param projectName The name of the project containing the main class
     * @param mainClass The fully qualified name of the main class
     * @param programArgs Optional program arguments
     * @param vmArgs Optional VM arguments
     * @param timeout Timeout in seconds to wait for the process to finish (0 = don't wait)
     * @return A status message with launch info
     */
    public String debugJavaApplication(String projectName, String mainClass,
                                       String programArgs, String vmArgs, int timeout)
    {
        return launchJavaApplication(projectName, mainClass, programArgs, vmArgs,
                ILaunchManager.DEBUG_MODE, timeout);
    }

    /**
     * Core launch method for both run and debug modes.
     */
    private String launchJavaApplication(String projectName, String mainClass,
                                         String programArgs, String vmArgs,
                                         String mode, int timeout)
    {
        Objects.requireNonNull(projectName, "Project name cannot be null");
        Objects.requireNonNull(mainClass, "Main class cannot be null");

        try
        {
            IProject project = ResourcesPlugin.getWorkspace().getRoot().getProject(projectName);
            if (!project.exists())
            {
                return "Error: Project '" + projectName + "' does not exist.";
            }
            if (!project.isOpen())
            {
                return "Error: Project '" + projectName + "' is closed.";
            }

            IJavaProject javaProject = JavaCore.create(project);
            if (javaProject == null || !javaProject.exists())
            {
                return "Error: Project '" + projectName + "' is not a Java project.";
            }

            // Verify the main class exists
            IType mainType = javaProject.findType(mainClass);
            if (mainType == null)
            {
                return "Error: Main class '" + mainClass + "' not found in project '" + projectName + "'.";
            }

            // Create launch configuration
            ILaunchManager launchManager = DebugPlugin.getDefault().getLaunchManager();
            ILaunchConfigurationType type = launchManager.getLaunchConfigurationType(
                    IJavaLaunchConfigurationConstants.ID_JAVA_APPLICATION);

            ILaunchConfigurationWorkingCopy workingCopy = type.newInstance(null,
                    "AssistAI-" + mainClass.substring(mainClass.lastIndexOf('.') + 1)
                            + "-" + System.currentTimeMillis());

            workingCopy.setAttribute(IJavaLaunchConfigurationConstants.ATTR_PROJECT_NAME,
                    javaProject.getElementName());
            workingCopy.setAttribute(IJavaLaunchConfigurationConstants.ATTR_MAIN_TYPE_NAME,
                    mainClass);

            if (programArgs != null && !programArgs.isBlank())
            {
                workingCopy.setAttribute(IJavaLaunchConfigurationConstants.ATTR_PROGRAM_ARGUMENTS,
                        programArgs);
            }
            if (vmArgs != null && !vmArgs.isBlank())
            {
                workingCopy.setAttribute(IJavaLaunchConfigurationConstants.ATTR_VM_ARGUMENTS,
                        vmArgs);
            }

            ILaunchConfiguration configuration = workingCopy.doSave();

            // Capture output
            var outputBuffer = new StringBuilder();
            var errorBuffer = new StringBuilder();
            var latch = new CountDownLatch(1);

            // Launch
            final ILaunch[] launchHolder = new ILaunch[1];
            sync.syncExec(() ->
            {
                try
                {
                    launchHolder[0] = configuration.launch(mode, new NullProgressMonitor());
                }
                catch (CoreException e)
                {
                    logger.error("Error launching application", e);
                }
            });

            ILaunch launch = launchHolder[0];
            if (launch == null)
            {
                return "Error: Failed to launch application.";
            }

            // Attach stream listeners to capture output
            for (IProcess process : launch.getProcesses())
            {
                IStreamMonitor stdoutMonitor = process.getStreamsProxy().getOutputStreamMonitor();
                IStreamMonitor stderrMonitor = process.getStreamsProxy().getErrorStreamMonitor();

                // Capture any content already buffered
                String existingOut = stdoutMonitor.getContents();
                if (existingOut != null && !existingOut.isEmpty())
                {
                    outputBuffer.append(existingOut);
                }
                String existingErr = stderrMonitor.getContents();
                if (existingErr != null && !existingErr.isEmpty())
                {
                    errorBuffer.append(existingErr);
                }

                stdoutMonitor.addListener(new IStreamListener()
                {
                    @Override
                    public void streamAppended(String text, IStreamMonitor monitor)
                    {
                        outputBuffer.append(text);
                    }
                });
                stderrMonitor.addListener(new IStreamListener()
                {
                    @Override
                    public void streamAppended(String text, IStreamMonitor monitor)
                    {
                        errorBuffer.append(text);
                    }
                });
            }

            String modeLabel = ILaunchManager.DEBUG_MODE.equals(mode) ? "debug" : "run";

            if (timeout > 0)
            {
                // Wait for process to finish
                boolean terminated = waitForTermination(launch, timeout);

                var result = new StringBuilder();
                result.append("Application '").append(mainClass).append("' launched in ").append(modeLabel).append(" mode.\n");

                if (!terminated)
                {
                    result.append("Note: Process still running after ").append(timeout).append("s timeout.\n");
                }
                else
                {
                    // Get exit code
                    for (IProcess process : launch.getProcesses())
                    {
                        int exitValue = process.getExitValue();
                        result.append("Exit code: ").append(exitValue).append("\n");
                    }
                }

                if (outputBuffer.length() > 0)
                {
                    result.append("\n--- stdout ---\n").append(truncateOutput(outputBuffer.toString(), 5000));
                }
                if (errorBuffer.length() > 0)
                {
                    result.append("\n--- stderr ---\n").append(truncateOutput(errorBuffer.toString(), 2000));
                }

                // Clean up configuration for short-lived runs
                if (terminated)
                {
                    try { configuration.delete(); } catch (CoreException e) { /* ignore */ }
                }

                return result.toString();
            }
            else
            {
                // Don't wait â return immediately
                return "Application '" + mainClass + "' launched in " + modeLabel + " mode. " +
                       "Use stopApplication to terminate it, or getConsoleOutput to see its output.";
            }
        }
        catch (Exception e)
        {
            logger.error("Error launching Java application", e);
            return "Error: " + e.getMessage();
        }
    }

    /**
     * Stops a running Java application by matching against the launch configuration name or main class.
     *
     * @param nameOrClass A substring to match against launch name or main class
     * @return A status message
     */
    public String stopApplication(String nameOrClass)
    {
        Objects.requireNonNull(nameOrClass, "Name or class filter cannot be null");

        try
        {
            ILaunchManager launchManager = DebugPlugin.getDefault().getLaunchManager();
            ILaunch[] launches = launchManager.getLaunches();

            var terminated = new ArrayList<String>();

            for (ILaunch launch : launches)
            {
                if (launch.isTerminated())
                {
                    continue;
                }

                String launchName = Optional.ofNullable(launch.getLaunchConfiguration())
                        .map(ILaunchConfiguration::getName)
                        .orElse("");
                String mainType = Optional.ofNullable(launch.getLaunchConfiguration())
                        .map(config ->
                        {
                            try
                            {
                                return config.getAttribute(
                                        IJavaLaunchConfigurationConstants.ATTR_MAIN_TYPE_NAME, "");
                            }
                            catch (CoreException e)
                            {
                                return "";
                            }
                        })
                        .orElse("");

                if (launchName.toLowerCase().contains(nameOrClass.toLowerCase())
                        || mainType.toLowerCase().contains(nameOrClass.toLowerCase()))
                {
                    launch.terminate();
                    terminated.add(mainType.isEmpty() ? launchName : mainType);
                }
            }

            if (terminated.isEmpty())
            {
                return "No running application found matching '" + nameOrClass + "'.";
            }

            return "Terminated " + terminated.size() + " application(s): " + String.join(", ", terminated);
        }
        catch (Exception e)
        {
            logger.error("Error stopping application", e);
            return "Error: " + e.getMessage();
        }
    }

    /**
     * Lists all active launches (running/debugging).
     *
     * @return A formatted list of active launches
     */
    public String listActiveLaunches()
    {
        try
        {
            ILaunchManager launchManager = DebugPlugin.getDefault().getLaunchManager();
            ILaunch[] launches = launchManager.getLaunches();

            var result = new StringBuilder();
            int activeCount = 0;

            for (ILaunch launch : launches)
            {
                if (launch.isTerminated())
                {
                    continue;
                }

                activeCount++;
                String configName = Optional.ofNullable(launch.getLaunchConfiguration())
                        .map(ILaunchConfiguration::getName)
                        .orElse("unknown");
                String mainType = "";
                try
                {
                    mainType = launch.getLaunchConfiguration() != null
                            ? launch.getLaunchConfiguration().getAttribute(
                                    IJavaLaunchConfigurationConstants.ATTR_MAIN_TYPE_NAME, "")
                            : "";
                }
                catch (CoreException e) { /* ignore */ }

                String mode = launch.getLaunchMode();

                result.append(activeCount).append(". ")
                      .append(configName);
                if (!mainType.isEmpty())
                {
                    result.append(" (").append(mainType).append(")");
                }
                result.append(" [").append(mode).append("]");

                // Show process status
                for (IProcess process : launch.getProcesses())
                {
                    result.append(" - ").append(process.getLabel());
                    if (process.isTerminated())
                    {
                        result.append(" (terminated)");
                    }
                }
                result.append("\n");
            }

            if (activeCount == 0)
            {
                return "No active launches.";
            }

            return "Active launches (" + activeCount + "):\n" + result;
        }
        catch (Exception e)
        {
            logger.error("Error listing launches", e);
            return "Error: " + e.getMessage();
        }
    }

    /**
     * Toggles a line breakpoint at the specified location.
     *
     * @param projectName The project name
     * @param typeName The fully qualified type name (e.g., com.example.Main)
     * @param lineNumber The 1-based line number
     * @return A status message
     */
    public String toggleBreakpoint(String projectName, String typeName, int lineNumber)
    {
        Objects.requireNonNull(projectName, "Project name cannot be null");
        Objects.requireNonNull(typeName, "Type name cannot be null");

        try
        {
            IProject project = ResourcesPlugin.getWorkspace().getRoot().getProject(projectName);
            if (!project.exists())
            {
                return "Error: Project '" + projectName + "' does not exist.";
            }

            // Check if a breakpoint already exists at this location
            IBreakpoint[] breakpoints = DebugPlugin.getDefault().getBreakpointManager().getBreakpoints();
            for (IBreakpoint bp : breakpoints)
            {
                if (bp instanceof IJavaLineBreakpoint)
                {
                    IJavaLineBreakpoint lineBreakpoint = (IJavaLineBreakpoint) bp;
                    if (typeName.equals(lineBreakpoint.getTypeName())
                            && lineNumber == lineBreakpoint.getLineNumber())
                    {
                        // Remove existing breakpoint
                        bp.delete();
                        return "Breakpoint removed at " + typeName + ":" + lineNumber;
                    }
                }
            }

            // Create a new line breakpoint
            JDIDebugModel.createLineBreakpoint(
                    project, typeName, lineNumber, -1, -1, 0, true, null);

            return "Breakpoint set at " + typeName + ":" + lineNumber;
        }
        catch (Exception e)
        {
            logger.error("Error toggling breakpoint", e);
            return "Error: " + e.getMessage();
        }
    }

    /**
     * Lists all breakpoints in the workspace.
     *
     * @return A formatted list of breakpoints
     */
    public String listBreakpoints()
    {
        try
        {
            IBreakpoint[] breakpoints = DebugPlugin.getDefault().getBreakpointManager().getBreakpoints();

            if (breakpoints.length == 0)
            {
                return "No breakpoints set.";
            }

            var result = new StringBuilder();
            result.append("Breakpoints (").append(breakpoints.length).append("):\n");

            for (int i = 0; i < breakpoints.length; i++)
            {
                IBreakpoint bp = breakpoints[i];
                result.append(i + 1).append(". ");

                if (bp instanceof IJavaLineBreakpoint)
                {
                    IJavaLineBreakpoint lineBp = (IJavaLineBreakpoint) bp;
                    result.append(lineBp.getTypeName())
                          .append(":").append(lineBp.getLineNumber());
                    if (!bp.isEnabled())
                    {
                        result.append(" [disabled]");
                    }
                    if (lineBp.getCondition() != null && !lineBp.getCondition().isEmpty())
                    {
                        result.append(" condition: ").append(lineBp.getCondition());
                    }
                }
                else
                {
                    result.append(bp.toString());
                }
                result.append("\n");
            }

            return result.toString();
        }
        catch (Exception e)
        {
            logger.error("Error listing breakpoints", e);
            return "Error: " + e.getMessage();
        }
    }

    /**
     * Removes all breakpoints from the workspace.
     *
     * @return A status message
     */
    public String removeAllBreakpoints()
    {
        try
        {
            IBreakpoint[] breakpoints = DebugPlugin.getDefault().getBreakpointManager().getBreakpoints();
            DebugPlugin.getDefault().getBreakpointManager().removeBreakpoints(breakpoints, true);
            return "Removed " + breakpoints.length + " breakpoint(s).";
        }
        catch (Exception e)
        {
            logger.error("Error removing breakpoints", e);
            return "Error: " + e.getMessage();
        }
    }

    /**
     * Gets the stack trace of all threads for a suspended debug target.
     *
     * @param nameOrClass A substring to match against the debug launch
     * @return The stack trace information
     */
    public String getStackTrace(String nameOrClass)
    {
        Objects.requireNonNull(nameOrClass, "Name or class filter cannot be null");

        try
        {
            ILaunchManager launchManager = DebugPlugin.getDefault().getLaunchManager();
            ILaunch[] launches = launchManager.getLaunches();

            for (ILaunch launch : launches)
            {
                if (launch.isTerminated() || !ILaunchManager.DEBUG_MODE.equals(launch.getLaunchMode()))
                {
                    continue;
                }

                String configName = Optional.ofNullable(launch.getLaunchConfiguration())
                        .map(ILaunchConfiguration::getName).orElse("");
                String mainType = "";
                try
                {
                    mainType = launch.getLaunchConfiguration() != null
                            ? launch.getLaunchConfiguration().getAttribute(
                                    IJavaLaunchConfigurationConstants.ATTR_MAIN_TYPE_NAME, "")
                            : "";
                }
                catch (CoreException e) { /* ignore */ }

                if (!configName.toLowerCase().contains(nameOrClass.toLowerCase())
                        && !mainType.toLowerCase().contains(nameOrClass.toLowerCase()))
                {
                    continue;
                }

                // Found a matching debug launch
                var result = new StringBuilder();
                result.append("Stack trace for: ").append(mainType.isEmpty() ? configName : mainType).append("\n\n");

                for (IDebugTarget target : launch.getDebugTargets())
                {
                    IThread[] threads = target.getThreads();
                    for (IThread thread : threads)
                    {
                        result.append("Thread: ").append(thread.getName());
                        if (thread.isSuspended())
                        {
                            result.append(" [SUSPENDED]\n");
                            IStackFrame[] frames = thread.getStackFrames();
                            for (int i = 0; i < frames.length; i++)
                            {
                                IStackFrame frame = frames[i];
                                result.append("  ").append(i).append(": ").append(frame.getName()).append("\n");

                                // Show local variables for the top frame
                                if (i == 0)
                                {
                                    try
                                    {
                                        IVariable[] variables = frame.getVariables();
                                        for (IVariable var : variables)
                                        {
                                            result.append("    ").append(var.getName())
                                                  .append(" = ").append(var.getValue().getValueString())
                                                  .append(" (").append(var.getReferenceTypeName()).append(")\n");
                                        }
                                    }
                                    catch (DebugException e)
                                    {
                                        result.append("    (unable to read variables: ").append(e.getMessage()).append(")\n");
                                    }
                                }
                            }
                        }
                        else
                        {
                            result.append(" [RUNNING]\n");
                        }
                    }
                }

                return result.toString();
            }

            return "No active debug session found matching '" + nameOrClass + "'.";
        }
        catch (Exception e)
        {
            logger.error("Error getting stack trace", e);
            return "Error: " + e.getMessage();
        }
    }

    /**
     * Resumes a suspended debug session.
     *
     * @param nameOrClass A substring to match against the debug launch
     * @return A status message
     */
    public String resumeDebug(String nameOrClass)
    {
        Objects.requireNonNull(nameOrClass, "Name or class filter cannot be null");

        try
        {
            ILaunch launch = findDebugLaunch(nameOrClass);
            if (launch == null)
            {
                return "No active debug session found matching '" + nameOrClass + "'.";
            }

            for (IDebugTarget target : launch.getDebugTargets())
            {
                target.resume();
            }

            return "Debug session resumed.";
        }
        catch (Exception e)
        {
            logger.error("Error resuming debug", e);
            return "Error: " + e.getMessage();
        }
    }

    /**
     * Steps over in a suspended debug session (executes current line).
     *
     * @param nameOrClass A substring to match against the debug launch
     * @return A status message
     */
    public String stepOver(String nameOrClass)
    {
        return performStepAction(nameOrClass, "over");
    }

    /**
     * Steps into in a suspended debug session (enters method call).
     *
     * @param nameOrClass A substring to match against the debug launch
     * @return A status message
     */
    public String stepInto(String nameOrClass)
    {
        return performStepAction(nameOrClass, "into");
    }

    /**
     * Steps return in a suspended debug session (runs until current method returns).
     *
     * @param nameOrClass A substring to match against the debug launch
     * @return A status message
     */
    public String stepReturn(String nameOrClass)
    {
        return performStepAction(nameOrClass, "return");
    }

    private String performStepAction(String nameOrClass, String stepType)
    {
        Objects.requireNonNull(nameOrClass, "Name or class filter cannot be null");

        try
        {
            ILaunch launch = findDebugLaunch(nameOrClass);
            if (launch == null)
            {
                return "No active debug session found matching '" + nameOrClass + "'.";
            }

            for (IDebugTarget target : launch.getDebugTargets())
            {
                for (IThread thread : target.getThreads())
                {
                    if (thread.isSuspended())
                    {
                        switch (stepType)
                        {
                            case "over":
                                thread.stepOver();
                                break;
                            case "into":
                                thread.stepInto();
                                break;
                            case "return":
                                thread.stepReturn();
                                break;
                        }

                        // Wait briefly for the step to complete
                        Thread.sleep(500);

                        // Return the new stack state
                        if (thread.isSuspended())
                        {
                            IStackFrame[] frames = thread.getStackFrames();
                            if (frames.length > 0)
                            {
                                return "Step " + stepType + " completed. Now at: " + frames[0].getName();
                            }
                        }
                        return "Step " + stepType + " completed. Thread is running.";
                    }
                }
            }

            return "No suspended thread found to step.";
        }
        catch (Exception e)
        {
            logger.error("Error performing step " + stepType, e);
            return "Error: " + e.getMessage();
        }
    }

    /**
     * Evaluates a Java expression in the context of a suspended debug frame.
     *
     * @param nameOrClass A substring to match against the debug launch
     * @param expression The Java expression to evaluate
     * @return The result of the evaluation
     */
    public String evaluateExpression(String nameOrClass, String expression)
    {
        Objects.requireNonNull(nameOrClass, "Name or class filter cannot be null");
        Objects.requireNonNull(expression, "Expression cannot be null");

        try
        {
            ILaunch launch = findDebugLaunch(nameOrClass);
            if (launch == null)
            {
                return "No active debug session found matching '" + nameOrClass + "'.";
            }

            for (IDebugTarget target : launch.getDebugTargets())
            {
                if (target instanceof org.eclipse.jdt.debug.core.IJavaDebugTarget)
                {
                    var javaTarget = (org.eclipse.jdt.debug.core.IJavaDebugTarget) target;
                    for (IThread thread : target.getThreads())
                    {
                        if (thread.isSuspended() && thread instanceof org.eclipse.jdt.debug.core.IJavaThread)
                        {
                            var javaThread = (org.eclipse.jdt.debug.core.IJavaThread) thread;
                            IStackFrame[] frames = thread.getStackFrames();
                            if (frames.length > 0 && frames[0] instanceof org.eclipse.jdt.debug.core.IJavaStackFrame)
                            {
                                var javaFrame = (org.eclipse.jdt.debug.core.IJavaStackFrame) frames[0];

                                // Use the evaluation engine
                                var project = org.eclipse.jdt.core.JavaCore.create(
                                        org.eclipse.core.resources.ResourcesPlugin.getWorkspace().getRoot())
                                        .getJavaProjects();

                                // Find the right project
                                org.eclipse.jdt.core.IJavaProject javaProject = null;
                                try
                                {
                                    String projectName = launch.getLaunchConfiguration().getAttribute(
                                            IJavaLaunchConfigurationConstants.ATTR_PROJECT_NAME, "");
                                    if (!projectName.isEmpty())
                                    {
                                        javaProject = org.eclipse.jdt.core.JavaCore.create(
                                                org.eclipse.core.resources.ResourcesPlugin.getWorkspace()
                                                        .getRoot().getProject(projectName));
                                    }
                                }
                                catch (CoreException e) { /* ignore */ }

                                if (javaProject == null && project.length > 0)
                                {
                                    javaProject = project[0];
                                }

                                var engine = org.eclipse.jdt.debug.eval.EvaluationManager
                                        .newAstEvaluationEngine(javaProject, javaTarget);

                                var latch = new CountDownLatch(1);
                                var resultHolder = new String[1];

                                engine.evaluate(expression, javaFrame, new org.eclipse.jdt.debug.eval.IEvaluationListener()
                                {
                                    @Override
                                    public void evaluationComplete(org.eclipse.jdt.debug.eval.IEvaluationResult result)
                                    {
                                        try
                                        {
                                            if (result.hasErrors())
                                            {
                                                var errors = result.getErrorMessages();
                                                resultHolder[0] = "Evaluation error: " + String.join("; ", errors);
                                            }
                                            else
                                            {
                                                var value = result.getValue();
                                                if (value != null)
                                                {
                                                    resultHolder[0] = value.getValueString()
                                                            + " (" + value.getReferenceTypeName() + ")";
                                                }
                                                else
                                                {
                                                    resultHolder[0] = "null";
                                                }
                                            }
                                        }
                                        catch (Exception e)
                                        {
                                            resultHolder[0] = "Error reading result: " + e.getMessage();
                                        }
                                        finally
                                        {
                                            latch.countDown();
                                        }
                                    }
                                }, org.eclipse.debug.core.DebugEvent.EVALUATION_IMPLICIT, false);

                                boolean completed = latch.await(10, TimeUnit.SECONDS);
                                engine.dispose();

                                if (!completed)
                                {
                                    return "Evaluation timed out after 10 seconds.";
                                }

                                return "Expression: " + expression + "\nResult: " + resultHolder[0];
                            }
                        }
                    }
                }
            }

            return "No suspended thread found for evaluation.";
        }
        catch (Exception e)
        {
            logger.error("Error evaluating expression", e);
            return "Error: " + e.getMessage();
        }
    }

    /**
     * Sets a conditional breakpoint at the specified location.
     *
     * @param projectName The project name
     * @param typeName The fully qualified type name
     * @param lineNumber The 1-based line number
     * @param condition The Java boolean expression that must evaluate to true for the breakpoint to trigger
     * @param hitCount Optional hit count (breakpoint triggers only after being hit N times)
     * @return A status message
     */
    public String setConditionalBreakpoint(String projectName, String typeName, int lineNumber,
                                           String condition, int hitCount)
    {
        Objects.requireNonNull(projectName, "Project name cannot be null");
        Objects.requireNonNull(typeName, "Type name cannot be null");
        Objects.requireNonNull(condition, "Condition cannot be null");

        try
        {
            IProject project = org.eclipse.core.resources.ResourcesPlugin.getWorkspace()
                    .getRoot().getProject(projectName);
            if (!project.exists())
            {
                return "Error: Project '" + projectName + "' does not exist.";
            }

            // Remove any existing breakpoint at this location first
            IBreakpoint[] breakpoints = DebugPlugin.getDefault().getBreakpointManager().getBreakpoints();
            for (IBreakpoint bp : breakpoints)
            {
                if (bp instanceof IJavaLineBreakpoint)
                {
                    IJavaLineBreakpoint lineBreakpoint = (IJavaLineBreakpoint) bp;
                    if (typeName.equals(lineBreakpoint.getTypeName())
                            && lineNumber == lineBreakpoint.getLineNumber())
                    {
                        bp.delete();
                    }
                }
            }

            // Create a new conditional line breakpoint
            IJavaLineBreakpoint bp = JDIDebugModel.createLineBreakpoint(
                    project, typeName, lineNumber, -1, -1, 0, true, null);

            bp.setCondition(condition);
            bp.setConditionEnabled(true);

            if (hitCount > 0)
            {
                bp.setHitCount(hitCount);
            }

            var result = "Conditional breakpoint set at " + typeName + ":" + lineNumber
                    + "\n  Condition: " + condition;
            if (hitCount > 0)
            {
                result += "\n  Hit count: " + hitCount;
            }
            return result;
        }
        catch (Exception e)
        {
            logger.error("Error setting conditional breakpoint", e);
            return "Error: " + e.getMessage();
        }
    }

    /**
     * Triggers a hot code replace in an active debug session.
     * This pushes changed classes into the running JVM without restarting.
     *
     * @param nameOrClass A substring to match against the debug launch
     * @return A status message
     */
    public String hotCodeReplace(String nameOrClass)
    {
        Objects.requireNonNull(nameOrClass, "Name or class filter cannot be null");

        try
        {
            ILaunch launch = findDebugLaunch(nameOrClass);
            if (launch == null)
            {
                return "No active debug session found matching '" + nameOrClass + "'.";
            }

            for (IDebugTarget target : launch.getDebugTargets())
            {
                if (target instanceof org.eclipse.jdt.debug.core.IJavaDebugTarget)
                {
                    var javaTarget = (org.eclipse.jdt.debug.core.IJavaDebugTarget) target;
                    if (javaTarget.supportsHotCodeReplace())
                    {
                        // Trigger a build first to compile latest changes
                        org.eclipse.core.resources.ResourcesPlugin.getWorkspace().build(
                                org.eclipse.core.resources.IncrementalProjectBuilder.INCREMENTAL_BUILD,
                                new org.eclipse.core.runtime.NullProgressMonitor());

                        // The JDT debug framework automatically performs hot code replace
                        // when a build completes and classes change while debugging.
                        // We just need to ensure the build happens.

                        return "Hot code replace triggered. Changed classes will be reloaded in the debug session.";
                    }
                    else
                    {
                        return "Error: The target JVM does not support hot code replace.";
                    }
                }
            }

            return "Error: No Java debug target found.";
        }
        catch (Exception e)
        {
            logger.error("Error performing hot code replace", e);
            return "Error: " + e.getMessage();
        }
    }

    /**
     * Finds an active debug launch matching the given name/class filter.
     */
    private ILaunch findDebugLaunch(String nameOrClass)
    {
        ILaunchManager launchManager = DebugPlugin.getDefault().getLaunchManager();
        for (ILaunch launch : launchManager.getLaunches())
        {
            if (launch.isTerminated() || !ILaunchManager.DEBUG_MODE.equals(launch.getLaunchMode()))
            {
                continue;
            }

            String configName = Optional.ofNullable(launch.getLaunchConfiguration())
                    .map(ILaunchConfiguration::getName).orElse("");
            String mainType = "";
            try
            {
                mainType = launch.getLaunchConfiguration() != null
                        ? launch.getLaunchConfiguration().getAttribute(
                                IJavaLaunchConfigurationConstants.ATTR_MAIN_TYPE_NAME, "")
                        : "";
            }
            catch (CoreException e) { /* ignore */ }

            if (configName.toLowerCase().contains(nameOrClass.toLowerCase())
                    || mainType.toLowerCase().contains(nameOrClass.toLowerCase()))
            {
                return launch;
            }
        }
        return null;
    }

    /**
     * Waits for a launch to terminate within the specified timeout.
     */
    private boolean waitForTermination(ILaunch launch, int timeoutSeconds)
    {
        long deadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(timeoutSeconds);
        while (!launch.isTerminated() && System.currentTimeMillis() < deadline)
        {
            try
            {
                Thread.sleep(200);
            }
            catch (InterruptedException e)
            {
                Thread.currentThread().interrupt();
                break;
            }
        }
        return launch.isTerminated();
    }

    /**
     * Truncates output if it exceeds the specified max length.
     */
    private String truncateOutput(String output, int maxLength)
    {
        if (output.length() <= maxLength)
        {
            return output;
        }
        return output.substring(0, maxLength) + "\n... (truncated, " + output.length() + " total chars)";
    }
}
