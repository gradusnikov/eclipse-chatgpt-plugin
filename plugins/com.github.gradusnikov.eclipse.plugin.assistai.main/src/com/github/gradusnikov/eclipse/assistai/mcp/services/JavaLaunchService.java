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
