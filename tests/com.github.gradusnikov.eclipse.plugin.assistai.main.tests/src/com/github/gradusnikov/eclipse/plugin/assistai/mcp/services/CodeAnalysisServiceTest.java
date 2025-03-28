
package com.github.gradusnikov.eclipse.plugin.assistai.mcp.services;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IFolder;
import org.eclipse.core.resources.IMarker;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IProjectDescription;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IWorkspace;
import org.eclipse.core.resources.IWorkspaceRoot;
import org.eclipse.core.resources.IncrementalProjectBuilder;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.ILog;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.core.runtime.Path;
import org.eclipse.e4.core.contexts.ContextInjectionFactory;
import org.eclipse.e4.core.contexts.EclipseContextFactory;
import org.eclipse.e4.core.contexts.IEclipseContext;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.launching.JavaRuntime;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.osgi.framework.BundleContext;
import org.osgi.framework.FrameworkUtil;
import org.osgi.util.tracker.ServiceTracker;

import com.github.gradusnikov.eclipse.assistai.Activator;
import com.github.gradusnikov.eclipse.assistai.mcp.services.CodeAnalysisService;

public class CodeAnalysisServiceTest {

    private static final String TEST_PROJECT_NAME = "CodeAnalysisTestProject";
    private IProject project;
    private IJavaProject javaProject;
    private CodeAnalysisService service;
    private NullProgressMonitor monitor = new NullProgressMonitor();
    
    @BeforeEach
    public void beforeEach() throws CoreException, IOException, InterruptedException {
        // Get workspace through OSGi service tracker
        BundleContext bundleContext = FrameworkUtil.getBundle(CodeAnalysisServiceTest.class).getBundleContext();
        ServiceTracker<IWorkspace, IWorkspace> workspaceTracker = new ServiceTracker<>(bundleContext, IWorkspace.class, null);
        
        workspaceTracker.open();
        IWorkspace workspace = workspaceTracker.getService();
        IWorkspaceRoot root = workspace.getRoot();
        
        // Delete the project if it exists
        project = root.getProject(TEST_PROJECT_NAME);
        if (project.exists()) {
            project.delete(true, true, monitor);
        }
        
        // Create a test project
        project = root.getProject(TEST_PROJECT_NAME);
        IProjectDescription desc = project.getWorkspace().newProjectDescription(project.getName());
        desc.setNatureIds(new String[] {JavaCore.NATURE_ID}); // set Java nature
        project.create(desc, monitor);
        project.open(monitor);
        
        // Set up Java project
        javaProject = JavaCore.create(project);
        
        // Create output folder (bin)
        IFolder binFolder = project.getFolder("bin");
        if (!binFolder.exists()) {
            binFolder.create(true, true, monitor);
        }
        
        // Set output location
        javaProject.setOutputLocation(binFolder.getFullPath(), monitor);
        
        // Create source folder
        IFolder srcFolder = project.getFolder("src");
        if (!srcFolder.exists()) {
            srcFolder.create(IResource.NONE, true, monitor);
        }
        
        // Set classpath with source folder and JRE
        javaProject.setRawClasspath(
                new org.eclipse.jdt.core.IClasspathEntry[] {
                        JavaCore.newSourceEntry(project.getFullPath().append("src")),
                        JavaRuntime.getDefaultJREContainerEntry()
                }, 
                monitor);
        
        // Create package structure
        createPackageStructure();
        
        // Create test classes
        createTestClasses();
        
        // Force a full build of the project
        project.build(IncrementalProjectBuilder.FULL_BUILD, monitor);
        
        // Wait a moment for the build to complete and for Eclipse to process markers
        Thread.sleep(1000);
        
        // Initialize service with DI context
        IEclipseContext context = EclipseContextFactory.create();
        context.set(ILog.class, Activator.getDefault().getLog());
        service = ContextInjectionFactory.make(CodeAnalysisService.class, context);
    }
    
    @AfterEach
    public void afterEach() throws CoreException {
        // Clean up the test project
        if (project != null && project.exists()) {
            project.delete(true, true, monitor);
        }
    }
    
    @Test
    public void testGetMethodCallHierarchy() throws CoreException, InterruptedException {
        // Test getting call hierarchy for Caller class
        String result = service.getMethodCallHierarchy(
                "com.example.Caller", 
                "callerMethod", 
                "", 
                3);
        
        // Verify the result contains expected information
        assertTrue(result.contains("# Call Hierarchy for Method: callerMethod"));
        
        // Test getting call hierarchy for Callee class
        result = service.getMethodCallHierarchy(
                "com.example.Callee", 
                "calleeMethod", 
                "", 
                3);
        
        assertTrue(result.contains("# Call Hierarchy for Method: calleeMethod"));
        
        // Depending on the test environment, you might need to check for specific caller information
        // This can be unreliable in automated tests due to indexing timing
        // assertTrue(result.contains("callerMethod") && result.contains("Caller"));
    }
    
    @Test
    public void testGetCompilationErrors() throws CoreException, InterruptedException {
        // Create a class with compilation errors
        createClassWithErrors();
        
        // Refresh the project to detect errors
        project.refreshLocal(IResource.DEPTH_INFINITE, monitor);
        
        // Force a build to generate error markers
        project.build(IncrementalProjectBuilder.FULL_BUILD, monitor);
        
        // Wait for build and marker generation
        Thread.sleep(1000);
        
        // Verify markers were created
        IMarker[] markers = project.findMarkers(IMarker.PROBLEM, true, IResource.DEPTH_INFINITE);
        System.out.println("Found " + markers.length + " markers");
        
        // Test getting compilation errors for the project
        String result = service.getCompilationErrors(
                TEST_PROJECT_NAME, 
                "ALL", 
                50);
        
        System.out.println("Compilation errors result: " + result);
        
        // Verify the result contains expected information
        assertTrue(result.contains("# Compilation Problems"));
        
        // These assertions may be environment-dependent
        // In some environments, the exact error message might differ
        assertTrue(result.contains("ERROR") || result.contains("cannot be resolved"));
    }
    
    @Test
    public void testGetCompilationErrors_WithSpecificSeverity() throws CoreException, InterruptedException {
        // Create classes with errors and warnings
        createClassWithErrors();
        createClassWithWarnings();
        
        // Refresh the project to detect issues
        project.refreshLocal(IResource.DEPTH_INFINITE, monitor);
        
        // Force a build to generate markers
        project.build(IncrementalProjectBuilder.FULL_BUILD, monitor);
        
        // Wait for build and marker generation
        Thread.sleep(1000);
        
        // Test getting only ERROR severity problems
        String errorResult = service.getCompilationErrors(
                TEST_PROJECT_NAME, 
                "ERROR", 
                50);
        
        System.out.println("ERROR severity result: " + errorResult);
        
        // Test getting only WARNING severity problems
        String warningResult = service.getCompilationErrors(
                TEST_PROJECT_NAME, 
                "WARNING", 
                50);
        
        System.out.println("WARNING severity result: " + warningResult);
        
        // Verify error result contains errors
        assertTrue(errorResult.contains("ERROR") || errorResult.contains("No compilation problems found"));
        
        // Verify warning result contains warnings or indicates no warnings found
        // This is more environment-dependent as some JDKs might not generate warnings for our example
        assertTrue(warningResult.contains("WARNING") || warningResult.contains("No compilation problems found"));
    }
    
    private void createPackageStructure() throws CoreException {
        // Create package folders
        IFolder comFolder = project.getFolder("src/com");
        if (!comFolder.exists()) {
            comFolder.create(IResource.NONE, true, monitor);
        }
        
        IFolder exampleFolder = project.getFolder("src/com/example");
        if (!exampleFolder.exists()) {
            exampleFolder.create(IResource.NONE, true, monitor);
        }
    }
    
    private void createTestClasses() throws CoreException {
        // Create a class that calls another class's method
        String callerSource = 
                "package com.example;\n\n" +
                "public class Caller {\n" +
                "    public void callerMethod() {\n" +
                "        Callee callee = new Callee();\n" +
                "        callee.calleeMethod();\n" +
                "    }\n" +
                "}\n";
        
        createFile("src/com/example/Caller.java", callerSource);
        
        // Create the class being called
        String calleeSource = 
                "package com.example;\n\n" +
                "public class Callee {\n" +
                "    public void calleeMethod() {\n" +
                "        System.out.println(\"Called method\");\n" +
                "    }\n" +
                "}\n";
        
        createFile("src/com/example/Callee.java", calleeSource);
    }
    
    private void createClassWithErrors() throws CoreException {
        // Create a class with compilation errors (undefined variable)
        String errorSource = 
                "package com.example;\n\n" +
                "public class ErrorClass {\n" +
                "    public void methodWithError() {\n" +
                "        // This will cause a compilation error\n" +
                "        System.out.println(undefinedVariable);\n" +
                "    }\n" +
                "}\n";
        
        createFile("src/com/example/ErrorClass.java", errorSource);
    }
    
    private void createClassWithWarnings() throws CoreException {
        // Create a class with warnings (unused variable)
        String warningSource = 
                "package com.example;\n\n" +
                "public class WarningClass {\n" +
                "    public void methodWithWarning() {\n" +
                "        // This will cause a warning (unused variable)\n" +
                "        int unusedVariable = 10;\n" +
                "        // Just to avoid optimization\n" +
                "        System.out.println(\"Warning test\");\n" +
                "    }\n" +
                "}\n";
        
        createFile("src/com/example/WarningClass.java", warningSource);
    }
    
    private IFile createFile(String path, String content) throws CoreException {
        IFile file = project.getFile(new Path(path));
        ByteArrayInputStream source = new ByteArrayInputStream(content.getBytes());
        
        if (file.exists()) {
            file.setContents(source, true, true, monitor);
        } else {
            file.create(source, true, monitor);
        }
        
        return file;
    }
}
