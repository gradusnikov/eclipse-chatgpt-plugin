package com.github.gradusnikov.eclipse.assistai.mcp.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.osgi.framework.BundleContext;
import org.osgi.framework.FrameworkUtil;
import org.osgi.util.tracker.ServiceTracker;

import com.github.gradusnikov.eclipse.assistai.Activator;
import com.github.gradusnikov.eclipse.assistai.mcp.services.CodeEditingService;
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
        String result = service.replaceLines(TEST_PROJECT_NAME, "src/testFile.txt", replacementContent, 2, 4);
        
        System.out.println( result );
        
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
        service.replaceLines(TEST_PROJECT_NAME, "src/testFile.txt", replacementContent, 1, 1);
        
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
        service.replaceLines(TEST_PROJECT_NAME, "src/testFile.txt", replacementContent, 3, 3);
        
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
        service.replaceLines(TEST_PROJECT_NAME, "src/testFile.txt", replacementContent, 1, 3);
        
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
        service.replaceLines(TEST_PROJECT_NAME, "src/testFile.txt", "", 2, 3);
        
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
        
        createFile("src/testFile.txt", initialContent);
        
        // Test with start line beyond file length
        Exception exception = assertThrows(RuntimeException.class, () -> {
            service.replaceLines(TEST_PROJECT_NAME, "src/testFile.txt", "New Content", 11, 13);
        });
        
        assertTrue(exception.getMessage().contains("range"));
        
        // Test with negative start line
        exception = assertThrows(IllegalArgumentException.class, () -> {
            service.replaceLines(TEST_PROJECT_NAME, "src/testFile.txt", "New Content", 0, 2);
        });
        
        assertTrue(exception.getMessage().contains("range"));
        
        // Test with end line less than start line
        exception = assertThrows(IllegalArgumentException.class, () -> {
            service.replaceLines(TEST_PROJECT_NAME, "src/testFile.txt", "New Content", 3, 2);
        });
        
        assertTrue(exception.getMessage().contains("range"));
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
	    String result = service.replaceStringInFile(TEST_PROJECT_NAME, "src/testFile.txt", oldString, newString, null, null);
	    
	    // Verify the operation was successful
	    assertTrue(result.contains("Success: String replaced in file"));
	    assertTrue(result.contains("Workspace state: saved=true"));
	    assertTrue(result.contains("cache=updated"));
	    
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
	    String result = service.replaceStringInFile(TEST_PROJECT_NAME, "src/testFile.txt", oldString, newString, 2, 3);
	    
	    // Verify the operation was successful
	    assertTrue(result.contains("Success: String replaced in file"));
	    
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
	    service.replaceStringInFile(TEST_PROJECT_NAME, "src/testFile.txt", oldString, newString, null, null);
	    
	    // Read the updated file content
	    String updatedContent = ResourceUtilities.readFileContent(testFile);
	    
	    // Expected content after replacement
	    String expectedContent = 
	            "This file contains .\n" +
	            "Other content will remain.\n";
	    
	    // Verify the content was correctly updated
	    assertEquals(expectedContent, updatedContent);
	}
	
	@Test
	public void testReplaceStringInFile_StringNotFound() throws CoreException, IOException {
	    // Create a test file
	    String initialContent = "This is a test file.\n";
	    
	    createFile("src/testFile.txt", initialContent);
	    
	    // Try to replace a string that doesn't exist in the file
	    String oldString = "non-existent string";
	    String newString = "replacement";
	    
	    // This should throw a RuntimeException
	    Exception exception = assertThrows(RuntimeException.class, () -> {
	        service.replaceStringInFile(TEST_PROJECT_NAME, "src/testFile.txt", oldString, newString, null, null);
	    });
	    
	    // Verify the exception message
	    assertTrue(exception.getMessage().contains("The specified string was not found in the file"));
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
	        service.replaceStringInFile(TEST_PROJECT_NAME, "src/testFile.txt", oldString, newString, 11, 13);
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
	    service.replaceStringInFile(TEST_PROJECT_NAME, "src/testFile.txt", oldString, newString, null, null);
	    
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

		 service.replaceLines(TEST_PROJECT_NAME, "src/testFile.txt", replacement, startLine, endLine);
		 
		 String updatedContent = ResourceUtilities.readFileContent(testFile);
		 System.out.println("------------");
		 System.out.println(updatedContent);
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

        String result = service.refactorExtractTypeToNewFile(TEST_PROJECT_NAME, "src/Outer.java", "Outer.Inner");

        assertTrue(result.contains("Success: Nested Java type 'Outer.Inner' was extracted"));
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

        String result = service.applyPatch( TEST_PROJECT_NAME, "src/patch.txt", patch, false );

        assertTrue( result.contains( "Success: Patch applied" ) );
        assertEquals( "alpha\nBETA\ngamma\ndelta\nepsilon\nZETA\n", ResourceUtilities.readFileContent( file ) );

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

        service.applyPatch( TEST_PROJECT_NAME, "src/crlf.txt", patch, false );

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

        assertThrows( RuntimeException.class,
                () -> service.applyPatch( TEST_PROJECT_NAME, "src/atomic.txt", patch, false ) );

        assertEquals( original, ResourceUtilities.readFileContent( file ) );
    }

    @Test
    public void testReplaceFileContentSynchronizesJdtModel() throws Exception
    {
        IFile file = createFile( "src/SynchronizedType.java",
                "public class SynchronizedType { int value = 1; }\n" );

        String result = service.replaceFileContent( TEST_PROJECT_NAME, "src/SynchronizedType.java",
                "public class SynchronizedType { int value = 2; }\n" );

        assertTrue( result.contains( "Workspace state: saved=true" ) );
        assertTrue( result.contains( "jdtConsistent=true" ) );

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

        String result = editorBackedService.formatFile( TEST_PROJECT_NAME, "src/settings.json" );

        assertEquals( "{\n  \"enabled\": true\n}\n", ResourceUtilities.readFileContent( file ) );
        assertTrue( result.contains( "formatted using test.json.format" ) );
        assertTrue( result.contains( "Workspace state: saved=true" ) );
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
