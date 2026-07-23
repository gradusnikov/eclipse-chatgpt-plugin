package com.github.gradusnikov.eclipse.assistai.mcp.services;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.Optional;

import com.github.gradusnikov.eclipse.assistai.mcp.operations.Operation;
import com.github.gradusnikov.eclipse.assistai.mcp.operations.OperationContext;
import com.github.gradusnikov.eclipse.assistai.mcp.operations.ProcessOutputSource;
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
import org.eclipse.jdt.core.IPackageFragment;
import org.eclipse.jdt.core.IPackageFragmentRoot;
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
import org.eclipse.swt.widgets.Display;

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
        return runJUnitPluginTests( projectName, timeout, false, false, List.of() );
    }

    public String runJUnitPluginTests( String projectName, Integer timeout, boolean withCoverage )
    {
        return runJUnitPluginTests( projectName, timeout, withCoverage, false, List.of() );
    }

    public String runJUnitPluginTests( String projectName, Integer timeout, boolean withCoverage,
                                        boolean includeAllPlugins, List<String> additionalBundles )
    {
        return runJUnitPluginTests( projectName, timeout, withCoverage, includeAllPlugins, additionalBundles, null );
    }

    /**
     * Runs all JUnit Plug-in Tests in the given project, optionally using a saved launch
     * configuration as a base.
     *
     * @param launcherName optional saved launch config name; when set all its settings are
     *                     reused (VM args, bundle selection, etc.) and only the project/
     *                     container targeting attributes are overridden
     */
    public String runJUnitPluginTests( String projectName, Integer timeout, boolean withCoverage,
                                        boolean includeAllPlugins, List<String> additionalBundles,
                                        String launcherName )
    {
        Objects.requireNonNull( projectName, "Project name cannot be null" );
        if ( projectName.isEmpty() )
        {
            throw new IllegalArgumentException( "Project name cannot be empty" );
        }
        if ( timeout == null || timeout <= 0 )
        {
            timeout = 300;
        }

        try
        {
            IJavaProject javaProject = getJavaProject( projectName );
            return launchJUnitPluginTests( javaProject, null, List.of(), timeout, withCoverage,
                includeAllPlugins, additionalBundles, launcherName );
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
        return runJUnitPluginTestClass( projectName, className, timeout, false, false, List.of() );
    }

    public String runJUnitPluginTestClass( String projectName, String className, Integer timeout,
                                            boolean withCoverage )
    {
        return runJUnitPluginTestClass( projectName, className, timeout, withCoverage, false, List.of() );
    }

    public String runJUnitPluginTestClass( String projectName, String className, Integer timeout,
                                            boolean withCoverage, boolean includeAllPlugins,
                                            List<String> additionalBundles )
    {
        return runJUnitPluginTestClass( projectName, className, timeout, withCoverage,
            includeAllPlugins, additionalBundles, null );
    }

    /**
     * Runs JUnit Plug-in Tests for a specific class, optionally using a saved launch
     * configuration as a base.
     *
     * @param launcherName optional saved launch config name; when set all its settings are
     *                     reused and only the project/class targeting attributes are overridden
     */
    public String runJUnitPluginTestClass( String projectName, String className, Integer timeout,
                                            boolean withCoverage, boolean includeAllPlugins,
                                            List<String> additionalBundles, String launcherName )
    {
        Objects.requireNonNull( projectName, "Project name cannot be null" );
        Objects.requireNonNull( className, "Class name cannot be null" );
        if ( timeout == null || timeout <= 0 )
        {
            timeout = 300;
        }

        try
        {
            IJavaProject javaProject = getJavaProject( projectName );
            IType type = javaProject.findType( className );
            if ( type == null )
            {
                return "Error: Class '" + className + "' not found in project '" + projectName + "'.";
            }
            return launchJUnitPluginTests( javaProject, null, List.of( type ), timeout, withCoverage,
                includeAllPlugins, additionalBundles, launcherName );
        }
        catch ( IllegalArgumentException | CoreException e )
        {
            return "Error running plug-in tests: " + e.getMessage();
        }
    }

    /**
     * Runs selected JUnit Plug-in Test classes in a single PDE launch.
     */
    public String runJUnitPluginTestClasses( String projectName, List<String> classNames,
                                             Integer timeout )
    {
        return runJUnitPluginTestClasses( projectName, classNames, timeout, false, List.of() );
    }

    public String runJUnitPluginTestClasses( String projectName, List<String> classNames,
                                             Integer timeout, boolean includeAllPlugins,
                                             List<String> additionalBundles )
    {
        return runJUnitPluginTestClasses( projectName, classNames, timeout, includeAllPlugins,
            additionalBundles, null );
    }

    /**
     * Runs selected JUnit Plug-in Test classes in a single PDE launch, optionally using a saved
     * launch configuration as a base.
     *
     * @param launcherName optional saved launch config name
     */
    public String runJUnitPluginTestClasses( String projectName, List<String> classNames,
                                             Integer timeout, boolean includeAllPlugins,
                                             List<String> additionalBundles, String launcherName )
    {
        Objects.requireNonNull( projectName, "Project name cannot be null" );
        Objects.requireNonNull( classNames, "Class names cannot be null" );
        if ( projectName.isEmpty() )
        {
            throw new IllegalArgumentException( "Project name cannot be empty" );
        }
        if ( classNames.isEmpty() )
        {
            throw new IllegalArgumentException( "At least one class name is required" );
        }
        if ( timeout == null || timeout <= 0 )
        {
            timeout = 300;
        }

        List<String> normalizedClassNames = new ArrayList<>();
        for ( String className : classNames )
        {
            Objects.requireNonNull( className, "Class names cannot contain null" );
            String normalized = className.trim();
            if ( normalized.isEmpty() )
            {
                throw new IllegalArgumentException( "Class names cannot be blank" );
            }
            if ( !normalizedClassNames.contains( normalized ) )
            {
                normalizedClassNames.add( normalized );
            }
        }

        try
        {
            IJavaProject javaProject = getJavaProject( projectName );
            List<IType> testClasses = new ArrayList<>( normalizedClassNames.size() );
            List<String> missingClassNames = new ArrayList<>();
            for ( String className : normalizedClassNames )
            {
                IType type = javaProject.findType( className );
                if ( type == null )
                {
                    missingClassNames.add( className );
                }
                else
                {
                    testClasses.add( type );
                }
            }
            if ( !missingClassNames.isEmpty() )
            {
                return "Error: Test classes not found in project '" + projectName + "': "
                    + String.join( ", ", missingClassNames );
            }

            return launchJUnitPluginTests( javaProject, null, testClasses, timeout, false,
                includeAllPlugins, additionalBundles, launcherName );
        }
        catch ( IllegalArgumentException | CoreException e )
        {
            return "Error running plug-in tests: " + e.getMessage();
        }
    }


    /**
     * Runs JUnit Plug-in Tests for all test classes in a specific package.
     */
    public String runJUnitPluginTestPackage( String projectName, String packageName,
                                              Integer timeout )
    {
        return runJUnitPluginTestPackage( projectName, packageName, timeout, false, false, List.of() );
    }

    public String runJUnitPluginTestPackage( String projectName, String packageName,
                                              Integer timeout, boolean withCoverage )
    {
        return runJUnitPluginTestPackage( projectName, packageName, timeout, withCoverage, false, List.of() );
    }

    public String runJUnitPluginTestPackage( String projectName, String packageName,
                                              Integer timeout, boolean withCoverage,
                                              boolean includeAllPlugins, List<String> additionalBundles )
    {
        return runJUnitPluginTestPackage( projectName, packageName, timeout, withCoverage,
            includeAllPlugins, additionalBundles, null );
    }

    /**
     * Runs JUnit Plug-in Tests for all test classes in a specific package, optionally using a saved
     * launch configuration as a base.
     *
     * @param launcherName optional saved launch config name; when set all its settings are reused
     *                     and only the project/package targeting attributes are overridden
     */
    public String runJUnitPluginTestPackage( String projectName, String packageName,
                                              Integer timeout, boolean withCoverage,
                                              boolean includeAllPlugins, List<String> additionalBundles,
                                              String launcherName )
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
        if ( timeout == null || timeout <= 0 )
        {
            timeout = 300;
        }

        try
        {
            IJavaProject javaProject = getJavaProject( projectName );
            IPackageFragment pkg = findPackage( javaProject, packageName );
            if ( pkg == null )
            {
                return "Error: Package '" + packageName + "' not found in project '" + projectName + "'.";
            }
            return launchJUnitPluginTests( javaProject, pkg, List.of(), timeout, withCoverage,
                includeAllPlugins, additionalBundles, launcherName );
        }
        catch ( IllegalArgumentException | CoreException e )
        {
            return "Error running plug-in tests: " + e.getMessage();
        }
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    /**
     * Backstop so a test instance that never reports and never dies cannot park a
     * thread forever. It is not the caller's timeout - the caller is handed an
     * operationId long before this - just an upper bound on how long we keep listening.
     */
    private static final int MAX_TEST_RUN_MINUTES = 120;

    private String launchJUnitPluginTests( IJavaProject javaProject, IPackageFragment packageFragment,
                                            List<IType> testClasses, int timeout, boolean withCoverage,
                                            boolean includeAllPlugins, List<String> additionalBundles )
    {
        return launchJUnitPluginTests( javaProject, packageFragment, testClasses, timeout,
            withCoverage, includeAllPlugins, additionalBundles, null );
    }

    /**
     * Core PDE JUnit launch. When {@code launcherName} is non-null, that saved configuration is
     * used as a base and only the test targeting attributes are overridden.
     */
    private String launchJUnitPluginTests( IJavaProject javaProject, IPackageFragment packageFragment,
                                            List<IType> testClasses, int timeout, boolean withCoverage,
                                            boolean includeAllPlugins, List<String> additionalBundles,
                                            String launcherName )
    {
        CountDownLatch latch = new CountDownLatch( 1 );
        UnitTestService.TestRunResult[] testRunResults = new UnitTestService.TestRunResult[1];
        Optional<Operation> operation = OperationContext.current();
        AtomicInteger finishedTests = new AtomicInteger();

        TestRunListener listener = new TestRunListener()
        {
            private UnitTestService.TestRunResult currentRun = null;

            @Override
            public void sessionStarted( ITestRunSession session )
            {
                currentRun = new UnitTestService.TestRunResult( session.getTestRunName() );
                operation.ifPresent( op -> op.setProgress( "test session started" ) );
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
                    int count = finishedTests.incrementAndGet();
                    operation.ifPresent( op -> {
                        op.setProgress( count + " tests finished; last: " + clazz + "#" + testName );
                        // Publish typed intermediate results so getOperationStatus
                        // can surface pass/fail counts and detailed test listing
                        // while the run is still going.
                        op.setIntermediateResult( "summary", currentRun.toSummary() );
                        op.setIntermediateResult( "results", currentRun.toResults() );
                    } );
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
                // Use the named saved config as a base — only override targeting attributes
                ILaunchConfiguration base = findExistingLaunchConfig( launchManager, launcherName );
                if ( base == null )
                {
                    JUnitCore.removeTestRunListener( listener );
                    return "Error: Launch configuration not found: " + launcherName;
                }
                workingCopy = base.getWorkingCopy();
            }
            else
            {
                boolean selectedClassLaunch = testClasses.size() > 1;
                String launchTypeId = selectedClassLaunch
                    ? SelectedJUnitPluginLaunchDelegate.LAUNCH_CONFIGURATION_TYPE
                    : "org.eclipse.pde.ui.JunitLaunchConfig";
                ILaunchConfigurationType type = launchManager.getLaunchConfigurationType( launchTypeId );

                if ( type == null )
                {
                    JUnitCore.removeTestRunListener( listener );
                    return "Error: PDE JUnit Plug-in Test launch configuration type '" + launchTypeId
                        + "' not found. Ensure the required PDE launcher is available.";
                }

                String launchName = buildLaunchName( javaProject, packageFragment, testClasses );
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

            // Always override targeting attributes — everything else from the base config is kept
            workingCopy.setAttribute( IJavaLaunchConfigurationConstants.ATTR_PROJECT_NAME,
                javaProject.getElementName() );

            boolean selectedClassLaunch = testClasses.size() > 1;
            if ( selectedClassLaunch )
            {
                List<String> classNames = testClasses.stream()
                    .map( IType::getFullyQualifiedName )
                    .toList();
                workingCopy.setAttribute( SelectedJUnitPluginLaunchDelegate.ATTR_TEST_CLASSES, classNames );
                workingCopy.setAttribute( IJavaLaunchConfigurationConstants.ATTR_MAIN_TYPE_NAME, "" );
                workingCopy.setAttribute( "org.eclipse.jdt.junit.CONTAINER", "" );
            }
            else if ( !testClasses.isEmpty() )
            {
                workingCopy.setAttribute( SelectedJUnitPluginLaunchDelegate.ATTR_TEST_CLASSES,
                    List.<String>of() );
                workingCopy.setAttribute( IJavaLaunchConfigurationConstants.ATTR_MAIN_TYPE_NAME,
                    testClasses.get( 0 ).getFullyQualifiedName() );
                workingCopy.setAttribute( "org.eclipse.jdt.junit.CONTAINER", "" );
            }
            else if ( packageFragment != null )
            {
                // Package scope: set CONTAINER to the package's handle identifier.
                // The JDT JUnit launcher resolves CONTAINER via JavaCore.create(handleId),
                // which works for IPackageFragment handles as well as project handles.
                workingCopy.setAttribute( SelectedJUnitPluginLaunchDelegate.ATTR_TEST_CLASSES,
                    List.<String>of() );
                workingCopy.setAttribute( IJavaLaunchConfigurationConstants.ATTR_MAIN_TYPE_NAME, "" );
                workingCopy.setAttribute( "org.eclipse.jdt.junit.CONTAINER",
                    packageFragment.getHandleIdentifier() );
            }
            else
            {
                workingCopy.setAttribute( SelectedJUnitPluginLaunchDelegate.ATTR_TEST_CLASSES,
                    List.<String>of() );
                workingCopy.setAttribute( IJavaLaunchConfigurationConstants.ATTR_MAIN_TYPE_NAME, "" );
                // CONTAINER must be the Java element handle identifier for the project:
                // "=<projectName>" is the format JDT JUnit launcher expects
                workingCopy.setAttribute( "org.eclipse.jdt.junit.CONTAINER",
                    javaProject.getHandleIdentifier() );
            }

            // Only set TEST_KIND and workspace/bundle config when not using a named launcher
            if ( launcherName == null || launcherName.isBlank() )
            {
                workingCopy.setAttribute( "org.eclipse.jdt.junit.TEST_KIND",
                    detectJUnitTestKind( javaProject ) );

                String launchName = buildLaunchName( javaProject, packageFragment, testClasses );
                String testWorkspace = System.getProperty( "java.io.tmpdir" )
                    + java.io.File.separator + "pde-test-workspace-"
                    + javaProject.getElementName() + "-"
                    + Integer.toHexString( launchName.hashCode() );
                workingCopy.setAttribute( IPDELauncherConstants.LOCATION, testWorkspace );
                workingCopy.setAttribute( IPDELauncherConstants.DOCLEAR, false );

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
                    workingCopy.setAttribute( IPDELauncherConstants.SELECTED_WORKSPACE_BUNDLES, workspaceBundles );
                    workingCopy.setAttribute( IPDELauncherConstants.SELECTED_TARGET_BUNDLES, new TreeSet<String>() );
                }
            }

            ILaunchConfiguration configuration = workingCopy.doSave();

            boolean useCoverage = withCoverage && coverageService.isCoverageAvailable();
            String launchMode = useCoverage ? coverageService.getCoverageLaunchMode() : ILaunchManager.RUN_MODE;

            long launchStartTime = System.currentTimeMillis();
            CoreException[] launchError = new CoreException[1];
            org.eclipse.debug.core.ILaunch[] launchRef = new org.eclipse.debug.core.ILaunch[1];
            sync.asyncExec( () -> {
                try
                {
                    launchRef[0] = configuration.launch( launchMode, new NullProgressMonitor() );
                }
                catch ( CoreException e )
                {
                    launchError[0] = e;
                    latch.countDown();
                    logger.log( org.eclipse.core.runtime.Status.error( "Error launching plug-in tests", e ) );
                }
            } );

            // How long the CALLER is prepared to wait is the framework's business: once its
            // inline wait elapses it hands the caller an operationId and this thread keeps
            // going. The bound here is only a backstop against a JVM that never reports and
            // never dies.
            // Run as an MCP operation, the caller has already been handed an operationId and
            // the only bound left is a backstop. Called directly - from a test, an agent -
            // there is no framework waiting for us, so the caller's timeout is still the bound.
            long waitBoundMillis = operation.isPresent()
                    ? TimeUnit.MINUTES.toMillis( MAX_TEST_RUN_MINUTES )
                    : TimeUnit.SECONDS.toMillis( timeout );
            long deadline = System.currentTimeMillis() + waitBoundMillis;
            Display display = Display.getCurrent();
            boolean completed = false;
            boolean attached = false;
            while ( !completed && System.currentTimeMillis() < deadline )
            {
                if ( display != null && !display.isDisposed() )
                {
                    while ( display.readAndDispatch() )
                    {
                    }
                }
                if ( !attached && launchRef[0] != null )
                {
                    // The launch is asynchronous, so it only exists once the UI thread has run
                    // it. Streams the test instance's output into the operation and makes
                    // cancelling it terminate the JVM.
                    attached = true;
                    org.eclipse.debug.core.ILaunch launched = launchRef[0];
                    operation.ifPresent( op -> ProcessOutputSource.attach( op, launched ) );
                }
                completed = latch.await( 100, TimeUnit.MILLISECONDS );
                if ( !completed && launchRef[0] != null && launchRef[0].isTerminated() )
                {
                    completed = true;
                }
            }

            if ( launchError[0] != null )
            {
                return "Error launching plug-in tests: " + launchError[0].getMessage();
            }
            if ( !completed )
            {
                return "Error: the test run did not report results in time.";
            }
            if ( testRunResults[0] == null )
            {
                return "Error: No test results collected. The test run may have failed to start.";
            }

            // The test session can finish just before the workbench process releases
            // its workspace lock. Wait briefly so an immediate rerun of the same
            // selection can safely reuse its workspace.
            waitForLaunchTermination( launchRef[0] );

            String results = testRunResults[0].toString();

            if ( useCoverage )
            {
                String execFile = coverageService.waitForLatestCoverageFile( launchStartTime, 10000 );
                results += coverageService.formatCoverageInfo( execFile, javaProject.getProject().getName() );
            }

            return results;
        }
        catch ( InterruptedException e )
        {
            // cancelOperation interrupts this thread; the test instance itself is
            // terminated by the operation's cancel hook.
            Thread.currentThread().interrupt();
            return "Test run cancelled.";
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

    private void waitForLaunchTermination( org.eclipse.debug.core.ILaunch launch )
        throws InterruptedException
    {
        if ( launch == null || launch.isTerminated() )
        {
            return;
        }

        long deadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis( 10 );
        Display display = Display.getCurrent();
        while ( !launch.isTerminated() && System.currentTimeMillis() < deadline )
        {
            if ( display != null && !display.isDisposed() )
            {
                while ( display.readAndDispatch() )
                {
                }
            }
            Thread.sleep( 100 );
        }
    }

    private String buildLaunchName( IJavaProject project, List<IType> testClasses )
    {
        return buildLaunchName( project, null, testClasses );
    }

    private String buildLaunchName( IJavaProject project, IPackageFragment pkg, List<IType> testClasses )
    {
        String base = "AssistAI-PDE-" + project.getElementName();
        if ( testClasses.size() == 1 )
        {
            return base + "-" + testClasses.get( 0 ).getElementName();
        }
        if ( testClasses.size() > 1 )
        {
            List<String> classNames = testClasses.stream()
                .map( IType::getFullyQualifiedName )
                .toList();
            return base + "-Selected-" + testClasses.size() + "-"
                + Integer.toHexString( classNames.hashCode() );
        }
        if ( pkg != null )
        {
            return base + "-" + pkg.getElementName();
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

    private IPackageFragment findPackage( IJavaProject javaProject, String packageName )
        throws JavaModelException
    {
        for ( IPackageFragmentRoot root : javaProject.getPackageFragmentRoots() )
        {
            if ( root.getKind() == IPackageFragmentRoot.K_SOURCE )
            {
                IPackageFragment pkg = root.getPackageFragment( packageName );
                if ( pkg.exists() )
                {
                    return pkg;
                }
            }
        }
        return null;
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
