package com.github.gradusnikov.eclipse.assistai.mcp.services;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import org.eclipse.debug.core.ILaunch;

import com.github.gradusnikov.eclipse.assistai.mcp.McpJson;
import com.github.gradusnikov.eclipse.assistai.mcp.operations.Operation;
import com.github.gradusnikov.eclipse.assistai.mcp.operations.OperationContext;
import com.github.gradusnikov.eclipse.assistai.mcp.operations.ProcessOutputSource;
import com.github.gradusnikov.eclipse.assistai.mcp.results.Diagnostic;
import com.github.gradusnikov.eclipse.assistai.mcp.results.DiagnosticCode;
import com.github.gradusnikov.eclipse.assistai.mcp.results.TestClassesResponse;
import com.github.gradusnikov.eclipse.assistai.mcp.results.TestRunResponse;
import com.github.gradusnikov.eclipse.assistai.mcp.results.TestRunResponse.CoverageResult;
import com.github.gradusnikov.eclipse.assistai.mcp.results.TestRunResponse.RunStatus;
import com.github.gradusnikov.eclipse.assistai.mcp.results.TestRunResponse.SkippedTestResult;
import com.github.gradusnikov.eclipse.assistai.mcp.results.TestRunResponse.SourceLocation;
import com.github.gradusnikov.eclipse.assistai.mcp.results.TestRunResponse.TestCaseResult;
import com.github.gradusnikov.eclipse.assistai.mcp.results.TestRunResponse.TestStatus;
import com.github.gradusnikov.eclipse.assistai.mcp.results.TestRunResponse.TestSummary;

import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
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
import org.eclipse.jdt.core.Signature;
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
     * One finished test case, as collected from JDT's notifier.
     * <p>
     * The trace and the source location are the additions that make a failure
     * actionable: previously a failed test named a class and a method and nothing
     * more, so the caller could not open where it broke.
     *
     * @param message the assertion message - the trace's first line
     * @param failureTrace the full trace, already cut to a publishable size
     * @param source where the failure is, or null when the trace named no workspace type
     */
    public record TestResult (String className, String testName, TestStatus status, String message,
            String failureTrace, boolean traceTruncated, SourceLocation source, double executionTime) {
    }
    
    /**
     * The live accumulator a test run writes into.
     * <p>
     * Deliberately mutable and synchronized: JDT's test run notifier calls
     * {@link #addTestResult} from its own thread while {@code getOperationStatus} reads
     * this for live progress on another. Making it immutable would end live progress.
     * Immutability is provided instead by {@link #snapshot}, which copies out a
     * {@link TestRunResponse} that is safe to publish mid-run.
     */
    public static class TestRunResult {
        private final String testRunName;
        private int totalCount;
        private int passedCount;
        private int failedCount;
        private int errorCount;
        private int skippedCount;
        private double totalTime;
        private final List<TestResult> testResults;
        
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

        public String getTestRunName() {
            return testRunName;
        }
        
        public synchronized void addTestResult(TestResult result) {
            testResults.add(result);
            totalCount++;
            
            switch (result.status()) {
                case PASSED -> passedCount++;
                case FAILED -> failedCount++;
                case ERROR -> errorCount++;
                // A status this vocabulary does not name is counted as skipped rather
                // than thrown on: this runs inside JDT's notifier, where an exception
                // would break the listener for the whole run over one odd status.
                case SKIPPED, UNKNOWN -> skippedCount++;
            }
            
            totalTime += result.executionTime();
        }

        /** The counts alone, for the 'summary' intermediate result. */
        public synchronized TestSummary summary() {
            return new TestSummary( totalCount, passedCount, failedCount, errorCount, skippedCount );
        }

        /**
         * An immutable view of everything collected so far.
         * <p>
         * Safe to publish while the run continues - that is what {@code RUNNING} is for.
         * Passing tests are counted but not listed: their names are the bulk of a large
         * run's payload and the least useful part of it.
         */
        public synchronized TestRunResponse snapshot( RunStatus status, String projectName,
                List<String> requestedClasses, CoverageResult coverage,
                List<Diagnostic> diagnostics, long durationMillis ) {
            TestSummary counts = new TestSummary( totalCount, passedCount, failedCount, errorCount, skippedCount );

            List<TestCaseResult> failures = new ArrayList<>();
            List<SkippedTestResult> skipped = new ArrayList<>();
            for ( TestResult result : testResults ) {
                switch ( result.status() ) {
                    case FAILED, ERROR -> failures.add( new TestCaseResult( result.className(),
                            result.testName(), result.status(), result.message(),
                            result.failureTrace(), result.traceTruncated(), result.source(),
                            result.executionTime() ) );
                    case SKIPPED, UNKNOWN -> skipped.add( new SkippedTestResult( result.className(),
                            result.testName(), result.message() ) );
                    case PASSED -> { /* counted only */ }
                }
            }

            return new TestRunResponse( status, projectName, List.copyOf( requestedClasses ), counts,
                    List.copyOf( failures ), List.copyOf( skipped ), coverage,
                    List.copyOf( diagnostics ), TestRunResponse.describe( status, counts ),
                    durationMillis );
        }
    }
    
    /**
     * Runs all tests in a specific project.
     * 
     * @param projectName The name of the project containing the tests
     * @param timeout Maximum time in seconds to wait for test completion
     * @return the run's outcome, carrying its diagnostics when it could not start
     */
    public TestRunResponse runAllTests(String projectName, Integer timeout) {
        return runAllTests(projectName, timeout, false);
    }

    public TestRunResponse runAllTests(String projectName, Integer timeout, boolean withCoverage) {
        return runAllTests(projectName, timeout, withCoverage, null);
    }

    /**
     * Runs all tests in a project, optionally using a saved launch configuration as a base.
     *
     * @param launcherName optional saved launch config name to reuse (VM args, classpath, env vars, etc.)
     */
    public TestRunResponse runAllTests(String projectName, Integer timeout, boolean withCoverage, String launcherName) {
        Objects.requireNonNull(projectName, "Project name cannot be null");
        
        if (projectName.isEmpty()) {
            throw new IllegalArgumentException("Error: Project name cannot be empty.");
        }
        
        int waitSeconds = normalizeTimeout(timeout);
        long startMillis = System.currentTimeMillis();

        try {
            IJavaProject javaProject = getJavaProject( projectName );
            return launchJUnitTests(javaProject, null, null, waitSeconds, null, withCoverage, launcherName);
        } catch (TestSetupException e) {
            return TestRunResponse.notStarted(projectName, List.of(), e.diagnostic(), elapsed(startMillis));
        } catch (CoreException e) {
            return TestRunResponse.notStarted(projectName, List.of(),
                    Diagnostic.fatal(DiagnosticCode.INTERNAL_ERROR, "Error running tests: " + e.getMessage()),
                    elapsed(startMillis));
        }
    }
    
    /**
     * Runs tests in a specific package.
     * 
     * @param projectName The name of the project containing the tests
     * @param packageName The fully qualified package name containing the tests
     * @param timeout Maximum time in seconds to wait for test completion
     * @return the run's outcome, carrying its diagnostics when it could not start
     */
    public TestRunResponse runPackageTests(String projectName, String packageName, Integer timeout) {
        return runPackageTests(projectName, packageName, timeout, false);
    }

    public TestRunResponse runPackageTests(String projectName, String packageName, Integer timeout, boolean withCoverage) {
        return runPackageTests(projectName, packageName, timeout, withCoverage, null);
    }

    /**
     * Runs tests in a package, optionally using a saved launch configuration as a base.
     *
     * @param launcherName optional saved launch config name to reuse
     */
    public TestRunResponse runPackageTests(String projectName, String packageName, Integer timeout, boolean withCoverage, String launcherName) {
        Objects.requireNonNull(projectName, "Project name cannot be null");
        Objects.requireNonNull(packageName, "Package name cannot be null");
        
        if (projectName.isEmpty()) {
            throw new IllegalArgumentException("Error: Project name cannot be empty.");
        }
        
        if (packageName.isEmpty()) {
            throw new IllegalArgumentException("Error: Package name cannot be empty.");
        }
        
        int waitSeconds = normalizeTimeout(timeout);
        long startMillis = System.currentTimeMillis();

        try {
            IJavaProject javaProject = getJavaProject( projectName );
            IPackageFragment pkg = findPackage(javaProject, packageName);
            if (pkg == null) {
                return TestRunResponse.notStarted(projectName, List.of(),
                        Diagnostic.fatal(DiagnosticCode.TEST_PACKAGE_NOT_FOUND,
                                "Package '" + packageName + "' not found in project '" + projectName + "'."),
                        elapsed(startMillis));
            }
            return launchJUnitTests(javaProject, pkg, null, waitSeconds, null, withCoverage, launcherName);
        } catch (TestSetupException e) {
            return TestRunResponse.notStarted(projectName, List.of(), e.diagnostic(), elapsed(startMillis));
        } catch (CoreException e) {
            return TestRunResponse.notStarted(projectName, List.of(),
                    Diagnostic.fatal(DiagnosticCode.INTERNAL_ERROR, "Error running tests: " + e.getMessage()),
                    elapsed(startMillis));
        }
    }
    
    /**
     * Runs tests for a specific class.
     * 
     * @param projectName The name of the project containing the tests
     * @param className The fully qualified name of the test class
     * @param timeout Maximum time in seconds to wait for test completion
     * @return the run's outcome, carrying its diagnostics when it could not start
     */
    public TestRunResponse runClassTests(String projectName, String className, Integer timeout) {
        return runClassTests(projectName, className, timeout, false);
    }

    public TestRunResponse runClassTests(String projectName, String className, Integer timeout, boolean withCoverage) {
        return runClassTests(projectName, className, timeout, withCoverage, null);
    }

    /**
     * Runs tests for a class, optionally using a saved launch configuration as a base.
     *
     * @param launcherName optional saved launch config name to reuse
     */
    public TestRunResponse runClassTests(String projectName, String className, Integer timeout, boolean withCoverage, String launcherName) {
        Objects.requireNonNull(projectName, "Project name cannot be null");
        Objects.requireNonNull(className, "Class name cannot be null");
        
        if (projectName.isEmpty()) {
            throw new IllegalArgumentException("Error: Project name cannot be empty.");
        }
        
        if (className.isEmpty()) {
            throw new IllegalArgumentException("Error: Class name cannot be empty.");
        }
        
        int waitSeconds = normalizeTimeout(timeout);
        long startMillis = System.currentTimeMillis();

        try {
            IJavaProject javaProject = getJavaProject( projectName );
            IType type = javaProject.findType(className);
            if (type == null) {
                return TestRunResponse.notStarted(projectName, List.of(className),
                        Diagnostic.fatal(DiagnosticCode.TEST_CLASS_NOT_FOUND,
                                "Class '" + className + "' not found in project '" + projectName + "'."),
                        elapsed(startMillis));
            }
            return launchJUnitTests(javaProject, null, type, waitSeconds, null, withCoverage, launcherName);
        } catch (TestSetupException e) {
            return TestRunResponse.notStarted(projectName, List.of(className), e.diagnostic(), elapsed(startMillis));
        } catch (CoreException e) {
            return TestRunResponse.notStarted(projectName, List.of(className),
                    Diagnostic.fatal(DiagnosticCode.INTERNAL_ERROR, "Error running tests: " + e.getMessage()),
                    elapsed(startMillis));
        }
    }
    
    /**
     * Runs a specific test method.
     * 
     * @param projectName The name of the project containing the tests
     * @param className The fully qualified name of the test class
     * @param methodName The name of the test method to run
     * @param timeout Maximum time in seconds to wait for test completion
     * @return the run's outcome, carrying its diagnostics when it could not start
     */
    public TestRunResponse runTestMethod(String projectName, String className, String methodName, Integer timeout) {
        return runTestMethod(projectName, className, methodName, timeout, false);
    }

    public TestRunResponse runTestMethod(String projectName, String className, String methodName, Integer timeout, boolean withCoverage) {
        return runTestMethod(projectName, className, methodName, timeout, withCoverage, null);
    }

    /**
     * Runs a specific test method, optionally using a saved launch configuration as a base.
     *
     * @param launcherName optional saved launch config name to reuse
     */
    public TestRunResponse runTestMethod(String projectName, String className, String methodName, Integer timeout, boolean withCoverage, String launcherName) {
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
        
        int waitSeconds = normalizeTimeout(timeout);
        long startMillis = System.currentTimeMillis();

        try {
            IJavaProject javaProject = getJavaProject( projectName );
            IType type = javaProject.findType(className);
            if (type == null) {
                return TestRunResponse.notStarted(projectName, List.of(className),
                        Diagnostic.fatal(DiagnosticCode.TEST_CLASS_NOT_FOUND,
                                "Class '" + className + "' not found in project '" + projectName + "'."),
                        elapsed(startMillis));
            }
            IMethod method = findMethod(type, methodName);
            if (method == null) {
                return TestRunResponse.notStarted(projectName, List.of(className),
                        Diagnostic.fatal(DiagnosticCode.TEST_CLASS_NOT_FOUND,
                                "Method '" + methodName + "' not found in class '" + className + "'."),
                        elapsed(startMillis));
            }
            return launchJUnitTests(javaProject, null, type, waitSeconds, methodName, withCoverage, launcherName);
        } catch (TestSetupException e) {
            return TestRunResponse.notStarted(projectName, List.of(className), e.diagnostic(), elapsed(startMillis));
        } catch (CoreException e) {
            return TestRunResponse.notStarted(projectName, List.of(className),
                    Diagnostic.fatal(DiagnosticCode.INTERNAL_ERROR, "Error running tests: " + e.getMessage()),
                    elapsed(startMillis));
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
     * JDT's launch attribute for a single test method - the key its own "Run single
     * test" shortcut writes. Named here because the constants class that defines it is
     * internal API.
     */
    static final String JDT_JUNIT_TEST_NAME = "org.eclipse.jdt.junit.TESTNAME";

    /**
     * The method name as JDT's launch shortcut spells it: {@code name(param,types)} with
     * the parameter types fully qualified and type arguments erased. JUnit 5 selects a
     * method by that exact spelling, so a bare name would not find an overloaded or
     * parameterized test. A name the caller already spelled with parentheses is kept,
     * and a method that does not exist is passed through for the launcher to report.
     */
    public String qualifiedTestName(IType testClass, String methodName) throws JavaModelException {
        if (methodName.contains("(")) {
            return methodName;
        }
        IMethod method = testClass == null ? null : findMethod(testClass, methodName);
        if (method == null) {
            return methodName;
        }
        StringBuilder name = new StringBuilder(methodName).append('(');
        String[] parameterTypes = method.getParameterTypes();
        for (int i = 0; i < parameterTypes.length; i++) {
            if (i > 0) {
                name.append(',');
            }
            name.append(resolvedTypeName(testClass, parameterTypes[i]));
        }
        return name.append(')').toString();
    }

    /** A parameter type signature as a fully qualified source name: {@code int[]}, {@code java.util.List}. */
    private static String resolvedTypeName(IType context, String signature) throws JavaModelException {
        String erased = Signature.getTypeErasure(signature);
        int dimensions = Signature.getArrayCount(erased);
        String element = Signature.getElementType(erased);
        String name = Signature.toString(element);
        if (Signature.getTypeSignatureKind(element) != Signature.BASE_TYPE_SIGNATURE
                && element.charAt(0) == Signature.C_UNRESOLVED) {
            String[][] resolved = context.resolveType(name);
            if (resolved != null && resolved.length == 1) {
                name = resolved[0][0].isEmpty() ? resolved[0][1] : resolved[0][0] + '.' + resolved[0][1];
            }
        }
        return name + "[]".repeat(dimensions);
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
     * <p>
     * When {@code launcherName} is non-null, the named saved launch configuration is used
     * as a base (reusing its VM args, classpath, env vars, etc.) and only the test
     * targeting attributes are overridden.
     */
    private TestRunResponse launchJUnitTests(IJavaProject javaProject, IPackageFragment packageFragment,
                                    IType testClass, int timeout, String methodName,
                                    boolean withCoverage, String launcherName) {
        final CountDownLatch latch = new CountDownLatch(1);
        final TestRunResult[] testRunResults = new TestRunResult[1];
        final Optional<Operation> operation = OperationContext.current();
        final AtomicInteger finishedTests = new AtomicInteger();
        final String projectName = javaProject.getProject().getName();
        final List<String> requestedClasses = testClass == null
                ? List.of()
                : List.of( testClass.getFullyQualifiedName() );
        // Wall clock for the whole operation. Kept apart from launchStartTime below,
        // which is the baseline for matching a coverage file by modification time and
        // must not be moved earlier or an older .exec file starts matching.
        final long runStartMillis = System.currentTimeMillis();
        
        try {
            // Register a test run listener to collect results
            TestRunListener listener = new TestRunListener() {
                private TestRunResult currentRun = null;
                
                @Override
                public void sessionStarted(ITestRunSession session) {
                    currentRun = new TestRunResult(session.getTestRunName());
                    // Published as soon as the session exists, not when it finishes, so a
                    // run that is cancelled or times out still reports the tests that did
                    // run. Null therefore means "the session never started", which is a
                    // different outcome and is reported as one.
                    testRunResults[0] = currentRun;
                    operation.ifPresent( op -> op.setProgress( "test session started" ) );
                }
                
                @Override
                public void sessionFinished(ITestRunSession session) {
                    latch.countDown();
                }
                
                @Override
                public void testCaseFinished(ITestCaseElement testCaseElement) {
                    if (currentRun != null) {
                        String className = testCaseElement.getTestClassName();
                        String testName = testCaseElement.getTestMethodName();
                        
                        currentRun.addTestResult( collectTestResult( javaProject, testCaseElement ) );
                        int count = finishedTests.incrementAndGet();
                        operation.ifPresent( op -> {
                            op.setProgress( count + " tests finished; last: " + className + "#" + testName );
                            // Publish structured intermediate results so getOperationStatus
                            // can surface pass/fail counts and the failures so far while
                            // the run is still going. RUNNING exists for exactly this
                            // snapshot: it is a real result, just not a final one.
                            TestRunResponse live = currentRun.snapshot( RunStatus.RUNNING, projectName,
                                    requestedClasses, null, List.of(), elapsed( runStartMillis ) );
                            op.setIntermediateResult( "summary", McpJson.toJson( live.summary() ) );
                            op.setIntermediateResult( "results", McpJson.toJson( live ) );
                        } );
                    }
                }
            };
            
            JUnitCore.addTestRunListener(listener);
            
            try {
                ILaunchManager launchManager = DebugPlugin.getDefault().getLaunchManager();
                ILaunchConfigurationWorkingCopy workingCopy;

                if (launcherName != null && !launcherName.isBlank()) {
                    // Use the named saved config as a base — only override targeting attributes
                    ILaunchConfiguration base = findExistingLaunchConfig(launchManager, launcherName);
                    if (base == null) {
                        throw new RuntimeException("Launch configuration not found: " + launcherName);
                    }
                    workingCopy = base.getWorkingCopy();
                } else {
                    // Build a deterministic launch name based on the test target
                    ILaunchConfigurationType type = launchManager.getLaunchConfigurationType(
                            "org.eclipse.jdt.junit.launchconfig");
                    String launchName = buildLaunchName(javaProject, packageFragment, testClass, methodName);
                    ILaunchConfiguration existing = findExistingLaunchConfig(launchManager, launchName);
                    if (existing != null) {
                        workingCopy = existing.getWorkingCopy();
                    } else {
                        workingCopy = type.newInstance(null, launchName);
                    }
                }

                // Always override targeting attributes — everything else from the base config is kept
                workingCopy.setAttribute(IJavaLaunchConfigurationConstants.ATTR_PROJECT_NAME,
                        javaProject.getElementName());

                // CONTAINER must use the Java element handle identifier (e.g. "=ProjectName"),
                // NOT the IResource path (e.g. "/ProjectName") — the JUnit launcher resolves
                // the input element via JavaCore.create(handleId), and a resource path causes
                // "The input element of the launch configuration does not exist".
                if (testClass != null) {
                    workingCopy.setAttribute(IJavaLaunchConfigurationConstants.ATTR_MAIN_TYPE_NAME,
                            testClass.getFullyQualifiedName());
                    workingCopy.setAttribute("org.eclipse.jdt.junit.CONTAINER", "");
                    if (methodName != null && !methodName.isEmpty()) {
                        // JDT reads the single test method from TESTNAME, the key its own launch
                        // shortcut writes. "TEST_METHOD" was never a JDT key, so it was silently
                        // ignored and the whole class ran (issue 158). The value carries the
                        // parameter types, as the shortcut writes it, so overloaded and
                        // parameterized JUnit 5 methods resolve too.
                        workingCopy.setAttribute(JDT_JUNIT_TEST_NAME, qualifiedTestName(testClass, methodName));
                        // A configuration saved by an earlier version still carries the dead key.
                        workingCopy.removeAttribute("org.eclipse.jdt.junit.TEST_METHOD");
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

                // Only set TEST_KIND when not using a named launcher (the base config already has it)
                if (launcherName == null || launcherName.isBlank()) {
                    String testKind = detectJUnitTestKind(javaProject, testClass, packageFragment);
                    workingCopy.setAttribute("org.eclipse.jdt.junit.TEST_KIND", testKind);
                }
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
                    // Whatever was collected before the deadline is still reported: a run
                    // that timed out after 30 of 40 tests knows more than "it timed out".
                    return abandoned( testRunResults[0], RunStatus.TIMED_OUT, projectName,
                            requestedClasses, Diagnostic.retryable( DiagnosticCode.TEST_RESULTS_NOT_REPORTED,
                                    "The test run did not report results in time." ),
                            runStartMillis );
                }
                
                if (testRunResults[0] == null) {
                    return TestRunResponse.notStarted( projectName, requestedClasses,
                            Diagnostic.fatal( DiagnosticCode.TEST_RESULTS_NOT_REPORTED,
                                    "No test results collected. The test run may have failed to start." ),
                            elapsed( runStartMillis ) );
                }
                
                CoverageResult coverage = null;
                if (withCoverage && !useCoverage) {
                    coverage = CoverageResult.unavailable();
                }
                else if (useCoverage) {
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
                
            } finally {
                JUnitCore.removeTestRunListener(listener);
            }
            
        } catch (InterruptedException e) {
            // cancelOperation interrupts this thread; the launch itself is terminated by
            // the operation's cancel hook.
            Thread.currentThread().interrupt();
            return abandoned( testRunResults[0], RunStatus.CANCELLED, projectName, requestedClasses,
                    Diagnostic.fatal( DiagnosticCode.TEST_RESULTS_NOT_REPORTED, "Test run cancelled." ),
                    runStartMillis );
        } catch (Exception e) {
            logger.error("Error running tests", e);
            return TestRunResponse.notStarted( projectName, requestedClasses,
                    Diagnostic.fatal( DiagnosticCode.INTERNAL_ERROR, "Error running tests: " + e.getMessage() ),
                    elapsed( runStartMillis ) );
        }
    }

    /**
     * A run that ended without the session reporting - cancelled or timed out - keeping
     * whatever the accumulator already held. Discarding it would throw away the results
     * of every test that did finish, which is usually most of them.
     */
    private TestRunResponse abandoned( TestRunResult collected, RunStatus status, String projectName,
            List<String> requestedClasses, Diagnostic diagnostic, long runStartMillis ) {
        if ( collected == null ) {
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
    
    /**
     * Finds all test classes in a project, split by the harness each one needs.
     * 
     * @param projectName The name of the project to search
     */
    public TestClassesResponse findTestClasses(String projectName) {
        Objects.requireNonNull(projectName, "Project name cannot be null");

        if (projectName.isEmpty()) {
            throw new IllegalArgumentException("Error: Project name cannot be empty.");
        }

        try {
            IJavaProject javaProject = getJavaProject( projectName );
            List<TestClassesResponse.TestClass> plainTests = new ArrayList<>();
            List<TestClassesResponse.TestClass> pdeTests = new ArrayList<>();

            for (IPackageFragmentRoot root : javaProject.getPackageFragmentRoots()) {
                if (root.getKind() == IPackageFragmentRoot.K_SOURCE) {
                    for (IJavaElement child : root.getChildren()) {
                        if (child instanceof IPackageFragment pkg) {
                            for (ICompilationUnit unit : pkg.getCompilationUnits()) {
                                for (IType type : unit.getAllTypes()) {
                                    if (isTestClass(type)) {
                                        String className = type.getFullyQualifiedName();
                                        String filePath = projectRelativePath(type);
                                        if (type.getElementName().endsWith("PDETest")) {
                                            pdeTests.add(new TestClassesResponse.TestClass(
                                                    className, filePath, true));
                                        } else {
                                            plainTests.add(new TestClassesResponse.TestClass(
                                                    className, filePath, likelyRequiresPdeHarness(type)));
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            return TestClassesResponse.of(projectName, plainTests, pdeTests);

        } catch (CoreException e) {
            throw new RuntimeException("Error finding test classes: " + e.getMessage(), e);
        }
    }

    /**
     * The type's file relative to its project - the pair the reading and editing tools
     * take. Null for a type with no file in the workspace, such as one from a library.
     */
    private static String projectRelativePath(IType type)
    {
        IResource resource = type.getResource();
        return resource instanceof IFile file ? file.getProjectRelativePath().toString() : null;
    }

    private boolean likelyRequiresPdeHarness(IType type) throws JavaModelException
    {
        ICompilationUnit unit = type.getCompilationUnit();
        if (unit == null)
        {
            return false;
        }

        String source = unit.getSource();
        return source.contains("ResourcesPlugin")
                || source.contains("PlatformUI")
                || source.contains("FrameworkUtil")
                || source.contains("BundleContext")
                || source.contains("ServiceTracker")
                || source.contains("Platform.getBundle(");
    }

    private IJavaProject getJavaProject( String projectName ) throws CoreException
    {
        // Get the project
        IProject project = ResourcesPlugin.getWorkspace().getRoot().getProject(projectName);
        
        if (!project.exists()) {
            throw new TestSetupException( Diagnostic.fatal( DiagnosticCode.PROJECT_NOT_FOUND,
                    "Project '" + projectName + "' does not exist." ) );
        }
        
        if (!project.isOpen()) {
            throw new TestSetupException( Diagnostic.retryable( DiagnosticCode.PROJECT_NOT_FOUND,
                    "Project '" + projectName + "' is closed." ) );
        }
        
        // Check if it's a Java project
        if (!project.hasNature(JavaCore.NATURE_ID)) {
            throw new TestSetupException( Diagnostic.fatal( DiagnosticCode.PROJECT_NOT_FOUND,
                    "Project '" + projectName + "' is not a Java project." ) );
        }
        
        IJavaProject javaProject = JavaCore.create(project);
        return javaProject;
    }

    /**
     * A request that cannot be launched, carrying the code the caller branches on.
     * <p>
     * Thrown rather than returned so that the several resolution steps between the
     * public method and the launch do not each need a two-valued return. Every public
     * entry point turns it straight back into a {@link TestRunResponse}: it never
     * escapes this service.
     */
    private static final class TestSetupException extends RuntimeException
    {
        private static final long serialVersionUID = 1L;

        private final transient Diagnostic diagnostic;

        TestSetupException( Diagnostic diagnostic )
        {
            super( diagnostic.message() );
            this.diagnostic = diagnostic;
        }

        Diagnostic diagnostic()
        {
            return diagnostic;
        }
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
     * Turns one of JDT's finished test cases into a result, including the two things
     * the old collection never captured: the failure trace and where the failure is.
     * <p>
     * Shared with {@code PDEService}, whose listener has exactly the same job.
     */
    static TestResult collectTestResult( IJavaProject javaProject, ITestCaseElement testCaseElement )
    {
        String className = testCaseElement.getTestClassName();
        String testName = testCaseElement.getTestMethodName();
        TestStatus status = toTestStatus( testCaseElement.getTestResult( true ) );
        String trace = testCaseElement.getFailureTrace() != null
                ? testCaseElement.getFailureTrace().getTrace()
                : null;
        double time = testCaseElement.getElapsedTimeInSeconds();

        // Only a test that did not pass has anywhere worth pointing at, and resolving a
        // location costs a JDT type lookup per test - not something to do 400 times for
        // results nobody will open.
        SourceLocation source = status == TestStatus.FAILED || status == TestStatus.ERROR
                ? resolveSourceLocation( javaProject, className, trace )
                : null;

        return new TestResult( className, testName, status,
                TestRunResponse.firstTraceLine( trace ),
                TestRunResponse.truncateTrace( trace ),
                TestRunResponse.isTraceTruncated( trace ),
                source, time );
    }

    private static TestStatus toTestStatus( Result result )
    {
        if ( result == null )
        {
            return TestStatus.UNKNOWN;
        }
        if ( result == Result.OK )
        {
            return TestStatus.PASSED;
        }
        if ( result == Result.FAILURE )
        {
            return TestStatus.FAILED;
        }
        if ( result == Result.ERROR )
        {
            return TestStatus.ERROR;
        }
        if ( result == Result.IGNORED )
        {
            return TestStatus.SKIPPED;
        }
        return TestStatus.UNKNOWN;
    }

    /**
     * A stack frame: {@code at com.example.FooTest.testBar(FooTest.java:84)}. The
     * declaring type is captured so a frame can be matched to the test class, and the
     * line so the caller can open it.
     */
    private static final Pattern STACK_FRAME =
            Pattern.compile( "^\\s*at\\s+([\\w$.]+)\\.[\\w$<>]+\\((?:[^):]*):(\\d+)\\)" );

    /**
     * Where a failure is, resolved from the trace through JDT.
     * <p>
     * The file comes from the workspace - {@code findType} then the type's resource -
     * so it is a real project-relative path the reading tools accept, not a file name
     * scraped out of the trace. The line comes from the first frame the JVM reported
     * inside the test class, which is where the assertion failed rather than where
     * JUnit's machinery noticed.
     * <p>
     * Anything that cannot be resolved is left null. A guessed line in the right file
     * is worse than no line: it sends the caller to code that is not the fault.
     *
     * @return null when the class is not a type in this project - which is the case for
     *         a synthetic or dynamically generated test class
     */
    public static SourceLocation resolveSourceLocation( IJavaProject javaProject, String className, String trace )
    {
        if ( javaProject == null || className == null || className.isBlank() )
        {
            return null;
        }
        try
        {
            // JDT reports nested classes with '$'; findType wants the source form.
            IType type = javaProject.findType( className.replace( '$', '.' ) );
            if ( type == null )
            {
                return null;
            }
            IResource resource = type.getResource();
            if ( !( resource instanceof IFile file ) )
            {
                return null;
            }
            return new SourceLocation( file.getProject().getName(),
                    file.getProjectRelativePath().toString(), traceLine( className, trace ) );
        }
        catch ( JavaModelException e )
        {
            return null;
        }
    }

    /**
     * The line of the first frame declared by {@code className}, or null when the trace
     * names none - an assertion thrown from a helper class, or no trace at all.
     */
    private static Integer traceLine( String className, String trace )
    {
        if ( trace == null || trace.isBlank() )
        {
            return null;
        }
        for ( String line : trace.split( "\\R" ) )
        {
            Matcher matcher = STACK_FRAME.matcher( line );
            if ( matcher.find() && className.equals( matcher.group( 1 ) ) )
            {
                try
                {
                    return Integer.valueOf( matcher.group( 2 ) );
                }
                catch ( NumberFormatException e )
                {
                    return null;
                }
            }
        }
        return null;
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
            if (methodName.length() > 4 && methodName.startsWith("test") && Character.isUpperCase(methodName.charAt(4))) {
                return true;
            }
        }
        
        return false;
    }
}
