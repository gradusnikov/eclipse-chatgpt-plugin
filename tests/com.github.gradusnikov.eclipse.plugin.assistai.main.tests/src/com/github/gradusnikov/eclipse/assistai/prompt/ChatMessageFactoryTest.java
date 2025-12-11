package com.github.gradusnikov.eclipse.assistai.prompt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Optional;
import java.util.concurrent.Callable;

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
import org.eclipse.e4.core.contexts.ContextInjectionFactory;
import org.eclipse.e4.core.contexts.EclipseContextFactory;
import org.eclipse.e4.core.contexts.IEclipseContext;
import org.eclipse.e4.ui.di.UISynchronize;
import org.eclipse.ui.IWorkbench;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PartInitException;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.activities.WorkbenchActivityHelper;
import org.eclipse.ui.ide.IDE;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.osgi.framework.BundleContext;
import org.osgi.framework.FrameworkUtil;
import org.osgi.util.tracker.ServiceTracker;

import com.github.gradusnikov.eclipse.assistai.Activator;
import com.github.gradusnikov.eclipse.assistai.chat.ChatMessage;
import com.github.gradusnikov.eclipse.assistai.mcp.services.CodeAnalysisService;
import com.github.gradusnikov.eclipse.assistai.mcp.services.CodeEditingService;
import com.github.gradusnikov.eclipse.assistai.mcp.services.ConsoleService;
import com.github.gradusnikov.eclipse.assistai.mcp.services.EditorService;
import com.github.gradusnikov.eclipse.assistai.mcp.services.GitService;
import com.github.gradusnikov.eclipse.assistai.tools.UISynchronizeCallable;

public class ChatMessageFactoryTest {

    private static final String TEST_PROJECT_NAME = "ChatMessageFactoryTestProject";
    private IProject project;
    private ChatMessageFactory chatMessageFactory;
    private EditorService editorService;
    private PromptRepository promptRepository;
    private NullProgressMonitor monitor = new NullProgressMonitor();
    private UISynchronize uiSync;
    
    @BeforeEach
    public void beforeEach() throws CoreException, IOException, InterruptedException {
        // Get workspace through OSGi service tracker
        BundleContext bundleContext = FrameworkUtil.getBundle(ChatMessageFactoryTest.class).getBundleContext();
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

        context.set( UISynchronize.class, uiSync );
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
        editorService = ContextInjectionFactory.make(EditorService.class, context);
        context.set( EditorService.class, editorService );
        promptRepository = ContextInjectionFactory.make(PromptRepository.class, context);
        context.set( PromptRepository.class, promptRepository );
        
        // Create instance of the actual PromptContextValueProvider using DI
        PromptContextValueProvider contextValueProvider = ContextInjectionFactory.make(PromptContextValueProvider.class, context);
        context.set(PromptContextValueProvider.class, contextValueProvider);
        
        // Create the service instance using dependency injection
        chatMessageFactory = ContextInjectionFactory.make(ChatMessageFactory.class, context);
    }
    
    @AfterEach
    public void afterEach() throws CoreException {
        // Clean up the test project
        if (project != null && project.exists()) {
            project.delete(true, true, monitor);
        }
    }
    
    @Test
    public void testCreateAssistantChatMessage() {
        String messageText = "This is an assistant message";
        ChatMessage message = chatMessageFactory.createAssistantChatMessage(messageText);
        
        assertNotNull(message);
        assertNotNull(message.getId());
        assertEquals("assistant", message.getRole());
        assertEquals(messageText, message.getContent());
    }
    
    @Test
    public void testCreateUserChatMessage_Document() throws CoreException {
        // Setup test data
        promptRepository.setPrompt(Prompts.DOCUMENT.name(), "Create documentation for ${selectedContent}");
//        editorService.setEditorSelection("TestClass");
        
        IFile testFile = createFile( "/src/Test.java", """
                publi static void main(String[] args) {
                    System.out.println("Hello world!");
                }
                """ );
        openFileInEditor( testFile );
        
        ChatMessage message = chatMessageFactory.createUserChatMessage(Prompts.DOCUMENT);
        
        assertNotNull(message);
        assertNotNull(message.getId());
        assertEquals("user", message.getRole());
        assertEquals("Create documentation for TestClass", message.getContent());
    }
//    
//    @Test
//    public void testCreateUserChatMessage_TestCase() throws CoreException {
//        // Setup test data
//        promptRepository.setPrompt("TEST_CASE", "Create test case for ${currentFileName}");
//        
//        // Create a test file to be the "current file"
//        IFile file = createFile("src/TestClass.java", "public class TestClass {}");
//        editorService.setCurrentFile(file);
//        
//        ChatMessage message = chatMessageFactory.createUserChatMessage(Prompts.TEST_CASE);
//        
//        assertNotNull(message);
//        assertEquals("user", message.getRole());
//        assertEquals("Create test case for src/TestClass.java", message.getContent());
//    }
//    
//    @Test
//    public void testCreateUserChatMessage_Refactor() throws CoreException {
//        // Setup test data
//        promptRepository.setPrompt("REFACTOR", "Refactor code ${selectedContent}");
//        editorService.setEditorSelection("void testMethod() { return; }");
//        
//        ChatMessage message = chatMessageFactory.createUserChatMessage(Prompts.REFACTOR);
//        
//        assertNotNull(message);
//        assertEquals("user", message.getRole());
//        assertEquals("Refactor code void testMethod() { return; }", message.getContent());
//    }
//    
//    @Test
//    public void testCreateUserChatMessage_Discuss() throws CoreException {
//        // Setup test data
//        promptRepository.setPrompt("DISCUSS", "Discuss code ${currentFileContents}");
//        
//        // Create a test file with content
//        IFile file = createFile("src/DiscussTest.java", "public class DiscussTest {}");
//        editorService.setCurrentFile(file);
//        editorService.setFileContent("public class DiscussTest {}");
//        
//        ChatMessage message = chatMessageFactory.createUserChatMessage(Prompts.DISCUSS);
//        
//        assertNotNull(message);
//        assertEquals("user", message.getRole());
//        assertEquals("Discuss code public class DiscussTest {}", message.getContent());
//    }
//    
//    @Test
//    public void testCreateUserChatMessage_FixErrors() throws CoreException {
//        // Setup test data
//        promptRepository.setPrompt("FIX_ERRORS", "Fix errors in ${errors}");
//        
//        ChatMessage message = chatMessageFactory.createUserChatMessage(Prompts.FIX_ERRORS);
//        
//        assertNotNull(message);
//        assertEquals("user", message.getRole());
//        // The errors content will be empty in the test environment
//        assertEquals("Fix errors in ", message.getContent());
//    }
//    
//    @Test
//    public void testCreateGenerateGitCommitCommentJob() throws CoreException {
//        // Setup test data
//        promptRepository.setPrompt("GIT_COMMENT", "Generate git comment for changes");
//        
//        ChatMessage message = chatMessageFactory.createGenerateGitCommitCommentJob();
//        
//        assertNotNull(message);
//        assertEquals("user", message.getRole());
//        assertEquals("Generate git comment for changes", message.getContent());
//    }
//    
//    @Test
//    public void testUpdatePromptText_WithPlaceholders() throws CoreException {
//        // Setup test data
//        IFile file = createFile("src/TestFile.java", "public class TestFile {}");
//        editorService.setCurrentFile(file);
//        editorService.setEditorSelection("selected code");
//        
//        String result = chatMessageFactory.updatePromptText("Project: ${currentProjectName}, File: ${currentFileName}, Selection: ${selectedContent}");
//        
//        assertEquals("Project: " + TEST_PROJECT_NAME + ", File: src/TestFile.java, Selection: selected code", result);
//    }
//    
//    @Test
//    public void testUpdatePromptText_NoPlaceholders() {
//        String promptText = "This text has no placeholders";
//        
//        String result = chatMessageFactory.updatePromptText(promptText);
//        
//        assertEquals(promptText, result);
//    }
//    
//    @Test
//    public void testCreateUserChatMessage_WithSupplier() {
//        ChatMessage message = chatMessageFactory.createUserChatMessage(() -> "Direct content");
//        
//        assertNotNull(message);
//        assertNotNull(message.getId());
//        assertEquals("user", message.getRole());
//        assertEquals("Direct content", message.getContent());
//    }
    
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
    private void openFileInEditor(IFile file) 
    {
        var page = Optional.ofNullable( PlatformUI.getWorkbench() )
                                 .map( IWorkbench::getActiveWorkbenchWindow )
                                 .map(IWorkbenchWindow::getActivePage).orElseThrow( () -> new RuntimeException("No active page") );
        
        try 
        {
            // Open the editor and get the editor reference
            var editor = IDE.openEditor(page, file);
            // Set focus to the editor
            if (editor != null) 
            {
                editor.setFocus();
            }
        } 
        catch (PartInitException e) 
        {
            throw new RuntimeException( e );
        }
    }

}
