
package com.github.gradusnikov.eclipse.plugin.assistai.mcp.services;

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
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.ILog;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.core.runtime.Path;
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
import com.github.gradusnikov.eclipse.assistai.tools.ResourceUtilities;

public class CodeEditingServiceTest {

    private static final String TEST_PROJECT_NAME = "CodeEditingTestProject";
    private IProject project;
    private CodeEditingService service;
    private NullProgressMonitor monitor = new NullProgressMonitor();
    
    @BeforeEach
    public void beforeEach() throws CoreException, IOException, InterruptedException {
        // Get workspace through OSGi service tracker
        BundleContext bundleContext = FrameworkUtil.getBundle(CodeEditingServiceTest.class).getBundleContext();
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
        project.create(desc, monitor);
        project.open(monitor);
        
        // Create source folder
        IFolder srcFolder = project.getFolder("src");
        if (!srcFolder.exists()) {
            srcFolder.create(IResource.NONE, true, monitor);
        }
        
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
        
        service = ContextInjectionFactory.make(CodeEditingService.class, context);
    }
    
    @AfterEach
    public void afterEach() throws CoreException {
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
        
        // Replace lines 1-3 (0-based index) with new content
        String replacementContent = "New Line A\nNew Line B";
        String result = service.replaceLines(TEST_PROJECT_NAME, "src/testFile.txt", 1, 3, replacementContent);
        
        // Verify the operation was successful
        assertTrue(result.contains("Success: Replaced 3 lines"));
        
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
        service.replaceLines(TEST_PROJECT_NAME, "src/testFile.txt", 0, 0, replacementContent);
        
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
        service.replaceLines(TEST_PROJECT_NAME, "src/testFile.txt", 2, 2, replacementContent);
        
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
        service.replaceLines(TEST_PROJECT_NAME, "src/testFile.txt", 0, 2, replacementContent);
        
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
        
        // Replace lines with empty content (effectively deleting lines 1-2)
        service.replaceLines(TEST_PROJECT_NAME, "src/testFile.txt", 1, 2, "");
        
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
            service.replaceLines(TEST_PROJECT_NAME, "src/testFile.txt", 10, 12, "New Content");
        });
        
        assertTrue(exception.getMessage().contains("beyond the end of the file"));
        
        // Test with negative start line
        exception = assertThrows(IllegalArgumentException.class, () -> {
            service.replaceLines(TEST_PROJECT_NAME, "src/testFile.txt", -1, 2, "New Content");
        });
        
        assertTrue(exception.getMessage().contains("Start line must be at least 0"));
        
        // Test with end line less than start line
        exception = assertThrows(IllegalArgumentException.class, () -> {
            service.replaceLines(TEST_PROJECT_NAME, "src/testFile.txt", 2, 1, "New Content");
        });
        
        assertTrue(exception.getMessage().contains("End line must be greater than or equal to start line"));
    }
    
    
	@Test
	public void testReplaceStringInFile() throws CoreException, IOException {
	    // Create a test file with content containing a specific string
	    String initialContent = 
	            "This is a test file.\n" +
	            "It contains some text to be replaced.\n" +
	            "This line should remain unchanged.\n";
	    
	    IFile testFile = createFile("src/testFile.txt", initialContent);
	    
	    // Replace a specific string
	    String oldString = "some text to be replaced";
	    String newString = "new replacement text";
	    String result = service.replaceStringInFile(TEST_PROJECT_NAME, "src/testFile.txt", oldString, newString, null, null);
	    
	    // Verify the operation was successful
	    assertTrue(result.contains("Success: String replaced in file"));
	    
	    // Read the updated file content
	    String updatedContent = ResourceUtilities.readFileContent(testFile);
	    
	    // Expected content after replacement
	    String expectedContent = 
	            "This is a test file.\n" +
	            "It contains new replacement text.\n" +
	            "This line should remain unchanged.\n";
	    
	    // Verify the content was correctly updated
	    assertEquals(expectedContent, updatedContent);
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
	        service.replaceStringInFile(TEST_PROJECT_NAME, "src/testFile.txt", oldString, newString, 10, 12);
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
package com.example.app;
/**
 * Hello world!
 *
 */
public class App
{
    public static void welcomeLeetCoder() {
        System.out.println("Welcome l33t coder!");
    }

    public static void welcome()
    {
        System.out.println( "Welcome to the application!" );
    }

    public static void greet()
    {
        System.out.println( "Greetings from the new method!" );
    }

    public static void main( String[] args )
    {
        System.out.println( "Hello World!" );
    }
}
				""";
		
		String initialContent = """
package com.example.app;

/**
 * Hello world!
 *
 */
public class App
public static void welcomeLeetCoder() {
    System.out.println("Welcome l33t coder!");
}
{
    public static void welcome()
    {
        System.out.println( "Welcome to the application!" );
    }

    public static void greet()
    {
        System.out.println( "Greetings from the new method!" );
    }

    public static void main( String[] args )
    {
        System.out.println( "Hello World!" );
    }
}				
				""";
		 int startLine=0;
		 int endLine=20;
		 
		 IFile testFile = createFile("src/testFile.txt", initialContent);

		 service.replaceLines(TEST_PROJECT_NAME, "src/testFile.txt", startLine, endLine, replacement);
		 
		 String updatedContent = ResourceUtilities.readFileContent(testFile);
		 System.out.println(updatedContent);
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
