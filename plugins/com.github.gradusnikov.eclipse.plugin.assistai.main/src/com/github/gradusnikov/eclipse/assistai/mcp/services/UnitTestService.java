package com.github.gradusnikov.eclipse.assistai.mcp.services;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
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
                default -> throw new IllegalArgumentException( "Unexpected value: " + result.status );
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
                    .filter(r -> "FAILED".equals(r.status) || "ERROR".equals(r.status))
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
        Objects.requireNonNull(projectName, "Project name cannot be null");
        
        if (projectName.isEmpty()) {
            throw new IllegalArgumentException("Error: Project name cannot be empty.");
        }
        
        if (timeout == null || timeout <= 0) {
            timeout = 60; // Default timeout of 60 seconds
        }
        
        try {
            IJavaProject javaProject = getJavaProject( projectName );
            
            // Run the tests
            return launchJUnitTests(javaProject, null, null, timeout);
            
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
        Objects.requireNonNull(projectName, "Project name cannot be null");
        Objects.requireNonNull(packageName, "Package name cannot be null");
        
        if (projectName.isEmpty()) {
            throw new IllegalArgumentException("Error: Project name cannot be empty.");
        }
        
        if (packageName.isEmpty()) {
            throw new IllegalArgumentException("Error: Package name cannot be empty.");
        }
        
        if (timeout == null || timeout <= 0) {
            timeout = 60; // Default timeout of 60 seconds
        }
        
        try {
            IJavaProject javaProject = getJavaProject( projectName );
            
            // Find the package
            IPackageFragment pkg = findPackage(javaProject, packageName);
            if (pkg == null) {
                throw new RuntimeException("Error: Package '" + packageName + "' not found in project '" + projectName + "'.");
            }
            
            // Run the tests
            return launchJUnitTests(javaProject, pkg, null, timeout);
            
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
        Objects.requireNonNull(projectName, "Project name cannot be null");
        Objects.requireNonNull(className, "Class name cannot be null");
        
        if (projectName.isEmpty()) {
            throw new IllegalArgumentException("Error: Project name cannot be empty.");
        }
        
        if (className.isEmpty()) {
            throw new IllegalArgumentException("Error: Class name cannot be empty.");
        }
        
        if (timeout == null || timeout <= 0) {
            timeout = 60; // Default timeout of 60 seconds
        }
        
        try {
            IJavaProject javaProject = getJavaProject( projectName );
            
            // Find the class
            IType type = javaProject.findType(className);
            if (type == null) {
                throw new RuntimeException("Error: Class '" + className + "' not found in project '" + projectName + "'.");
            }
            
            // Run the tests
            return launchJUnitTests(javaProject, null, type, timeout);
            
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
            timeout = 60; // Default timeout of 60 seconds
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
            
            // Create a test name with the specific method
            String testName = className + "." + methodName;
            
            // Run the tests
            return launchJUnitTests(javaProject, null, type, timeout, methodName);
            
        } catch (CoreException e) {
            throw new RuntimeException("Error running tests: " + e.getMessage(), e);
        }
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
     * Launches JUnit tests using Eclipse's JUnit infrastructure.
     */
    private String launchJUnitTests(IJavaProject javaProject, IPackageFragment packageFragment, 
                                    IType testClass, int timeout) {
        return launchJUnitTests(javaProject, packageFragment, testClass, timeout, null);
    }
    
    /**
     * Launches JUnit tests using Eclipse's JUnit infrastructure with optional method filtering.
     */
    private String launchJUnitTests(IJavaProject javaProject, IPackageFragment packageFragment, 
                                   IType testClass, int timeout, String methodName) {
        final CountDownLatch latch = new CountDownLatch(1);
        final TestRunResult[] testRunResults = new TestRunResult[1];
        
        try {
            // Register a test run listener to collect results
            TestRunListener listener = new TestRunListener() {
                private TestRunResult currentRun = null;
                
                @Override
                public void sessionStarted(ITestRunSession session) {
                    currentRun = new TestRunResult(session.getTestRunName());
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
                    }
                }
            };
            
            JUnitCore.addTestRunListener(listener);
            
            try {
                // Create and configure the launch
                ILaunchManager launchManager = DebugPlugin.getDefault().getLaunchManager();
                ILaunchConfigurationType type = launchManager.getLaunchConfigurationType(
                        "org.eclipse.jdt.junit.launchconfig");
                
                // Create a temporary launch configuration
                ILaunchConfigurationWorkingCopy workingCopy = type.newInstance(null, 
                        "UnitTestService-" + System.currentTimeMillis());
                
                // Set the project name
                workingCopy.setAttribute(IJavaLaunchConfigurationConstants.ATTR_PROJECT_NAME, 
                        javaProject.getElementName());
                
                // Set the test target
                if (testClass != null) {
                    workingCopy.setAttribute(IJavaLaunchConfigurationConstants.ATTR_MAIN_TYPE_NAME, 
                            testClass.getFullyQualifiedName());
                    
                    if (methodName != null && !methodName.isEmpty()) {
                        workingCopy.setAttribute("org.eclipse.jdt.junit.TEST_METHOD", methodName);
                    }
                } else if (packageFragment != null) {
                    workingCopy.setAttribute(IJavaLaunchConfigurationConstants.ATTR_MAIN_TYPE_NAME, "");
                    workingCopy.setAttribute("org.eclipse.jdt.junit.CONTAINER", 
                            packageFragment.getPath().toString());
                } else {
                    workingCopy.setAttribute(IJavaLaunchConfigurationConstants.ATTR_MAIN_TYPE_NAME, "");
                    workingCopy.setAttribute("org.eclipse.jdt.junit.CONTAINER", 
                            javaProject.getPath().toString());
                }
                
                // Set JUnit 5 as the test runner
                workingCopy.setAttribute("org.eclipse.jdt.junit.TEST_KIND", "org.eclipse.jdt.junit.loader.junit5");
                
                // Create the actual configuration
                ILaunchConfiguration configuration = workingCopy.doSave();
                
                // Launch the tests
                sync.syncExec(() -> {
                    try {
                        configuration.launch(ILaunchManager.RUN_MODE, new NullProgressMonitor());
                    } catch (CoreException e) {
                        logger.error("Error launching tests", e);
                    }
                });
                
                // Wait for completion
                boolean completed = latch.await(timeout, TimeUnit.SECONDS);
                
                if (!completed) {
                    return "Error: Test execution timed out after " + timeout + " seconds.";
                }
                
                if (testRunResults[0] == null) {
                    return "Error: No test results collected. The test run may have failed to start.";
                }
                
                return testRunResults[0].toString();
                
            } finally {
                JUnitCore.removeTestRunListener(listener);
            }
            
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
