package com.github.gradusnikov.eclipse.assistai.mcp.services;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.ILog;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.debug.core.DebugPlugin;
import org.eclipse.debug.core.ILaunchConfiguration;
import org.eclipse.debug.core.ILaunchConfigurationType;
import org.eclipse.debug.core.ILaunchConfigurationWorkingCopy;
import org.eclipse.debug.core.ILaunchManager;
import org.eclipse.e4.core.di.annotations.Creatable;
import org.eclipse.e4.ui.di.UISynchronize;
import org.eclipse.jdt.core.IAnnotation;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.IMethod;
import org.eclipse.jdt.core.IPackageFragment;
import org.eclipse.jdt.core.IPackageFragmentRoot;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.core.JavaModelException;
import org.eclipse.jdt.junit.JUnitCore;
import org.eclipse.jdt.junit.TestRunListener;
import org.eclipse.jdt.junit.model.ITestCaseElement;
import org.eclipse.jdt.junit.model.ITestElement.Result;
import org.eclipse.jdt.junit.model.ITestRunSession;
import org.eclipse.jdt.launching.IJavaLaunchConfigurationConstants;

import jakarta.inject.Inject;

@Creatable
public class UnitTestService
{
    @Inject
    ILog logger;

    @Inject
    UISynchronize sync;

    @Inject
    CoverageService coverageService;

    @Inject
    TestRunManager testRunManager;

    // -------------------------------------------------------------------------
    // Inner types
    // -------------------------------------------------------------------------

    /**
     * Represents a test result with details about the test execution.
     */
    public record TestResult( String className, String testName, String status, String message,
                              double executionTime )
    {
        @Override
        public String toString()
        {
            StringBuilder sb = new StringBuilder();
            sb.append( className ).append( "#" ).append( testName )
              .append( " [" ).append( status ).append( "] - " )
              .append( executionTime ).append( "s" );
            if ( message != null && !message.isEmpty() )
            {
                sb.append( "\n  Message: " ).append( message );
            }
            return sb.toString();
        }
    }

    /**
     * Represents the results of a test run with summary information.
     */
    public static class TestRunResult
    {
        private String testRunName;
        private int totalCount;
        private int passedCount;
        private int failedCount;
        private int errorCount;
        private int skippedCount;
        private double totalTime;
        private List<TestResult> testResults;

        public TestRunResult( String testRunName )
        {
            this.testRunName = testRunName;
            this.testResults = new ArrayList<>();
        }

        public synchronized void addTestResult( TestResult result )
        {
            testResults.add( result );
            totalCount++;
            switch ( result.status() )
            {
                case String s when s.equals( Result.OK.toString() ) -> passedCount++;
                case String s when s.equals( Result.FAILURE.toString() ) -> failedCount++;
                case String s when s.equals( Result.ERROR.toString() ) -> errorCount++;
                case String s when s.equals( Result.IGNORED.toString() ) -> skippedCount++;
                case String s when s.equals( Result.UNDEFINED.toString() ) -> skippedCount++;
                default -> throw new IllegalArgumentException( "Unexpected value: " + result.status() );
            }
            totalTime += result.executionTime();
        }

        public synchronized String getTestRunName() { return testRunName; }
        public synchronized int getTotalCount() { return totalCount; }
        public synchronized int getPassedCount() { return passedCount; }
        public synchronized int getFailedCount() { return failedCount; }
        public synchronized int getErrorCount() { return errorCount; }
        public synchronized int getSkippedCount() { return skippedCount; }
        public synchronized double getTotalTime() { return totalTime; }
        public synchronized List<TestResult> getTestResults() { return new ArrayList<>( testResults ); }

        @Override
        public synchronized String toString()
        {
            StringBuilder sb = new StringBuilder();
            sb.append( "Test Run: " ).append( testRunName ).append( "\n" );
            sb.append( "Summary: Total: " ).append( totalCount )
              .append( ", Passed: " ).append( passedCount )
              .append( ", Failed: " ).append( failedCount )
              .append( ", Errors: " ).append( errorCount )
              .append( ", Skipped: " ).append( skippedCount )
              .append( ", Time: " ).append( String.format( "%.2f", totalTime ) ).append( "s\n\n" );

            if ( failedCount > 0 || errorCount > 0 )
            {
                sb.append( "Failed Tests:\n" );
                testResults.stream()
                           .filter( r -> "FAILED".equals( r.status() ) || "ERROR".equals( r.status() ) )
                           .forEach( r -> sb.append( "  " ).append( r.toString() ).append( "\n" ) );
                sb.append( "\n" );
            }

            sb.append( "All Tests:\n" );
            testResults.forEach( r -> sb.append( "  " ).append( r.toString() ).append( "\n" ) );
            return sb.toString();
        }
    }

    // -------------------------------------------------------------------------
    // Async public API - each method returns a run ID immediately
    // -------------------------------------------------------------------------

    /**
     * Starts all tests in a project asynchronously.
     * @return run ID - use getTestRunStatus to poll for results
     */
    public String startAllTests( String projectName, boolean withCoverage )
    {
        return startAllTests( projectName, withCoverage, null );
    }

    /**
     * Starts all tests in a project asynchronously, optionally based on a named launch configuration.
     * When {@code launcherName} is provided the saved config is used as a base and only the test
     * container attribute is overridden; all other settings (VM args, classpath, env vars, etc.) are kept.
     *
     * @param launcherName optional saved launch config name (use listLaunchConfigurations to find it)
     * @return run ID - use getTestRunStatus to poll for results
     */
    public String startAllTests( String projectName, boolean withCoverage, String launcherName )
    {
        Objects.requireNonNull( projectName, "Project name cannot be null" );
        if ( projectName.isEmpty() )
        {
            throw new IllegalArgumentException( "Project name cannot be empty" );
        }
        try
        {
            IJavaProject javaProject = getJavaProject( projectName );
            return startJUnitTests( javaProject, null, null, null, withCoverage,
                "Running all tests in " + projectName, launcherName );
        }
        catch ( CoreException e )
        {
            throw new RuntimeException( "Error starting tests: " + e.getMessage(), e );
        }
    }

    /**
     * Starts tests for a package asynchronously.
     * @return run ID - use getTestRunStatus to poll for results
     */
    public String startPackageTests( String projectName, String packageName, boolean withCoverage )
    {
        return startPackageTests( projectName, packageName, withCoverage, null );
    }

    /**
     * Starts tests for a package asynchronously, optionally based on a named launch configuration.
     *
     * @param launcherName optional saved launch config name (use listLaunchConfigurations to find it)
     * @return run ID - use getTestRunStatus to poll for results
     */
    public String startPackageTests( String projectName, String packageName, boolean withCoverage,
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
        try
        {
            IJavaProject javaProject = getJavaProject( projectName );
            IPackageFragment pkg = findPackage( javaProject, packageName );
            if ( pkg == null )
            {
                throw new RuntimeException(
                    "Package '" + packageName + "' not found in project '" + projectName + "'" );
            }
            return startJUnitTests( javaProject, pkg, null, null, withCoverage,
                "Running package " + packageName + " in " + projectName, launcherName );
        }
        catch ( CoreException e )
        {
            throw new RuntimeException( "Error starting tests: " + e.getMessage(), e );
        }
    }

    /**
     * Starts tests for a specific class asynchronously.
     * @return run ID - use getTestRunStatus to poll for results
     */
    public String startClassTests( String projectName, String className, boolean withCoverage )
    {
        return startClassTests( projectName, className, withCoverage, null );
    }

    /**
     * Starts tests for a specific class asynchronously, optionally based on a named launch configuration.
     *
     * @param launcherName optional saved launch config name (use listLaunchConfigurations to find it)
     * @return run ID - use getTestRunStatus to poll for results
     */
    public String startClassTests( String projectName, String className, boolean withCoverage,
                                   String launcherName )
    {
        Objects.requireNonNull( projectName, "Project name cannot be null" );
        Objects.requireNonNull( className, "Class name cannot be null" );
        if ( projectName.isEmpty() )
        {
            throw new IllegalArgumentException( "Project name cannot be empty" );
        }
        if ( className.isEmpty() )
        {
            throw new IllegalArgumentException( "Class name cannot be empty" );
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
            return startJUnitTests( javaProject, null, type, null, withCoverage,
                "Running " + className + " in " + projectName, launcherName );
        }
        catch ( CoreException e )
        {
            throw new RuntimeException( "Error starting tests: " + e.getMessage(), e );
        }
    }

    /**
     * Starts a specific test method asynchronously.
     * @return run ID - use getTestRunStatus to poll for results
     */
    public String startTestMethod( String projectName, String className, String methodName,
                                   boolean withCoverage )
    {
        return startTestMethod( projectName, className, methodName, withCoverage, null );
    }

    /**
     * Starts a specific test method asynchronously, optionally based on a named launch configuration.
     *
     * @param launcherName optional saved launch config name (use listLaunchConfigurations to find it)
     * @return run ID - use getTestRunStatus to poll for results
     */
    public String startTestMethod( String projectName, String className, String methodName,
                                   boolean withCoverage, String launcherName )
    {
        Objects.requireNonNull( projectName, "Project name cannot be null" );
        Objects.requireNonNull( className, "Class name cannot be null" );
        Objects.requireNonNull( methodName, "Method name cannot be null" );
        if ( projectName.isEmpty() )
        {
            throw new IllegalArgumentException( "Project name cannot be empty" );
        }
        if ( className.isEmpty() )
        {
            throw new IllegalArgumentException( "Class name cannot be empty" );
        }
        if ( methodName.isEmpty() )
        {
            throw new IllegalArgumentException( "Method name cannot be empty" );
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
            IMethod method = findMethod( type, methodName );
            if ( method == null )
            {
                throw new RuntimeException(
                    "Method '" + methodName + "' not found in class '" + className + "'" );
            }
            return startJUnitTests( javaProject, null, type, methodName, withCoverage,
                "Running " + className + "." + methodName + " in " + projectName, launcherName );
        }
        catch ( CoreException e )
        {
            throw new RuntimeException( "Error starting tests: " + e.getMessage(), e );
        }
    }

    // -------------------------------------------------------------------------
    // Core async launch logic
    // -------------------------------------------------------------------------

    private String startJUnitTests( IJavaProject javaProject, IPackageFragment packageFragment,
                                    IType testClass, String methodName, boolean withCoverage,
                                    String description, String launcherName )
    {
        TestRunSession session = testRunManager.createSession( description, withCoverage,
            javaProject.getElementName() );

        TestRunListener listener = new TestRunListener()
        {
            private TestRunResult currentRun = null;

            @Override
            public void sessionStarted( ITestRunSession s )
            {
                currentRun = new TestRunResult( s.getTestRunName() );
                session.setTestRunResult( currentRun );
            }

            @Override
            public void testCaseFinished( ITestCaseElement e )
            {
                if ( currentRun != null )
                {
                    String cls = e.getTestClassName();
                    String name = e.getTestMethodName();
                    String status = e.getTestResult( true ).toString();
                    String msg = e.getFailureTrace() != null ? e.getFailureTrace().getTrace() : "";
                    double time = e.getElapsedTimeInSeconds();
                    currentRun.addTestResult( new TestResult( cls, name, status, msg, time ) );
                }
            }

            @Override
            public void sessionFinished( ITestRunSession s )
            {
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
                    session.setState( TestRunSession.State.COMPLETED );
                    JUnitCore.removeTestRunListener( this );
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
                // Use the named saved config as base — clone it without saving
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
                    "org.eclipse.jdt.junit.launchconfig" );
                String launchName = buildLaunchName( javaProject, packageFragment, testClass, methodName );
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

            // Always override the targeting attributes — everything else from the base config is kept
            workingCopy.setAttribute( IJavaLaunchConfigurationConstants.ATTR_PROJECT_NAME,
                javaProject.getElementName() );

            if ( testClass != null )
            {
                workingCopy.setAttribute( IJavaLaunchConfigurationConstants.ATTR_MAIN_TYPE_NAME,
                    testClass.getFullyQualifiedName() );
                workingCopy.setAttribute( "org.eclipse.jdt.junit.CONTAINER", "" );
                if ( methodName != null && !methodName.isEmpty() )
                {
                    workingCopy.setAttribute( "org.eclipse.jdt.junit.TEST_METHOD", methodName );
                }
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

            // Only set TEST_KIND when not using a named launcher (the base config already has it)
            if ( launcherName == null || launcherName.isBlank() )
            {
                workingCopy.setAttribute( "org.eclipse.jdt.junit.TEST_KIND",
                    detectJUnitTestKind( javaProject ) );
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
                    logger.error( "Error launching tests", e );
                }
            } );

            return session.getRunId();
        }
        catch ( Exception e )
        {
            JUnitCore.removeTestRunListener( listener );
            session.setErrorMessage( e.getMessage() );
            session.setState( TestRunSession.State.FAILED );
            throw new RuntimeException( "Error setting up test launch: " + e.getMessage(), e );
        }
    }

    // -------------------------------------------------------------------------
    // findTestClasses
    // -------------------------------------------------------------------------

    /**
     * Finds all test classes in a project.
     */
    public String findTestClasses( String projectName )
    {
        Objects.requireNonNull( projectName, "Project name cannot be null" );
        if ( projectName.isEmpty() )
        {
            throw new IllegalArgumentException( "Project name cannot be empty" );
        }
        try
        {
            IJavaProject javaProject = getJavaProject( projectName );
            List<String> testClasses = new ArrayList<>();

            for ( IPackageFragmentRoot root : javaProject.getPackageFragmentRoots() )
            {
                if ( root.getKind() == IPackageFragmentRoot.K_SOURCE )
                {
                    for ( IJavaElement child : root.getChildren() )
                    {
                        if ( child instanceof IPackageFragment pkg )
                        {
                            for ( ICompilationUnit unit : pkg.getCompilationUnits() )
                            {
                                for ( IType type : unit.getAllTypes() )
                                {
                                    if ( isTestClass( type ) )
                                    {
                                        testClasses.add( type.getFullyQualifiedName() );
                                    }
                                }
                            }
                        }
                    }
                }
            }

            if ( testClasses.isEmpty() )
            {
                return "No test classes found in project '" + projectName + "'.";
            }

            StringBuilder result = new StringBuilder();
            result.append( "Found " ).append( testClasses.size() )
                  .append( " test classes in project '" ).append( projectName ).append( "':\n\n" );
            testClasses.forEach( c -> result.append( "- " ).append( c ).append( "\n" ) );
            return result.toString();
        }
        catch ( CoreException e )
        {
            throw new RuntimeException( "Error finding test classes: " + e.getMessage(), e );
        }
    }

    // -------------------------------------------------------------------------
    // Private helpers (shared with callers)
    // -------------------------------------------------------------------------

    /**
     * Detects the appropriate JUnit test kind loader based on the project's classpath.
     */
    private String detectJUnitTestKind( IJavaProject javaProject ) throws JavaModelException
    {
        IType jupiterTest = javaProject.findType( "org.junit.jupiter.api.Test" );
        if ( jupiterTest != null )
        {
            for ( var entry : javaProject.getResolvedClasspath( true ) )
            {
                String entryPath = entry.getPath().toString();
                if ( entryPath.contains( "junit-jupiter-api" ) )
                {
                    if ( entryPath.matches( ".*junit-jupiter-api[_-]6\\..*" ) )
                    {
                        return "org.eclipse.jdt.junit.loader.junit6";
                    }
                    break;
                }
            }
            String typePath = jupiterTest.getPath().toString();
            if ( typePath.matches( ".*junit-jupiter-api[_-]6\\..*" ) )
            {
                return "org.eclipse.jdt.junit.loader.junit6";
            }
            return "org.eclipse.jdt.junit.loader.junit5";
        }
        if ( javaProject.findType( "org.junit.Test" ) != null )
        {
            return "org.eclipse.jdt.junit.loader.junit4";
        }
        if ( javaProject.findType( "junit.framework.TestCase" ) != null )
        {
            return "org.eclipse.jdt.junit.loader.junit3";
        }
        return "org.eclipse.jdt.junit.loader.junit5";
    }

    private String buildLaunchName( IJavaProject javaProject, IPackageFragment packageFragment,
                                    IType testClass, String methodName )
    {
        String projectName = javaProject.getElementName();
        if ( testClass != null && methodName != null )
        {
            return projectName + " - " + testClass.getFullyQualifiedName() + "." + methodName;
        }
        if ( testClass != null )
        {
            return projectName + " - " + testClass.getFullyQualifiedName();
        }
        if ( packageFragment != null )
        {
            return projectName + " - " + packageFragment.getElementName();
        }
        return projectName + " - All Tests";
    }

    private ILaunchConfiguration findExistingLaunchConfig( ILaunchManager launchManager, String name )
    {
        try
        {
            for ( ILaunchConfiguration config : launchManager.getLaunchConfigurations() )
            {
                if ( config.getName().equals( name ) )
                {
                    return config;
                }
            }
        }
        catch ( CoreException e )
        {
            logger.error( "Error searching for existing launch configuration", e );
        }
        return null;
    }

    private IMethod findMethod( IType type, String methodName ) throws JavaModelException
    {
        for ( IMethod method : type.getMethods() )
        {
            if ( method.getElementName().equals( methodName ) )
            {
                return method;
            }
        }
        return null;
    }

    private IPackageFragment findPackage( IJavaProject javaProject,
                                          String packageName ) throws JavaModelException
    {
        return JavaModelUtils.findPackage( javaProject, packageName );
    }

    private IJavaProject getJavaProject( String projectName ) throws CoreException
    {
        return JavaModelUtils.getJavaProject( projectName );
    }

    private boolean isTestClass( IType type ) throws JavaModelException
    {
        if ( type.getElementName().endsWith( "Test" ) )
        {
            return true;
        }
        for ( IMethod method : type.getMethods() )
        {
            String methodName = method.getElementName();
            for ( IAnnotation annotation : method.getAnnotations() )
            {
                String annotationName = annotation.getElementName();
                if ( annotationName.contains( "Test" ) || annotationName.contains( "ParameterizedTest" ) )
                {
                    return true;
                }
            }
            if ( methodName.startsWith( "test" ) && methodName.length() > 4
                 && Character.isUpperCase( methodName.charAt( 4 ) ) )
            {
                return true;
            }
        }
        return false;
    }
}
