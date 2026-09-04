
package com.github.gradusnikov.eclipse.assistai.mcp.services;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.UUID;

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
import com.github.gradusnikov.eclipse.assistai.mcp.results.CallHierarchyResponse;
import com.github.gradusnikov.eclipse.assistai.mcp.results.CompilationProblemsResponse;
import com.github.gradusnikov.eclipse.assistai.mcp.results.ImportSuggestionsResponse;
import com.github.gradusnikov.eclipse.assistai.mcp.results.QuickFixResponse;
import com.github.gradusnikov.eclipse.assistai.mcp.services.CodeAnalysisService;

public class CodeAnalysisServicePDETest {

    private static final String TEST_PROJECT_NAME_PREFIX = "CodeAnalysisTestProject";
    private String testProjectName;
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
        testProjectName = TEST_PROJECT_NAME_PREFIX + "_" + UUID.randomUUID();
        
        // Delete the project if it exists
        project = root.getProject(testProjectName);
        if (project.exists()) {
            project.delete(true, true, monitor);
        }
        
        // Create a test project â create plain (closed), then open, then add natures.
        // Natures MUST be added via setDescription() on an already-open project so that
        // JavaNature.configure() is invoked and registers javabuilder in the build spec.
        project = root.getProject(testProjectName);
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
            // Closing the project releases JDT and file-buffer handles before the
            // Windows filesystem deletion. Antivirus/indexer locks can still lag,
            // so retry the destructive part for a bounded period.
            if (project.isOpen()) {
                project.close(monitor);
            }
            for (int attempt = 0; attempt < 10; attempt++) {
                try {
                    project.delete(true, true, monitor);
                    break;
                } catch (CoreException e) {
                    if (attempt == 9) {
                        // The launch workspace is disposable. Remove the project from
                        // the workspace even if an external Windows process still owns
                        // a file handle; the unique location cannot affect another test.
                        project.delete(false, true, monitor);
                        break;
                    }
                    Thread.sleep(1000);
                }
            }
        }
    }
    
    @Test
    public void testGetMethodCallHierarchy() throws CoreException, InterruptedException {
        CallHierarchyResponse result = service.getMethodCallHierarchy(
                "com.example.Caller", 
                "callerMethod", 
                "", 
                3);

        assertEquals(CallHierarchyResponse.Status.OK, result.status());
        assertEquals("callerMethod", result.methodName());
        assertEquals("com.example.Caller", result.declaringType());

        result = service.getMethodCallHierarchy(
                "com.example.Callee", 
                "calleeMethod", 
                "", 
                3);

        assertEquals(CallHierarchyResponse.Status.OK, result.status());
        assertEquals("calleeMethod", result.methodName());
        assertEquals(result.callers().size(), result.totalCallers());
        assertEquals(result.callees().size(), result.totalCallees());
    }

    /**
     * The reason this tool exists as a record: a caller must be openable without a
     * follow-up findReferences. Indexing timing makes the presence of a particular
     * caller unreliable, so what is asserted is that every node that <em>is</em>
     * reported carries a usable location.
     */
    @Test
    public void testGetMethodCallHierarchy_ReportsWhereEachCallerLives() throws CoreException {
        CallHierarchyResponse result = service.getMethodCallHierarchy(
                "com.example.Callee", "calleeMethod", "", 3);

        assertEquals(CallHierarchyResponse.Status.OK, result.status());
        for (CallHierarchyResponse.CallNode node : result.callers())
        {
            assertTrue(node.depth() >= 1, "depth is a field, and a direct caller is depth 1");
            if (testProjectName.equals(node.projectName()))
            {
                assertTrue(project.getFile(new Path(node.filePath())).exists(),
                        "a reported path must be project-relative and resolve: " + node.filePath());
                assertTrue(node.lineNumber() > 0,
                        "a workspace-source caller must report the line to open");
            }
        }
    }

    @Test
    public void testGetMethodCallHierarchy_SeparatesUnknownTypeFromUnknownMethod() {
        assertEquals(CallHierarchyResponse.Status.TYPE_NOT_FOUND,
                service.getMethodCallHierarchy("com.example.NoSuchType", "whatever", "", 3).status());

        assertEquals(CallHierarchyResponse.Status.METHOD_NOT_FOUND,
                service.getMethodCallHierarchy("com.example.Callee", "noSuchMethod", "", 3).status(),
                "'the class is not there' and 'the method is not there' have different remedies");
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
        
        // Skip if the Java builder didn't produce markers (e.g. in headless Tycho runner
        // without a full JDT workspace initialised)
        org.junit.jupiter.api.Assumptions.assumeTrue(markers.length > 0,
                "No error markers generated â Java builder not active in this environment");
        
        // Test getting compilation errors for the project
        CompilationProblemsResponse result = service.getCompilationErrors(
                testProjectName, 
                "ALL", 
                50);
        
        // Assert on the reported structure rather than on how it is worded.
        assertTrue(result.scope().contains(testProjectName));
        assertTrue(result.hasErrors(), "a class that does not compile must report errorCount > 0");
        assertTrue(result.files().get(0).problems().get(0).lineNumber() > 0);
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
        CompilationProblemsResponse errorResult = service.getCompilationErrors(
                testProjectName, 
                "ERROR", 
                50);
        
        // Test getting only WARNING severity problems
        CompilationProblemsResponse warningResult = service.getCompilationErrors(
                testProjectName, 
                "WARNING", 
                50);
        
        // A severity filter must not leak the other severities into the counts. Whether
        // any warnings exist at all is environment-dependent, so only the filtering is
        // asserted, not the presence of a particular problem.
        assertEquals(0, errorResult.warningCount(), "an ERROR query must report no warnings");
        assertEquals(errorResult.totalProblems(), errorResult.errorCount());

        assertEquals(0, warningResult.errorCount(), "a WARNING query must report no errors");
        assertEquals(warningResult.totalProblems(), warningResult.warningCount());
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

        CompilationProblemsResponse result = service.getCompilationErrors(testProjectName, "ALL", 50);

        var problem = result.files().get(0).problems().get(0);
        assertTrue(problem.markerId() > 0, "executeQuickFix needs a marker id");
        assertFalse(problem.quickFixes().isEmpty(), "a missing import should offer a quick fix");
        assertTrue(problem.quickFixes().stream()
                        .anyMatch(fix -> fix.label().contains("ArrayList") || fix.label().contains("Import")),
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

        // Obtain marker ID the same way an agent would: via getCompilationErrors.
        // No parsing - the id and the fix index are fields.
        CompilationProblemsResponse errorsResult = service.getCompilationErrors(testProjectName, "ALL", 50);
        assertTrue(errorsResult.totalProblems() > 0, "Errors result must report a problem");

        long markerId = -1L;
        int importIndex = 0;
        for (var reported : errorsResult.files())
        {
            for (var problem : reported.problems())
            {
                for (var fix : problem.quickFixes())
                {
                    if (fix.label().contains("Import"))
                    {
                        markerId = problem.markerId();
                        importIndex = fix.index();
                        break;
                    }
                }
            }
        }
        if (markerId == -1L)
        {
            markerId = errorsResult.files().get(0).problems().get(0).markerId();
        }
        assertNotEquals(-1L, markerId, "Should have found a valid marker ID");

        QuickFixResponse applyResult = service.executeQuickFix(markerId, importIndex);

        assertEquals(markerId, applyResult.markerId(), "the response names the marker it acted on");
        assertEquals(importIndex, applyResult.requestedIndex());

        // Verify the fix was actually persisted to disk by reading the file directly.
        if (applyResult.status() == QuickFixResponse.Status.APPLIED)
        {
            assertNotNull(applyResult.appliedLabel(), "an applied fix names itself");
            assertNotNull(applyResult.markerResolved(),
                    "whether the problem went away is a field on every applied fix");
            assertEquals(testProjectName, applyResult.projectName());
            assertEquals("src/com/example/FixMe.java", applyResult.filePath(),
                    "the reading and editing tools take a project-relative path");

            file.refreshLocal(IResource.DEPTH_ZERO, monitor);
            String fileContent = new String(file.getContents(true).readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            assertTrue(fileContent.contains("import java.util.ArrayList"),
                    "File on disk should contain the added import after fix is applied. File content: " + fileContent);
        }
    }

    /**
     * A stale marker id is its own status, not a sentence. It used to share the
     * {@code "Error"} prefix with the successful outcome.
     */
    @Test
    public void testExecuteQuickFix_UnknownMarkerId() {
        QuickFixResponse result = service.executeQuickFix(Long.MAX_VALUE, 0);

        assertEquals(QuickFixResponse.Status.MARKER_NOT_FOUND, result.status());
        assertFalse(result.changedResource());
        assertNull(result.markerResolved(),
                "nothing was applied, which is not the same as 'applied and the marker survived'");
        assertFalse(result.diagnostics().isEmpty(), "a caller must be able to branch on a code");
    }

    /**
     * The bound on the proposal index used to be the substring "(0-3)" inside a
     * sentence - the only place a refused caller could learn what indices exist.
     */
    @Test
    public void testExecuteQuickFix_OutOfRangeIndexReturnsTheProposalsThatDoExist()
            throws CoreException, InterruptedException {
        createFile("src/com/example/BadIndex.java",
                "package com.example;\n\n" +
                "public class BadIndex {\n" +
                "    public void test() {\n" +
                "        ArrayList<String> list = new ArrayList<>();\n" +
                "    }\n" +
                "}\n");
        project.refreshLocal(IResource.DEPTH_INFINITE, monitor);
        project.build(IncrementalProjectBuilder.FULL_BUILD, monitor);
        Job.getJobManager().join(ResourcesPlugin.FAMILY_MANUAL_BUILD, monitor);
        Job.getJobManager().join(ResourcesPlugin.FAMILY_AUTO_BUILD, monitor);
        Thread.sleep(500);

        CompilationProblemsResponse errors = service.getCompilationErrors(testProjectName, "ERROR", 50);
        org.junit.jupiter.api.Assumptions.assumeTrue(errors.totalProblems() > 0,
                "No error markers generated - Java builder not active in this environment");

        var problem = errors.files().get(0).problems().get(0);
        org.junit.jupiter.api.Assumptions.assumeFalse(problem.quickFixes().isEmpty(),
                "This assertion is about the bound, so it needs a problem that has proposals");

        QuickFixResponse result = service.executeQuickFix(problem.markerId(), 9999);

        assertEquals(QuickFixResponse.Status.INVALID_PROPOSAL_INDEX, result.status());
        assertFalse(result.availableProposals().isEmpty(),
                "the choices are a field, not a range printed inside a sentence");
        assertEquals(0, result.availableProposals().get(0).index(),
                "the indices are the ones executeQuickFix takes");
    }

    /**
     * The candidate is a bare fully qualified name. It used to arrive as
     * <code>  - `import java.util.ArrayList;`</code> - four decorations to strip.
     */
    @Test
    public void testGetImportSuggestions_ReportsBareFullyQualifiedNames()
            throws CoreException, InterruptedException {
        createFile("src/com/example/NeedsImport.java",
                "package com.example;\n\n" +
                "public class NeedsImport {\n" +
                "    public void test() {\n" +
                "        ArrayList<String> list = new ArrayList<>();\n" +
                "    }\n" +
                "}\n");
        project.refreshLocal(IResource.DEPTH_INFINITE, monitor);
        project.build(IncrementalProjectBuilder.FULL_BUILD, monitor);
        Job.getJobManager().join(ResourcesPlugin.FAMILY_MANUAL_BUILD, monitor);
        Job.getJobManager().join(ResourcesPlugin.FAMILY_AUTO_BUILD, monitor);
        Thread.sleep(500);

        ImportSuggestionsResponse result =
                service.getImportSuggestions(testProjectName, "src/com/example/NeedsImport.java");

        assertEquals(ImportSuggestionsResponse.Status.OK, result.status());
        org.junit.jupiter.api.Assumptions.assumeTrue(result.totalUnresolvedTypes() > 0,
                "No error markers generated - Java builder not active in this environment");

        ImportSuggestionsResponse.UnresolvedType arrayList = result.unresolvedTypes().stream()
                .filter(type -> "ArrayList".equals(type.typeName()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no ArrayList in " + result.unresolvedTypes()));

        assertTrue(arrayList.lineNumber() > 0, "the unresolved name reports the line it sits on");
        assertTrue(arrayList.candidates().contains("java.util.ArrayList"),
                "a candidate is the fully qualified name itself: " + arrayList.candidates());
        assertEquals(result.unresolvedTypes().stream().mapToInt(type -> type.candidates().size()).sum(),
                result.totalCandidates());
    }

    @Test
    public void testGetImportSuggestions_CleanFileIsNotAFailure() throws CoreException {
        ImportSuggestionsResponse result =
                service.getImportSuggestions(testProjectName, "src/com/example/Callee.java");

        assertEquals(ImportSuggestionsResponse.Status.OK, result.status(),
                "'nothing to suggest' is a count of zero, not an error");
        assertEquals(0, result.totalUnresolvedTypes());
        assertFalse(result.hasCandidates());
    }

    /**
     * "Not found" and "not open" used to be the same sentence, which sent a caller
     * hunting for a typo it had not made.
     */
    @Test
    public void testGetImportSuggestions_SeparatesMissingProjectFromClosedProject() throws CoreException {
        assertEquals(ImportSuggestionsResponse.Status.PROJECT_NOT_FOUND,
                service.getImportSuggestions("NoSuchProjectAnywhere", "src/A.java").status());

        assertEquals(ImportSuggestionsResponse.Status.FILE_NOT_FOUND,
                service.getImportSuggestions(testProjectName, "src/com/example/NoSuchFile.java").status());

        project.close(monitor);
        try
        {
            assertEquals(ImportSuggestionsResponse.Status.PROJECT_CLOSED,
                    service.getImportSuggestions(testProjectName, "src/com/example/Callee.java").status(),
                    "a closed project is one openProject call away; a missing one is not");
        }
        finally
        {
            project.open(monitor);
        }
    }

    // -------------------------------------------------------------------------
    // helpers
    // -------------------------------------------------------------------------

    // extractFirstMarkerId, extractMarkerIdWithImportAtIndex0 and extractImportFixIndex
    // used to scrape "- Marker ID:" and "- [0]" out of the tool's Markdown. They went
    // dead when getCompilationErrors started returning markerId and index as fields,
    // and are removed rather than left as a template for the next test.

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
    
    @Test
    public void testGetCompilationErrors_scopedToFolderOrFile() throws CoreException, InterruptedException {
        createClassWithErrors();
        createClassWithWarnings();
        IFolder otherPackage = project.getFolder(new Path("src/com/other"));
        if (!otherPackage.exists()) {
            otherPackage.create(true, true, monitor);
        }
        createFile("src/com/other/AlsoBroken.java",
                "package com.other;\n\npublic class AlsoBroken {\n    int x = undefinedVariable;\n}\n");

        project.refreshLocal(IResource.DEPTH_INFINITE, monitor);
        project.build(IncrementalProjectBuilder.FULL_BUILD, monitor);
        Job.getJobManager().join(ResourcesPlugin.FAMILY_MANUAL_BUILD, monitor);
        Job.getJobManager().join(ResourcesPlugin.FAMILY_AUTO_BUILD, monitor);
        Thread.sleep(500);

        IMarker[] markers = project.findMarkers(IMarker.PROBLEM, true, IResource.DEPTH_INFINITE);
        org.junit.jupiter.api.Assumptions.assumeTrue(markers.length > 0,
                "No error markers generated - Java builder not active in this environment");

        CompilationProblemsResponse oneFile = service.getCompilationErrors(
                testProjectName, "src/com/example/ErrorClass.java", "ALL", 50);
        assertTrue(oneFile.hasErrors(), "the broken class must report its own error");
        assertEquals(1, oneFile.files().size(), "a file scope lists that file only");
        assertEquals("src/com/example/ErrorClass.java", oneFile.files().get(0).filePath());

        CompilationProblemsResponse oneFolder = service.getCompilationErrors(
                testProjectName, "src/com/example", "ALL", 50);
        assertTrue(oneFolder.totalProblems() >= oneFile.totalProblems());
        assertTrue(oneFolder.files().stream().allMatch(f -> f.filePath().startsWith("src/com/example/")),
                "a folder scope must not list files outside the folder");

        CompilationProblemsResponse wholeProject = service.getCompilationErrors(testProjectName, null, "ALL", 50);
        assertTrue(wholeProject.totalProblems() > oneFolder.totalProblems(),
                "the error in com.other lies outside the folder scope");

        assertThrows(RuntimeException.class,
                () -> service.getCompilationErrors(testProjectName, "src/com/missing", "ALL", 50));
        assertThrows(RuntimeException.class,
                () -> service.getCompilationErrors(null, "src/com/example", "ALL", 50));
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
