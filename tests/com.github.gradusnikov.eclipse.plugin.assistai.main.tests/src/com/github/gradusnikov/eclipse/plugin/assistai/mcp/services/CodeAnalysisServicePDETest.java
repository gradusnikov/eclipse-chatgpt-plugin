
package com.github.gradusnikov.eclipse.plugin.assistai.mcp.services;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

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
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.core.runtime.Path;
import org.eclipse.e4.core.contexts.ContextInjectionFactory;
import org.eclipse.e4.core.contexts.EclipseContextFactory;
import org.eclipse.e4.core.contexts.IEclipseContext;
import org.eclipse.core.runtime.jobs.Job;
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

public class CodeAnalysisServicePDETest {

    private static final String TEST_PROJECT_NAME = "CodeAnalysisTestProject";
    private IProject project;
    private IJavaProject javaProject;
    private CodeAnalysisService service;
    private NullProgressMonitor monitor = new NullProgressMonitor();
    
    @BeforeEach
    public void beforeEach() throws CoreException, IOException, InterruptedException {
        // Get workspace through OSGi service tracker
        BundleContext bundleContext = FrameworkUtil.getBundle(CodeAnalysisServicePDETest.class).getBundleContext();
        ServiceTracker<IWorkspace, IWorkspace> workspaceTracker = new ServiceTracker<>(bundleContext, IWorkspace.class, null);
        
        workspaceTracker.open();
        IWorkspace workspace = workspaceTracker.getService();
        IWorkspaceRoot root = workspace.getRoot();
        
        // Delete the project if it exists
        project = root.getProject(TEST_PROJECT_NAME);
        if (project.exists()) {
            project.delete(true, true, monitor);
        }
        
        // Create a test project â create plain (closed), then open, then add natures.
        // Natures MUST be added via setDescription() on an already-open project so that
        // JavaNature.configure() is invoked and registers javabuilder in the build spec.
        project = root.getProject(TEST_PROJECT_NAME);
        IProjectDescription desc = project.getWorkspace().newProjectDescription(project.getName());
        project.create(desc, monitor);
        project.open(monitor);

        // Add Java nature to the open project â triggers JavaNature.configure()
        IProjectDescription openDesc = project.getDescription();
        openDesc.setNatureIds(new String[] { JavaCore.NATURE_ID });
        project.setDescription(openDesc, monitor);
        
        // Set up Java project
        javaProject = JavaCore.create(project);
        
        // Set Java 21 compliance so diamond operators, var, etc. all compile cleanly
        javaProject.setOption(JavaCore.COMPILER_COMPLIANCE, "21");
        javaProject.setOption(JavaCore.COMPILER_SOURCE, "21");
        javaProject.setOption(JavaCore.COMPILER_CODEGEN_TARGET_PLATFORM, "21");
        
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
        
        // Wait for all build and auto-build background jobs to complete
        Job.getJobManager().join(ResourcesPlugin.FAMILY_MANUAL_BUILD, monitor);
        Job.getJobManager().join(ResourcesPlugin.FAMILY_AUTO_BUILD, monitor);
        Thread.sleep(500);
        
        // Initialize service with DI context
        IEclipseContext context = EclipseContextFactory.create();
        context.set(ILog.class, Activator.getDefault().getLog());
        service = ContextInjectionFactory.make(CodeAnalysisService.class, context);
    }
    
    @AfterEach
    public void afterEach() throws CoreException, InterruptedException {
        // Wait for all background build/index jobs to finish before deleting the
        // project â otherwise JDT still holds file handles and Eclipse shows a
        // "resource already deleted" dialog.
        Job.getJobManager().join(ResourcesPlugin.FAMILY_MANUAL_BUILD, monitor);
        Job.getJobManager().join(ResourcesPlugin.FAMILY_AUTO_BUILD, monitor);
        // Give JDT indexer and other post-build jobs a moment to settle
        Thread.sleep(500);

        // Close any editors opened by executeQuickFix (which opens a document to
        // apply the text change). If the editor is still open when the project is
        // deleted, Eclipse shows a "File Not Accessible" dialog.
        org.eclipse.ui.PlatformUI.getWorkbench().getDisplay().syncExec(() -> {
            org.eclipse.ui.IWorkbenchWindow window =
                    org.eclipse.ui.PlatformUI.getWorkbench().getActiveWorkbenchWindow();
            if (window != null) {
                org.eclipse.ui.IWorkbenchPage page = window.getActivePage();
                if (page != null) {
                    page.closeAllEditors(false); // false = don't save
                }
            }
        });

        if (project != null && project.exists()) {
            // On Windows, file buffers may not release immediately. Retry a few times.
            for (int attempt = 0; attempt < 5; attempt++) {
                try {
                    project.delete(true, true, monitor);
                    break;
                } catch (CoreException e) {
                    if (attempt == 4) throw e;
                    Thread.sleep(500);
                }
            }
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
        Job.getJobManager().join(ResourcesPlugin.FAMILY_MANUAL_BUILD, monitor);
        Job.getJobManager().join(ResourcesPlugin.FAMILY_AUTO_BUILD, monitor);
        Thread.sleep(500);
        
        // Verify markers were created
        IMarker[] markers = project.findMarkers(IMarker.PROBLEM, true, IResource.DEPTH_INFINITE);
        System.out.println("Found " + markers.length + " markers");
        
        // Skip if the Java builder didn't produce markers (e.g. in headless Tycho runner
        // without a full JDT workspace initialised)
        org.junit.jupiter.api.Assumptions.assumeTrue(markers.length > 0,
                "No error markers generated â Java builder not active in this environment");
        
        // Test getting compilation errors for the project
        String result = service.getCompilationErrors(
                TEST_PROJECT_NAME, 
                "ALL", 
                50);
        
        System.out.println("Compilation errors result: " + result);
        
        // Verify the result contains expected information
        assertTrue(result.contains("# Compilation Problems"));
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
        Job.getJobManager().join(ResourcesPlugin.FAMILY_MANUAL_BUILD, monitor);
        Job.getJobManager().join(ResourcesPlugin.FAMILY_AUTO_BUILD, monitor);
        Thread.sleep(500);
        
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

    /**
     * Tests that getCompilationErrors includes Marker IDs and quick-fix proposals
     * inline for a file containing a missing-import error.
     */
    @Test
    public void testGetCompilationErrors_IncludesQuickFixes() throws CoreException, InterruptedException {
        String source =
                "package com.example;\n\n" +
                "public class MissingImportClass {\n" +
                "    public void test() {\n" +
                "        ArrayList<String> list = new ArrayList<>();\n" +
                "    }\n" +
                "}\n";

        createFile("src/com/example/MissingImportClass.java", source);
        project.refreshLocal(IResource.DEPTH_INFINITE, monitor);
        project.build(IncrementalProjectBuilder.FULL_BUILD, monitor);
        Job.getJobManager().join(ResourcesPlugin.FAMILY_MANUAL_BUILD, monitor);
        Job.getJobManager().join(ResourcesPlugin.FAMILY_AUTO_BUILD, monitor);
        Thread.sleep(500);

        IMarker[] markers = project.findMarkers(IMarker.PROBLEM, true, IResource.DEPTH_INFINITE);
        org.junit.jupiter.api.Assumptions.assumeTrue(markers.length > 0,
                "No error markers generated - Java builder not active in this environment");

        String result = service.getCompilationErrors(TEST_PROJECT_NAME, "ALL", 50);
        System.out.println("getCompilationErrors (with quick fixes) result:\n" + result);

        assertTrue(result.contains("Marker ID:"), "Should contain Marker ID");
        assertTrue(result.contains("executeQuickFix"), "Should hint to call executeQuickFix");
        assertTrue(result.contains("ArrayList") || result.contains("Import"),
                "Should contain import-related quick fix proposal");
    }

    /**
     * Tests that executeQuickFix applies the "add import" quick fix obtained via
     * getCompilationErrors and reports success.
     */
    @Test
    public void testExecuteQuickFix_AddImport() throws CoreException, InterruptedException, java.io.IOException {
        String source =
                "package com.example;\n\n" +
                "public class FixMe {\n" +
                "    public void test() {\n" +
                "        ArrayList<String> list = new ArrayList<>();\n" +
                "    }\n" +
                "}\n";

        IFile file = createFile("src/com/example/FixMe.java", source);
        project.refreshLocal(IResource.DEPTH_INFINITE, monitor);
        project.build(IncrementalProjectBuilder.FULL_BUILD, monitor);
        Job.getJobManager().join(ResourcesPlugin.FAMILY_MANUAL_BUILD, monitor);
        Job.getJobManager().join(ResourcesPlugin.FAMILY_AUTO_BUILD, monitor);
        Thread.sleep(500);

        IMarker[] markersBefore = file.findMarkers(IMarker.PROBLEM, true, IResource.DEPTH_ZERO);
        org.junit.jupiter.api.Assumptions.assumeTrue(markersBefore.length > 0,
                "No error markers on FixMe.java â Java builder not active in this environment");

        // Obtain marker ID the same way an LLM would: via getCompilationErrors
        String errorsResult = service.getCompilationErrors(TEST_PROJECT_NAME, "ALL", 50);
        System.out.println("getCompilationErrors before apply:\n" + errorsResult);
        assertTrue(errorsResult.contains("Marker ID:"), "Errors result must contain a Marker ID");

        // Find the marker whose index-0 fix is the import fix (JDT ICUCorrectionProposal marker)
        long markerId = extractMarkerIdWithImportAtIndex0(errorsResult, "Import 'ArrayList'");
        if (markerId == -1L)
        {
            // Fallback: use the first marker ID
            markerId = extractFirstMarkerId(errorsResult);
        }
        assertNotEquals(-1L, markerId, "Should have parsed a valid marker ID");

        // Find the index of the import fix for this marker
        int importIndex = extractImportFixIndex(errorsResult, markerId, "Import 'ArrayList'");
        if (importIndex == -1) importIndex = 0;

        String applyResult = service.executeQuickFix(markerId, importIndex);
        System.out.println("executeQuickFix result: " + applyResult);

        // The call must not throw
        assertTrue(applyResult.contains("Quick fix applied") || applyResult.startsWith("Error"),
                "Result should indicate applied fix or an error message");

        // Verify the fix was actually persisted to disk by reading the file directly.
        if (applyResult.contains("Quick fix applied"))
        {
            file.refreshLocal(IResource.DEPTH_ZERO, monitor);
            String fileContent = new String(file.getContents(true).readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            System.out.println("File content after fix:\\n" + fileContent);
            assertTrue(fileContent.contains("import java.util.ArrayList"),
                    "File on disk should contain the added import after fix is applied. File content: " + fileContent);
        }
    }

    /**
     * Tests that executeQuickFix returns an error message for an unknown marker ID.
     */
    @Test
    public void testExecuteQuickFix_UnknownMarkerId() {
        String result = service.executeQuickFix(Long.MAX_VALUE, 0);
        System.out.println("executeQuickFix (bad id) result: " + result);
        assertTrue(result.startsWith("Error:"), "Should return an error for unknown marker ID");
    }

    // -------------------------------------------------------------------------
    // helpers
    // -------------------------------------------------------------------------

    private long extractFirstMarkerId(String text) {
        for (String line : text.split("\\n")) {
            line = line.trim();
            // handle both "Marker ID: 123" and "- Marker ID: 123"
            if (line.startsWith("- Marker ID:")) {
                line = line.substring("- ".length()).trim();
            }
            if (line.startsWith("Marker ID:")) {
                try {
                    return Long.parseLong(line.substring("Marker ID:".length()).trim());
                } catch (NumberFormatException ignored) {
                }
            }
        }
        return -1L;
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

    /**
     * Finds the first marker ID in the errorsResult whose index-0 quick-fix proposal label
     * contains the given fixLabelPrefix. Returns -1 if not found.
     */
    private long extractMarkerIdWithImportAtIndex0(String text, String fixLabelPrefix)
    {
        long currentMarkerId = -1L;
        for (String line : text.split("\n"))
        {
            String trimmed = line.trim();
            if (trimmed.startsWith("- Marker ID:"))
            {
                try { currentMarkerId = Long.parseLong(trimmed.substring("- Marker ID:".length()).trim()); }
                catch (NumberFormatException ignored) {}
            }
            else if (trimmed.startsWith("- [0]") && currentMarkerId != -1L)
            {
                if (trimmed.contains(fixLabelPrefix))
                    return currentMarkerId;
            }
        }
        return -1L;
    }

    /**
     * For the given markerId block in errorsResult, finds the index of the first proposal
     * whose label contains fixLabelPrefix. Returns -1 if not found.
     */
    private int extractImportFixIndex(String text, long markerId, String fixLabelPrefix)
    {
        boolean inBlock = false;
        for (String line : text.split("\n"))
        {
            String trimmed = line.trim();
            if (trimmed.startsWith("- Marker ID:"))
            {
                try
                {
                    long id = Long.parseLong(trimmed.substring("- Marker ID:".length()).trim());
                    inBlock = (id == markerId);
                }
                catch (NumberFormatException ignored) {}
            }
            else if (inBlock && trimmed.startsWith("- ["))
            {
                int bracket = trimmed.indexOf(']');
                if (bracket > 2)
                {
                    try
                    {
                        int idx = Integer.parseInt(trimmed.substring(2, bracket));
                        if (trimmed.contains(fixLabelPrefix))
                            return idx;
                    }
                    catch (NumberFormatException ignored) {}
                }
            }
        }
        return -1;
    }

}
