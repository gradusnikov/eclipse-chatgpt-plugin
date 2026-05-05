package com.github.gradusnikov.eclipse.assistai.mcp.services;

import java.util.Objects;
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
import org.eclipse.debug.core.DebugPlugin;
import org.eclipse.debug.core.ILaunchConfiguration;
import org.eclipse.debug.core.ILaunchConfigurationType;
import org.eclipse.debug.core.ILaunchConfigurationWorkingCopy;
import org.eclipse.debug.core.ILaunchManager;
import org.eclipse.e4.core.di.annotations.Creatable;
import org.eclipse.e4.ui.di.UISynchronize;
import org.eclipse.jdt.core.IJavaProject;
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

    // -------------------------------------------------------------------------
    // Target platform
    // -------------------------------------------------------------------------

    /**
     * Returns a description of the currently active target platform.
     * If the workspace is using the running platform (no explicit target set),
     * returns a message saying so.
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
     * Sets the active target platform to the given workspace-relative target file path
     * (e.g. "MyProject/my.target"). The job runs asynchronously; this method waits
     * up to 120 seconds for it to complete.
     *
     * @param targetFilePath workspace-relative path to the .target file
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
     * Reloads (resolves) the currently active target platform.
     * Should be called after modifying the active .target file.
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
    // JUnit Plug-in Test
    // -------------------------------------------------------------------------

    /**
     * Runs all JUnit Plug-in Tests in the given project.
     */
    public String runJUnitPluginTests( String projectName, Integer timeout )
    {
        Objects.requireNonNull( projectName, "Project name cannot be null" );
        if ( projectName.isEmpty() )
        {
            throw new IllegalArgumentException( "Project name cannot be empty" );
        }
        if ( timeout == null || timeout <= 0 )
        {
            timeout = 120;
        }

        try
        {
            IJavaProject javaProject = getJavaProject( projectName );
            return launchJUnitPluginTests( javaProject, null, null, timeout );
        }
        catch ( IllegalArgumentException | CoreException e )
        {
            return "Error running plug-in tests: " + e.getMessage();
        }
    }

    /**
     * Runs JUnit Plug-in Tests for a specific class.
     */
    public String runJUnitPluginTestClass( String projectName, String className, Integer timeout )
    {
        Objects.requireNonNull( projectName, "Project name cannot be null" );
        Objects.requireNonNull( className, "Class name cannot be null" );
        if ( timeout == null || timeout <= 0 )
        {
            timeout = 120;
        }

        try
        {
            IJavaProject javaProject = getJavaProject( projectName );
            IType type = javaProject.findType( className );
            if ( type == null )
            {
                return "Error: Class '" + className + "' not found in project '" + projectName + "'.";
            }
            return launchJUnitPluginTests( javaProject, null, type, timeout );
        }
        catch ( IllegalArgumentException | CoreException e )
        {
            return "Error running plug-in tests: " + e.getMessage();
        }
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    private String launchJUnitPluginTests( IJavaProject javaProject, Object packageFragment,
                                            IType testClass, int timeout )
    {
        CountDownLatch latch = new CountDownLatch( 1 );
        UnitTestService.TestRunResult[] testRunResults = new UnitTestService.TestRunResult[1];

        TestRunListener listener = new TestRunListener()
        {
            private UnitTestService.TestRunResult currentRun = null;

            @Override
            public void sessionStarted( ITestRunSession session )
            {
                currentRun = new UnitTestService.TestRunResult( session.getTestRunName() );
            }

            @Override
            public void sessionFinished( ITestRunSession session )
            {
                testRunResults[0] = currentRun;
                latch.countDown();
            }

            @Override
            public void testCaseFinished( ITestCaseElement testCaseElement )
            {
                if ( currentRun != null )
                {
                    String clazz = testCaseElement.getTestClassName();
                    String testName = testCaseElement.getTestMethodName();
                    String status = testCaseElement.getTestResult( true ).toString();
                    String message = testCaseElement.getFailureTrace() != null
                        ? testCaseElement.getFailureTrace().getTrace() : "";
                    double time = testCaseElement.getElapsedTimeInSeconds();
                    currentRun.addTestResult( new UnitTestService.TestResult( clazz, testName, status, message, time ) );
                }
            }
        };

        JUnitCore.addTestRunListener( listener );

        try
        {
            ILaunchManager launchManager = DebugPlugin.getDefault().getLaunchManager();
            // PDE JUnit Plug-in Test launch config type
            ILaunchConfigurationType type = launchManager.getLaunchConfigurationType(
                "org.eclipse.pde.ui.JunitLaunchConfig" );

            if ( type == null )
            {
                return "Error: PDE JUnit Plug-in Test launch configuration type not found. "
                    + "Ensure org.eclipse.pde.ui is available in the running Eclipse instance.";
            }

            String launchName = buildLaunchName( javaProject, testClass );
            ILaunchConfiguration existing = findExistingLaunchConfig( launchManager, launchName );
            ILaunchConfigurationWorkingCopy workingCopy;
            if ( existing != null )
            {
                workingCopy = existing.getWorkingCopy();
            }
            else
            {
                workingCopy = type.newInstance( null, launchName );
            }

            workingCopy.setAttribute( IJavaLaunchConfigurationConstants.ATTR_PROJECT_NAME,
                javaProject.getElementName() );

            if ( testClass != null )
            {
                workingCopy.setAttribute( IJavaLaunchConfigurationConstants.ATTR_MAIN_TYPE_NAME,
                    testClass.getFullyQualifiedName() );
                workingCopy.setAttribute( "org.eclipse.jdt.junit.CONTAINER", "" );
            }
            else
            {
                workingCopy.setAttribute( IJavaLaunchConfigurationConstants.ATTR_MAIN_TYPE_NAME, "" );
                // CONTAINER must be the Java element handle identifier for the project:
                // "=<projectName>" is the format JDT JUnit launcher expects
                workingCopy.setAttribute( "org.eclipse.jdt.junit.CONTAINER",
                    javaProject.getHandleIdentifier() );
            }

            // Detect JUnit version
            workingCopy.setAttribute( "org.eclipse.jdt.junit.TEST_KIND",
                detectJUnitTestKind( javaProject ) );

            // Set a fixed workspace data dir so the PDE launcher never shows
            // the "Select a workspace" dialog interactively
            String testWorkspace = System.getProperty( "java.io.tmpdir" )
                + java.io.File.separator + "pde-test-workspace-"
                + javaProject.getElementName();
            workingCopy.setAttribute( IPDELauncherConstants.LOCATION, testWorkspace );
            workingCopy.setAttribute( IPDELauncherConstants.DOCLEAR, false );

            ILaunchConfiguration configuration = workingCopy.doSave();

            CoreException[] launchError = new CoreException[1];
            sync.syncExec( () -> {
                try
                {
                    configuration.launch( ILaunchManager.RUN_MODE, new NullProgressMonitor() );
                }
                catch ( CoreException e )
                {
                    launchError[0] = e;
                    logger.log( org.eclipse.core.runtime.Status.error( "Error launching plug-in tests", e ) );
                }
            } );

            if ( launchError[0] != null )
            {
                return "Error launching plug-in tests: " + launchError[0].getMessage();
            }

            boolean completed = latch.await( timeout, TimeUnit.SECONDS );
            if ( !completed )
            {
                return "Error: Test execution timed out after " + timeout + " seconds.";
            }
            if ( testRunResults[0] == null )
            {
                return "Error: No test results collected. The test run may have failed to start.";
            }
            return testRunResults[0].toString();
        }
        catch ( Exception e )
        {
            logger.error( "Error running plug-in tests", e );
            return "Error running plug-in tests: " + e.getMessage();
        }
        finally
        {
            JUnitCore.removeTestRunListener( listener );
        }
    }

    private String buildLaunchName( IJavaProject project, IType testClass )
    {
        String base = "AssistAI-PDE-" + project.getElementName();
        if ( testClass != null )
        {
            base += "-" + testClass.getElementName();
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
        IWorkspace workspace = ResourcesPlugin.getWorkspace();
        var project = workspace.getRoot().getProject( projectName );
        if ( !project.exists() )
        {
            throw new IllegalArgumentException( "Project not found: " + projectName );
        }
        return JavaCore.create( project );
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
