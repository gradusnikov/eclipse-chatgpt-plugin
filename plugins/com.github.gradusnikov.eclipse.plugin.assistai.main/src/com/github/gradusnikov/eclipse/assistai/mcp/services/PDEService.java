package com.github.gradusnikov.eclipse.assistai.mcp.services;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IWorkspace;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.ILog;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.core.runtime.Path;
import org.eclipse.core.runtime.jobs.IJobChangeEvent;
import org.eclipse.core.runtime.jobs.JobChangeAdapter;
import org.eclipse.debug.core.DebugException;
import org.eclipse.debug.core.DebugPlugin;
import org.eclipse.debug.core.ILaunch;
import org.eclipse.debug.core.ILaunchConfiguration;
import org.eclipse.debug.core.ILaunchConfigurationType;
import org.eclipse.debug.core.ILaunchConfigurationWorkingCopy;
import org.eclipse.debug.core.ILaunchManager;
import org.eclipse.debug.core.model.IProcess;
import org.eclipse.e4.core.di.annotations.Creatable;
import org.eclipse.e4.ui.di.UISynchronize;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.IPackageFragment;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.core.JavaModelException;
import org.eclipse.jdt.junit.JUnitCore;
import org.eclipse.jdt.junit.TestRunListener;
import org.eclipse.jdt.junit.model.ITestCaseElement;
import org.eclipse.jdt.junit.model.ITestRunSession;
import org.eclipse.jdt.launching.IJavaLaunchConfigurationConstants;
import org.eclipse.pde.core.target.ITargetDefinition;
import org.eclipse.pde.core.target.ITargetHandle;
import org.eclipse.pde.core.target.ITargetPlatformService;
import org.eclipse.pde.core.target.LoadTargetDefinitionJob;
import org.eclipse.pde.launching.IPDELauncherConstants;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;

/**
 * Service providing PDE (Plugin Development Environment) related operations:
 * target platform management and JUnit Plug-in Test execution.
 */
@Creatable
@Singleton
public class PDEService
{
    @Inject
    private ILog logger;

    @Inject
    private UISynchronize sync;

    @Inject
    private CoverageService coverageService;

    @Inject
    private TestRunManager testRunManager;

    // -------------------------------------------------------------------------
    // Target platform
    // -------------------------------------------------------------------------

    /**
     * Returns a description of the currently active target platform.
     */
    public String getActiveTarget()
    {
        try
        {
            ITargetPlatformService service = getTargetPlatformService();
            ITargetHandle handle = service.getWorkspaceTargetHandle();

            if ( handle == null )
            {
                return "Active target: <running platform> (no explicit target file set)";
            }

            ITargetDefinition definition = handle.getTargetDefinition();
            String name = definition.getName() != null ? definition.getName() : "<unnamed>";

            StringBuilder sb = new StringBuilder();
            sb.append( "Active target: " ).append( name ).append( "\n" );
            sb.append( "Memento: " ).append( handle.getMemento() ).append( "\n" );
            sb.append( "Exists: " ).append( handle.exists() ).append( "\n" );
            sb.append( "Resolved: " ).append( definition.isResolved() ).append( "\n" );

            if ( definition.isResolved() )
            {
                var bundles = definition.getBundles();
                sb.append( "Bundle count: " ).append( bundles != null ? bundles.length : 0 ).append( "\n" );
            }

            return sb.toString();
        }
        catch ( CoreException e )
        {
            return "Error getting active target: " + e.getMessage();
        }
    }

    /**
     * Sets the active target platform from a workspace-relative .target file path.
     */
    public String setActiveTarget( String targetFilePath )
    {
        Objects.requireNonNull( targetFilePath, "Target file path cannot be null" );

        try
        {
            IWorkspace workspace = ResourcesPlugin.getWorkspace();
            IFile file = workspace.getRoot().getFile( new Path( targetFilePath ) );

            if ( !file.exists() )
            {
                return "Error: Target file not found in workspace: " + targetFilePath;
            }

            ITargetPlatformService service = getTargetPlatformService();
            ITargetHandle handle = service.getTarget( file );
            ITargetDefinition definition = handle.getTargetDefinition();

            CountDownLatch latch = new CountDownLatch( 1 );
            String[] result = { null };

            LoadTargetDefinitionJob.load( definition, new JobChangeAdapter()
            {
                @Override
                public void done( IJobChangeEvent event )
                {
                    result[0] = event.getResult().isOK()
                        ? "Target platform set to: " + definition.getName() + " (" + targetFilePath + ")"
                        : "Error setting target platform: " + event.getResult().getMessage();
                    latch.countDown();
                }
            } );

            boolean completed = latch.await( 120, TimeUnit.SECONDS );
            if ( !completed )
            {
                return "Error: Timed out waiting for target platform to load.";
            }
            return result[0];
        }
        catch ( Exception e )
        {
            return "Error setting active target: " + e.getMessage();
        }
    }

    /**
     * Reloads the currently active target platform.
     */
    public String reloadTarget()
    {
        try
        {
            ITargetPlatformService service = getTargetPlatformService();
            ITargetHandle handle = service.getWorkspaceTargetHandle();

            if ( handle == null )
            {
                return "Error: No explicit target platform is set. Nothing to reload.";
            }

            ITargetDefinition definition = handle.getTargetDefinition();
            CountDownLatch latch = new CountDownLatch( 1 );
            String[] result = { null };

            LoadTargetDefinitionJob.load( definition, new JobChangeAdapter()
            {
                @Override
                public void done( IJobChangeEvent event )
                {
                    result[0] = event.getResult().isOK()
                        ? "Target platform reloaded: " + definition.getName()
                        : "Error reloading target platform: " + event.getResult().getMessage();
                    latch.countDown();
                }
            } );

            boolean completed = latch.await( 120, TimeUnit.SECONDS );
            if ( !completed )
            {
                return "Error: Timed out waiting for target platform to reload.";
            }
            return result[0];
        }
        catch ( Exception e )
        {
            return "Error reloading target: " + e.getMessage();
        }
    }

    // -------------------------------------------------------------------------
    // JUnit Plug-in Test - async public API
    // -------------------------------------------------------------------------

    /**
     * Starts all JUnit Plug-in Tests in the given project asynchronously.
     * @return run ID - use getTestRunStatus (eclipse-ide server) to poll for results
     */
    public String startJUnitPluginTests( String projectName, boolean withCoverage,
                                         boolean includeAllPlugins, List<String> additionalBundles )
    {
        return startJUnitPluginTests( projectName, withCoverage, includeAllPlugins, additionalBundles, null );
    }

    /**
     * Starts all JUnit Plug-in Tests in the given project asynchronously, optionally based on a
     * named launch configuration. When {@code launcherName} is provided the saved config is used as
     * a base and only the project/container attributes are overridden.
     *
     * @param launcherName optional saved launch config name (use (eclipse-runner MCP server).listLaunchConfigurations to find it)
     * @return run ID - use getTestRunStatus (eclipse-ide server) to poll for results
     */
    public String startJUnitPluginTests( String projectName, boolean withCoverage,
                                         boolean includeAllPlugins, List<String> additionalBundles,
                                         String launcherName )
    {
        Objects.requireNonNull( projectName, "Project name cannot be null" );
        if ( projectName.isEmpty() )
        {
            throw new IllegalArgumentException( "Project name cannot be empty" );
        }
        try
        {
            IJavaProject javaProject = getJavaProject( projectName );
            return startPluginTests( javaProject, null, null, withCoverage, includeAllPlugins,
                additionalBundles, "Running all plug-in tests in " + projectName, launcherName );
        }
        catch ( CoreException e )
        {
            throw new RuntimeException( "Error starting plug-in tests: " + e.getMessage(), e );
        }
    }

    /**
     * Starts JUnit Plug-in Tests for a specific class asynchronously.
     * @return run ID - use getTestRunStatus (eclipse-ide server) to poll for results
     */
    public String startJUnitPluginTestClass( String projectName, String className,
                                              boolean withCoverage, boolean includeAllPlugins,
                                              List<String> additionalBundles )
    {
        return startJUnitPluginTestClass( projectName, className, withCoverage, includeAllPlugins,
            additionalBundles, null );
    }

    /**
     * Starts JUnit Plug-in Tests for a specific class asynchronously, optionally based on a named
     * launch configuration. When {@code launcherName} is provided the saved config is used as a base
     * and only the project/class attributes are overridden.
     *
     * @param launcherName optional saved launch config name (use (eclipse-runner MCP server).listLaunchConfigurations to find it)
     * @return run ID - use getTestRunStatus (eclipse-ide server) to poll for results
     */
    public String startJUnitPluginTestClass( String projectName, String className,
                                              boolean withCoverage, boolean includeAllPlugins,
                                              List<String> additionalBundles, String launcherName )
    {
        Objects.requireNonNull( projectName, "Project name cannot be null" );
        Objects.requireNonNull( className, "Class name cannot be null" );
        if ( projectName.isEmpty() )
        {
            throw new IllegalArgumentException( "Project name cannot be empty" );
        }
        try
        {
            IJavaProject javaProject = getJavaProject( projectName );
            IType type = javaProject.findType( className );
            if ( type == null )
            {
                throw new RuntimeException(
                    "Class '" + className + "' not found in project '" + projectName + "'" );
            }
            return startPluginTests( javaProject, type, null, withCoverage, includeAllPlugins,
                additionalBundles, "Running " + className + " in " + projectName, launcherName );
        }
        catch ( CoreException e )
        {
            throw new RuntimeException( "Error starting plug-in tests: " + e.getMessage(), e );
        }
    }

    /**
     * Starts JUnit Plug-in Tests for a specific package asynchronously.
     * @return run ID - use getTestRunStatus (eclipse-ide server) to poll for results
     */
    public String startJUnitPluginTestPackage( String projectName, String packageName,
                                               boolean withCoverage, boolean includeAllPlugins,
                                               List<String> additionalBundles )
    {
        return startJUnitPluginTestPackage( projectName, packageName, withCoverage, includeAllPlugins,
            additionalBundles, null );
    }

    /**
     * Starts JUnit Plug-in Tests for a specific package asynchronously, optionally based on a named
     * launch configuration. When {@code launcherName} is provided the saved config is used as a base
     * and only the project/package attributes are overridden.
     *
     * @param launcherName optional saved launch config name (use (eclipse-runner MCP server).listLaunchConfigurations to find it)
     * @return run ID - use getTestRunStatus (eclipse-ide server) to poll for results
     */
    public String startJUnitPluginTestPackage( String projectName, String packageName,
                                               boolean withCoverage, boolean includeAllPlugins,
                                               List<String> additionalBundles, String launcherName )
    {
        Objects.requireNonNull( projectName, "Project name cannot be null" );
        Objects.requireNonNull( packageName, "Package name cannot be null" );
        if ( projectName.isEmpty() )
        {
            throw new IllegalArgumentException( "Project name cannot be empty" );
        }
        if ( packageName.isEmpty() )
        {
            throw new IllegalArgumentException( "Package name cannot be empty" );
        }
        try
        {
            IJavaProject javaProject = getJavaProject( projectName );
            IPackageFragment pkg = JavaModelUtils.findPackage( javaProject, packageName );
            if ( pkg == null )
            {
                throw new RuntimeException(
                    "Package '" + packageName + "' not found in project '" + projectName + "'" );
            }
            return startPluginTests( javaProject, null, pkg, withCoverage, includeAllPlugins,
                additionalBundles, "Running package " + packageName + " in " + projectName,
                launcherName );
        }
        catch ( CoreException e )
        {
            throw new RuntimeException( "Error starting plug-in tests: " + e.getMessage(), e );
        }
    }

    // -------------------------------------------------------------------------
    // Core async launch logic
    // -------------------------------------------------------------------------

    private String startPluginTests( IJavaProject javaProject, IType testClass,
                                      IPackageFragment packageFragment,
                                      boolean withCoverage, boolean includeAllPlugins,
                                      List<String> additionalBundles, String description,
                                      String launcherName )
    {
        TestRunSession session = testRunManager.createSession( description, withCoverage,
            javaProject.getElementName() );

        TestRunListener listener = new TestRunListener()
        {
            private ITestRunSession ourSession = null;
            private UnitTestService.TestRunResult currentRun = null;

            @Override
            public void sessionStarted( ITestRunSession s )
            {
                if ( ourSession == null )
                {
                    ourSession = s;
                    currentRun = new UnitTestService.TestRunResult( s.getTestRunName() );
                    session.setTestRunResult( currentRun );
                }
            }

            @Override
            public void testCaseFinished( ITestCaseElement e )
            {
                if ( e.getTestRunSession() == ourSession )
                {
                    String clazz = e.getTestClassName();
                    String name = e.getTestMethodName();
                    String status = e.getTestResult( true ).toString();
                    String msg = e.getFailureTrace() != null ? e.getFailureTrace().getTrace() : "";
                    double time = e.getElapsedTimeInSeconds();
                    currentRun.addTestResult(
                        new UnitTestService.TestResult( clazz, name, status, msg, time ) );
                }
            }

            @Override
            public void sessionFinished( ITestRunSession s )
            {
                if ( s != ourSession )
                {
                    return;
                }
                try
                {
                    if ( withCoverage && coverageService.isCoverageAvailable() )
                    {
                        String execFile = coverageService.waitForLatestCoverageFile(
                            session.getStartTime(), 10000 );
                        session.setCoverageInfo( coverageService.formatCoverageInfo(
                            execFile, session.getProjectName() ) );
                    }
                }
                finally
                {
                    JUnitCore.removeTestRunListener( this );
                    // Delay COMPLETED until the child Eclipse JVM has fully exited so that
                    // a subsequent launch on the same workspace path does not hit the
                    // "workspace is used by another application" lock conflict.
                    ILaunch launch = session.getLaunch();
                    if ( launch != null && !launch.isTerminated() )
                    {
                        Thread waiter = new Thread( () -> {
                            long deadline = System.currentTimeMillis() + 20_000;
                            for ( IProcess p : launch.getProcesses() )
                            {
                                while ( !p.isTerminated()
                                        && System.currentTimeMillis() < deadline )
                                {
                                    try { Thread.sleep( 200 ); }
                                    catch ( InterruptedException ie )
                                    {
                                        Thread.currentThread().interrupt();
                                        break;
                                    }
                                }
                                if ( !p.isTerminated() )
                                {
                                    try
                                    {
                                        p.terminate();
                                        logger.log( org.eclipse.core.runtime.Status.warning(
                                            "PDE test process did not exit within 20 s — force-terminated" ) );
                                    }
                                    catch ( DebugException e )
                                    {
                                        logger.log( org.eclipse.core.runtime.Status.error(
                                            "Could not force-terminate PDE test process", e ) );
                                    }
                                }
                            }
                            session.setState( TestRunSession.State.COMPLETED );
                        }, "pde-process-wait-" + session.getRunId() );
                        waiter.setDaemon( true );
                        waiter.start();
                    }
                    else
                    {
                        session.setState( TestRunSession.State.COMPLETED );
                    }
                }
            }
        };

        JUnitCore.addTestRunListener( listener );

        try
        {
            ILaunchManager launchManager = DebugPlugin.getDefault().getLaunchManager();
            ILaunchConfigurationWorkingCopy workingCopy;

            if ( launcherName != null && !launcherName.isBlank() )
            {
                // Use the named saved config as base — only override targeting attributes
                ILaunchConfiguration base = findExistingLaunchConfig( launchManager, launcherName );
                if ( base == null )
                {
                    JUnitCore.removeTestRunListener( listener );
                    session.setErrorMessage( "Launch configuration not found: " + launcherName );
                    session.setState( TestRunSession.State.FAILED );
                    throw new RuntimeException( "Launch configuration not found: " + launcherName );
                }
                workingCopy = base.getWorkingCopy();
            }
            else
            {
                ILaunchConfigurationType type = launchManager.getLaunchConfigurationType(
                    "org.eclipse.pde.ui.JunitLaunchConfig" );

                if ( type == null )
                {
                    JUnitCore.removeTestRunListener( listener );
                    session.setErrorMessage( "PDE JUnit Plug-in Test launch configuration type not found. "
                        + "Ensure org.eclipse.pde.ui is available in the running Eclipse instance." );
                    session.setState( TestRunSession.State.FAILED );
                    return session.getRunId();
                }

                String launchName = buildLaunchName( javaProject, testClass, packageFragment );
                ILaunchConfiguration existing = findExistingLaunchConfig( launchManager, launchName );
                if ( existing != null )
                {
                    workingCopy = existing.getWorkingCopy();
                }
                else
                {
                    workingCopy = type.newInstance( null, launchName );
                }
            }

            // Always override the targeting attributes
            workingCopy.setAttribute( IJavaLaunchConfigurationConstants.ATTR_PROJECT_NAME,
                javaProject.getElementName() );

            if ( testClass != null )
            {
                workingCopy.setAttribute( IJavaLaunchConfigurationConstants.ATTR_MAIN_TYPE_NAME,
                    testClass.getFullyQualifiedName() );
                workingCopy.setAttribute( "org.eclipse.jdt.junit.CONTAINER", "" );
            }
            else if ( packageFragment != null )
            {
                workingCopy.setAttribute( IJavaLaunchConfigurationConstants.ATTR_MAIN_TYPE_NAME, "" );
                workingCopy.setAttribute( "org.eclipse.jdt.junit.CONTAINER",
                    packageFragment.getHandleIdentifier() );
            }
            else
            {
                workingCopy.setAttribute( IJavaLaunchConfigurationConstants.ATTR_MAIN_TYPE_NAME, "" );
                workingCopy.setAttribute( "org.eclipse.jdt.junit.CONTAINER",
                    javaProject.getHandleIdentifier() );
            }

            // Only set TEST_KIND and PDE bundle config when not using a named launcher
            if ( launcherName == null || launcherName.isBlank() )
            {
                workingCopy.setAttribute( "org.eclipse.jdt.junit.TEST_KIND",
                    detectJUnitTestKind( javaProject ) );

                String testWorkspace = System.getProperty( "java.io.tmpdir" )
                    + java.io.File.separator + "pde-test-workspace-"
                    + javaProject.getElementName();
                workingCopy.setAttribute( IPDELauncherConstants.LOCATION, testWorkspace );
                workingCopy.setAttribute( IPDELauncherConstants.DOCLEAR, true );
                workingCopy.setAttribute( IPDELauncherConstants.ASKCLEAR, false );

                if ( includeAllPlugins )
                {
                    workingCopy.setAttribute( IPDELauncherConstants.USE_DEFAULT, true );
                    workingCopy.setAttribute( IPDELauncherConstants.AUTOMATIC_ADD, true );
                }
                else
                {
                    workingCopy.setAttribute( IPDELauncherConstants.USE_DEFAULT, false );
                    workingCopy.setAttribute( IPDELauncherConstants.AUTOMATIC_ADD, false );
                    workingCopy.setAttribute( IPDELauncherConstants.INCLUDE_OPTIONAL, true );
                    workingCopy.setAttribute( IPDELauncherConstants.AUTOMATIC_INCLUDE_REQUIREMENTS, true );
                    workingCopy.setAttribute( IPDELauncherConstants.AUTOMATIC_VALIDATE, true );

                    Set<String> workspaceBundles = new TreeSet<>();
                    workspaceBundles.add( javaProject.getElementName() + "@default:false" );
                    if ( additionalBundles != null )
                    {
                        for ( String bundle : additionalBundles )
                        {
                            workspaceBundles.add( bundle + "@default:false" );
                        }
                    }
                    workingCopy.setAttribute( IPDELauncherConstants.SELECTED_WORKSPACE_BUNDLES,
                        workspaceBundles );
                    workingCopy.setAttribute( IPDELauncherConstants.SELECTED_TARGET_BUNDLES,
                        new TreeSet<String>() );
                }
            }

            ILaunchConfiguration configuration = workingCopy.doSave();

            boolean useCoverage = withCoverage && coverageService.isCoverageAvailable();
            String launchMode = useCoverage ? coverageService.getCoverageLaunchMode()
                                            : ILaunchManager.RUN_MODE;

            sync.asyncExec( () -> {
                try
                {
                    var launch = configuration.launch( launchMode, new NullProgressMonitor() );
                    session.setLaunch( launch );
                    testRunManager.attachConsoleCapture( session );
                }
                catch ( CoreException e )
                {
                    JUnitCore.removeTestRunListener( listener );
                    session.setErrorMessage( e.getMessage() );
                    session.setState( TestRunSession.State.FAILED );
                    logger.log( org.eclipse.core.runtime.Status.error(
                        "Error launching plug-in tests", e ) );
                }
            } );

            return session.getRunId();
        }
        catch ( Exception e )
        {
            JUnitCore.removeTestRunListener( listener );
            session.setErrorMessage( e.getMessage() );
            session.setState( TestRunSession.State.FAILED );
            throw new RuntimeException( "Error setting up plug-in test launch: " + e.getMessage(), e );
        }
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    private String buildLaunchName( IJavaProject project, IType testClass,
                                     IPackageFragment packageFragment )
    {
        String base = "AssistAI-PDE-" + project.getElementName();
        if ( testClass != null )
        {
            base += "-" + testClass.getElementName();
        }
        else if ( packageFragment != null )
        {
            base += "-" + packageFragment.getElementName();
        }
        return base;
    }

    private ILaunchConfiguration findExistingLaunchConfig( ILaunchManager manager, String name )
        throws CoreException
    {
        for ( ILaunchConfiguration config : manager.getLaunchConfigurations() )
        {
            if ( config.getName().equals( name ) )
            {
                return config;
            }
        }
        return null;
    }

    private IJavaProject getJavaProject( String projectName ) throws CoreException
    {
        return JavaModelUtils.getJavaProject( projectName );
    }

    private String detectJUnitTestKind( IJavaProject javaProject ) throws JavaModelException
    {
        IType jupiterTest = javaProject.findType( "org.junit.jupiter.api.Test" );
        if ( jupiterTest != null )
        {
            for ( var entry : javaProject.getResolvedClasspath( true ) )
            {
                String path = entry.getPath().toString();
                if ( path.contains( "junit-jupiter-api" ) )
                {
                    if ( path.matches( ".*junit-jupiter-api[_-]6\\..*" ) )
                    {
                        return "org.eclipse.jdt.junit.loader.junit6";
                    }
                    break;
                }
            }
            return "org.eclipse.jdt.junit.loader.junit5";
        }
        if ( javaProject.findType( "org.junit.Test" ) != null )
        {
            return "org.eclipse.jdt.junit.loader.junit4";
        }
        return "org.eclipse.jdt.junit.loader.junit5";
    }

    private ITargetPlatformService getTargetPlatformService()
    {
        var serviceRef = org.eclipse.core.runtime.Platform.getBundle( "org.eclipse.pde.core" )
            .getBundleContext()
            .getServiceReference( ITargetPlatformService.class );
        if ( serviceRef == null )
        {
            throw new IllegalStateException( "ITargetPlatformService is not available" );
        }
        return org.eclipse.core.runtime.Platform.getBundle( "org.eclipse.pde.core" )
            .getBundleContext()
            .getService( serviceRef );
    }
}
