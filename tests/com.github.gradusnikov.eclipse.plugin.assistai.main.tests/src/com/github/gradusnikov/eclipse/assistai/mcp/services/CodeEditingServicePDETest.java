package com.github.gradusnikov.eclipse.assistai.mcp.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IFolder;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IProjectDescription;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IWorkspace;
import org.eclipse.core.resources.IWorkspaceRoot;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.ILog;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.core.runtime.Path;
import org.eclipse.jdt.core.IClasspathEntry;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.launching.JavaRuntime;
import org.eclipse.e4.core.contexts.ContextInjectionFactory;
import org.eclipse.e4.core.contexts.EclipseContextFactory;
import org.eclipse.e4.core.contexts.IEclipseContext;
import org.eclipse.e4.ui.di.UISynchronize;
import org.eclipse.ui.PartInitException;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.ide.IDE;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.osgi.framework.BundleContext;
import org.osgi.framework.FrameworkUtil;
import org.osgi.util.tracker.ServiceTracker;

import com.github.gradusnikov.eclipse.assistai.Activator;
import com.github.gradusnikov.eclipse.assistai.mcp.services.CodeEditingService;
import com.github.gradusnikov.eclipse.assistai.mcp.results.DiagnosticCode;
import com.github.gradusnikov.eclipse.assistai.resources.EditResult;
import com.github.gradusnikov.eclipse.assistai.resources.Occurrence;
import com.github.gradusnikov.eclipse.assistai.resources.ResourceCache;
import com.github.gradusnikov.eclipse.assistai.resources.ResourceDescriptor;
import com.github.gradusnikov.eclipse.assistai.tools.ResourceUtilities;

public class CodeEditingServicePDETest {

    
    private static final String TEST_PROJECT_NAME = "CodeEditingTestProject";
    private IProject project;
    private CodeEditingService service;
    private ResourceCache resourceCache;
    private NullProgressMonitor monitor = new NullProgressMonitor();
    
    @BeforeEach
    public void beforeEach() throws CoreException, IOException, InterruptedException {
        // Get workspace through OSGi service tracker
        BundleContext bundleContext = FrameworkUtil.getBundle(CodeEditingServicePDETest.class).getBundleContext();
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
        desc.setNatureIds(new String[] { JavaCore.NATURE_ID });
        project.create(desc, monitor);
        project.open(monitor);
        
        // Create source folder
        IFolder srcFolder = project.getFolder("src");
        if (!srcFolder.exists()) {
            srcFolder.create(IResource.NONE, true, monitor);
        }

        IJavaProject javaProject = JavaCore.create(project);
        IClasspathEntry[] classpath = {
            JavaCore.newSourceEntry(srcFolder.getFullPath()),
            JavaCore.newContainerEntry(new Path(JavaRuntime.JRE_CONTAINER))
        };
        javaProject.setRawClasspath(classpath, project.getFullPath().append("bin"), monitor);
        
        // Initialize service with DI context
        IEclipseContext context = EclipseContextFactory.create();
        context.set(ILog.class, Activator.getDefault().getLog());
        
        // Mock UISynchronize to avoid UI thread issues in tests
        context.set(UISynchronize.class, new UISynchronize() {
            @Override
            public void syncExec(Runnable runnable) {
                runnable.run();
            }
            
            @Override
            public void asyncExec(Runnable runnable) {
                runnable.run();
            }

			@Override
			protected boolean isUIThread(Thread thread) {
				return false;
			}

			@Override
			protected void showBusyWhile(Runnable runnable) {
				
			}

			@Override
			protected boolean dispatchEvents() {
				return false;
			}
        });
        
        resourceCache = ContextInjectionFactory.make(ResourceCache.class, context);
        context.set(ResourceCache.class, resourceCache);
        service = ContextInjectionFactory.make(CodeEditingService.class, context);
    }
    
    @AfterEach
    public void afterEach() throws CoreException {
        if (resourceCache != null) {
            resourceCache.dispose();
        }
        // Clean up the test project
        if (project != null && project.exists()) {
            project.delete(true, true, monitor);
        }
    }
    
    /** replaceLines with no staleness check and no preview, which is what these tests mean. */
    private EditResult replaceLines(String filePath, String replacement, int startLine, int endLine) {
        return service.replaceLines(TEST_PROJECT_NAME, filePath, replacement, startLine, endLine,
                IResource.NULL_STAMP, false);
    }

    /** insertIntoFile with no staleness check and no preview. */
    private EditResult insertIntoFile(String filePath, String content, int atLine) {
        return service.insertIntoFile(TEST_PROJECT_NAME, filePath, content, atLine,
                IResource.NULL_STAMP, false);
    }

    /** deleteLinesInFile with no staleness check and no preview. */
    private EditResult deleteLines(String filePath, int startLine, int endLine) {
        return service.deleteLinesInFile(TEST_PROJECT_NAME, filePath, startLine, endLine,
                IResource.NULL_STAMP, false);
    }

    @Test
    public void testInsertIntoFile_beforeLineAndAppend() throws CoreException, IOException {
        IFile testFile = createFile("src/testFile.txt", "Line 1\nLine 2\n");

        EditResult inserted = insertIntoFile("src/testFile.txt", "Inserted", 2);
        assertEquals(EditResult.EditStatus.APPLIED, inserted.status());
        assertEquals("Line 1\nInserted\nLine 2\n", ResourceUtilities.readFileContent(testFile));
        // The caller wrote the text, so the result counts the change instead of echoing it.
        assertEquals("\\ diff omitted (+1 -0 lines): the change is the one requested\n", inserted.unifiedDiff());

        // One past the last line appends rather than failing.
        EditResult appended = insertIntoFile("src/testFile.txt", "Appended", 4);
        assertEquals(EditResult.EditStatus.APPLIED, appended.status());
        assertEquals("Line 1\nInserted\nLine 2\nAppended\n", ResourceUtilities.readFileContent(testFile));
    }

    @Test
    public void testInsertIntoFile_lineBeyondEndIsRejected() throws CoreException, IOException {
        IFile testFile = createFile("src/testFile.txt", "Line 1\nLine 2\n");

        EditResult result = insertIntoFile("src/testFile.txt", "Inserted", 9);

        assertEquals(EditResult.EditStatus.REJECTED, result.status());
        assertEquals(DiagnosticCode.INVALID_RANGE, result.diagnostics().get(0).code());
        assertEquals("Line 1\nLine 2\n", ResourceUtilities.readFileContent(testFile));
    }

    @Test
    public void testDeleteLinesInFile_removesRangeAndRefusesRangesBeyondTheFile()
            throws CoreException, IOException {
        IFile testFile = createFile("src/testFile.txt", "Line 1\nLine 2\nLine 3\nLine 4\n");

        EditResult deleted = deleteLines("src/testFile.txt", 2, 3);
        assertEquals(EditResult.EditStatus.APPLIED, deleted.status());
        assertEquals("Line 1\nLine 4\n", ResourceUtilities.readFileContent(testFile));

        EditResult rejected = deleteLines("src/testFile.txt", 1, 7);
        assertEquals(EditResult.EditStatus.REJECTED, rejected.status());
        assertEquals(DiagnosticCode.INVALID_RANGE, rejected.diagnostics().get(0).code());
        assertEquals("Line 1\nLine 4\n", ResourceUtilities.readFileContent(testFile));
    }

    @Test
    public void testReplaceLines_previewDoesNotWrite() throws CoreException, IOException {
        String initialContent = "Line 1\nLine 2\nLine 3\n";
        IFile testFile = createFile("src/testFile.txt", initialContent);

        EditResult result = service.replaceLines(TEST_PROJECT_NAME, "src/testFile.txt", "Changed", 2, 2,
                IResource.NULL_STAMP, true);

        assertEquals(EditResult.EditStatus.PREVIEW, result.status());
        assertTrue(result.unifiedDiff().contains("Changed"));
        assertEquals(initialContent, ResourceUtilities.readFileContent(testFile));
    }

    @Test
    public void testReplaceLines_staleModificationStampIsRejected() throws CoreException, IOException {
        String initialContent = "Line 1\nLine 2\nLine 3\n";
        IFile testFile = createFile("src/testFile.txt", initialContent);

        EditResult result = service.replaceLines(TEST_PROJECT_NAME, "src/testFile.txt", "Changed", 2, 2,
                testFile.getModificationStamp() + 1, false);

        assertEquals(EditResult.EditStatus.REJECTED, result.status());
        assertEquals(DiagnosticCode.VERSION_CONFLICT, result.diagnostics().get(0).code());
        assertEquals(initialContent, ResourceUtilities.readFileContent(testFile));
    }

    @Test
    public void testReplaceLines() throws CoreException, IOException {
        // Create a test file with multiple lines
        String initialContent = 
                "Line 1\n" +
                "Line 2\n" +
                "Line 3\n" +
                "Line 4\n" +
                "Line 5\n";
        
        IFile testFile = createFile("src/testFile.txt", initialContent);
        
        // Replace lines 2-4 (1-based index) with new content
        String replacementContent = "New Line A\nNew Line B";
        EditResult result = replaceLines("src/testFile.txt", replacementContent, 2, 4);
        
        // Read the updated file content
        String updatedContent = ResourceUtilities.readFileContent(testFile);
        
        // Expected content after replacement
        String expectedContent = 
                "Line 1\n" +
                "New Line A\n" +
                "New Line B\n" +
                "Line 5\n";
        
        // Verify the content was correctly updated
        assertEquals(expectedContent, updatedContent);
    }
    
    @Test
    public void testReplaceLines_FirstLine() throws CoreException, IOException {
        // Create a test file
        String initialContent = 
                "Line 1\n" +
                "Line 2\n" +
                "Line 3\n";
        
        IFile testFile = createFile("src/testFile.txt", initialContent);
        
        // Replace the first line
        String replacementContent = "New First Line";
        replaceLines("src/testFile.txt", replacementContent, 1, 1);
        
        // Read the updated file content
        String updatedContent = ResourceUtilities.readFileContent(testFile);
        
        // Expected content after replacement
        String expectedContent = 
                "New First Line\n" +
                "Line 2\n" +
                "Line 3\n";
        
        // Verify the content was correctly updated
        assertEquals(expectedContent, updatedContent);
    }
    
    @Test
    public void testReplaceLines_LastLine() throws CoreException, IOException {
        // Create a test file
        String initialContent = 
                "Line 1\n" +
                "Line 2\n" +
                "Line 3\n";
        
        IFile testFile = createFile("src/testFile.txt", initialContent);
        
        // Replace the last line
        String replacementContent = "New Last Line";
        replaceLines("src/testFile.txt", replacementContent, 3, 3);
        
        // Read the updated file content
        String updatedContent = ResourceUtilities.readFileContent(testFile);
        
        // Expected content after replacement
        String expectedContent = 
                "Line 1\n" +
                "Line 2\n" +
                "New Last Line\n";
        
        // Verify the content was correctly updated
        assertEquals(expectedContent, updatedContent);
    }
    
    @Test
    public void testReplaceLines_AllLines() throws CoreException, IOException {
        // Create a test file
        String initialContent = 
                "Line 1\n" +
                "Line 2\n" +
                "Line 3\n";
        
        IFile testFile = createFile("src/testFile.txt", initialContent);
        
        // Replace all lines
        String replacementContent = "Completely New Content";
        replaceLines("src/testFile.txt", replacementContent, 1, 3);
        
        // Read the updated file content
        String updatedContent = ResourceUtilities.readFileContent(testFile);
        
        // Expected content after replacement
        String expectedContent = "Completely New Content\n";
        
        // Verify the content was correctly updated
        assertEquals(expectedContent, updatedContent);
    }
    
    @Test
    public void testReplaceLines_EmptyReplacement() throws CoreException, IOException {
        // Create a test file
        String initialContent = 
                "Line 1\n" +
                "Line 2\n" +
                "Line 3\n" +
                "Line 4\n";
        
        IFile testFile = createFile("src/testFile.txt", initialContent);
        
        // Replace lines with empty content (effectively deleting lines 2-3)
        replaceLines("src/testFile.txt", "", 2, 3);
        
        // Read the updated file content
        String updatedContent = ResourceUtilities.readFileContent(testFile);
        
        // Expected content after replacement
        String expectedContent = 
                "Line 1\n" +
                "Line 4\n";
        
        // Verify the content was correctly updated
        assertEquals(expectedContent, updatedContent);
    }
    
    @Test
    public void testReplaceLines_InvalidLineNumbers() throws CoreException, IOException {
        // Create a test file
        String initialContent = 
                "Line 1\n" +
                "Line 2\n" +
                "Line 3\n";
        
        IFile testFile = createFile("src/testFile.txt", initialContent);
        
        // A range the file cannot satisfy is a rejection the caller branches on, not
        // an exception, and nothing is written.
        EditResult beyondEnd = replaceLines("src/testFile.txt", "New Content", 11, 13);
        assertEquals(EditResult.EditStatus.REJECTED, beyondEnd.status());
        assertEquals(DiagnosticCode.INVALID_RANGE, beyondEnd.diagnostics().get(0).code());
        
        EditResult zeroStart = replaceLines("src/testFile.txt", "New Content", 0, 2);
        assertEquals(EditResult.EditStatus.REJECTED, zeroStart.status());
        assertEquals(DiagnosticCode.INVALID_RANGE, zeroStart.diagnostics().get(0).code());
        
        EditResult inverted = replaceLines("src/testFile.txt", "New Content", 3, 2);
        assertEquals(EditResult.EditStatus.REJECTED, inverted.status());
        assertEquals(DiagnosticCode.INVALID_RANGE, inverted.diagnostics().get(0).code());
        
        // An end line past the end of the file used to be clamped to the last line,
        // which rewrote lines the caller had excluded without saying so.
        EditResult endBeyondEnd = replaceLines("src/testFile.txt", "New Content", 2, 9);
        assertEquals(EditResult.EditStatus.REJECTED, endBeyondEnd.status());
        assertEquals(DiagnosticCode.INVALID_RANGE, endBeyondEnd.diagnostics().get(0).code());
        
        assertEquals(initialContent, ResourceUtilities.readFileContent(testFile));
    }
    
    
	@Test
	public void testReplaceStringInFile() throws CoreException, IOException {
	    // Create a test file with content containing a specific string
	    String initialContent = 
	            "This is a test file.\n" +
	            "It contains some text to be replaced.\n" +
	            "This line should remain unchanged.\n";
	    
	    IFile testFile = createFile("src/testFile.txt", initialContent);
	    resourceCache.put(ResourceDescriptor.fromWorkspaceFile(testFile, "text"), "stale content");
	    
	    // Replace a specific string
	    String oldString = "some text to be replaced";
	    String newString = "new replacement text";
	    EditResult result = replaceAll("src/testFile.txt", oldString, newString, null, null);
	    
	    // Verify the operation was successful, from the reported state rather than
	    // from how it happens to be worded.
	    assertEquals(EditResult.EditStatus.APPLIED, result.status());
	    assertTrue(result.workspaceState().savedToDisk());
	    assertTrue(result.workspaceState().cacheUpdated());
	    
	    // Read the updated file content
	    String updatedContent = ResourceUtilities.readFileContent(testFile);
	    
	    // Expected content after replacement
	    String expectedContent = 
	            "This is a test file.\n" +
	            "It contains new replacement text.\n" +
	            "This line should remain unchanged.\n";
	    
	    // Verify the content was correctly updated
	    assertEquals(expectedContent, updatedContent);
	    assertEquals(expectedContent, resourceCache.get(testFile).orElseThrow().content());
	}
	
	@Test
	public void testReplaceStringInFile_WithLineRange() throws CoreException, IOException {
	    // Create a test file with multiple occurrences of the same string
	    String initialContent = 
	            "Line 1: Replace this text.\n" +
	            "Line 2: Replace this text.\n" +
	            "Line 3: Replace this text.\n" +
	            "Line 4: Replace this text.\n";
	    
	    IFile testFile = createFile("src/testFile.txt", initialContent);
	    
	    // Replace the string only within a specific line range (lines 2-3)
	    String oldString = "Replace this text";
	    String newString = "Text was replaced";
	    EditResult result = replaceAll("src/testFile.txt", oldString, newString, 2, 3);
	    
	    // Verify the operation was successful
	    assertEquals(EditResult.EditStatus.APPLIED, result.status());
	    assertEquals(2, result.edits().size(), "only the two occurrences in range should change");
	    
	    // Read the updated file content
	    String updatedContent = ResourceUtilities.readFileContent(testFile);
	    
	    // Expected content after replacement (only lines 2-3 should be affected)
	    String expectedContent = 
	            "Line 1: Replace this text.\n" +
	            "Line 2: Text was replaced.\n" +
	            "Line 3: Text was replaced.\n" +
	            "Line 4: Replace this text.\n";
	    
	    // Verify the content was correctly updated
	    assertEquals(expectedContent, updatedContent);
	}
	
	@Test
	public void testReplaceStringInFile_EmptyReplacement() throws CoreException, IOException {
	    // Create a test file
	    String initialContent = 
	            "This file contains some text that will be removed.\n" +
	            "Other content will remain.\n";
	    
	    IFile testFile = createFile("src/testFile.txt", initialContent);
	    
	    // Replace a string with empty content (effectively removing it)
	    String oldString = "some text that will be removed";
	    String newString = "";
	    replaceAll("src/testFile.txt", oldString, newString, null, null);
	    
	    // Read the updated file content
	    String updatedContent = ResourceUtilities.readFileContent(testFile);
	    
	    // Expected content after replacement
	    String expectedContent = 
	            "This file contains .\n" +
	            "Other content will remain.\n";
	    
	    // Verify the content was correctly updated
	    assertEquals(expectedContent, updatedContent);
	}
	
	/**
	 * Replaces every occurrence, which is what these tests were written against.
	 * The tool's own default is UNIQUE - it refuses to guess between matches - so
	 * replace-all has to be asked for explicitly.
	 */
	private EditResult replaceAll(String filePath, String oldString, String newString,
			Integer startLine, Integer endLine) {
		return service.replaceString(TEST_PROJECT_NAME, filePath, oldString, newString,
				startLine, endLine, IResource.NULL_STAMP, Occurrence.ALL, null, false);
	}

	@Test
	public void testReplaceStringInFile_StringNotFound() throws CoreException, IOException {
	    // Create a test file
	    String initialContent = "This is a test file.\n";
	    
	    createFile("src/testFile.txt", initialContent);
	    
	    // Try to replace a string that doesn't exist in the file
	    String oldString = "non-existent string";
	    String newString = "replacement";
	    
	    // Text that is not there is a rejection the caller acts on, not an exception:
	    // the edit simply did not apply, and the reason is a code rather than prose.
	    EditResult result = replaceAll("src/testFile.txt", oldString, newString, null, null);

	    assertEquals(EditResult.EditStatus.REJECTED, result.status());
	    assertEquals(DiagnosticCode.TEXT_NOT_FOUND, result.diagnostics().get(0).code());
	    assertTrue(result.edits().isEmpty());
	}
	
	@Test
	public void testReplaceStringInFile_InvalidLineRange() throws CoreException, IOException {
	    // Create a test file
	    String initialContent = 
	            "Line 1\n" +
	            "Line 2\n" +
	            "Line 3\n";
	    
	    createFile("src/testFile.txt", initialContent);
	    
	    // Test with start line beyond file length
	    String oldString = "Line";
	    String newString = "NewLine";
	    
	    Exception exception = assertThrows(RuntimeException.class, () -> {
	        replaceAll("src/testFile.txt", oldString, newString, 11, 13);
	    });
	    
	    assertTrue(exception.getMessage().contains("Start line"));
	    assertTrue(exception.getMessage().contains("beyond the end of the file"));
	}
	
	@Test
	public void testReplaceStringInFile_MultipleOccurrences() throws CoreException, IOException {
	    // Create a test file with multiple occurrences of the same string
	    String initialContent = 
	            "This text will be replaced. Some other content.\n" +
	            "More content. This text will be replaced again.\n";
	    
	    IFile testFile = createFile("src/testFile.txt", initialContent);
	    
	    // Replace all occurrences of a string
	    String oldString = "This text will be replaced";
	    String newString = "Replacement successful";
	    replaceAll("src/testFile.txt", oldString, newString, null, null);
	    
	    // Read the updated file content
	    String updatedContent = ResourceUtilities.readFileContent(testFile);
	    
	    // Expected content after replacement
	    String expectedContent = 
	            "Replacement successful. Some other content.\n" +
	            "More content. Replacement successful again.\n";
	    
	    // Verify the content was correctly updated
	    assertEquals(expectedContent, updatedContent);
	}

    @Test
	public void testReplaceLinesIssue() throws CoreException, IOException
	{
		String replacement = """
    public static void main(String[] args) {
        System.out.println("Hello World!");
    }}
				""";
		
		String initialContent = """
package com.example.snake;

public class ApplicationNew {

    public String getHelloWorld() {
        return "Hello World!";
    }
}			
				""";

		 int startLine=5;
		 int endLine=8;
		 
		 IFile testFile = createFile("src/testFile.txt", initialContent);

		 replaceLines("src/testFile.txt", replacement, startLine, endLine);
		 
		 String updatedContent = ResourceUtilities.readFileContent(testFile);
	}
    @Test
    public void testRefactorExtractTypeToNewFile() throws Exception
    {
        IFile outerFile = createFile("src/Outer.java", """
                public class Outer {
                    static class Inner {
                        String value() {
                            return "value";
                        }
                    }

                    Inner inner = new Inner();
                }
                """);

        EditResult result = service.refactorExtractTypeToNewFile(TEST_PROJECT_NAME, "src/Outer.java", "Outer.Inner");

        assertEquals(EditResult.EditStatus.APPLIED, result.status());
        // The result names the extracted file - what a caller reads or edits next.
        assertEquals(TEST_PROJECT_NAME, result.projectName());
        assertEquals("src/Inner.java", result.filePath());
        // ...and lists everything the refactoring touched, so the source file no longer
        // has to be guessed at from the tool's description.
        assertTrue(result.affectedResources().stream().anyMatch(
                        a -> "src/Inner.java".equals(a.filePath()) && a.kind() == EditResult.ChangeKind.CREATED),
                () -> result.affectedResources().toString());
        assertTrue(result.affectedResources().stream().anyMatch(
                        a -> "src/Outer.java".equals(a.filePath()) && a.kind() == EditResult.ChangeKind.MODIFIED),
                () -> result.affectedResources().toString());
        assertTrue(project.getFile("src/Inner.java").exists());
        assertTrue(ResourceUtilities.readFileContent(outerFile).contains("Inner inner = new Inner();"));
        assertTrue(ResourceUtilities.readFileContent(project.getFile("src/Inner.java")).contains("class Inner"));
    }

    @Test
    public void testApplyPatchAppliesMultipleHunksAndSupportsUndo() throws Exception
    {
        String original = "alpha\nbeta\ngamma\ndelta\nepsilon\nzeta\n";
        IFile file = createFile( "src/patch.txt", original );
        String patch = """
                --- a/src/patch.txt
                +++ b/src/patch.txt
                @@ -1,3 +1,3 @@
                 alpha
                -beta
                +BETA
                 gamma
                @@ -5,2 +5,2 @@
                 epsilon
                -zeta
                +ZETA
                """;

        EditResult result = applyPatch( "src/patch.txt", patch );

        assertEquals( EditResult.EditStatus.APPLIED, result.status() );
        assertEquals( "alpha\nBETA\ngamma\ndelta\nepsilon\nZETA\n", ResourceUtilities.readFileContent( file ) );

        // The whole patch is one write, so one undo puts the file back as it was.
        service.undoEdit( TEST_PROJECT_NAME, "src/patch.txt" );
        assertEquals( original, ResourceUtilities.readFileContent( file ) );
    }

    @Test
    public void testApplyPatchPreservesCrLfLineDelimiter() throws Exception
    {
        IFile file = createFile( "src/crlf.txt", "one\r\ntwo\r\nthree\r\n" );
        String patch = """
                @@ -1,3 +1,3 @@
                 one
                -two
                +TWO
                 three
                """;

        applyPatch( "src/crlf.txt", patch );

        assertEquals( "one\r\nTWO\r\nthree\r\n", ResourceUtilities.readFileContent( file ) );
    }

    @Test
    public void testApplyPatchDoesNotWriteWhenAnyHunkFails() throws Exception
    {
        String original = "one\ntwo\nthree\n";
        IFile file = createFile( "src/atomic.txt", original );
        String patch = """
                @@ -1,1 +1,1 @@
                -one
                +ONE
                @@ -3,1 +3,1 @@
                -missing
                +THREE
                """;

        // A hunk whose context is not in the file rejects the whole patch. It is
        // retryable: re-reading the file and recomputing the patch is what fixes it.
        EditResult result = applyPatch( "src/atomic.txt", patch );

        assertEquals( EditResult.EditStatus.REJECTED, result.status() );
        assertEquals( DiagnosticCode.TEXT_NOT_FOUND, result.diagnostics().get( 0 ).code() );
        assertTrue( result.diagnostics().get( 0 ).retryable() );
        assertEquals( original, ResourceUtilities.readFileContent( file ) );
    }

    @Test
    public void testApplyPatchRejectsAMalformedPatch() throws Exception
    {
        String original = "one\ntwo\n";
        IFile file = createFile( "src/malformed.txt", original );

        EditResult result = applyPatch( "src/malformed.txt", "this is not a unified diff\n" );

        assertEquals( EditResult.EditStatus.REJECTED, result.status() );
        assertEquals( DiagnosticCode.INVALID_RANGE, result.diagnostics().get( 0 ).code() );
        assertEquals( original, ResourceUtilities.readFileContent( file ) );
    }

    /** applyPatch with no wizard, no staleness check and no preview. */
    private EditResult applyPatch( String filePath, String patch )
    {
        return service.applyPatch( TEST_PROJECT_NAME, filePath, patch, false, IResource.NULL_STAMP, false );
    }

    @Test
    public void testReplaceFileContentSynchronizesJdtModel() throws Exception
    {
        IFile file = createFile( "src/SynchronizedType.java",
                "public class SynchronizedType { int value = 1; }\n" );

        EditResult result = service.replaceFileContent( TEST_PROJECT_NAME, "src/SynchronizedType.java",
                "public class SynchronizedType { int value = 2; }\n", IResource.NULL_STAMP, false );

        assertEquals( EditResult.EditStatus.APPLIED, result.status() );
        assertTrue( result.workspaceState().savedToDisk() );
        assertEquals( "true", result.workspaceState().jdtConsistent() );

        var compilationUnit = JavaCore.createCompilationUnitFrom( file );
        assertTrue( compilationUnit.isConsistent() );
        assertTrue( compilationUnit.getSource().contains( "value = 2" ) );
    }

	
    @Test
    public void testFormatFileUsesRegisteredEditorForNonJavaResource() throws Exception
    {
        IFile file = createFile( "src/settings.json", "{\"enabled\":true}" );
        CodeEditingService editorBackedService = new CodeEditingService()
        {
            @Override
            protected String formatUsingRegisteredEditor( IFile target ) throws Exception
            {
                String formatted = "{\n  \"enabled\": true\n}\n";
                try (ByteArrayInputStream source = new ByteArrayInputStream( formatted.getBytes() ))
                {
                    target.setContents( source, IResource.FORCE, null );
                }
                return "test.json.format";
            }
        };
        editorBackedService.logger = service.logger;
        editorBackedService.sync = service.sync;
        editorBackedService.resourceCache = resourceCache;
        // formatFile resolves the file through the same access rules as every other
        // editing entry point, so this hand-built instance needs the service too.
        editorBackedService.aiIgnoreService = service.aiIgnoreService;

        EditResult result = editorBackedService.formatFile( TEST_PROJECT_NAME, "src/settings.json" );

        assertEquals( "{\n  \"enabled\": true\n}\n", ResourceUtilities.readFileContent( file ) );
        assertEquals( EditResult.EditStatus.APPLIED, result.status() );
        assertTrue( result.workspaceState().savedToDisk() );
        // The editor's formatter wrote the file itself, so the edit is only described
        // afterwards - but it is described in the same fields a JDT format would use.
        assertEquals( 1, result.edits().size() );
        assertTrue( result.unifiedDiff().contains( "\"enabled\"" ) );
    }

    @Test
    public void testOrganizeImportsInPackageNamesThePackageAndTheFilesItChanged() throws Exception
    {
        project.getFolder( "src/pkg" ).create( IResource.NONE, true, monitor );
        createFile( "src/pkg/Clean.java", "package pkg;\n\npublic class Clean {}\n" );
        createFile( "src/pkg/Unused.java", """
                package pkg;

                import java.util.List;

                public class Unused {}
                """ );

        EditResult result = service.organizeImportsInPackage( TEST_PROJECT_NAME, "pkg" );

        assertEquals( EditResult.EditStatus.APPLIED, result.status() );
        // The package folder is what the result is addressed to - the same thing
        // refactorRenamePackage reports - and the files it actually changed are the
        // list. A file it left alone is not in it.
        assertEquals( TEST_PROJECT_NAME, result.projectName() );
        assertEquals( "src/pkg", result.filePath() );
        assertEquals( 1, result.affectedResources().size(), () -> result.affectedResources().toString() );
        assertEquals( "src/pkg/Unused.java", result.affectedResources().get( 0 ).filePath() );
        assertEquals( EditResult.ChangeKind.MODIFIED, result.affectedResources().get( 0 ).kind() );
        assertTrue( result.affectedResources().get( 0 ).version().isKnown(),
                "the caller needs the new stamp to edit that file next" );
        assertTrue( result.diagnostics().isEmpty(), () -> result.diagnostics().toString() );
        assertTrue( !ResourceUtilities.readFileContent( project.getFile( "src/pkg/Unused.java" ) )
                .contains( "import java.util.List" ) );
    }

    @Test
    public void testOrganizeImports_writesTheFileWhileItIsOpenInAnEditor() throws Exception
    {
        project.getFolder( "src/pkg" ).create( IResource.NONE, true, monitor );
        IFile file = createFile( "src/pkg/Open.java", "package pkg;\n\nimport java.util.List;\n\npublic class Open {}\n" );

        // An open editor makes JDT treat the unit as a working copy, whose save() is a no-op.
        PlatformUI.getWorkbench().getDisplay().syncExec( () -> {
            try
            {
                IDE.openEditor( PlatformUI.getWorkbench().getActiveWorkbenchWindow().getActivePage(), file );
            }
            catch ( PartInitException e )
            {
                throw new IllegalStateException( e );
            }
        } );
        try
        {
            EditResult result = service.organizeImports( TEST_PROJECT_NAME, "src/pkg/Open.java" );

            assertEquals( EditResult.EditStatus.APPLIED, result.status() );
            assertFalse( ResourceUtilities.readFileContent( file ).contains( "import java.util.List" ),
                    "the organized imports must reach the disk, not only the editor buffer" );
            assertNotEquals( result.versionBefore().modificationStamp(), result.versionAfter().modificationStamp() );
        }
        finally
        {
            PlatformUI.getWorkbench().getDisplay().syncExec(
                    () -> PlatformUI.getWorkbench().getActiveWorkbenchWindow().getActivePage().closeAllEditors( false ) );
        }
    }

    @Test
    public void testFormatCode_keepsTheLineDelimiterOfTheInput() {
        String lfSource = "package x;\npublic class A {\nint a;\nvoid f(){a=1;}\n}\n";

        String lfFormatted = service.formatCode(lfSource, TEST_PROJECT_NAME);
        assertTrue(lfFormatted.contains("a = 1;"), lfFormatted);
        assertFalse(lfFormatted.contains("\r"), lfFormatted);

        String crlfFormatted = service.formatCode(lfSource.replace("\n", "\r\n"), TEST_PROJECT_NAME);
        assertTrue(crlfFormatted.contains("a = 1;"), crlfFormatted);
        assertFalse(crlfFormatted.replace("\r\n", "").contains("\n"), crlfFormatted);
    }

    @Test
    public void testFormatFile_javaFileKeepsItsLineDelimiter() throws CoreException, IOException {
        IFile testFile = createFile("src/A.java", "package x;\npublic class A {\nint a;\nvoid f(){a=1;}\n}\n");

        EditResult result = service.formatFile(TEST_PROJECT_NAME, "src/A.java");

        assertEquals(EditResult.EditStatus.APPLIED, result.status());
        String formatted = ResourceUtilities.readFileContent(testFile);
        assertTrue(formatted.contains("a = 1;"), formatted);
        assertFalse(formatted.contains("\r"), formatted);
        // The IDE computed this change, so the diff is the caller's only account of it.
        assertTrue(result.unifiedDiff().contains("-void f(){a=1;}"), result.unifiedDiff());
    }

    @Test
    public void testReplaceFileContent_previewKeepsTheWholeDiff() throws CoreException, IOException {
        createFile("src/long.txt", "old\n");
        String longContent = "line\n".repeat(200);

        EditResult result = service.replaceFileContent(TEST_PROJECT_NAME, "src/long.txt", longContent, IResource.NULL_STAMP, true);

        assertEquals(EditResult.EditStatus.PREVIEW, result.status());
        // A preview exists to show the outcome, so its diff is never cut short; a large reply is the client's to page.
        String diff = result.unifiedDiff();
        assertTrue(diff.startsWith("--- src/long.txt\n+++ src/long.txt\n@@"), diff);
        assertEquals(200, diff.lines().filter("+line"::equals).count(), diff);
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
