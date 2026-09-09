package com.github.gradusnikov.eclipse.assistai.mcp.services;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.Optional;

import com.github.gradusnikov.eclipse.assistai.mcp.McpJson;
import com.github.gradusnikov.eclipse.assistai.mcp.operations.Operation;
import com.github.gradusnikov.eclipse.assistai.mcp.operations.OperationContext;
import com.github.gradusnikov.eclipse.assistai.mcp.operations.ProcessOutputSource;
import com.github.gradusnikov.eclipse.assistai.mcp.results.ActiveTargetResponse;
import com.github.gradusnikov.eclipse.assistai.mcp.results.Diagnostic;
import com.github.gradusnikov.eclipse.assistai.mcp.results.DiagnosticCode;
import com.github.gradusnikov.eclipse.assistai.mcp.results.TestRunResponse;
import com.github.gradusnikov.eclipse.assistai.mcp.results.TestRunResponse.CoverageResult;
import com.github.gradusnikov.eclipse.assistai.mcp.results.TestRunResponse.RunStatus;
import com.github.gradusnikov.eclipse.assistai.mcp.results.TestRunResponse.TestSummary;

import java.util.concurrent.TimeUnit;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IWorkspace;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.ILog;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.core.runtime.Path;
import org.eclipse.core.runtime.jobs.IJobChangeEvent;
import org.eclipse.core.runtime.jobs.JobChangeAdapter;
import org.eclipse.debug.core.DebugPlugin;
import org.eclipse.debug.core.ILaunch;
import org.eclipse.debug.core.ILaunchListener;
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

    /** How long we wait for a target platform to load before giving up on it. */
    private static final int TARGET_LOAD_TIMEOUT_SECONDS = 120;

    /**
     * The target platform the workspace is building against, or the running-platform
     * state when no target file is set.
     */
    public ActiveTargetResponse getActiveTarget()
    {
        try
        {
            return describeWorkspaceTarget();
        }
        catch ( Exception e )
        {
            return ActiveTargetResponse.failed( Diagnostic.fatal( DiagnosticCode.INTERNAL_ERROR,
                "Could not read the active target platform: " + e.getMessage() ) );
        }
    }

    /**
     * Sets the active target platform to the given workspace-relative target file path
     * (e.g. "MyProject/my.target"). The job runs asynchronously; this method waits up to
     * {@link #TARGET_LOAD_TIMEOUT_SECONDS} seconds for it and then reports the target
     * that is in force - which, when the load failed, is not the one that was asked for.
     *
     * @param targetFilePath workspace-relative path to the .target file
     */
    public ActiveTargetResponse setActiveTarget( String targetFilePath )
    {
        Objects.requireNonNull( targetFilePath, "Target file path cannot be null" );

        try
        {
            IWorkspace workspace = ResourcesPlugin.getWorkspace();
            IFile file = workspace.getRoot().getFile( new Path( targetFilePath ) );

            if ( !file.exists() )
            {
                return failure( Diagnostic.fatal( DiagnosticCode.RESOURCE_NOT_FOUND,
                    "Target file not found in workspace: " + targetFilePath ) );
            }

            ITargetPlatformService service = getTargetPlatformService();
            return load( service.getTarget( file ), "load" );
        }
        catch ( Exception e )
        {
            return failure( Diagnostic.fatal( DiagnosticCode.INTERNAL_ERROR,
                "Error setting active target: " + e.getMessage() ) );
        }
    }

    /**
     * Reloads (resolves) the currently active target platform.
     * Should be called after modifying the active .target file.
     */
    public ActiveTargetResponse reloadTarget()
    {
        try
        {
            ITargetPlatformService service = getTargetPlatformService();
            ITargetHandle handle = service.getWorkspaceTargetHandle();

            if ( handle == null )
            {
                // Not a failure. A workspace with no target file builds against the
                // running platform - the same state getActiveTarget has always reported
                // as ordinary. There is nothing to reload and nothing wrong.
                return ActiveTargetResponse.runningPlatform();
            }
            return load( handle, "reload" );
        }
        catch ( Exception e )
        {
            return failure( Diagnostic.fatal( DiagnosticCode.INTERNAL_ERROR,
                "Error reloading target: " + e.getMessage() ) );
        }
    }

    /**
     * Loads a target definition, waits for the job, and reports what is in force
     * afterwards. Shared by set and reload, which differ only in where the handle comes
     * from - and which previously each carried their own copy of the wait, the timeout
     * and four error sentences.
     *
     * @param action the verb for the messages: "load" or "reload"
     */
    private ActiveTargetResponse load( ITargetHandle handle, String action ) throws CoreException
    {
        ITargetDefinition definition = handle.getTargetDefinition();
        CountDownLatch latch = new CountDownLatch( 1 );
        IStatus[] jobResult = new IStatus[1];

        LoadTargetDefinitionJob.load( definition, new JobChangeAdapter()
        {
            @Override
            public void done( IJobChangeEvent event )
            {
                jobResult[0] = event.getResult();
                latch.countDown();
            }
        } );

        boolean completed;
        try
        {
            completed = latch.await( TARGET_LOAD_TIMEOUT_SECONDS, TimeUnit.SECONDS );
        }
        catch ( InterruptedException e )
        {
            Thread.currentThread().interrupt();
            return failure( Diagnostic.retryable( DiagnosticCode.INTERNAL_ERROR,
                "Interrupted while waiting for the target platform to " + action + "." ) );
        }

        if ( !completed )
        {
            // We stopped waiting; the job did not stop working. Whatever it eventually
            // does, what is in force at this moment is what the response describes.
            return failure( Diagnostic.retryable( DiagnosticCode.OPERATION_TIMED_OUT,
                "Timed out after " + TARGET_LOAD_TIMEOUT_SECONDS + "s waiting for the target platform to "
                    + action + ". The load job may still be running." ) );
        }
        if ( jobResult[0] != null && !jobResult[0].isOK() )
        {
            return failure( Diagnostic.fatal( DiagnosticCode.DEPENDENCY_RESOLUTION_FAILED,
                "The target platform failed to " + action + ": " + jobResult[0].getMessage() ) );
        }
        return describeWorkspaceTarget();
    }

    /** What the workspace is building against right now. */
    private ActiveTargetResponse describeWorkspaceTarget() throws CoreException
    {
        ITargetPlatformService service = getTargetPlatformService();
        ITargetHandle handle = service.getWorkspaceTargetHandle();
        if ( handle == null )
        {
            return ActiveTargetResponse.runningPlatform();
        }

        // Ask for the definition PDE holds, not handle.getTargetDefinition(): the handle
        // re-reads the .target file into a new, never-resolved instance on every call, so
        // "resolved" from it is always false - even straight after LoadTargetDefinitionJob
        // resolved the workspace target, which is the very thing reload is asked about.
        ITargetDefinition definition = service.getWorkspaceTargetDefinition();
        if ( definition == null )
        {
            definition = handle.getTargetDefinition();
        }
        boolean resolved = definition.isResolved();
        Integer bundleCount = null;
        if ( resolved )
        {
            // Only meaningful once resolved: 0 would say "the target contains nothing",
            // which is a different and much worse answer than "not resolved yet".
            var bundles = definition.getBundles();
            bundleCount = bundles != null ? bundles.length : 0;
        }
        return ActiveTargetResponse.active( definition.getName(), handle.getMemento(), handle.exists(),
            resolved, bundleCount );
    }

    /**
     * A failure that still says which target platform is in force. That is the caller's
     * next question after a set or reload did not happen, and the prose this replaces
     * never answered it - nor, since it was returned rather than thrown, did it even
     * reach the caller as a failure.
     */
    private ActiveTargetResponse failure( Diagnostic diagnostic )
    {
        try
        {
            return describeWorkspaceTarget().withFailure( diagnostic );
        }
        catch ( Exception e )
        {
            return ActiveTargetResponse.failed( diagnostic );
        }
    }

    // -------------------------------------------------------------------------
    // JUnit Plug-in Test
    // -------------------------------------------------------------------------

    /**
     * Runs all JUnit Plug-in Tests in the given project.
     */
    public TestRunResponse runJUnitPluginTests( String projectName, Integer timeout )
    {
        return runJUnitPluginTests( projectName, timeout, false, false, List.of() );
    }

    public TestRunResponse runJUnitPluginTests( String projectName, Integer timeout, boolean withCoverage )
    {
        return runJUnitPluginTests( projectName, timeout, withCoverage, false, List.of() );
    }

    public TestRunResponse runJUnitPluginTests( String projectName, Integer timeout, boolean withCoverage,
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
    public TestRunResponse runJUnitPluginTests( String projectName, Integer timeout, boolean withCoverage,
                                        boolean includeAllPlugins, List<String> additionalBundles,
                                        String launcherName )
    {
        Objects.requireNonNull( projectName, "Project name cannot be null" );
        if ( projectName.isEmpty() )
        {
            throw new IllegalArgumentException( "Project name cannot be empty" );
        }
        int waitSeconds = normalizeTimeout( timeout );
        long startMillis = System.currentTimeMillis();

        try
        {
            IJavaProject javaProject = getJavaProject( projectName );
            return launchJUnitPluginTests( javaProject, null, List.of(), waitSeconds, withCoverage,
                includeAllPlugins, additionalBundles, launcherName );
        }
        catch ( ProjectNotFoundException e )
        {
            return TestRunResponse.notStarted( projectName, List.of(),
                Diagnostic.fatal( DiagnosticCode.PROJECT_NOT_FOUND, e.getMessage() ), elapsed( startMillis ) );
        }
        catch ( IllegalArgumentException | CoreException e )
        {
            return TestRunResponse.notStarted( projectName, List.of(),
                Diagnostic.fatal( DiagnosticCode.INTERNAL_ERROR, "Error running plug-in tests: " + e.getMessage() ),
                elapsed( startMillis ) );
        }
    }

    /**
     * Runs JUnit Plug-in Tests for a specific class.
     */
    public TestRunResponse runJUnitPluginTestClass( String projectName, String className, Integer timeout )
    {
        return runJUnitPluginTestClass( projectName, className, timeout, false, false, List.of() );
    }

    public TestRunResponse runJUnitPluginTestClass( String projectName, String className, Integer timeout,
                                            boolean withCoverage )
    {
        return runJUnitPluginTestClass( projectName, className, timeout, withCoverage, false, List.of() );
    }

    public TestRunResponse runJUnitPluginTestClass( String projectName, String className, Integer timeout,
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
    public TestRunResponse runJUnitPluginTestClass( String projectName, String className, Integer timeout,
                                            boolean withCoverage, boolean includeAllPlugins,
                                            List<String> additionalBundles, String launcherName )
    {
        Objects.requireNonNull( projectName, "Project name cannot be null" );
        Objects.requireNonNull( className, "Class name cannot be null" );
        int waitSeconds = normalizeTimeout( timeout );
        long startMillis = System.currentTimeMillis();

        try
        {
            IJavaProject javaProject = getJavaProject( projectName );
            IType type = javaProject.findType( className );
            if ( type == null )
            {
                return TestRunResponse.notStarted( projectName, List.of( className ),
                    Diagnostic.fatal( DiagnosticCode.TEST_CLASS_NOT_FOUND,
                        "Class '" + className + "' not found in project '" + projectName + "'." ),
                    elapsed( startMillis ) );
            }
            return launchJUnitPluginTests( javaProject, null, List.of( type ), waitSeconds, withCoverage,
                includeAllPlugins, additionalBundles, launcherName );
        }
        catch ( ProjectNotFoundException e )
        {
            return TestRunResponse.notStarted( projectName, List.of( className ),
                Diagnostic.fatal( DiagnosticCode.PROJECT_NOT_FOUND, e.getMessage() ), elapsed( startMillis ) );
        }
        catch ( IllegalArgumentException | CoreException e )
        {
            return TestRunResponse.notStarted( projectName, List.of( className ),
                Diagnostic.fatal( DiagnosticCode.INTERNAL_ERROR, "Error running plug-in tests: " + e.getMessage() ),
                elapsed( startMillis ) );
        }
    }

    /**
     * Runs a single JUnit Plug-in Test method.
     */
    public TestRunResponse runJUnitPluginTestMethod( String projectName, String className, String methodName,
                                                     Integer timeout )
    {
        return runJUnitPluginTestMethod( projectName, className, methodName, timeout, false, false, List.of(), null );
    }

    /**
     * Runs a single JUnit Plug-in Test method, optionally using a saved launch configuration as a base.
     *
     * @param launcherName optional saved launch config name; when set all its settings are
     *                     reused and only the project/class/method targeting attributes are overridden
     */
    public TestRunResponse runJUnitPluginTestMethod( String projectName, String className, String methodName,
                                                     Integer timeout, boolean withCoverage,
                                                     boolean includeAllPlugins, List<String> additionalBundles,
                                                     String launcherName )
    {
        Objects.requireNonNull( projectName, "Project name cannot be null" );
        Objects.requireNonNull( className, "Class name cannot be null" );
        Objects.requireNonNull( methodName, "Method name cannot be null" );
        int waitSeconds = normalizeTimeout( timeout );
        long startMillis = System.currentTimeMillis();

        try
        {
            IJavaProject javaProject = getJavaProject( projectName );
            IType type = javaProject.findType( className );
            if ( type == null )
            {
                return TestRunResponse.notStarted( projectName, List.of( className ),
                    Diagnostic.fatal( DiagnosticCode.TEST_CLASS_NOT_FOUND,
                        "Class '" + className + "' not found in project '" + projectName + "'." ),
                    elapsed( startMillis ) );
            }
            return launchJUnitPluginTests( javaProject, null, List.of( type ), methodName, waitSeconds,
                withCoverage, includeAllPlugins, additionalBundles, launcherName );
        }
        catch ( ProjectNotFoundException e )
        {
            return TestRunResponse.notStarted( projectName, List.of( className ),
                Diagnostic.fatal( DiagnosticCode.PROJECT_NOT_FOUND, e.getMessage() ), elapsed( startMillis ) );
        }
        catch ( IllegalArgumentException | CoreException e )
        {
            return TestRunResponse.notStarted( projectName, List.of( className ),
                Diagnostic.fatal( DiagnosticCode.INTERNAL_ERROR, "Error running plug-in tests: " + e.getMessage() ),
                elapsed( startMillis ) );
        }
    }

    /**
     * Runs selected JUnit Plug-in Test classes in a single PDE launch.
     */
    public TestRunResponse runJUnitPluginTestClasses( String projectName, List<String> classNames,
                                             Integer timeout )
    {
        return runJUnitPluginTestClasses( projectName, classNames, timeout, false, List.of() );
    }

    public TestRunResponse runJUnitPluginTestClasses( String projectName, List<String> classNames,
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
    public TestRunResponse runJUnitPluginTestClasses( String projectName, List<String> classNames,
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
        int waitSeconds = normalizeTimeout( timeout );
        long startMillis = System.currentTimeMillis();

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
                return TestRunResponse.notStarted( projectName, normalizedClassNames,
                    Diagnostic.fatal( DiagnosticCode.TEST_CLASS_NOT_FOUND,
                        "Test classes not found in project '" + projectName + "': "
                            + String.join( ", ", missingClassNames ) ),
                    elapsed( startMillis ) );
            }

            return launchJUnitPluginTests( javaProject, null, testClasses, waitSeconds, false,
                includeAllPlugins, additionalBundles, launcherName );
        }
        catch ( ProjectNotFoundException e )
        {
            return TestRunResponse.notStarted( projectName, normalizedClassNames,
                Diagnostic.fatal( DiagnosticCode.PROJECT_NOT_FOUND, e.getMessage() ), elapsed( startMillis ) );
        }
        catch ( IllegalArgumentException | CoreException e )
        {
            return TestRunResponse.notStarted( projectName, normalizedClassNames,
                Diagnostic.fatal( DiagnosticCode.INTERNAL_ERROR, "Error running plug-in tests: " + e.getMessage() ),
                elapsed( startMillis ) );
        }
    }


    /**
     * Runs JUnit Plug-in Tests for all test classes in a specific package.
     */
    public TestRunResponse runJUnitPluginTestPackage( String projectName, String packageName,
                                              Integer timeout )
    {
        return runJUnitPluginTestPackage( projectName, packageName, timeout, false, false, List.of() );
    }

    public TestRunResponse runJUnitPluginTestPackage( String projectName, String packageName,
                                              Integer timeout, boolean withCoverage )
    {
        return runJUnitPluginTestPackage( projectName, packageName, timeout, withCoverage, false, List.of() );
    }

    public TestRunResponse runJUnitPluginTestPackage( String projectName, String packageName,
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
    public TestRunResponse runJUnitPluginTestPackage( String projectName, String packageName,
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
        int waitSeconds = normalizeTimeout( timeout );
        long startMillis = System.currentTimeMillis();

        try
        {
            IJavaProject javaProject = getJavaProject( projectName );
            IPackageFragment pkg = findPackage( javaProject, packageName );
            if ( pkg == null )
            {
                return TestRunResponse.notStarted( projectName, List.of(),
                    Diagnostic.fatal( DiagnosticCode.TEST_PACKAGE_NOT_FOUND,
                        "Package '" + packageName + "' not found in project '" + projectName + "'." ),
                    elapsed( startMillis ) );
            }
            return launchJUnitPluginTests( javaProject, pkg, List.of(), waitSeconds, withCoverage,
                includeAllPlugins, additionalBundles, launcherName );
        }
        catch ( ProjectNotFoundException e )
        {
            return TestRunResponse.notStarted( projectName, List.of(),
                Diagnostic.fatal( DiagnosticCode.PROJECT_NOT_FOUND, e.getMessage() ), elapsed( startMillis ) );
        }
        catch ( IllegalArgumentException | CoreException e )
        {
            return TestRunResponse.notStarted( projectName, List.of(),
                Diagnostic.fatal( DiagnosticCode.INTERNAL_ERROR, "Error running plug-in tests: " + e.getMessage() ),
                elapsed( startMillis ) );
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

    /**
     * How long to keep waiting, after the test instance exits with code 23, for PDE to
     * launch it again. Equinox exits with 23 (IApplication.EXIT_RESTART) when it had to
     * rewire bundles on first boot, and PDE's LaunchListener honours that by relaunching
     * the configuration - but only once the debug framework's asynchronous TERMINATE
     * event reaches it, which can be well after the launch reports itself terminated.
     */
    private static final long RESTART_GRACE_MILLIS = 15_000;

    /**
     * JDT's own test-container attribute. Not exposed as API by
     * {@code org.eclipse.jdt.junit}, so it is a string literal wherever it is used - once,
     * here.
     */
    private static final String JDT_JUNIT_CONTAINER = "org.eclipse.jdt.junit.CONTAINER";

    private TestRunResponse launchJUnitPluginTests( IJavaProject javaProject, IPackageFragment packageFragment,
                                            List<IType> testClasses, int timeout, boolean withCoverage,
                                            boolean includeAllPlugins, List<String> additionalBundles )
    {
        return launchJUnitPluginTests( javaProject, packageFragment, testClasses, null, timeout,
            withCoverage, includeAllPlugins, additionalBundles, null );
    }

    /**
     * Core PDE JUnit launch. When {@code methodName} is non-null, only that single test method
     * is executed via {@link SelectedJUnitPluginLaunchDelegate} for true single-method isolation.
     * Delegates to the main overload with methodName forwarded through targeting.
     */
    private TestRunResponse launchJUnitPluginTests( IJavaProject javaProject, IPackageFragment packageFragment,
                                            List<IType> testClasses, String methodName, int timeout,
                                            boolean withCoverage, boolean includeAllPlugins,
                                            List<String> additionalBundles, String launcherName )
    {
        return launchJUnitPluginTests( javaProject, packageFragment, testClasses, methodName, timeout,
            withCoverage, includeAllPlugins, additionalBundles, launcherName, true );
    }

    /**
     * Core PDE JUnit launch. When {@code launcherName} is non-null, that saved configuration is
     * used as a base and only the test targeting attributes are overridden.
     */
    private TestRunResponse launchJUnitPluginTests( IJavaProject javaProject, IPackageFragment packageFragment,
                                            List<IType> testClasses, int timeout, boolean withCoverage,
                                            boolean includeAllPlugins, List<String> additionalBundles,
                                            String launcherName )
    {
        return launchJUnitPluginTests( javaProject, packageFragment, testClasses, null, timeout,
            withCoverage, includeAllPlugins, additionalBundles, launcherName, false );
    }

    /**
     * Core PDE JUnit launch — single entry point.
     * When {@code launcherName} is non-null, that saved configuration is used as a base.
     * When {@code methodName} is non-null and {@code singleMethod} is true, only that method
     * is executed via {@link SelectedJUnitPluginLaunchDelegate}.
     */
    private TestRunResponse launchJUnitPluginTests( IJavaProject javaProject, IPackageFragment packageFragment,
                                            List<IType> testClasses, String methodName, int timeout,
                                            boolean withCoverage, boolean includeAllPlugins,
                                            List<String> additionalBundles, String launcherName,
                                            boolean singleMethod )
    {
        CountDownLatch latch = new CountDownLatch( 1 );
        UnitTestService.TestRunResult[] testRunResults = new UnitTestService.TestRunResult[1];
        Optional<Operation> operation = OperationContext.current();
        AtomicInteger finishedTests = new AtomicInteger();
        String projectName = javaProject.getProject().getName();
        List<String> requestedClasses = testClasses.stream()
            .map( IType::getFullyQualifiedName )
            .toList();
        // Wall clock for the whole operation. Kept apart from launchStartTime below,
        // which is the baseline for matching a coverage file by modification time and
        // must not be moved earlier or an older .exec file starts matching.
        long runStartMillis = System.currentTimeMillis();

        TestRunListener listener = new TestRunListener()
        {
            private UnitTestService.TestRunResult currentRun = null;

            @Override
            public void sessionStarted( ITestRunSession session )
            {
                currentRun = new UnitTestService.TestRunResult( session.getTestRunName() );
                // Published as soon as the session exists, not when it finishes, so a run
                // that is cancelled or times out still reports the tests that did run.
                // Null therefore means "the session never started".
                testRunResults[0] = currentRun;
                operation.ifPresent( op -> op.setProgress( "test session started" ) );
            }

            @Override
            public void sessionFinished( ITestRunSession session )
            {
                latch.countDown();
            }

            @Override
            public void testCaseFinished( ITestCaseElement testCaseElement )
            {
                if ( currentRun != null )
                {
                    String clazz = testCaseElement.getTestClassName();
                    String testName = testCaseElement.getTestMethodName();
                    currentRun.addTestResult(
                        UnitTestService.collectTestResult( javaProject, testCaseElement ) );
                    int count = finishedTests.incrementAndGet();
                    operation.ifPresent( op -> {
                        op.setProgress( count + " tests finished; last: " + clazz + "#" + testName );
                        // Publish structured intermediate results so getOperationStatus can
                        // surface pass/fail counts and the failures so far while the run is
                        // still going. RUNNING exists for exactly this snapshot.
                        TestRunResponse live = currentRun.snapshot( RunStatus.RUNNING, projectName,
                            requestedClasses, null, List.of(), elapsed( runStartMillis ) );
                        op.setIntermediateResult( "summary", McpJson.toJson( live.summary() ) );
                        op.setIntermediateResult( "results", McpJson.toJson( live ) );
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
                // Use the named saved config as a base ΓÇö only override targeting attributes
                ILaunchConfiguration base = findExistingLaunchConfig( launchManager, launcherName );
                if ( base == null )
                {
                    return TestRunResponse.notStarted( projectName, requestedClasses,
                        Diagnostic.fatal( DiagnosticCode.LAUNCH_CONFIGURATION_NOT_FOUND,
                            "Launch configuration not found: " + launcherName ),
                        elapsed( runStartMillis ) );
                }
                workingCopy = base.getWorkingCopy();
            }
            else
            {
                boolean selectedClassLaunch = testClasses.size() > 1;
                boolean singleMethodLaunch = singleMethod && testClasses.size() == 1
                    && methodName != null && !methodName.isBlank();
                String launchTypeId = ( selectedClassLaunch || singleMethodLaunch )
                    ? SelectedJUnitPluginLaunchDelegate.LAUNCH_CONFIGURATION_TYPE
                    : "org.eclipse.pde.ui.JunitLaunchConfig";
                ILaunchConfigurationType type = launchManager.getLaunchConfigurationType( launchTypeId );

                if ( type == null )
                {
                    return TestRunResponse.notStarted( projectName, requestedClasses,
                        Diagnostic.fatal( DiagnosticCode.PDE_LAUNCH_TYPE_MISSING,
                            "PDE JUnit Plug-in Test launch configuration type '" + launchTypeId
                                + "' not found. Ensure the required PDE launcher is available." ),
                        elapsed( runStartMillis ) );
                }

                String launchName = buildLaunchName( javaProject, packageFragment, testClasses );
                ILaunchConfiguration existing = findExistingLaunchConfig( launchManager, launchName );
                if ( existing != null && existing.getType().equals(type) )
                {
                    workingCopy = existing.getWorkingCopy();
                }
                else
                {
                    workingCopy = type.newInstance( null, launchName );
                }
            }

            // Always override targeting attributes — everything else from the base config is kept
            applyTestTargeting( workingCopy, javaProject, packageFragment, testClasses, methodName );

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

            // Log workspace location so it's visible in test output and MCP tool results
            String wsLocation = configuration.getAttribute( IPDELauncherConstants.LOCATION, (String) null );
            assert wsLocation != null;
            System.out.println( "[PDEService] Test workspace: " + wsLocation );

            boolean useCoverage = withCoverage && coverageService.isCoverageAvailable();
            String launchMode = useCoverage ? coverageService.getCoverageLaunchMode() : ILaunchManager.RUN_MODE;

            // Equinox can request a restart (exit code 23 = IApplication.EXIT_RESTART) on the
            // first boot of a workspace when it needs to rewire bundles (reused workspace/config).
            // Watch for the auto-restart launch: when our launch exits with code 23, PDE's
            // LaunchListener.launchTerminated() synchronously calls doRestart(), which creates a
            // new ILaunch for the same config with IPDEConstants.RESTART=true. Capture it here.
            String configName = configuration.getName();
            org.eclipse.debug.core.ILaunch[] launchRef = new org.eclipse.debug.core.ILaunch[1];
            org.eclipse.debug.core.ILaunch[] restartLaunchRef = new org.eclipse.debug.core.ILaunch[1];
            ILaunchListener restartLaunchListener = new ILaunchListener()
            {
                @Override
                public void launchAdded( ILaunch launch )
                {
                    try
                    {
                        ILaunchConfiguration lc = launch.getLaunchConfiguration();
                        if ( launchRef[0] != null && launchRef[0].isTerminated()
                                && getRawExitCode( launchRef[0] ) == org.eclipse.equinox.app.IApplication.EXIT_RESTART
                                && lc != null && lc.getName().equals( configName )
                                && lc.getAttribute( org.eclipse.pde.internal.launching.IPDEConstants.RESTART, false ) )
                        {
                            restartLaunchRef[0] = launch;
                        }
                    }
                    catch ( CoreException ignored ) {}
                }
                @Override public void launchRemoved( ILaunch launch ) {}
                @Override public void launchChanged( ILaunch launch ) {}
            };

            ILaunchManager launchManagerForListener = DebugPlugin.getDefault().getLaunchManager();
            launchManagerForListener.addLaunchListener( restartLaunchListener );
            long launchStartTime = System.currentTimeMillis();
            CoreException[] launchError = new CoreException[1];
            try
            {
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
                long restartGraceDeadline = 0;
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
                        // canceling it terminate the JVM.
                        attached = true;
                        org.eclipse.debug.core.ILaunch launched = launchRef[0];
                        operation.ifPresent( op -> ProcessOutputSource.attach( op, launched ) );
                    }
                    completed = latch.await( 100, TimeUnit.MILLISECONDS );
                    if ( !completed && launchRef[0] != null && launchRef[0].isTerminated() )
                    {
                        // When Equinox needs to rewire bundles it exits with code 23 and PDE's
                        // LaunchListener launches the same configuration again with RESTART=true.
                        // It does that from the process's TERMINATE debug event, which is
                        // dispatched asynchronously - so the launch can already report itself
                        // terminated here while the restart launch does not exist yet. Concluding
                        // at that instant reported "no test results, exit code 23" for a run that
                        // was about to succeed. Give the listener a bounded grace period instead.
                        if ( testRunResults[0] == null
                            && getRawExitCode( launchRef[0] ) == org.eclipse.equinox.app.IApplication.EXIT_RESTART )
                        {
                            if ( restartLaunchRef[0] != null )
                            {
                                String msg = "[PDEService] Equinox requested restart (exit 23) —"
                                    + " bundle cache is now warm, following auto-restarted launch";
                                System.out.println( msg );
                                operation.ifPresent( op -> op.setProgress( msg ) );
                                launchRef[0] = restartLaunchRef[0];
                                restartLaunchRef[0] = null;
                                attached = false; // re-attach process output to the new launch
                                restartGraceDeadline = 0;
                            }
                            else if ( restartGraceDeadline == 0 )
                            {
                                restartGraceDeadline = System.currentTimeMillis() + RESTART_GRACE_MILLIS;
                            }
                            else if ( System.currentTimeMillis() >= restartGraceDeadline )
                            {
                                completed = true;
                            }
                        }
                        else
                        {
                            completed = true;
                        }
                    }
                }

                if ( launchError[0] != null )
                {
                    return TestRunResponse.notStarted( projectName, requestedClasses,
                        Diagnostic.fatal( DiagnosticCode.DEPENDENCY_RESOLUTION_FAILED,
                            "Error launching plug-in tests: " + launchError[0].getMessage() ),
                        elapsed( runStartMillis ) );
                }
                if ( !completed )
                {
                    // Whatever was collected before the deadline is still reported: a run
                    // that timed out after 30 of 40 tests knows more than "it timed out".
                    return abandoned( testRunResults[0], RunStatus.TIMED_OUT, projectName, requestedClasses,
                        Diagnostic.retryable( DiagnosticCode.TEST_RESULTS_NOT_REPORTED,
                            "The test run did not report results in time." ),
                        runStartMillis );
                }

                // The test session can finish just before the workbench process releases
                // its workspace lock. Wait briefly so an immediate rerun of the same
                // selection can safely reuse its workspace.
                waitForLaunchTermination( launchRef[0] );

                if ( testRunResults[0] == null )
                {
                    String exitHint = launchRef[0] == null ? ""
                        : "\n\nEclipse exit code: " + getExitCode( launchRef[0] );
                    if ( launchRef[0] != null
                        && getRawExitCode( launchRef[0] ) == org.eclipse.equinox.app.IApplication.EXIT_RESTART )
                    {
                        exitHint += "\nExit code 23 is Equinox asking for a restart; PDE did not launch the"
                            + " configuration again within " + RESTART_GRACE_MILLIS / 1000 + "s.";
                    }
                    return TestRunResponse.notStarted( projectName, requestedClasses,
                        Diagnostic.fatal( DiagnosticCode.TEST_RESULTS_NOT_REPORTED,
                            "No test results collected. The test run may have failed to start."
                                + "\nCheck the console output of the launch or the workspace log file."
                                + exitHint ),
                        elapsed( runStartMillis ) );
                }

                CoverageResult coverage = null;
                if ( withCoverage && !useCoverage )
                {
                    coverage = CoverageResult.unavailable();
                }
                else if ( useCoverage )
                {
                    String execFile = coverageService.waitForLatestCoverageFile( launchStartTime, 10000 );
                    coverage = CoverageResult.of( execFile,
                        coverageService.formatCoverageInfo( execFile, projectName ) );
                }

                List<Diagnostic> diagnostics = coverage != null && !coverage.available()
                    ? List.of( Diagnostic.fatal( DiagnosticCode.COVERAGE_UNAVAILABLE,
                        "Coverage was requested but no coverage tooling (EclEmma/JaCoCo) is installed." ) )
                    : List.of();

                TestSummary counts = testRunResults[0].summary();
                return testRunResults[0].snapshot( TestRunResponse.terminalStatus( counts ), projectName,
                    requestedClasses, coverage, diagnostics, elapsed( runStartMillis ) );
            }
            finally
            {
                launchManagerForListener.removeLaunchListener( restartLaunchListener );
            }
        }
        catch ( InterruptedException e )
        {
            // cancelOperation interrupts this thread; the test instance itself is
            // terminated by the operation's cancel hook.
            Thread.currentThread().interrupt();
            return abandoned( testRunResults[0], RunStatus.CANCELLED, projectName, requestedClasses,
                Diagnostic.fatal( DiagnosticCode.TEST_RESULTS_NOT_REPORTED, "Test run cancelled." ),
                runStartMillis );
        }
        catch ( Exception e )
        {
            logger.error( "Error running plug-in tests", e );
            return TestRunResponse.notStarted( projectName, requestedClasses,
                Diagnostic.fatal( DiagnosticCode.INTERNAL_ERROR,
                    "Error running plug-in tests: " + e.getMessage() ),
                elapsed( runStartMillis ) );
        }
        finally
        {
            JUnitCore.removeTestRunListener( listener );
        }
    }

    /**
     * Writes the attributes that say <em>what to run</em> onto a launch configuration.
     * Everything else - bundles, workspace location, VM arguments - is left as the base
     * configuration had it.
     * <p>
     * Extracted from the launch so that its regression test can assert on the
     * configuration the product builds rather than on a second copy of these rules,
     * which is how the selected-classes defect below survived three attempts: the test
     * exercised {@code evaluateTests} in isolation, and the defect was in what JDT does
     * <em>before</em> calling it.
     */
    static void applyTestTargeting( ILaunchConfigurationWorkingCopy workingCopy, IJavaProject javaProject,
                                    IPackageFragment packageFragment, List<IType> testClasses )
    {
        applyTestTargeting( workingCopy, javaProject, packageFragment, testClasses, null );
    }

    /**
     * Writes the attributes that say <em>what to run</em> onto a launch configuration.
     * When {@code methodName} is non-null the launch is scoped to a single method on a
     * single class, using {@link SelectedJUnitPluginLaunchDelegate} so that
     * {@code evaluateTests} returns an {@code IMethod} for true single-method isolation.
     */
    static void applyTestTargeting( ILaunchConfigurationWorkingCopy workingCopy, IJavaProject javaProject,
                                    IPackageFragment packageFragment, List<IType> testClasses,
                                    String methodName )
    {
        workingCopy.setAttribute( IJavaLaunchConfigurationConstants.ATTR_PROJECT_NAME,
            javaProject.getElementName() );

        boolean singleMethodLaunch = methodName != null && !methodName.isBlank()
            && testClasses.size() == 1;

        if ( singleMethodLaunch )
        {
            // Use SelectedJUnitPluginLaunchDelegate so evaluateTests returns IMethod,
            // giving JDT's runner true single-method isolation.
            workingCopy.setAttribute( SelectedJUnitPluginLaunchDelegate.ATTR_TEST_CLASSES,
                List.of( testClasses.get( 0 ).getFullyQualifiedName() ) );
            workingCopy.setAttribute( SelectedJUnitPluginLaunchDelegate.ATTR_TEST_METHOD, methodName );
            // ATTR_MAIN_TYPE_NAME must name an existing type for JDT's pre-flight
            // type resolution (see multi-class comment below for why it cannot be blank).
            workingCopy.setAttribute( IJavaLaunchConfigurationConstants.ATTR_MAIN_TYPE_NAME,
                testClasses.get( 0 ).getFullyQualifiedName() );
            workingCopy.setAttribute( JDT_JUNIT_CONTAINER, "" );
        }
        else if ( testClasses.size() > 1 )
        {
            List<String> classNames = testClasses.stream()
                .map( IType::getFullyQualifiedName )
                .toList();
            workingCopy.setAttribute( SelectedJUnitPluginLaunchDelegate.ATTR_TEST_CLASSES, classNames );

            // JDT resolves a test target from the configuration BEFORE it asks the
            // delegate. For a JUnit 5 or 6 test kind,
            // JUnitLaunchConfigurationDelegate.getVMRunnerConfiguration calls the private
            // final getTestTarget first, which reads CONTAINER and then MAIN_TYPE_NAME and
            // aborts with "The input type of the launch configuration does not exist" when
            // both are blank. Blanking both is what this branch used to do, which is why
            // the comma-separated className form never ran.
            //
            // The first selected class is named here only to give that resolution
            // something that exists. It must be a type, not the project or package
            // handle: a container-shaped target is taken as the answer outright and
            // evaluateTests is never called, so CONTAINER here would silently run every
            // test in the project. A type is not a container, so JDT goes on to call
            // evaluateTests - and SelectedJUnitPluginLaunchDelegate widens it back to the
            // whole list.
            workingCopy.setAttribute( IJavaLaunchConfigurationConstants.ATTR_MAIN_TYPE_NAME,
                testClasses.get( 0 ).getFullyQualifiedName() );
            workingCopy.setAttribute( JDT_JUNIT_CONTAINER, "" );
        }
        else if ( !testClasses.isEmpty() )
        {
            workingCopy.setAttribute( SelectedJUnitPluginLaunchDelegate.ATTR_TEST_CLASSES,
                List.<String>of() );
            workingCopy.setAttribute( IJavaLaunchConfigurationConstants.ATTR_MAIN_TYPE_NAME,
                testClasses.get( 0 ).getFullyQualifiedName() );
            workingCopy.setAttribute( JDT_JUNIT_CONTAINER, "" );
        }
        else if ( packageFragment != null )
        {
            // Package scope: set CONTAINER to the package's handle identifier.
            // The JDT JUnit launcher resolves CONTAINER via JavaCore.create(handleId),
            // which works for IPackageFragment handles as well as project handles.
            workingCopy.setAttribute( SelectedJUnitPluginLaunchDelegate.ATTR_TEST_CLASSES,
                List.<String>of() );
            workingCopy.setAttribute( IJavaLaunchConfigurationConstants.ATTR_MAIN_TYPE_NAME, "" );
            workingCopy.setAttribute( JDT_JUNIT_CONTAINER, packageFragment.getHandleIdentifier() );
        }
        else
        {
            workingCopy.setAttribute( SelectedJUnitPluginLaunchDelegate.ATTR_TEST_CLASSES,
                List.<String>of() );
            workingCopy.setAttribute( IJavaLaunchConfigurationConstants.ATTR_MAIN_TYPE_NAME, "" );
            // CONTAINER must be the Java element handle identifier for the project:
            // "=<projectName>" is the format JDT JUnit launcher expects
            workingCopy.setAttribute( JDT_JUNIT_CONTAINER, javaProject.getHandleIdentifier() );
        }
    }

    /**
     * A run that ended without the session reporting - cancelled or timed out - keeping
     * whatever the accumulator already held. Discarding it would throw away the results
     * of every test that did finish, which is usually most of them.
     */
    private TestRunResponse abandoned( UnitTestService.TestRunResult collected, RunStatus status,
            String projectName, List<String> requestedClasses, Diagnostic diagnostic, long runStartMillis )
    {
        if ( collected == null )
        {
            return TestRunResponse.aborted( status, projectName, requestedClasses, diagnostic,
                elapsed( runStartMillis ) );
        }
        TestRunResponse partial = collected.snapshot( status, projectName, requestedClasses, null,
            List.of( diagnostic ), elapsed( runStartMillis ) );
        return new TestRunResponse( partial.status(), partial.projectName(), partial.requestedClasses(),
            partial.summary(), partial.failedTests(), partial.skippedTests(), partial.coverage(),
            partial.diagnostics(), diagnostic.message() + " " + partial.summaryText(),
            partial.durationMillis() );
    }

    /** The caller's timeout, defaulted. Zero or negative means "use the default". */
    private static int normalizeTimeout( Integer timeout )
    {
        return timeout == null || timeout <= 0 ? 300 : timeout;
    }

    private static long elapsed( long startMillis )
    {
        return System.currentTimeMillis() - startMillis;
    }

    /**
     * No open project of that name. A subtype of {@link IllegalArgumentException} so
     * that the existing contract - an invalid request is an exception - is unchanged,
     * while the run methods can still tell this apart from every other bad argument and
     * report it as a {@code PROJECT_NOT_FOUND} diagnostic.
     */
    private static final class ProjectNotFoundException extends IllegalArgumentException
    {
        private static final long serialVersionUID = 1L;

        ProjectNotFoundException( String message )
        {
            super( message );
        }
    }

    private int getRawExitCode( ILaunch iLaunch )
    {
        return java.util.Arrays.stream( iLaunch.getProcesses() )
            .mapToInt( process -> {
                try { return process.getExitValue(); }
                catch ( org.eclipse.debug.core.DebugException e ) { return -1; }
            } )
            .findFirst()
            .orElse( -1 );
    }

    private String getExitCode( ILaunch iLaunch )
    {
        return java.util.Arrays.stream( iLaunch.getProcesses() )
            .map( process -> {
                try
                {
                    return String.valueOf( process.getExitValue() ) + " (" + process.getLabel() + ")";
                }
                catch ( org.eclipse.debug.core.DebugException e )
                {
                    logger.log( org.eclipse.core.runtime.Status.error(
                        "Error requesting exit code from process '" + process.getLabel() + "'", e ) );
                    return "Exception requesting exit code from process '" + process.getLabel() + "': " + e.getMessage();
                }
            } )
            .collect( java.util.stream.Collectors.joining( "\n" ) );
    }

    private void waitForLaunchTermination( ILaunch launch )
        throws InterruptedException
    {
        if ( launch == null || launch.isTerminated() )
        {
            return;
        }

        long deadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis( 30 );
        long forceTerminateAt = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis( 20 );
        Display display = Display.getCurrent();
        while ( !launch.isTerminated() && System.currentTimeMillis() < deadline )
        {
            if ( System.currentTimeMillis() > forceTerminateAt )
            {
                forceTerminateAt = Long.MAX_VALUE;
                try
                {
                    launch.terminate();
                }
                catch ( org.eclipse.debug.core.DebugException e )
                {
                    logger.log( org.eclipse.core.runtime.Status.error(
                        "Error requesting terminate on a launch that didn't terminate nicely in a while", e ) );
                }
            }
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
            throw new ProjectNotFoundException( "Project not found: " + projectName );
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
