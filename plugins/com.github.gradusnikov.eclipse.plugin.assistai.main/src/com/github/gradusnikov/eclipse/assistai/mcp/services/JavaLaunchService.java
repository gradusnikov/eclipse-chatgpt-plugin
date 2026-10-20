package com.github.gradusnikov.eclipse.assistai.mcp.services;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IMarker;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IncrementalProjectBuilder;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IAdaptable;
import org.eclipse.core.runtime.ILog;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.debug.core.DebugEvent;
import org.eclipse.debug.core.DebugException;
import org.eclipse.debug.core.DebugPlugin;
import org.eclipse.debug.core.IDebugEventSetListener;
import org.eclipse.debug.core.ILaunch;
import org.eclipse.debug.core.ILaunchConfiguration;
import org.eclipse.debug.core.ILaunchConfigurationType;
import org.eclipse.debug.core.ILaunchConfigurationWorkingCopy;
import org.eclipse.debug.core.ILaunchManager;
import org.eclipse.debug.core.IStreamListener;
import org.eclipse.debug.core.model.IBreakpoint;
import org.eclipse.debug.core.model.IDebugElement;
import org.eclipse.debug.core.model.IDebugTarget;
import org.eclipse.debug.core.model.IProcess;
import org.eclipse.debug.core.model.ISourceLocator;
import org.eclipse.debug.core.model.IStackFrame;
import org.eclipse.debug.core.model.IStreamMonitor;
import org.eclipse.debug.core.model.IThread;
import org.eclipse.debug.core.model.IVariable;
import org.eclipse.e4.core.di.annotations.Creatable;
import org.eclipse.e4.ui.di.UISynchronize;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.debug.core.IJavaDebugTarget;
import org.eclipse.jdt.debug.core.IJavaHotCodeReplaceListener;
import org.eclipse.jdt.debug.core.IJavaLineBreakpoint;
import org.eclipse.jdt.debug.core.IJavaStackFrame;
import org.eclipse.jdt.debug.core.IJavaThread;
import org.eclipse.jdt.debug.core.IJavaValue;
import org.eclipse.jdt.debug.core.JDIDebugModel;
import org.eclipse.jdt.debug.eval.EvaluationManager;
import org.eclipse.jdt.debug.eval.IAstEvaluationEngine;
import org.eclipse.jdt.debug.eval.IEvaluationResult;
import org.eclipse.jdt.launching.IJavaLaunchConfigurationConstants;

import com.github.gradusnikov.eclipse.assistai.mcp.results.ActiveLaunchesResponse;
import com.github.gradusnikov.eclipse.assistai.mcp.results.BreakpointResponse;
import com.github.gradusnikov.eclipse.assistai.mcp.results.BreakpointsResponse;
import com.github.gradusnikov.eclipse.assistai.mcp.results.Diagnostic;
import com.github.gradusnikov.eclipse.assistai.mcp.results.DiagnosticCode;
import com.github.gradusnikov.eclipse.assistai.mcp.results.EvaluationResponse;
import com.github.gradusnikov.eclipse.assistai.mcp.results.HotCodeReplaceResponse;
import com.github.gradusnikov.eclipse.assistai.mcp.results.LaunchConfigurationsResponse;
import com.github.gradusnikov.eclipse.assistai.mcp.results.LaunchResponse;
import com.github.gradusnikov.eclipse.assistai.mcp.results.StackTraceResponse;
import com.github.gradusnikov.eclipse.assistai.mcp.results.StepResponse;
import com.github.gradusnikov.eclipse.assistai.mcp.results.StopApplicationResponse;
import com.github.gradusnikov.eclipse.assistai.tools.LineOffsets;

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
     */
    public LaunchResponse runJavaApplication(String projectName, String mainClass,
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
     */
    public LaunchResponse debugJavaApplication(String projectName, String mainClass,
                                       String programArgs, String vmArgs, int timeout)
    {
        return launchJavaApplication(projectName, mainClass, programArgs, vmArgs,
                ILaunchManager.DEBUG_MODE, timeout);
    }

    /**
     * Core launch method for both run and debug modes.
     */
    private LaunchResponse launchJavaApplication(String projectName, String mainClass,
                                         String programArgs, String vmArgs,
                                         String mode, int timeout)
    {
        Objects.requireNonNull(projectName, "Project name cannot be null");
        Objects.requireNonNull(mainClass, "Main class cannot be null");

        String modeLabel = modeLabel( mode );

        try
        {
            IProject project = ResourcesPlugin.getWorkspace().getRoot().getProject(projectName);
            if (!project.exists())
            {
                return LaunchResponse.failedToStart( null, modeLabel, projectName, mainClass,
                        Diagnostic.fatal( DiagnosticCode.PROJECT_NOT_FOUND,
                                "No project named '" + projectName + "' in the workspace." ) );
            }
            if (!project.isOpen())
            {
                return LaunchResponse.failedToStart( null, modeLabel, projectName, mainClass,
                        Diagnostic.fatal( DiagnosticCode.RESOURCE_NOT_ACCESSIBLE,
                                "Project '" + projectName + "' is closed; open it before launching." ) );
            }

            IJavaProject javaProject = JavaCore.create(project);
            if (javaProject == null || !javaProject.exists())
            {
                return LaunchResponse.failedToStart( null, modeLabel, projectName, mainClass,
                        Diagnostic.fatal( DiagnosticCode.RESOURCE_NOT_ACCESSIBLE,
                                "Project '" + projectName + "' is not a Java project." ) );
            }

            // Verify the main class exists
            IType mainType = javaProject.findType(mainClass);
            if (mainType == null)
            {
                return LaunchResponse.failedToStart( null, modeLabel, projectName, mainClass,
                        Diagnostic.fatal( DiagnosticCode.RESOURCE_NOT_FOUND,
                                "Project '" + projectName + "' resolves no type named '" + mainClass + "'." ) );
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

            // Launch the in-memory working copy directly. We deliberately do NOT
            // doSave() it: saving adds a throwaway "AssistAI-<Class>-<timestamp>"
            // entry to the user's Run/Debug Configurations list on every launch,
            // and the previous cleanup only deleted it for terminated timed runs
            // (never for background launches), so those entries accumulated.
            return executeLaunch(workingCopy, mode, timeout);
        }
        catch (Exception e)
        {
            logger.error("Error launching Java application", e);
            return LaunchResponse.failedToStart( null, modeLabel, projectName, mainClass,
                    Diagnostic.fatal( DiagnosticCode.INTERNAL_ERROR,
                            "The launch could not be assembled: " + e.getMessage() ) );
        }
    }

    /**
     * Launches an existing, saved launch configuration by name, reusing its full
     * setup: classpath, program/VM arguments, environment variables, working
     * directory, and any agent settings (e.g. JRebel). Unlike
     * {@link #runJavaApplication}/{@link #debugJavaApplication}, this does not
     * create a new configuration.
     *
     * @param configurationName The exact name of the launch configuration (see {@link #listLaunchConfigurations})
     * @param mode "run" or "debug" (anything else defaults to run)
     * @param timeout Timeout in seconds to wait for the process to finish (0 = don't wait)
     */
    public LaunchResponse launchConfiguration(String configurationName, String mode, int timeout)
    {
        Objects.requireNonNull(configurationName, "Configuration name cannot be null");

        String launchMode = ILaunchManager.DEBUG_MODE.equalsIgnoreCase(mode)
                ? ILaunchManager.DEBUG_MODE
                : ILaunchManager.RUN_MODE;
        String modeLabel = modeLabel( launchMode );

        try
        {
            ILaunchManager launchManager = DebugPlugin.getDefault().getLaunchManager();
            ILaunchConfiguration configuration = findLaunchConfigurationByName(launchManager, configurationName);
            if (configuration == null)
            {
                return LaunchResponse.failedToStart( configurationName, modeLabel, null, null,
                        Diagnostic.fatal( DiagnosticCode.LAUNCH_CONFIGURATION_NOT_FOUND,
                                "No launch configuration named '" + configurationName
                                        + "'. Use listLaunchConfigurations to see the available names." ) );
            }
            if (!configuration.supportsMode(launchMode))
            {
                return LaunchResponse.failedToStart( configuration.getName(), modeLabel,
                        configurationAttribute( configuration, IJavaLaunchConfigurationConstants.ATTR_PROJECT_NAME ),
                        configurationAttribute( configuration, IJavaLaunchConfigurationConstants.ATTR_MAIN_TYPE_NAME ),
                        Diagnostic.fatal( DiagnosticCode.RESOURCE_NOT_ACCESSIBLE,
                                "Launch configuration '" + configuration.getName() + "' does not support "
                                        + modeLabel + " mode." ) );
            }
            return executeLaunch(configuration, launchMode, timeout);
        }
        catch (Exception e)
        {
            logger.error("Error launching configuration", e);
            return LaunchResponse.failedToStart( configurationName, modeLabel, null, null,
                    Diagnostic.fatal( DiagnosticCode.INTERNAL_ERROR,
                            "The launch could not be assembled: " + e.getMessage() ) );
        }
    }

    /**
     * The launch configurations saved in the workspace, optionally filtered by type.
     *
     * <p>Recognised {@code typeFilter} values:
     * <ul>
     *   <li>{@code null}, {@code ""} or {@code "all"} — return every configuration</li>
     *   <li>{@code "junit"} — only {@code org.eclipse.jdt.junit.launchconfig}</li>
     *   <li>{@code "junit-plugin"} — only {@code org.eclipse.pde.ui.JunitLaunchConfig}</li>
     *   <li>any other string — substring match against the type identifier</li>
     * </ul>
     *
     * @param typeFilter optional filter (see above)
     */
    public LaunchConfigurationsResponse listLaunchConfigurations( String typeFilter )
    {
        var configurations = new ArrayList<LaunchConfigurationsResponse.LaunchConfigurationInfo>();

        try
        {
            ILaunchManager launchManager = DebugPlugin.getDefault().getLaunchManager();

            for ( ILaunchConfiguration config : launchManager.getLaunchConfigurations() )
            {
                String typeId   = "";
                String typeName = "";
                try
                {
                    ILaunchConfigurationType type = config.getType();
                    typeId   = type.getIdentifier();
                    typeName = type.getName();
                }
                catch ( CoreException e )
                {
                    // A configuration whose type is no longer installed still has a
                    // name, and knowing it exists is what the caller asked for.
                }

                if ( !matchesTypeFilter( typeId, typeFilter ) )
                {
                    continue;
                }

                configurations.add( new LaunchConfigurationsResponse.LaunchConfigurationInfo(
                        config.getName(),
                        typeId,
                        typeName,
                        config.getAttribute( IJavaLaunchConfigurationConstants.ATTR_PROJECT_NAME, "" ),
                        config.getAttribute( IJavaLaunchConfigurationConstants.ATTR_MAIN_TYPE_NAME, "" ) ) );
            }
        }
        catch ( CoreException e )
        {
            logger.error( "Error listing launch configurations", e );
            throw new IllegalStateException(
                    "Could not read the workspace launch configurations: " + e.getMessage(), e );
        }

        return LaunchConfigurationsResponse.of( typeFilter, configurations );
    }

    /**
     * Returns true if the given type identifier matches the requested filter.
     */
    private boolean matchesTypeFilter( String typeId, String typeFilter )
    {
        if ( typeFilter == null || typeFilter.isBlank() || "all".equalsIgnoreCase( typeFilter ) )
        {
            return true;
        }
        return switch ( typeFilter.toLowerCase() )
        {
            case "junit"        -> "org.eclipse.jdt.junit.launchconfig".equals( typeId );
            case "junit-plugin" -> "org.eclipse.pde.ui.JunitLaunchConfig".equals( typeId );
            default             -> typeId.toLowerCase().contains( typeFilter.toLowerCase() );
        };
    }

    /**
     * Finds a saved launch configuration by name: exact match first, then a
     * case-insensitive fallback.
     */
    private ILaunchConfiguration findLaunchConfigurationByName(ILaunchManager launchManager, String name)
            throws CoreException
    {
        ILaunchConfiguration[] configurations = launchManager.getLaunchConfigurations();
        for (ILaunchConfiguration configuration : configurations)
        {
            if (configuration.getName().equals(name))
            {
                return configuration;
            }
        }
        for (ILaunchConfiguration configuration : configurations)
        {
            if (configuration.getName().equalsIgnoreCase(name))
            {
                return configuration;
            }
        }
        return null;
    }

    /**
     * Launches the given configuration (which may be an unsaved working copy or a
     * persisted configuration), attaches stream listeners, and either waits for
     * termination returning captured output (timeout &gt; 0) or returns
     * immediately (timeout == 0).
     *
     * @param configuration The configuration (or working copy) to launch
     * @param mode {@link ILaunchManager#RUN_MODE} or {@link ILaunchManager#DEBUG_MODE}
     * @param timeout Timeout in seconds to wait for the process to finish (0 = don't wait)
     */
    private LaunchResponse executeLaunch(ILaunchConfiguration configuration, String mode, int timeout)
    {
        // StringBuffer, not StringBuilder: the stream listeners below append from the
        // debug plug-in's own threads while this thread reads.
        var outputBuffer = new StringBuffer();
        var errorBuffer = new StringBuffer();

        long started = System.currentTimeMillis();
        String modeLabel   = modeLabel( mode );
        String launchName  = configuration.getName();
        String projectName = configurationAttribute( configuration,
                IJavaLaunchConfigurationConstants.ATTR_PROJECT_NAME );
        String mainClass   = configurationAttribute( configuration,
                IJavaLaunchConfigurationConstants.ATTR_MAIN_TYPE_NAME );

        final ILaunch[] launchHolder = new ILaunch[1];
        final CoreException[] launchError = new CoreException[1];
        sync.syncExec(() ->
        {
            try
            {
                launchHolder[0] = configuration.launch(mode, new NullProgressMonitor());
            }
            catch (CoreException e)
            {
                // Carry the launcher's own message out of the UI thread. Logging it and
                // returning only a generic "Eclipse refused to launch" left the caller -
                // and the AI driving it - to guess at a cause the launcher had already
                // spelled out (a bad classpath, an unmet build-path requirement, ...).
                logger.error("Error launching application", e);
                launchError[0] = e;
            }
        });

        ILaunch launch = launchHolder[0];
        if (launch == null)
        {
            String cause = launchError[0] != null
                    ? launchError[0].getMessage()
                    : "see the error log for the cause";
            return LaunchResponse.failedToStart( launchName, modeLabel, projectName, mainClass,
                    Diagnostic.fatal( DiagnosticCode.LAUNCH_FAILED,
                            "Could not launch '" + launchName + "': " + cause ) );
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

        Long pid = launch.getProcesses().length > 0 ? processId( launch.getProcesses()[0] ) : null;

        if (timeout > 0)
        {
            // Wait for process to finish
            boolean terminated = waitForTermination(launch, timeout);

            var stdout = LaunchResponse.ProcessOutput.of( outputBuffer.toString(), LaunchResponse.MAX_STDOUT_CHARS );
            var stderr = LaunchResponse.ProcessOutput.of( errorBuffer.toString(), LaunchResponse.MAX_STDERR_CHARS );
            long duration = System.currentTimeMillis() - started;

            if (!terminated)
            {
                return LaunchResponse.running( launchName, modeLabel, projectName, mainClass, pid, true,
                        duration, stdout, stderr );
            }

            return LaunchResponse.completed( launchName, modeLabel, projectName, mainClass, pid,
                    exitCode( launch ), duration, stdout, stderr );
        }

        // Don't wait -- return immediately
        return LaunchResponse.running( launchName, modeLabel, projectName, mainClass, pid, false,
                System.currentTimeMillis() - started,
                LaunchResponse.ProcessOutput.of( outputBuffer.toString(), LaunchResponse.MAX_STDOUT_CHARS ),
                LaunchResponse.ProcessOutput.of( errorBuffer.toString(), LaunchResponse.MAX_STDERR_CHARS ) );
    }

    /** {@code "run"} or {@code "debug"} - what the caller passed and what it reads back. */
    private String modeLabel( String mode )
    {
        return ILaunchManager.DEBUG_MODE.equals( mode ) ? "debug" : "run";
    }

    /**
     * The exit value of a launch's first process, or null when there is none to report.
     * <p>
     * Null rather than a sentinel: {@code -1} is a perfectly ordinary exit code, so
     * reporting it for "we could not tell" would make a failed program indistinguishable
     * from an unreadable one.
     */
    private Integer exitCode( ILaunch launch )
    {
        for ( IProcess process : launch.getProcesses() )
        {
            if ( !process.isTerminated() )
            {
                continue;
            }
            try
            {
                return process.getExitValue();
            }
            catch ( DebugException e )
            {
                return null;
            }
        }
        return null;
    }

    /**
     * Stops the running Java applications matching the launch configuration name or main
     * class.
     * <p>
     * "Nothing matched" and "it went wrong" used to be two sentences a caller had to read
     * apart, and the terminated names were comma-joined although launch configuration
     * names routinely contain commas.
     *
     * @param nameOrClass A substring to match against launch name or main class
     */
    public StopApplicationResponse stopApplication(String nameOrClass)
    {
        Objects.requireNonNull(nameOrClass, "Name or class filter cannot be null");

        String needle = nameOrClass.toLowerCase();

        var terminated  = new ArrayList<StopApplicationResponse.TerminatedLaunch>();
        var diagnostics = new ArrayList<Diagnostic>();
        int matched = 0;

        for (ILaunch launch : DebugPlugin.getDefault().getLaunchManager().getLaunches())
        {
            if (launch.isTerminated())
            {
                continue;
            }

            ILaunchConfiguration configuration = launch.getLaunchConfiguration();
            String configName = Optional.ofNullable( configuration )
                    .map( ILaunchConfiguration::getName ).orElse( "" );
            String mainType = Optional.ofNullable( configurationAttribute( configuration,
                    IJavaLaunchConfigurationConstants.ATTR_MAIN_TYPE_NAME ) ).orElse( "" );

            if (!configName.toLowerCase().contains(needle) && !mainType.toLowerCase().contains(needle))
            {
                continue;
            }

            matched++;
            try
            {
                launch.terminate();
                terminated.add( new StopApplicationResponse.TerminatedLaunch(
                        configName.isEmpty() ? null : configName,
                        mainType.isEmpty() ? null : mainType,
                        launch.getLaunchMode() ) );
            }
            catch (DebugException e)
            {
                // One launch that will not die must not hide the ones that did.
                logger.error("Error stopping application", e);
                diagnostics.add( Diagnostic.retryable( DiagnosticCode.INTERNAL_ERROR,
                        "'" + configName + "' could not be terminated: " + e.getMessage() ) );
            }
        }

        return StopApplicationResponse.of( nameOrClass, matched, terminated, diagnostics );
    }

    /**
     * The launches that are still running, in run or debug mode.
     * <p>
     * A terminated launch lingers in the launch manager until Eclipse reaps it, and is
     * not what this tool was asked for, so it is left out; a live launch whose process
     * has already exited is reported with that process flagged terminated, which is the
     * state a caller needs to notice.
     */
    public ActiveLaunchesResponse listActiveLaunches()
    {
        var launches = new ArrayList<ActiveLaunchesResponse.ActiveLaunch>();

        for ( ILaunch launch : DebugPlugin.getDefault().getLaunchManager().getLaunches() )
        {
            if ( launch.isTerminated() )
            {
                continue;
            }

            ILaunchConfiguration configuration = launch.getLaunchConfiguration();

            var processes = new ArrayList<ActiveLaunchesResponse.LaunchProcess>();
            for ( IProcess process : launch.getProcesses() )
            {
                processes.add( new ActiveLaunchesResponse.LaunchProcess(
                        process.getLabel(), process.isTerminated(), processId( process ) ) );
            }

            Long pid = processes.stream()
                    .map( ActiveLaunchesResponse.LaunchProcess::pid )
                    .filter( Objects::nonNull )
                    .findFirst()
                    .orElse( null );

            launches.add( new ActiveLaunchesResponse.ActiveLaunch(
                    Optional.ofNullable( configuration ).map( ILaunchConfiguration::getName ).orElse( "unknown" ),
                    launch.getLaunchMode(),
                    launch.isTerminated(),
                    configurationAttribute( configuration, IJavaLaunchConfigurationConstants.ATTR_MAIN_TYPE_NAME ),
                    configurationAttribute( configuration, IJavaLaunchConfigurationConstants.ATTR_PROJECT_NAME ),
                    pid,
                    processes ) );
        }

        return ActiveLaunchesResponse.of( launches );
    }

    /**
     * An attribute of a launch configuration, or null when the configuration is gone or
     * names none - a distinct answer from the empty string a default would give back.
     */
    private String configurationAttribute( ILaunchConfiguration configuration, String attribute )
    {
        if ( configuration == null )
        {
            return null;
        }
        try
        {
            String value = configuration.getAttribute( attribute, "" );
            return value.isEmpty() ? null : value;
        }
        catch ( CoreException e )
        {
            return null;
        }
    }

    /**
     * The operating system process id the debug plug-in recorded for a process, or null
     * when it recorded none. The attribute is optional, so its absence is a fact about
     * the launch rather than a failure.
     */
    private Long processId( IProcess process )
    {
        String pid = process.getAttribute( IProcess.ATTR_PROCESS_ID );
        if ( pid == null || pid.isBlank() )
        {
            return null;
        }
        try
        {
            return Long.valueOf( pid.trim() );
        }
        catch ( NumberFormatException e )
        {
            return null;
        }
    }

    /**
     * Sets a line breakpoint at the given location, or removes the one already there.
     * <p>
     * Which way the door went used to be readable only from the words "set" versus
     * "removed" inside a sentence; it is now {@link BreakpointResponse#action()}. The
     * location is validated before anything is created: a breakpoint on a type the
     * project does not resolve is created happily by JDT, never binds, and used to be
     * reported as "Breakpoint set".
     *
     * @param projectName The project name
     * @param typeName The fully qualified type name (e.g., com.example.Main)
     * @param lineNumber The 1-based line number
     */
    public BreakpointResponse toggleBreakpoint(String projectName, String typeName, int lineNumber)
    {
        Objects.requireNonNull(projectName, "Project name cannot be null");
        Objects.requireNonNull(typeName, "Type name cannot be null");

        try
        {
            // Removing comes first and is not validated: a breakpoint left behind by a
            // type that has since been renamed away still has to be removable.
            IJavaLineBreakpoint existing = findLineBreakpoint( typeName, lineNumber );
            if ( existing != null )
            {
                BreakpointsResponse.BreakpointInfo removed = describeBreakpoint( existing );
                existing.delete();
                return BreakpointResponse.removed( projectName, typeName, lineNumber, removed );
            }

            IProject project = ResourcesPlugin.getWorkspace().getRoot().getProject(projectName);
            BreakpointResponse invalid = validateBreakpointLocation( project, projectName, typeName, lineNumber );
            if ( invalid != null )
            {
                return invalid;
            }

            IJavaLineBreakpoint created = JDIDebugModel.createLineBreakpoint(
                    project, typeName, lineNumber, -1, -1, 0, true, null);

            return BreakpointResponse.set( projectName, typeName, lineNumber, describeBreakpoint( created ) );
        }
        catch (CoreException e)
        {
            logger.error("Error toggling breakpoint", e);
            return BreakpointResponse.failed( projectName, typeName, lineNumber,
                    Diagnostic.fatal( DiagnosticCode.INTERNAL_ERROR,
                            "The breakpoint manager refused the change: " + e.getMessage() ) );
        }
    }

    /**
     * The registered Java line breakpoint at a type and line, or null when there is none.
     */
    private IJavaLineBreakpoint findLineBreakpoint( String typeName, int lineNumber ) throws CoreException
    {
        for ( IBreakpoint breakpoint : DebugPlugin.getDefault().getBreakpointManager().getBreakpoints() )
        {
            if ( breakpoint instanceof IJavaLineBreakpoint lineBreakpoint
                    && typeName.equals( lineBreakpoint.getTypeName() )
                    && lineNumber == lineBreakpoint.getLineNumber() )
            {
                return lineBreakpoint;
            }
        }
        return null;
    }

    /**
     * Checks that a breakpoint asked for at this location could ever bind.
     *
     * @return the response to send instead of creating anything, or null when the
     *         location is sound
     */
    private BreakpointResponse validateBreakpointLocation( IProject project, String projectName, String typeName,
            int lineNumber )
    {
        if ( !project.exists() || !project.isOpen() )
        {
            return BreakpointResponse.projectNotFound( projectName, typeName, lineNumber );
        }

        IType type = findBreakpointType( project, typeName );
        if ( type == null )
        {
            return BreakpointResponse.typeNotFound( projectName, typeName, lineNumber );
        }

        int totalLines = typeSourceLineCount( type );
        if ( lineNumber < 1 || ( totalLines > 0 && lineNumber > totalLines ) )
        {
            return BreakpointResponse.invalidLine( projectName, typeName, lineNumber, totalLines );
        }
        return null;
    }

    /**
     * The type a breakpoint names, or null when the project resolves none.
     * <p>
     * A breakpoint inside a nested or local class names the nested type; its source lives
     * in the enclosing type's file, so that is what is looked up when the nested name
     * does not resolve on its own.
     */
    private IType findBreakpointType( IProject project, String typeName )
    {
        try
        {
            IJavaProject javaProject = JavaCore.create( project );
            if ( javaProject == null || !javaProject.exists() )
            {
                return null;
            }
            IType type = javaProject.findType( typeName );
            int nested = typeName.indexOf( '$' );
            if ( type == null && nested > 0 )
            {
                type = javaProject.findType( typeName.substring( 0, nested ) );
            }
            return type;
        }
        catch ( CoreException e )
        {
            logger.error( "Error resolving type " + typeName, e );
            return null;
        }
    }

    /**
     * How many lines the type's source has, or -1 when there is no source to measure - a
     * binary type with no attachment. Counted by {@link LineOffsets}, whose line tracker
     * handles CRLF, rather than by splitting on '\n'.
     */
    private int typeSourceLineCount( IType type )
    {
        try
        {
            ICompilationUnit unit = type.getCompilationUnit();
            if ( unit == null )
            {
                return -1;
            }
            String source = unit.getSource();
            return source == null ? -1 : LineOffsets.countLines( source );
        }
        catch ( CoreException e )
        {
            return -1;
        }
    }

    /**
     * The breakpoints set in the workspace.
     */
    public BreakpointsResponse listBreakpoints()
    {
        var breakpoints = new ArrayList<BreakpointsResponse.BreakpointInfo>();

        for ( IBreakpoint breakpoint : DebugPlugin.getDefault().getBreakpointManager().getBreakpoints() )
        {
            breakpoints.add( describeBreakpoint( breakpoint ) );
        }

        return BreakpointsResponse.of( breakpoints );
    }

    private BreakpointsResponse.BreakpointInfo describeBreakpoint( IBreakpoint breakpoint )
    {
        String typeName  = null;
        String condition = null;
        int lineNumber   = -1;
        int hitCount     = 0;
        boolean enabled  = false;

        try
        {
            enabled = breakpoint.isEnabled();
            if ( breakpoint instanceof IJavaLineBreakpoint lineBreakpoint )
            {
                typeName   = lineBreakpoint.getTypeName();
                lineNumber = lineBreakpoint.getLineNumber();
                hitCount   = lineBreakpoint.getHitCount();
                String expression = lineBreakpoint.getCondition();
                condition = expression == null || expression.isBlank() ? null : expression;
            }
        }
        catch ( CoreException e )
        {
            // One unreadable breakpoint must not cost the caller the whole listing.
            logger.error( "Error reading breakpoint " + breakpoint, e );
        }

        SourceLocation location = breakpointSource( breakpoint, typeName );

        return new BreakpointsResponse.BreakpointInfo(
                location == null ? null : location.projectName(),
                location == null ? null : location.filePath(),
                typeName,
                lineNumber,
                enabled,
                condition,
                hitCount,
                breakpoint.getModelIdentifier() );
    }

    /** A workspace location in the form every reading and editing tool takes. */
    private record SourceLocation( String projectName, String filePath )
    {
    }

    /**
     * Where a breakpoint's source file is.
     * <p>
     * A breakpoint set from an editor carries the file on its marker. One created
     * against a project - which is what {@link #toggleBreakpoint} does - carries only
     * the project, so the file has to be resolved from the type name instead; without
     * that the caller is told a line number but not which file to open it in.
     */
    private SourceLocation breakpointSource( IBreakpoint breakpoint, String typeName )
    {
        IMarker marker = breakpoint.getMarker();
        if ( marker == null )
        {
            return null;
        }

        IResource resource = marker.getResource();
        if ( resource instanceof IFile file )
        {
            return new SourceLocation( file.getProject().getName(),
                    file.getProjectRelativePath().toString() );
        }
        if ( resource instanceof IProject project )
        {
            return new SourceLocation( project.getName(), typeSourcePath( project, typeName ) );
        }
        return null;
    }

    /**
     * The project-relative path of a type's source file, or null when the type is not in
     * this project's source - a breakpoint in a library type has no file to open.
     */
    private String typeSourcePath( IProject project, String typeName )
    {
        if ( typeName == null || typeName.isBlank() )
        {
            return null;
        }
        try
        {
            IJavaProject javaProject = JavaCore.create( project );
            if ( javaProject == null || !javaProject.exists() )
            {
                return null;
            }

            IType type = javaProject.findType( typeName );
            int nested = typeName.indexOf( '$' );
            if ( type == null && nested > 0 )
            {
                // A breakpoint inside a nested or local class names the nested type; its
                // source lives in the enclosing type's file.
                type = javaProject.findType( typeName.substring( 0, nested ) );
            }
            if ( type != null && type.getResource() instanceof IFile file )
            {
                return file.getProjectRelativePath().toString();
            }
        }
        catch ( CoreException e )
        {
            logger.error( "Error resolving the source file of type " + typeName, e );
        }
        return null;
    }

    /**
     * Removes all breakpoints from the workspace.
     * <p>
     * Deliberately still a {@code String}: the whole answer is one integer that
     * {@code listBreakpoints} can confirm, and a record wrapping one count would be
     * ceremony. What did change is the failure channel - a refusal now throws, so the
     * framework marks the result an error, instead of returning a sentence beginning
     * {@code "Error:"} that reads as success.
     *
     * @return how many breakpoints were removed
     */
    public String removeAllBreakpoints()
    {
        IBreakpoint[] breakpoints = DebugPlugin.getDefault().getBreakpointManager().getBreakpoints();
        try
        {
            DebugPlugin.getDefault().getBreakpointManager().removeBreakpoints(breakpoints, true);
        }
        catch (CoreException e)
        {
            logger.error("Error removing breakpoints", e);
            throw new IllegalStateException( "Could not remove the breakpoints: " + e.getMessage(), e );
        }
        return "Removed " + breakpoints.length + " breakpoint(s).";
    }

    /**
     * Where a debugged program is stopped: every thread of the first matching debug
     * session, and the frames of the suspended ones.
     * <p>
     * No matching session and a matching session that is running are both states, not
     * failures, and are reported as {@code sessionFound} and {@code anyThreadSuspended}
     * rather than as sentences the caller has to read.
     *
     * @param nameOrClass A substring to match against the debug launch
     */
    public StackTraceResponse getStackTrace( String nameOrClass )
    {
        Objects.requireNonNull( nameOrClass, "Name or class filter cannot be null" );

        String needle = nameOrClass.toLowerCase();

        for ( ILaunch launch : DebugPlugin.getDefault().getLaunchManager().getLaunches() )
        {
            if ( launch.isTerminated() || !ILaunchManager.DEBUG_MODE.equals( launch.getLaunchMode() ) )
            {
                continue;
            }

            ILaunchConfiguration configuration = launch.getLaunchConfiguration();
            String launchName = Optional.ofNullable( configuration )
                    .map( ILaunchConfiguration::getName ).orElse( "" );
            String mainType = Optional.ofNullable( configurationAttribute( configuration,
                    IJavaLaunchConfigurationConstants.ATTR_MAIN_TYPE_NAME ) ).orElse( "" );

            if ( !launchName.toLowerCase().contains( needle ) && !mainType.toLowerCase().contains( needle ) )
            {
                continue;
            }

            var threads = new ArrayList<StackTraceResponse.ThreadTrace>();
            try
            {
                for ( IDebugTarget target : launch.getDebugTargets() )
                {
                    for ( IThread thread : target.getThreads() )
                    {
                        threads.add( describeThread( launch, thread ) );
                    }
                }
            }
            catch ( DebugException e )
            {
                logger.error( "Error getting stack trace", e );
                throw new IllegalStateException( "Could not read the stack trace: " + e.getMessage(), e );
            }

            return StackTraceResponse.of( nameOrClass, launchName, mainType, threads );
        }

        return StackTraceResponse.notFound( nameOrClass );
    }

    private StackTraceResponse.ThreadTrace describeThread( ILaunch launch, IThread thread ) throws DebugException
    {
        if ( !thread.isSuspended() )
        {
            // A running thread has no stack to walk. That is a state, and the empty
            // frame list plus the flag say so without a word of prose.
            return new StackTraceResponse.ThreadTrace( thread.getName(), false, 0, List.of() );
        }

        IStackFrame[] stackFrames = thread.getStackFrames();
        var frames = new ArrayList<StackTraceResponse.Frame>( stackFrames.length );
        for ( int i = 0; i < stackFrames.length; i++ )
        {
            frames.add( describeFrame( launch, stackFrames[i], i ) );
        }
        return new StackTraceResponse.ThreadTrace( thread.getName(), true, frames.size(), frames );
    }

    private StackTraceResponse.Frame describeFrame( ILaunch launch, IStackFrame frame, int index )
    {
        String declaringType = null;
        String methodName    = null;
        int lineNumber       = -1;
        boolean nativeMethod = false;
        boolean synthetic    = false;

        try
        {
            lineNumber = frame.getLineNumber();
            if ( frame instanceof IJavaStackFrame javaFrame )
            {
                declaringType = javaFrame.getDeclaringTypeName();
                methodName    = javaFrame.getMethodName();
                nativeMethod  = javaFrame.isNative();
                synthetic     = javaFrame.isSynthetic();
            }
            else
            {
                methodName = frame.getName();
            }
        }
        catch ( DebugException e )
        {
            // A frame invalidated by the thread resuming under us costs its own detail,
            // not the rest of the trace.
            logger.error( "Error reading stack frame " + index, e );
        }

        SourceLocation location = frameSource( launch, frame );

        return new StackTraceResponse.Frame(
                index,
                declaringType,
                methodName,
                location == null ? null : location.projectName(),
                location == null ? null : location.filePath(),
                lineNumber,
                nativeMethod,
                synthetic,
                index == 0 ? frameVariables( frame ) : List.of() );
    }

    /**
     * The workspace file a stack frame came from.
     * <p>
     * The launch's own source locator is what Eclipse uses to open a frame, so it
     * already knows the source path this launch was configured with. A JRE or library
     * frame resolves to something that is not a workspace file - a class file, an
     * archive entry - and is reported with no location at all rather than with a path
     * the reading tools would reject.
     */
    private SourceLocation frameSource( ILaunch launch, IStackFrame frame )
    {
        ISourceLocator locator = launch.getSourceLocator();
        if ( locator == null )
        {
            return null;
        }

        Object element = locator.getSourceElement( frame );
        IResource resource = null;
        if ( element instanceof IResource found )
        {
            resource = found;
        }
        else if ( element instanceof IAdaptable adaptable )
        {
            resource = adaptable.getAdapter( IResource.class );
        }

        if ( resource == null || resource.getProject() == null )
        {
            return null;
        }
        return new SourceLocation( resource.getProject().getName(),
                resource.getProjectRelativePath().toString() );
    }

    /**
     * The locals visible in a frame. Reading them is a round trip to the VM per frame,
     * so only the frame execution is stopped in is worth the cost.
     */
    private List<StackTraceResponse.Variable> frameVariables( IStackFrame frame )
    {
        var variables = new ArrayList<StackTraceResponse.Variable>();
        try
        {
            for ( IVariable variable : frame.getVariables() )
            {
                String typeName = null;
                String value    = null;
                try
                {
                    typeName = variable.getReferenceTypeName();
                    value    = variable.getValue().getValueString();
                }
                catch ( DebugException e )
                {
                    // A value the VM will not render still has a name worth reporting.
                }
                variables.add( new StackTraceResponse.Variable( variable.getName(), typeName, value ) );
            }
        }
        catch ( DebugException e )
        {
            logger.error( "Error reading the variables of the top stack frame", e );
        }
        return variables;
    }

    /**
     * Resumes a suspended debug session and reports where it stops next.
     * <p>
     * "Run to the next breakpoint" asks the same question a step does - where is the
     * program counter now - so it answers in the same shape. The old
     * {@code "No active debug session found matching '…'"} was the one failure sentence
     * in this service not prefixed {@code "Error:"}, so a caller checking
     * {@code startsWith("Error:")} read a completely missed resume as done and then
     * waited at a breakpoint that would never be reached; that is now
     * {@link StepResponse.Status#SESSION_NOT_FOUND}.
     *
     * @param nameOrClass A substring to match against the debug launch
     * @param threadName Optional: the thread to resume. Blank resumes the whole session
     * @param timeout Seconds to wait for the next suspend (0 = do not wait)
     */
    public StepResponse resumeDebug(String nameOrClass, String threadName, int timeout)
    {
        return performStepAction(nameOrClass, threadName, StepResponse.Kind.RESUME, timeout);
    }

    /**
     * Steps over the current line and reports the line it lands on.
     *
     * @param nameOrClass A substring to match against the debug launch
     * @param threadName Optional: the thread to step. Blank takes the first suspended one
     * @param timeout Seconds to wait for the step to complete (0 = do not wait)
     */
    public StepResponse stepOver(String nameOrClass, String threadName, int timeout)
    {
        return performStepAction(nameOrClass, threadName, StepResponse.Kind.STEP_OVER, timeout);
    }

    /**
     * Steps into the call on the current line and reports the line it lands on.
     *
     * @param nameOrClass A substring to match against the debug launch
     * @param threadName Optional: the thread to step. Blank takes the first suspended one
     * @param timeout Seconds to wait for the step to complete (0 = do not wait)
     */
    public StepResponse stepInto(String nameOrClass, String threadName, int timeout)
    {
        return performStepAction(nameOrClass, threadName, StepResponse.Kind.STEP_INTO, timeout);
    }

    /**
     * Runs until the current method returns and reports the line it lands on.
     *
     * @param nameOrClass A substring to match against the debug launch
     * @param threadName Optional: the thread to step. Blank takes the first suspended one
     * @param timeout Seconds to wait for the step to complete (0 = do not wait)
     */
    public StepResponse stepReturn(String nameOrClass, String threadName, int timeout)
    {
        return performStepAction(nameOrClass, threadName, StepResponse.Kind.STEP_RETURN, timeout);
    }

    /**
     * One implementation for the three steps and the resume, because they have one
     * answer: the location the program is at afterwards.
     * <p>
     * The synchronisation is a bounded wait on the debug model's own
     * {@link DebugEvent#SUSPEND}, registered <em>before</em> the request is issued so the
     * event cannot be missed. What it replaces was {@code Thread.sleep(500)}, which meant
     * a step taking longer than half a second reported {@code "Thread is running."} and
     * the caller concluded the program had resumed.
     */
    private StepResponse performStepAction(String nameOrClass, String threadName, StepResponse.Kind kind,
            int timeoutSeconds)
    {
        Objects.requireNonNull(nameOrClass, "Name or class filter cannot be null");

        ILaunch launch = findDebugLaunch(nameOrClass);
        if (launch == null)
        {
            return StepResponse.sessionNotFound(kind, nameOrClass);
        }

        String launchName = launchName( launch );
        boolean named = threadName != null && !threadName.isBlank();

        try
        {
            IThread thread = findSuspendedThread( launch, threadName );
            if ( thread == null )
            {
                return named && !hasThreadNamed( launch, threadName )
                        ? StepResponse.threadNotFound( kind, nameOrClass, launchName, threadName )
                        : StepResponse.noSuspendedThread( kind, nameOrClass, launchName );
            }

            final IThread stepped   = thread;
            final IDebugTarget target = thread.getDebugTarget();
            final String steppedName = thread.getName();
            final boolean wholeSession = kind == StepResponse.Kind.RESUME && !named;

            if ( !canPerform( stepped, kind ) )
            {
                return StepResponse.failed( kind, nameOrClass, launchName, steppedName,
                        Diagnostic.fatal( DiagnosticCode.INTERNAL_ERROR,
                                "Thread '" + steppedName + "' cannot " + kind.name().toLowerCase().replace( '_', ' ' )
                                        + " in its current state." ) );
            }

            // A resume of the whole session can stop in any of its threads; a step stops
            // in the thread it was issued on.
            Predicate<DebugEvent> accept = wholeSession
                    ? event -> isSuspendOf( event, target ) || isTerminationOf( event, target )
                    : event -> isSuspendOf( event, stepped ) || isTerminationOf( event, target )
                            || isTerminationOf( event, stepped );

            long timeoutMillis = Math.max( 0, timeoutSeconds ) * 1000L;
            long started = System.currentTimeMillis();

            awaitDebugEvent( accept, () -> perform( launch, stepped, kind, wholeSession ), timeoutMillis );

            long waited = System.currentTimeMillis() - started;

            if ( timeoutMillis == 0 )
            {
                return StepResponse.running( kind, nameOrClass, launchName, steppedName );
            }
            if ( launch.isTerminated() || target.isTerminated() )
            {
                return StepResponse.terminated( kind, nameOrClass, launchName, steppedName, waited );
            }

            IThread stopped = stepped.isSuspended() ? stepped : findSuspendedThread( launch, null );
            if ( stopped == null )
            {
                return StepResponse.timedOut( kind, nameOrClass, launchName, steppedName, waited,
                        Diagnostic.retryable( DiagnosticCode.INTERNAL_ERROR,
                                "'" + launchName + "' had not suspended again after " + waited
                                        + " ms; retry with a longer timeout, or call getStackTrace to check." ) );
            }

            IStackFrame[] frames = stopped.getStackFrames();
            StackTraceResponse.Frame frame = frames.length > 0 ? describeFrame( launch, frames[0], 0 ) : null;

            return StepResponse.suspendedAt( kind, nameOrClass, launchName, stopped.getName(), frame, waited );
        }
        catch (CoreException e)
        {
            logger.error("Error performing " + kind, e);
            return StepResponse.failed( kind, nameOrClass, launchName, threadName,
                    Diagnostic.fatal( DiagnosticCode.INTERNAL_ERROR,
                            "The debug model refused the request: " + e.getMessage() ) );
        }
    }

    /** Whether the thread can do what was asked right now. */
    private boolean canPerform( IThread thread, StepResponse.Kind kind ) throws DebugException
    {
        return switch ( kind )
        {
            case STEP_OVER   -> thread.canStepOver();
            case STEP_INTO   -> thread.canStepInto();
            case STEP_RETURN -> thread.canStepReturn();
            case RESUME      -> thread.canResume();
        };
    }

    /** Issues the request. Called with the suspend listener already registered. */
    private void perform( ILaunch launch, IThread thread, StepResponse.Kind kind, boolean wholeSession )
            throws DebugException
    {
        switch ( kind )
        {
            case STEP_OVER   -> thread.stepOver();
            case STEP_INTO   -> thread.stepInto();
            case STEP_RETURN -> thread.stepReturn();
            case RESUME      ->
            {
                if ( wholeSession )
                {
                    for ( IDebugTarget target : launch.getDebugTargets() )
                    {
                        target.resume();
                    }
                }
                else
                {
                    thread.resume();
                }
            }
        }
    }

    /** Something a debug request does, which the debug model may refuse. */
    @FunctionalInterface
    private interface DebugAction
    {
        void run() throws CoreException;
    }

    /**
     * Issues a debug request and waits for the model to report the event it produces.
     * <p>
     * The listener is registered before the request, so an event that arrives while the
     * request is still returning is still seen - the ordering the old
     * {@code Thread.sleep} could not guarantee at any duration.
     *
     * @param timeoutMillis 0 means do not wait at all - issue the request and return
     * @return whether the awaited event arrived
     */
    private boolean awaitDebugEvent( Predicate<DebugEvent> accept, DebugAction request, long timeoutMillis )
            throws CoreException
    {
        var latch = new CountDownLatch( 1 );

        IDebugEventSetListener listener = events ->
        {
            for ( DebugEvent event : events )
            {
                if ( accept.test( event ) )
                {
                    latch.countDown();
                    return;
                }
            }
        };

        DebugPlugin.getDefault().addDebugEventListener( listener );
        try
        {
            request.run();
            return timeoutMillis > 0 && latch.await( timeoutMillis, TimeUnit.MILLISECONDS );
        }
        catch ( InterruptedException e )
        {
            Thread.currentThread().interrupt();
            return false;
        }
        finally
        {
            DebugPlugin.getDefault().removeDebugEventListener( listener );
        }
    }

    /**
     * Whether an event is {@code element} - or one of its threads - suspending.
     * <p>
     * Evaluation suspends are filtered out: the debug model suspends and resumes a thread
     * to run an expression, and those events say nothing about where the program is.
     */
    private boolean isSuspendOf( DebugEvent event, Object element )
    {
        if ( event.getKind() != DebugEvent.SUSPEND || event.isEvaluation() )
        {
            return false;
        }
        if ( event.getSource() == element )
        {
            return true;
        }
        return element instanceof IDebugTarget target
                && event.getSource() instanceof IDebugElement source
                && source.getDebugTarget() == target;
    }

    private boolean isTerminationOf( DebugEvent event, Object element )
    {
        return event.getKind() == DebugEvent.TERMINATE && event.getSource() == element;
    }

    /**
     * The thread a step or resume acts on: the one named, or the first suspended one when
     * no name was given. Null when nothing suitable is suspended.
     */
    private IThread findSuspendedThread( ILaunch launch, String threadName ) throws DebugException
    {
        boolean named = threadName != null && !threadName.isBlank();

        for ( IDebugTarget target : launch.getDebugTargets() )
        {
            for ( IThread thread : target.getThreads() )
            {
                if ( thread.isSuspended() && ( !named || matchesThreadName( thread.getName(), threadName ) ) )
                {
                    return thread;
                }
            }
        }
        return null;
    }

    /** Whether the session has a thread of that name at all, suspended or not. */
    private boolean hasThreadNamed( ILaunch launch, String threadName ) throws DebugException
    {
        for ( IDebugTarget target : launch.getDebugTargets() )
        {
            for ( IThread thread : target.getThreads() )
            {
                if ( matchesThreadName( thread.getName(), threadName ) )
                {
                    return true;
                }
            }
        }
        return false;
    }

    /** Exact name first, then a case-insensitive substring, as the launch lookup does. */
    private boolean matchesThreadName( String actual, String requested )
    {
        return actual != null
                && ( actual.equals( requested ) || actual.toLowerCase().contains( requested.toLowerCase() ) );
    }

    /** The debug session's configuration name, or {@code "unknown"} when it has none. */
    private String launchName( ILaunch launch )
    {
        return Optional.ofNullable( launch.getLaunchConfiguration() )
                .map( ILaunchConfiguration::getName ).orElse( "unknown" );
    }

    /** How long the VM is given to answer an evaluation before we stop waiting. */
    private static final long EVALUATION_TIMEOUT_MILLIS = 10_000L;

    /**
     * Evaluates a Java expression in a suspended frame.
     * <p>
     * A successful evaluation and a compile error in the expression used to land in the
     * same field of the same sentence, distinguished only by the prefix
     * {@code "Evaluation error:"}, so a caller pretty-printing the answer presented a
     * compile failure as the value. Value and type were joined as
     * {@code value + " (" + type + ")"}, which is not invertible for any object whose
     * {@code toString()} contains a parenthesis. The frame and thread used are now named,
     * where before the tool silently took {@code frames[0]} of the first suspended thread
     * it found.
     *
     * @param nameOrClass A substring to match against the debug launch
     * @param expression The Java expression to evaluate
     * @param threadName Optional: the suspended thread whose top frame to use. Blank
     *            takes the first suspended thread
     */
    public EvaluationResponse evaluateExpression(String nameOrClass, String expression, String threadName)
    {
        Objects.requireNonNull(nameOrClass, "Name or class filter cannot be null");
        Objects.requireNonNull(expression, "Expression cannot be null");

        ILaunch launch = findDebugLaunch(nameOrClass);
        if (launch == null)
        {
            return EvaluationResponse.sessionNotFound( nameOrClass, expression );
        }

        String launchName = launchName( launch );
        boolean named = threadName != null && !threadName.isBlank();

        IAstEvaluationEngine engine = null;
        try
        {
            IThread thread = findSuspendedThread( launch, threadName );
            if ( thread == null )
            {
                return named && !hasThreadNamed( launch, threadName )
                        ? EvaluationResponse.threadNotFound( nameOrClass, expression, launchName, threadName )
                        : EvaluationResponse.noSuspendedThread( nameOrClass, expression, launchName );
            }

            String usedThread = thread.getName();

            if ( !( thread instanceof IJavaThread )
                    || !( thread.getDebugTarget() instanceof IJavaDebugTarget javaTarget ) )
            {
                return EvaluationResponse.failed( nameOrClass, expression, launchName, usedThread, null,
                        Diagnostic.fatal( DiagnosticCode.INTERNAL_ERROR,
                                "'" + launchName + "' is not a Java debug session; expressions cannot be evaluated in it." ) );
            }

            IStackFrame[] frames = thread.getStackFrames();
            if ( frames.length == 0 || !( frames[0] instanceof IJavaStackFrame javaFrame ) )
            {
                return EvaluationResponse.failed( nameOrClass, expression, launchName, usedThread, null,
                        Diagnostic.fatal( DiagnosticCode.INTERNAL_ERROR,
                                "Thread '" + usedThread + "' is suspended but exposes no Java frame to evaluate in." ) );
            }

            StackTraceResponse.Frame frame = describeFrame( launch, frames[0], 0 );

            IJavaProject javaProject = evaluationProject( launch );
            if ( javaProject == null )
            {
                return EvaluationResponse.failed( nameOrClass, expression, launchName, usedThread, frame,
                        Diagnostic.fatal( DiagnosticCode.PROJECT_NOT_FOUND,
                                "No Java project could be resolved to compile the expression against." ) );
            }

            engine = EvaluationManager.newAstEvaluationEngine( javaProject, javaTarget );

            var latch  = new CountDownLatch( 1 );
            var holder = new AtomicReference<IEvaluationResult>();

            engine.evaluate( expression, javaFrame, result ->
            {
                holder.set( result );
                latch.countDown();
            }, DebugEvent.EVALUATION_IMPLICIT, false );

            if ( !latch.await( EVALUATION_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS ) )
            {
                return EvaluationResponse.timedOut( nameOrClass, expression, launchName, usedThread, frame,
                        EVALUATION_TIMEOUT_MILLIS,
                        Diagnostic.retryable( DiagnosticCode.INTERNAL_ERROR,
                                "The VM did not answer the evaluation within " + EVALUATION_TIMEOUT_MILLIS + " ms." ) );
            }

            return describeEvaluation( nameOrClass, expression, launchName, usedThread, frame, holder.get() );
        }
        catch ( InterruptedException e )
        {
            Thread.currentThread().interrupt();
            return EvaluationResponse.failed( nameOrClass, expression, launchName, threadName, null,
                    Diagnostic.retryable( DiagnosticCode.INTERNAL_ERROR, "The evaluation was interrupted." ) );
        }
        catch ( CoreException e )
        {
            logger.error( "Error evaluating expression", e );
            return EvaluationResponse.failed( nameOrClass, expression, launchName, threadName, null,
                    Diagnostic.fatal( DiagnosticCode.INTERNAL_ERROR,
                            "The debug model refused the evaluation: " + e.getMessage() ) );
        }
        finally
        {
            if ( engine != null )
            {
                engine.dispose();
            }
        }
    }

    /**
     * Turns JDT's evaluation result into the answer.
     * <p>
     * An expression that threw and an expression that did not compile are different
     * things for a caller to do something about, and JDT reports both through
     * {@code hasErrors()}. {@code getException()} is what tells them apart.
     */
    private EvaluationResponse describeEvaluation( String nameOrClass, String expression, String launchName,
            String threadName, StackTraceResponse.Frame frame, IEvaluationResult result )
    {
        if ( result == null )
        {
            return EvaluationResponse.failed( nameOrClass, expression, launchName, threadName, frame,
                    Diagnostic.fatal( DiagnosticCode.INTERNAL_ERROR,
                            "The evaluation engine reported completion without a result." ) );
        }

        if ( result.getException() != null )
        {
            return EvaluationResponse.failed( nameOrClass, expression, launchName, threadName, frame,
                    Diagnostic.fatal( DiagnosticCode.INTERNAL_ERROR,
                            "The expression threw " + result.getException().toString() ) );
        }

        if ( result.hasErrors() )
        {
            String[] messages = result.getErrorMessages();
            return EvaluationResponse.compileError( nameOrClass, expression, launchName, threadName, frame,
                    messages == null ? List.of() : List.of( messages ) );
        }

        IJavaValue value = result.getValue();
        if ( value == null )
        {
            return EvaluationResponse.of( nameOrClass, expression, launchName, threadName, frame, null, null, true );
        }

        String rendered     = null;
        String declaredType = null;
        boolean isNull      = false;
        try
        {
            rendered     = value.getValueString();
            declaredType = value.getReferenceTypeName();
            // getJavaType() is null exactly for the null reference, which getValueString()
            // renders as the four characters "null" - the same as a String holding them.
            isNull       = value.getJavaType() == null;
        }
        catch ( DebugException e )
        {
            logger.error( "Error reading the evaluated value", e );
        }

        return EvaluationResponse.of( nameOrClass, expression, launchName, threadName, frame,
                rendered, declaredType, isNull );
    }

    /**
     * The Java project the expression is compiled against: the one the launch names, or
     * failing that the first in the workspace, which is what the compiler needs to
     * resolve names the frame's own classpath does not.
     */
    private IJavaProject evaluationProject( ILaunch launch )
    {
        String projectName = configurationAttribute( launch.getLaunchConfiguration(),
                IJavaLaunchConfigurationConstants.ATTR_PROJECT_NAME );
        if ( projectName != null )
        {
            IJavaProject javaProject = JavaCore.create(
                    ResourcesPlugin.getWorkspace().getRoot().getProject( projectName ) );
            if ( javaProject != null && javaProject.exists() )
            {
                return javaProject;
            }
        }

        try
        {
            IJavaProject[] projects = JavaCore.create( ResourcesPlugin.getWorkspace().getRoot() ).getJavaProjects();
            return projects.length > 0 ? projects[0] : null;
        }
        catch ( CoreException e )
        {
            return null;
        }
    }

    /**
     * Sets a breakpoint that only triggers when a condition holds.
     * <p>
     * The old rendering was {@code Type:line … Condition: i > 100}, which a caller had to
     * split on a ':' that also occurs inside the condition - the exact defect
     * {@link BreakpointsResponse} records as already fixed for the read side. The
     * breakpoint is now reported in that same read shape, condition in its own field.
     *
     * @param projectName The project name
     * @param typeName The fully qualified type name
     * @param lineNumber The 1-based line number
     * @param condition The Java boolean expression that must evaluate to true for the breakpoint to trigger
     * @param hitCount Optional hit count (breakpoint triggers only after being hit N times)
     */
    public BreakpointResponse setConditionalBreakpoint(String projectName, String typeName, int lineNumber,
                                           String condition, int hitCount)
    {
        Objects.requireNonNull(projectName, "Project name cannot be null");
        Objects.requireNonNull(typeName, "Type name cannot be null");
        Objects.requireNonNull(condition, "Condition cannot be null");

        try
        {
            IProject project = ResourcesPlugin.getWorkspace().getRoot().getProject(projectName);
            BreakpointResponse invalid = validateBreakpointLocation( project, projectName, typeName, lineNumber );
            if ( invalid != null )
            {
                return invalid;
            }

            IJavaLineBreakpoint existing = findLineBreakpoint( typeName, lineNumber );
            boolean replaced = existing != null;
            if ( replaced )
            {
                existing.delete();
            }

            IJavaLineBreakpoint bp = JDIDebugModel.createLineBreakpoint(
                    project, typeName, lineNumber, -1, -1, 0, true, null);

            bp.setCondition(condition);
            bp.setConditionEnabled(true);

            if (hitCount > 0)
            {
                bp.setHitCount(hitCount);
            }

            BreakpointsResponse.BreakpointInfo info = describeBreakpoint( bp );
            return replaced
                    ? BreakpointResponse.replaced( projectName, typeName, lineNumber, info )
                    : BreakpointResponse.set( projectName, typeName, lineNumber, info );
        }
        catch (CoreException e)
        {
            logger.error("Error setting conditional breakpoint", e);
            return BreakpointResponse.failed( projectName, typeName, lineNumber,
                    Diagnostic.fatal( DiagnosticCode.INTERNAL_ERROR,
                            "The breakpoint manager refused the change: " + e.getMessage() ) );
        }
    }

    /** How long the VM is given to report a hot code replace after the build finishes. */
    private static final long HOT_CODE_REPLACE_TIMEOUT_MILLIS = 30_000L;

    /**
     * Rebuilds the debugged project and reports whether the new bytecode reached the VM.
     * <p>
     * What this replaces triggered a build and returned "Hot code replace triggered" -
     * an intention, not an observation - so a replace that failed on a schema change and
     * one that worked produced the same sentence, and the caller then debugged stale
     * bytecode believing it was new. The outcome now comes from JDT's own
     * {@link IJavaHotCodeReplaceListener}, registered before the build so the
     * notification cannot be missed.
     * <p>
     * It also builds only the project the launch names. Building the whole workspace to
     * answer a question about one debug session made every unrelated project's build the
     * caller's problem.
     *
     * @param nameOrClass A substring to match against the debug launch
     */
    public HotCodeReplaceResponse hotCodeReplace(String nameOrClass)
    {
        Objects.requireNonNull(nameOrClass, "Name or class filter cannot be null");

        ILaunch launch = findDebugLaunch(nameOrClass);
        if (launch == null)
        {
            return HotCodeReplaceResponse.sessionNotFound( nameOrClass );
        }

        String launchName = launchName( launch );

        IJavaDebugTarget javaTarget = null;
        for ( IDebugTarget target : launch.getDebugTargets() )
        {
            if ( target instanceof IJavaDebugTarget found )
            {
                javaTarget = found;
                break;
            }
        }
        if ( javaTarget == null )
        {
            return HotCodeReplaceResponse.noJavaTarget( nameOrClass, launchName );
        }

        String requestedProject = configurationAttribute( launch.getLaunchConfiguration(),
                IJavaLaunchConfigurationConstants.ATTR_PROJECT_NAME );

        if ( !javaTarget.supportsHotCodeReplace() )
        {
            return HotCodeReplaceResponse.notSupported( nameOrClass, launchName, requestedProject );
        }

        var latch    = new CountDownLatch( 1 );
        var status   = new AtomicReference<HotCodeReplaceResponse.Status>();
        var failure  = new AtomicReference<DebugException>();
        var obsolete = new AtomicBoolean( false );

        IJavaHotCodeReplaceListener listener = new IJavaHotCodeReplaceListener()
        {
            @Override
            public void hotCodeReplaceSucceeded( IJavaDebugTarget target )
            {
                status.compareAndSet( null, HotCodeReplaceResponse.Status.SUCCEEDED );
                latch.countDown();
            }

            @Override
            public void hotCodeReplaceFailed( IJavaDebugTarget target, DebugException exception )
            {
                // A null exception is JDT's way of saying the VM cannot hot swap at all.
                status.compareAndSet( null, exception == null
                        ? HotCodeReplaceResponse.Status.NOT_SUPPORTED
                        : HotCodeReplaceResponse.Status.FAILED );
                failure.set( exception );
                latch.countDown();
            }

            @Override
            public void obsoleteMethods( IJavaDebugTarget target )
            {
                obsolete.set( true );
                latch.countDown();
            }
        };

        long started = System.currentTimeMillis();
        // Registered on the target rather than through JDIDebugModel: a target-scoped
        // listener is guaranteed to be notified, where a model-wide one is skipped for
        // any target that has its own.
        javaTarget.addHotCodeReplaceListener( listener );
        try
        {
            String builtProject = buildForHotCodeReplace( requestedProject );
            boolean reported = latch.await( HOT_CODE_REPLACE_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS );
            long waited = System.currentTimeMillis() - started;

            if ( !reported )
            {
                // No notification at all. Ask the VM itself whether there was anything to
                // replace: in sync means nothing needed doing, which is the ordinary case
                // with autobuild on.
                return javaTarget.isOutOfSynch()
                        ? HotCodeReplaceResponse.timedOut( nameOrClass, launchName, builtProject, waited,
                                Diagnostic.retryable( DiagnosticCode.INTERNAL_ERROR,
                                        "The VM is out of sync with the workspace and reported no replace within "
                                                + waited + " ms." ) )
                        : HotCodeReplaceResponse.inSync( nameOrClass, launchName, builtProject, waited );
            }

            HotCodeReplaceResponse.Status reportedStatus = status.get();
            if ( reportedStatus == HotCodeReplaceResponse.Status.FAILED )
            {
                DebugException cause = failure.get();
                return HotCodeReplaceResponse.failed( nameOrClass, launchName, builtProject, waited,
                        Diagnostic.fatal( DiagnosticCode.INTERNAL_ERROR,
                                "The VM refused the new bytecode: "
                                        + ( cause == null ? "no reason given" : cause.getMessage() )
                                        + ". The running code is unchanged." ) );
            }
            if ( reportedStatus == HotCodeReplaceResponse.Status.NOT_SUPPORTED )
            {
                return HotCodeReplaceResponse.notSupported( nameOrClass, launchName, builtProject );
            }

            return obsolete.get() || hasObsoleteFrames( javaTarget )
                    ? HotCodeReplaceResponse.obsoleteMethods( nameOrClass, launchName, builtProject, waited )
                    : HotCodeReplaceResponse.succeeded( nameOrClass, launchName, builtProject, waited );
        }
        catch ( InterruptedException e )
        {
            Thread.currentThread().interrupt();
            return HotCodeReplaceResponse.timedOut( nameOrClass, launchName, requestedProject,
                    System.currentTimeMillis() - started,
                    Diagnostic.retryable( DiagnosticCode.INTERNAL_ERROR, "The wait was interrupted." ) );
        }
        catch ( CoreException e )
        {
            logger.error("Error performing hot code replace", e);
            return HotCodeReplaceResponse.failed( nameOrClass, launchName, requestedProject,
                    System.currentTimeMillis() - started,
                    Diagnostic.fatal( DiagnosticCode.INTERNAL_ERROR,
                            "The build ahead of the replace failed: " + e.getMessage() ) );
        }
        finally
        {
            javaTarget.removeHotCodeReplaceListener( listener );
        }
    }

    /**
     * Builds the project the launch names, falling back to the workspace when it names
     * none.
     *
     * @return the project that was built, or null when the whole workspace was
     */
    private String buildForHotCodeReplace( String projectName ) throws CoreException
    {
        if ( projectName != null && !projectName.isBlank() )
        {
            IProject project = ResourcesPlugin.getWorkspace().getRoot().getProject( projectName );
            if ( project.exists() && project.isOpen() )
            {
                project.build( IncrementalProjectBuilder.INCREMENTAL_BUILD, new NullProgressMonitor() );
                return projectName;
            }
        }
        ResourcesPlugin.getWorkspace().build( IncrementalProjectBuilder.INCREMENTAL_BUILD,
                new NullProgressMonitor() );
        return null;
    }

    /**
     * Whether any suspended frame is still running pre-replace bytecode.
     * <p>
     * Asked directly rather than inferred from notification order: JDT reports
     * {@code hotCodeReplaceSucceeded} and {@code obsoleteMethods} as separate events, and
     * whichever arrives first would otherwise decide the answer.
     */
    private boolean hasObsoleteFrames( IJavaDebugTarget target )
    {
        try
        {
            for ( IThread thread : target.getThreads() )
            {
                if ( !thread.isSuspended() )
                {
                    continue;
                }
                for ( IStackFrame frame : thread.getStackFrames() )
                {
                    if ( frame instanceof IJavaStackFrame javaFrame && javaFrame.isObsolete() )
                    {
                        return true;
                    }
                }
            }
        }
        catch ( DebugException e )
        {
            logger.error( "Error checking for obsolete frames", e );
        }
        return false;
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
}
