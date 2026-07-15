package com.github.gradusnikov.eclipse.assistai.mcp.services;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import org.eclipse.debug.core.ILaunch;

import com.github.gradusnikov.eclipse.assistai.mcp.operations.Operation;
import com.github.gradusnikov.eclipse.assistai.mcp.operations.OperationContext;
import com.github.gradusnikov.eclipse.assistai.mcp.operations.ProcessOutputSource;
import java.util.concurrent.TimeUnit;

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
public class UnitTestService {
    
    @Inject
    ILog logger;
    
    @Inject
    UISynchronize sync;
    
    @Inject
    CoverageService coverageService;
    
    /**
     * Represents a test result with details about the test execution
     */
    public record TestResult (String className, String testName, String status, String message, double executionTime) {
        
        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder();
            sb.append(className).append("#").append(testName)
              .append(" [").append(status).append("] - ")
              .append(executionTime).append("s");
            
            if (message != null && !message.isEmpty()) {
                sb.append("\n  Message: ").append(message);
            }
            
            return sb.toString();
        }
    }
    
    /**
     * Represents the results of a test run with summary information
     */
    public static class TestRunResult {
        private String testRunName;
        private int totalCount;
        private int passedCount;
        private int failedCount;
        private int errorCount;
        private int skippedCount;
        private double totalTime;
        private List<TestResult> testResults;
        
        public TestRunResult(String testRunName) {
            this.testRunName = testRunName;
            this.testResults = new ArrayList<>();
            this.totalCount = 0;
            this.passedCount = 0;
            this.failedCount = 0;
            this.errorCount = 0;
            this.skippedCount = 0;
            this.totalTime = 0.0;
        }
        
        public void addTestResult(TestResult result) {
            testResults.add(result);
            totalCount++;
            
            switch (result.status) {
                case String s when s.equals(Result.OK.toString()) -> passedCount++;
                case String s when s.equals(Result.FAILURE.toString()) -> failedCount++;
                case String s when s.equals(Result.ERROR.toString()) -> errorCount++;
                case String s when s.equals(Result.IGNORED.toString()) -> skippedCount++;
                case String s when s.equals(Result.UNDEFINED.toString()) -> skippedCount++;
                // Runs inside JDT's test run notifier: throwing here would break the
                // listener for the whole run over nothing more than an unknown status.
                default -> skippedCount++;
            }
            
            totalTime += result.executionTime;
        }
        
        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder();
            sb.append("Test Run: ").append(testRunName).append("\n");
            sb.append("Summary: Total: ").append(totalCount)
              .append(", Passed: ").append(passedCount)
              .append(", Failed: ").append(failedCount)
              .append(", Errors: ").append(errorCount)
              .append(", Skipped: ").append(skippedCount)
              .append(", Time: ").append(String.format("%.2f", totalTime)).append("s\n\n");
            
            if (failedCount > 0 || errorCount > 0) {
                sb.append("Failed Tests:\n");
                testResults.stream()
                    // JDT reports a failure as Result.FAILURE ("FAILURE"), never "FAILED":
                    // filtering on the latter left this block empty for every failing run.
                    .filter(r -> Result.FAILURE.toString().equals(r.status) || Result.ERROR.toString().equals(r.status))
                    .forEach(r -> sb.append("  ").append(r.toString()).append("\n"));
                sb.append("\n");
            }
            
            sb.append("All Tests:\n");
            testResults.forEach(r -> sb.append("  ").append(r.toString()).append("\n"));
            
            return sb.toString();
        }
    }
    
    /**
     * Runs all tests in a specific project.
     * 
     * @param projectName The name of the project containing the tests
     * @param timeout Maximum time in seconds to wait for test completion
     * @return A formatted string with test results
     */
    public String runAllTests(String projectName, Integer timeout) {
        return runAllTests(projectName, timeout, false);
    }

    public String runAllTests(String projectName, Integer timeout, boolean withCoverage) {
        Objects.requireNonNull(projectName, "Project name cannot be null");
        
        if (projectName.isEmpty()) {
            throw new IllegalArgumentException("Error: Project name cannot be empty.");
        }
        
        if (timeout == null || timeout <= 0) {
            timeout = 300; // Default timeout of 300 seconds
        }
        
        try {
            IJavaProject javaProject = getJavaProject( projectName );
            
            // Run the tests
            return launchJUnitTests(javaProject, null, null, timeout, null, withCoverage);
            
        } catch (CoreException e) {
            throw new RuntimeException("Error running tests: " + e.getMessage(), e);
        }
    }
    
    /**
     * Runs tests in a specific package.
     * 
     * @param projectName The name of the project containing the tests
     * @param packageName The fully qualified package name containing the tests
     * @param timeout Maximum time in seconds to wait for test completion
     * @return A formatted string with test results
     */
    public String runPackageTests(String projectName, String packageName, Integer timeout) {
        return runPackageTests(projectName, packageName, timeout, false);
    }

    public String runPackageTests(String projectName, String packageName, Integer timeout, boolean withCoverage) {
        Objects.requireNonNull(projectName, "Project name cannot be null");
        Objects.requireNonNull(packageName, "Package name cannot be null");
        
        if (projectName.isEmpty()) {
            throw new IllegalArgumentException("Error: Project name cannot be empty.");
        }
        
        if (packageName.isEmpty()) {
            throw new IllegalArgumentException("Error: Package name cannot be empty.");
        }
        
        if (timeout == null || timeout <= 0) {
            timeout = 300; // Default timeout of 300 seconds
        }
        
        try {
            IJavaProject javaProject = getJavaProject( projectName );
            
            // Find the package
            IPackageFragment pkg = findPackage(javaProject, packageName);
            if (pkg == null) {
                throw new RuntimeException("Error: Package '" + packageName + "' not found in project '" + projectName + "'.");
            }
            
            // Run the tests
            return launchJUnitTests(javaProject, pkg, null, timeout, null, withCoverage);
            
        } catch (CoreException e) {
            throw new RuntimeException("Error running tests: " + e.getMessage(), e);
        }
    }
    
    /**
     * Runs tests for a specific class.
     * 
     * @param projectName The name of the project containing the tests
     * @param className The fully qualified name of the test class
     * @param timeout Maximum time in seconds to wait for test completion
     * @return A formatted string with test results
     */
    public String runClassTests(String projectName, String className, Integer timeout) {
        return runClassTests(projectName, className, timeout, false);
    }

    public String runClassTests(String projectName, String className, Integer timeout, boolean withCoverage) {
        Objects.requireNonNull(projectName, "Project name cannot be null");
        Objects.requireNonNull(className, "Class name cannot be null");
        
        if (projectName.isEmpty()) {
            throw new IllegalArgumentException("Error: Project name cannot be empty.");
        }
        
        if (className.isEmpty()) {
            throw new IllegalArgumentException("Error: Class name cannot be empty.");
        }
        
        if (timeout == null || timeout <= 0) {
            timeout = 300; // Default timeout of 300 seconds
        }
        
        try {
            IJavaProject javaProject = getJavaProject( projectName );
            
            // Find the class
            IType type = javaProject.findType(className);
            if (type == null) {
                throw new RuntimeException("Error: Class '" + className + "' not found in project '" + projectName + "'.");
            }
            
            // Run the tests
            return launchJUnitTests(javaProject, null, type, timeout, null, withCoverage);
            
        } catch (CoreException e) {
            throw new RuntimeException("Error running tests: " + e.getMessage(), e);
        }
    }
    
    /**
     * Runs a specific test method.
     * 
     * @param projectName The name of the project containing the tests
     * @param className The fully qualified name of the test class
     * @param methodName The name of the test method to run
     * @param timeout Maximum time in seconds to wait for test completion
     * @return A formatted string with test results
     */
    public String runTestMethod(String projectName, String className, String methodName, Integer timeout) {
        return runTestMethod(projectName, className, methodName, timeout, false);
    }

    public String runTestMethod(String projectName, String className, String methodName, Integer timeout, boolean withCoverage) {
        Objects.requireNonNull(projectName, "Project name cannot be null");
        Objects.requireNonNull(className, "Class name cannot be null");
        Objects.requireNonNull(methodName, "Method name cannot be null");
        
        if (projectName.isEmpty()) {
            throw new IllegalArgumentException("Error: Project name cannot be empty.");
        }
        
        if (className.isEmpty()) {
            throw new IllegalArgumentException("Error: Class name cannot be empty.");
        }
        
        if (methodName.isEmpty()) {
            throw new IllegalArgumentException("Error: Method name cannot be empty.");
        }
        
        if (timeout == null || timeout <= 0) {
            timeout = 300; // Default timeout of 300 seconds
        }
        
        try {
            IJavaProject javaProject = getJavaProject( projectName );
            
            // Find the class
            IType type = javaProject.findType(className);
            if (type == null) {
                throw new RuntimeException("Error: Class '" + className + "' not found in project '" + projectName + "'.");
            }
            
            // Find the method
            IMethod method = findMethod(type, methodName);
            if (method == null) {
                throw new RuntimeException("Error: Method '" + methodName + "' not found in class '" + className + "'.");
            }
            
            // Run the tests
            return launchJUnitTests(javaProject, null, type, timeout, methodName, withCoverage);
            
        } catch (CoreException e) {
            throw new RuntimeException("Error running tests: " + e.getMessage(), e);
        }
    }
    
    /**
     * Detects the appropriate JUnit test kind loader. When a specific test class
     * is provided, inspects its annotations and superclass to determine the exact
     * JUnit version used — this avoids misdetection in PDE/mixed-classpath projects
     * where multiple JUnit versions are resolvable. Falls back to project-level
     * classpath analysis when no test class is given.
     */
    private String detectJUnitTestKind(IJavaProject javaProject, IType testClass, IPackageFragment packageFragment) throws JavaModelException {
        if (testClass != null) {
            String detected = detectJUnitTestKindFromClass(testClass);
            if (detected != null) {
                if (detected.equals("org.eclipse.jdt.junit.loader.junit5")) {
                    String refined = detectJupiterVersion(javaProject);
                    return refined != null ? refined : detected;
                }
                return detected;
            }
        }
        if (packageFragment != null) {
            String detected = detectJUnitTestKindFromPackage(packageFragment);
            if (detected != null) {
                if (detected.equals("org.eclipse.jdt.junit.loader.junit5")) {
                    String refined = detectJupiterVersion(javaProject);
                    return refined != null ? refined : detected;
                }
                return detected;
            }
        }
        return detectJUnitTestKindFromProject(javaProject);
    }

    private String detectJUnitTestKindFromPackage(IPackageFragment packageFragment) throws JavaModelException {
        int junit4Count = 0;
        int junit5Count = 0;
        int junit3Count = 0;

        for (ICompilationUnit cu : packageFragment.getCompilationUnits()) {
            for (IType type : cu.getTypes()) {
                String kind = detectJUnitTestKindFromClass(type);
                if (kind != null) {
                    switch (kind) {
                        case "org.eclipse.jdt.junit.loader.junit3":
                            junit3Count++;
                            break;
                        case "org.eclipse.jdt.junit.loader.junit4":
                            junit4Count++;
                            break;
                        case "org.eclipse.jdt.junit.loader.junit5":
                            junit5Count++;
                            break;
                    }
                }
            }
        }

        if (junit5Count > 0 && junit4Count == 0 && junit3Count == 0) {
            return "org.eclipse.jdt.junit.loader.junit5";
        }
        if (junit4Count > 0 && junit5Count == 0 && junit3Count == 0) {
            return "org.eclipse.jdt.junit.loader.junit4";
        }
        if (junit3Count > 0 && junit4Count == 0 && junit5Count == 0) {
            return "org.eclipse.jdt.junit.loader.junit3";
        }
        if (junit4Count > 0 || junit3Count > 0) {
            return "org.eclipse.jdt.junit.loader.junit4";
        }
        return null;
    }

    private String detectJupiterVersion(IJavaProject javaProject) throws JavaModelException {
        for (var entry : javaProject.getResolvedClasspath(true)) {
            String entryPath = entry.getPath().toString();
            if (entryPath.contains("junit-jupiter-api")) {
                if (entryPath.matches(".*junit-jupiter-api[_-]6\\..*")) {
                    return "org.eclipse.jdt.junit.loader.junit6";
                }
                return "org.eclipse.jdt.junit.loader.junit5";
            }
        }
        IType jupiterTest = javaProject.findType("org.junit.jupiter.api.Test");
        if (jupiterTest != null) {
            String typePath = jupiterTest.getPath().toString();
            if (typePath.matches(".*junit-jupiter-api[_-]6\\..*")) {
                return "org.eclipse.jdt.junit.loader.junit6";
            }
        }
        return null;
    }

    private String detectJUnitTestKindFromClass(IType testClass) throws JavaModelException {
        String[] imports = getImportsFromCompilationUnit(testClass);
        boolean importsJUnit5 = false;
        boolean importsJUnit4 = false;
        for (String imp : imports) {
            if (imp.startsWith("org.junit.jupiter.")) {
                importsJUnit5 = true;
            } else if (imp.equals("org.junit.Test") || imp.equals("org.junit.runner.RunWith")
                    || (imp.startsWith("org.junit.") && !imp.startsWith("org.junit.jupiter."))) {
                importsJUnit4 = true;
            }
        }

        boolean hasJUnit4Indicator = false;
        boolean hasJUnit5Indicator = false;

        for (IMethod method : testClass.getMethods()) {
            for (IAnnotation annotation : method.getAnnotations()) {
                String name = annotation.getElementName();
                if (name.equals("org.junit.Test")) {
                    hasJUnit4Indicator = true;
                } else if (name.equals("org.junit.jupiter.api.Test")
                        || name.equals("org.junit.jupiter.params.ParameterizedTest")) {
                    hasJUnit5Indicator = true;
                } else if (name.equals("Test")) {
                    if (importsJUnit5) {
                        hasJUnit5Indicator = true;
                    } else if (importsJUnit4) {
                        hasJUnit4Indicator = true;
                    }
                } else if (name.equals("ParameterizedTest")) {
                    if (importsJUnit5) {
                        hasJUnit5Indicator = true;
                    }
                }
            }
        }

        for (IAnnotation annotation : testClass.getAnnotations()) {
            String name = annotation.getElementName();
            if (name.equals("RunWith") || name.equals("org.junit.runner.RunWith")) {
                hasJUnit4Indicator = true;
            }
            if (name.equals("ExtendWith") || name.equals("org.junit.jupiter.api.extension.ExtendWith")) {
                hasJUnit5Indicator = true;
            }
        }

        if (hasJUnit5Indicator) {
            return "org.eclipse.jdt.junit.loader.junit5";
        }
        if (hasJUnit4Indicator) {
            return "org.eclipse.jdt.junit.loader.junit4";
        }

        IType superType = findSuperType(testClass);
        if (superType != null && "junit.framework.TestCase".equals(superType.getFullyQualifiedName())) {
            return "org.eclipse.jdt.junit.loader.junit3";
        }

        if (importsJUnit5) {
            return "org.eclipse.jdt.junit.loader.junit5";
        }
        if (importsJUnit4) {
            return "org.eclipse.jdt.junit.loader.junit4";
        }

        return null;
    }

    private IType findSuperType(IType type) throws JavaModelException {
        String superName = type.getSuperclassName();
        if (superName == null) {
            return null;
        }
        String[][] resolved = type.resolveType(superName);
        if (resolved != null && resolved.length > 0) {
            String fqn = resolved[0][0].isEmpty() ? resolved[0][1] : resolved[0][0] + "." + resolved[0][1];
            return type.getJavaProject().findType(fqn);
        }
        return null;
    }

    private String[] getImportsFromCompilationUnit(IType type) {
        ICompilationUnit cu = type.getCompilationUnit();
        if (cu == null) {
            return new String[0];
        }
        try {
            var imports = cu.getImports();
            String[] result = new String[imports.length];
            for (int i = 0; i < imports.length; i++) {
                result[i] = imports[i].getElementName();
            }
            return result;
        } catch (JavaModelException e) {
            return new String[0];
        }
    }

    private String detectJUnitTestKindFromProject(IJavaProject javaProject) throws JavaModelException {
        IType jupiterTest = javaProject.findType("org.junit.jupiter.api.Test");
        if (jupiterTest != null) {
            for (var entry : javaProject.getResolvedClasspath(true)) {
                String entryPath = entry.getPath().toString();
                if (entryPath.contains("junit-jupiter-api")) {
                    if (entryPath.matches(".*junit-jupiter-api[_-]6\\..*")) {
                        return "org.eclipse.jdt.junit.loader.junit6";
                    }
                    break;
                }
            }
            String typePath = jupiterTest.getPath().toString();
            if (typePath.matches(".*junit-jupiter-api[_-]6\\..*")) {
                return "org.eclipse.jdt.junit.loader.junit6";
            }
            return "org.eclipse.jdt.junit.loader.junit5";
        }
        if (javaProject.findType("org.junit.Test") != null) {
            return "org.eclipse.jdt.junit.loader.junit4";
        }
        if (javaProject.findType("junit.framework.TestCase") != null) {
            return "org.eclipse.jdt.junit.loader.junit3";
        }
        return "org.eclipse.jdt.junit.loader.junit5";
    }
    
    private String buildLaunchName(IJavaProject javaProject, IPackageFragment packageFragment,
                                   IType testClass, String methodName) {
        String projectName = javaProject.getElementName();
        if (testClass != null && methodName != null) {
            return projectName + " - " + testClass.getFullyQualifiedName() + "." + methodName;
        }
        if (testClass != null) {
            return projectName + " - " + testClass.getFullyQualifiedName();
        }
        if (packageFragment != null) {
            return projectName + " - " + packageFragment.getElementName();
        }
        return projectName + " - All Tests";
    }
    
    private ILaunchConfiguration findExistingLaunchConfig(ILaunchManager launchManager, String name) {
        try {
            for (ILaunchConfiguration config : launchManager.getLaunchConfigurations()) {
                if (config.getName().equals(name)) {
                    return config;
                }
            }
        } catch (CoreException e) {
            logger.error("Error searching for existing launch configuration", e);
        }
        return null;
    }
    
    /**
     * Finds a method in a type by name.
     */
    private IMethod findMethod(IType type, String methodName) throws JavaModelException {
        for (IMethod method : type.getMethods()) {
            if (method.getElementName().equals(methodName)) {
                return method;
            }
        }
        return null;
    }
    
    /**
     * Finds a package in a Java project by name.
     */
    private IPackageFragment findPackage(IJavaProject javaProject, String packageName) throws JavaModelException {
        for (IPackageFragmentRoot root : javaProject.getPackageFragmentRoots()) {
            if (root.getKind() == IPackageFragmentRoot.K_SOURCE) {
                IPackageFragment pkg = root.getPackageFragment(packageName);
                if (pkg.exists()) {
                    return pkg;
                }
            }
        }
        return null;
    }
    
    /**
     * Backstop so a test JVM that never reports and never dies cannot park a thread
     * forever. It is not the caller's timeout - the caller is handed an operationId
     * long before this - just an upper bound on how long we keep listening.
     */
    private static final int MAX_TEST_RUN_MINUTES = 120;

    /**
     * Waits for the run to finish, treating the death of the test JVM as an ending too:
     * a crashed JVM never sends sessionFinished, and waiting for one that will never
     * arrive is what used to hang these tools.
     */
    private boolean awaitTestRun( CountDownLatch latch, ILaunch launch, long boundMillis ) throws InterruptedException
    {
        long deadline = System.currentTimeMillis() + boundMillis;
        while ( System.currentTimeMillis() < deadline )
        {
            if ( latch.await( 200, TimeUnit.MILLISECONDS ) )
            {
                return true;
            }
            if ( launch != null && launch.isTerminated() )
            {
                // The JVM is gone; give the JUnit listener a moment to deliver the last events.
                return latch.await( 5, TimeUnit.SECONDS );
            }
        }
        return false;
    }

    /**
     * Launches JUnit tests using Eclipse's JUnit infrastructure with optional method filtering and coverage.
     * <p>
     * The {@code timeout} argument no longer bounds this method: a long execution tool
     * is waited on by the framework, which hands the caller an operationId when its
     * inline wait elapses while the run carries on here.
     */
    private String launchJUnitTests(IJavaProject javaProject, IPackageFragment packageFragment, 
                                   IType testClass, int timeout, String methodName, boolean withCoverage) {
        final CountDownLatch latch = new CountDownLatch(1);
        final TestRunResult[] testRunResults = new TestRunResult[1];
        final Optional<Operation> operation = OperationContext.current();
        final AtomicInteger finishedTests = new AtomicInteger();
        
        try {
            // Register a test run listener to collect results
            TestRunListener listener = new TestRunListener() {
                private TestRunResult currentRun = null;
                
                @Override
                public void sessionStarted(ITestRunSession session) {
                    currentRun = new TestRunResult(session.getTestRunName());
                    operation.ifPresent( op -> op.setProgress( "test session started" ) );
                }
                
                @Override
                public void sessionFinished(ITestRunSession session) {
                    testRunResults[0] = currentRun;
                    latch.countDown();
                }
                
                @Override
                public void testCaseFinished(ITestCaseElement testCaseElement) {
                    if (currentRun != null) {
                        String className = testCaseElement.getTestClassName();
                        String testName = testCaseElement.getTestMethodName();
                        String status = testCaseElement.getTestResult(true).toString();
                        String message = testCaseElement.getFailureTrace() != null ? 
                                         testCaseElement.getFailureTrace().getTrace() : "";
                        double time = testCaseElement.getElapsedTimeInSeconds();
                        
                        currentRun.addTestResult(new TestResult(className, testName, status, message, time));
                        operation.ifPresent( op -> op.setProgress(
                                finishedTests.incrementAndGet() + " tests finished; last: " + className + "#" + testName ) );
                    }
                }
            };
            
            JUnitCore.addTestRunListener(listener);
            
            try {
                // Create and configure the launch
                ILaunchManager launchManager = DebugPlugin.getDefault().getLaunchManager();
                ILaunchConfigurationType type = launchManager.getLaunchConfigurationType(
                        "org.eclipse.jdt.junit.launchconfig");
                
                // Build a deterministic launch name based on the test target
                String launchName = buildLaunchName(javaProject, packageFragment, testClass, methodName);
                
                // Reuse existing launch configuration or create a new one
                ILaunchConfigurationWorkingCopy workingCopy;
                ILaunchConfiguration existing = findExistingLaunchConfig(launchManager, launchName);
                if (existing != null) {
                    workingCopy = existing.getWorkingCopy();
                } else {
                    workingCopy = type.newInstance(null, launchName);
                }
                
                // Set the project name
                workingCopy.setAttribute(IJavaLaunchConfigurationConstants.ATTR_PROJECT_NAME, 
                        javaProject.getElementName());
                
                // Set the test target
                // CONTAINER must use the Java element handle identifier (e.g. "=ProjectName"),
                // NOT the IResource path (e.g. "/ProjectName") â the JUnit launcher resolves
                // the input element via JavaCore.create(handleId), and a resource path causes
                // "The input element of the launch configuration does not exist".
                if (testClass != null) {
                    workingCopy.setAttribute(IJavaLaunchConfigurationConstants.ATTR_MAIN_TYPE_NAME, 
                            testClass.getFullyQualifiedName());
                    workingCopy.setAttribute("org.eclipse.jdt.junit.CONTAINER", "");
                    
                    if (methodName != null && !methodName.isEmpty()) {
                        workingCopy.setAttribute("org.eclipse.jdt.junit.TEST_METHOD", methodName);
                    }
                } else if (packageFragment != null) {
                    workingCopy.setAttribute(IJavaLaunchConfigurationConstants.ATTR_MAIN_TYPE_NAME, "");
                    workingCopy.setAttribute("org.eclipse.jdt.junit.CONTAINER", 
                            packageFragment.getHandleIdentifier());
                } else {
                    workingCopy.setAttribute(IJavaLaunchConfigurationConstants.ATTR_MAIN_TYPE_NAME, "");
                    workingCopy.setAttribute("org.eclipse.jdt.junit.CONTAINER", 
                            javaProject.getHandleIdentifier());
                }
                
                // Detect the appropriate JUnit version — prefer class-level detection when available
                String testKind = detectJUnitTestKind(javaProject, testClass, packageFragment);
                workingCopy.setAttribute("org.eclipse.jdt.junit.TEST_KIND", testKind);
                
                // Create the actual configuration
                ILaunchConfiguration configuration = workingCopy.doSave();
                
                // Determine launch mode
                boolean useCoverage = withCoverage && coverageService.isCoverageAvailable();
                String launchMode = useCoverage ? coverageService.getCoverageLaunchMode() : ILaunchManager.RUN_MODE;
                
                long launchStartTime = System.currentTimeMillis();
                final ILaunch[] launchRef = new ILaunch[1];
                // Launch the tests
                sync.syncExec(() -> {
                    try {
                        launchRef[0] = configuration.launch(launchMode, new NullProgressMonitor());
                    } catch (CoreException e) {
                        logger.error("Error launching tests", e);
                    }
                });
                
                // Streams the test JVM's output into the operation and makes cancelling it
                // terminate the JVM: interrupting this thread alone would leave it running.
                operation.ifPresent( op -> ProcessOutputSource.attach( op, launchRef[0] ) );

                // How long the CALLER is prepared to wait is the framework's business: once
                // its inline wait elapses it hands the caller an operationId and this thread
                // keeps going. So wait for the run to actually end, not for the caller.
                // Run as an MCP operation, the caller has already been handed an operationId
                // and the only bound left is a backstop. Called directly - from a test, an
                // agent - there is no framework waiting for us, so the caller's timeout is
                // still the bound.
                long waitBoundMillis = operation.isPresent()
                        ? TimeUnit.MINUTES.toMillis( MAX_TEST_RUN_MINUTES )
                        : TimeUnit.SECONDS.toMillis( timeout );
                boolean completed = awaitTestRun( latch, launchRef[0], waitBoundMillis );
                
                if (!completed) {
                    return "Error: the test run did not report results in time.";
                }
                
                if (testRunResults[0] == null) {
                    return "Error: No test results collected. The test run may have failed to start.";
                }
                
                String results = testRunResults[0].toString();
                
                if (useCoverage) {
                    String execFile = coverageService.waitForLatestCoverageFile( launchStartTime, 10000 );
                    results += coverageService.formatCoverageInfo( execFile, javaProject.getProject().getName() );
                }
                
                return results;
                
            } finally {
                JUnitCore.removeTestRunListener(listener);
            }
            
        } catch (InterruptedException e) {
            // cancelOperation interrupts this thread; the launch itself is terminated by
            // the operation's cancel hook.
            Thread.currentThread().interrupt();
            return "Test run cancelled.";
        } catch (Exception e) {
            logger.error("Error running tests", e);
            return "Error running tests: " + e.getMessage();
        }
    }
    
    /**
     * Finds all test classes in a project.
     * 
     * @param projectName The name of the project to search
     * @return A list of fully qualified class names of test classes
     */
    public String findTestClasses(String projectName) {
        Objects.requireNonNull(projectName, "Project name cannot be null");
        
        if (projectName.isEmpty()) {
            throw new IllegalArgumentException("Error: Project name cannot be empty.");
        }
        
        try {
            IJavaProject javaProject = getJavaProject( projectName );
            
            // Find test classes
            List<String> testClasses = new ArrayList<>();
            
            for (IPackageFragmentRoot root : javaProject.getPackageFragmentRoots()) {
                if (root.getKind() == IPackageFragmentRoot.K_SOURCE) {
                    for (IJavaElement child : root.getChildren()) {
                        if (child instanceof IPackageFragment) {
                            IPackageFragment pkg = (IPackageFragment) child;
                            for (ICompilationUnit unit : pkg.getCompilationUnits()) {
                                for (IType type : unit.getAllTypes()) {
                                    if (isTestClass(type)) {
                                        testClasses.add(type.getFullyQualifiedName());
                                    }
                                }
                            }
                        }
                    }
                }
            }
            
            if (testClasses.isEmpty()) {
                return "No test classes found in project '" + projectName + "'.";
            }
            
            StringBuilder result = new StringBuilder();
            result.append("Found ").append(testClasses.size()).append(" test classes in project '")
                  .append(projectName).append("':\n\n");
            
            for (String className : testClasses) {
                result.append("- ").append(className).append("\n");
            }
            
            return result.toString();
            
        } catch (CoreException e) {
            throw new RuntimeException("Error finding test classes: " + e.getMessage(), e);
        }
    }

    private IJavaProject getJavaProject( String projectName ) throws CoreException
    {
        // Get the project
        IProject project = ResourcesPlugin.getWorkspace().getRoot().getProject(projectName);
        
        if (!project.exists()) {
            throw new RuntimeException("Error: Project '" + projectName + "' does not exist.");
        }
        
        if (!project.isOpen()) {
            throw new RuntimeException("Error: Project '" + projectName + "' is closed.");
        }
        
        // Check if it's a Java project
        if (!project.hasNature(JavaCore.NATURE_ID)) {
            throw new RuntimeException("Error: Project '" + projectName + "' is not a Java project.");
        }
        
        IJavaProject javaProject = JavaCore.create(project);
        return javaProject;
    }
    
    /**
     * Determines if a class is a test class by checking for test annotations
     * or methods following test naming conventions.
     */
    private boolean isTestClass(IType type) throws JavaModelException {
        // Check if class name ends with Test
        if (type.getElementName().endsWith("Test")) {
            return true;
        }
        
        // Check for test methods
        for (IMethod method : type.getMethods()) {
            String methodName = method.getElementName();
            
            // Check for JUnit 4/5 annotations
            for (IAnnotation annotation : method.getAnnotations()) {
                String annotationName = annotation.getElementName();
                if (annotationName.contains("Test") || annotationName.contains("ParameterizedTest")) {
                    return true;
                }
            }
            
            // Check for test naming convention (testXXX)
            if (methodName.startsWith("test") && Character.isUpperCase(methodName.charAt(4))) {
                return true;
            }
        }
        
        return false;
    }
}
