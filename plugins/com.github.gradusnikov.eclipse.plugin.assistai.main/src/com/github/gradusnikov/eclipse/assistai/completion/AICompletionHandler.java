package com.github.gradusnikov.eclipse.assistai.completion;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.core.runtime.ILog;
import org.eclipse.jface.text.BadLocationException;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.ITextSelection;
import org.eclipse.jface.text.source.ISourceViewer;
import org.eclipse.jface.viewers.ISelection;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.handlers.HandlerUtil;
import org.eclipse.ui.texteditor.ITextEditor;

import com.github.gradusnikov.eclipse.assistai.Activator;
import com.github.gradusnikov.eclipse.assistai.chat.ChatMessage;
import com.github.gradusnikov.eclipse.assistai.chat.Conversation;
import java.util.concurrent.CompletableFuture;
import com.github.gradusnikov.eclipse.assistai.mcp.services.CodeEditingService;
import com.github.gradusnikov.eclipse.assistai.prompt.ChatMessageFactory;
import com.github.gradusnikov.eclipse.assistai.prompt.Prompts;

/**
 * Handler for the AI Code Completion command (Alt+/).
 * Supports three modes based on preferences:
 * 1. Ghost text with streaming - Shows real-time streaming preview
 * 2. Ghost text without streaming - Shows preview after completion
 * 3. Direct insertion - Inserts completion directly without preview
 */
public class AICompletionHandler extends AbstractHandler {
    
    // Track ghost text managers per editor
    private static final Map<ITextEditor, GhostTextManager> ghostTextManagers = new WeakHashMap<>();
    
    // Track current completion request
    private static final AtomicReference<CompletableFuture<String>> currentRequest = new AtomicReference<>();
    
    private final ILog logger;

    private final CompletionContextBuilder contextBuilder;

    private final StreamingCompletionClient streamingClient;

    private final ChatMessageFactory chatMessageFactory;
    
    private final CodeEditingService codeEditingService;
    
    public AICompletionHandler()
    {
        Activator activator = Activator.getDefault();
        this.logger = activator.getLog();
        this.contextBuilder = activator.make(CompletionContextBuilder.class);
        this.streamingClient = activator.make(StreamingCompletionClient.class);
        this.chatMessageFactory = activator.make(ChatMessageFactory.class);
        this.codeEditingService = activator.make(CodeEditingService.class);
    }
    
    
    @Override
    public Object execute(ExecutionEvent event) throws ExecutionException {
        IWorkbenchWindow window = HandlerUtil.getActiveWorkbenchWindow(event);
        if (window == null) {
            return null;
        }
        
        IWorkbenchPage page = window.getActivePage();
        if (page == null) {
            return null;
        }
        
        IEditorPart editor = page.getActiveEditor();
        if (!(editor instanceof ITextEditor)) {
            return null;
        }
        
        ITextEditor textEditor = (ITextEditor) editor;
        
        
        try {
            Activator activator = Activator.getDefault();
            CompletionConfiguration configuration = activator.make(CompletionConfiguration.class);
            
            // Check if completion is enabled
            if (!configuration.isEnabled()) {
                return null;
            }
            
            // Cancel any existing request
            CompletableFuture<String> existingRequest = currentRequest.getAndSet(null);
            if (existingRequest != null) {
                existingRequest.cancel(true);
            }
            
            // Get or create ghost text manager for this editor
            GhostTextManager ghostManager = getGhostTextManager(textEditor);
            
            // Check if ghost text is available for this editor
            if (ghostManager == null) {
                // Fall back to direct insertion if ghost text is not supported
                executeDirectInsertion(textEditor);
                return null;
            }
            
            // Dismiss any existing ghost text
            ghostManager.dismissCompletion();
            executeWithStreamingGhostText(textEditor, ghostManager);
            
        } catch (Exception e) {
            throw new ExecutionException("Error executing AI completion", e);
        }
        
        return null;
    }
    
    /**
     * Executes completion with streaming ghost text preview.
     * Shows text incrementally as it streams in from the LLM.
     */
    private void executeWithStreamingGhostText(ITextEditor textEditor, GhostTextManager ghostManager) throws Exception {
        
        Optional<CompletionContext> contextOpt = contextBuilder.build(textEditor);
        if (contextOpt.isEmpty()) {
            return;
        }
        
        CompletionContext ctx = contextOpt.get();
        if (ctx.codeBeforeCursor().isBlank()) {
            return;
        }
        
        // Use ChatMessageFactory to create the conversation with proper variable substitution
        Conversation conversation = createCompletionConversation();
        
        // Get cursor offset at the time of request
        int cursorOffset = getCaretOffset(textEditor);
        
        // Buffer for streaming response
        StringBuilder streamingBuffer = new StringBuilder();
        
        // Start streaming
        CompletableFuture<String> future = streamingClient.startStreaming(
            conversation,
            // On chunk - append to buffer and update ghost text
            chunk -> {
                streamingBuffer.append(chunk);
                String parsed = parseCompletion(streamingBuffer.toString());
                if ( Objects.nonNull( parsed ) ) {
                    // Update on UI thread
                    Display.getDefault().asyncExec(() -> {
                        if (!ghostManager.isShowing()) {
                            // First content - show ghost text at original cursor position
                            ghostManager.showGhostText(parsed, cursorOffset);
                        } else {
                            // Update existing ghost text
                            ghostManager.updateGhostText(parsed);
                        }
                    });
                }
            }
        );
        
        // Handle completion and errors via CompletableFuture
        future.thenAccept(fullResponse -> {
            String completion = parseCompletion(fullResponse);
            if ( Objects.nonNull(completion) ) {
                // Format the completion using CodeEditingService
                String formattedCompletion = codeEditingService.formatCompletion(completion, ctx, textEditor);
                Display.getDefault().asyncExec(() -> {
                    ghostManager.updateGhostText(formattedCompletion);
                });
            } else {
                Display.getDefault().asyncExec(() -> {
                    ghostManager.dismissCompletion();
                });
            }
            currentRequest.set(null);
        }).exceptionally(error -> {
            Display.getDefault().asyncExec(() -> {
                ghostManager.dismissCompletion();
            });
            currentRequest.set(null);
            return null;
        });
        
        currentRequest.set(future);
    }
    
    /**
     * Executes completion with direct insertion (no ghost text).
     * Uses streaming client internally, waits for completion, then inserts.
     */
    private void executeDirectInsertion(ITextEditor textEditor) throws Exception {
        
        Optional<CompletionContext> contextOpt = contextBuilder.build(textEditor);
        if (contextOpt.isEmpty()) {
            return;
        }
        
        CompletionContext ctx = contextOpt.get();
        if (ctx.codeBeforeCursor().isBlank()) {
            return;
        }
        
        // Use ChatMessageFactory to create the conversation with proper variable substitution
        Conversation conversation = createCompletionConversation();
        
        // Start streaming and insert when complete
        streamingClient.startStreaming(
            conversation,
            null  // No chunk callback needed for direct insertion
        ).thenAccept(fullResponse -> {
            String completion = parseCompletion(fullResponse);
            if ( Objects.nonNull(completion) ) {
                Display.getDefault().asyncExec(() -> {
                    insertText(textEditor, completion);
                });
            }
        });
        // Errors are silently ignored for direct insertion
    }
    
    /**
     * Inserts text at the current cursor position.
     */
    private void insertText(ITextEditor editor, String text) {
        IDocument document = editor.getDocumentProvider().getDocument(editor.getEditorInput());
        ISelection selection = editor.getSelectionProvider().getSelection();
        
        if (document == null || !(selection instanceof ITextSelection)) {
            return;
        }
        
        ITextSelection textSelection = (ITextSelection) selection;
        int offset = textSelection.getOffset();
        int length = textSelection.getLength();
        
        try {
            document.replace(offset, length, text);
            editor.selectAndReveal(offset + text.length(), 0);
        } catch (BadLocationException e) {
            // Ignore
        }
    }
    
    
    /**
     * Creates a Conversation with a single user message for completion.
     * This is a convenience method that combines context setup, message creation,
     * and conversation creation.
     * 
     * @param completionContext The completion context
     * @return A Conversation ready to be sent to the LLM
     */
    public Conversation createCompletionConversation()
    {
        Conversation conversation = new Conversation();
        ChatMessage message = chatMessageFactory.createUserChatMessage( Prompts.COMPLETION );
        conversation.add(message);
        return conversation;
    }
    
    /**
     * Gets the caret offset from the editor (widget offset for StyledText).
     */
    private int getCaretOffset(ITextEditor editor) {
        try {
            // First try the adapter approach
            ISourceViewer viewer = editor.getAdapter(ISourceViewer.class);
            
            // If adapter doesn't work, try reflection
            if (viewer == null) {
                viewer = getSourceViewerViaReflection(editor);
            }
            
            if (viewer != null) {
                return viewer.getTextWidget().getCaretOffset();
            }
        } catch (Exception e) {
            Activator.getDefault().getLog().warn("Failed to get caret offset: " + e.getMessage());
        }
        return 0;
    }
    
    /**
     * Gets or creates a GhostTextManager for the given editor.
     * Returns null if the editor doesn't support ISourceViewer/ITextViewer.
     */
    private GhostTextManager getGhostTextManager(ITextEditor editor) {
        // Don't use computeIfAbsent because it caches null values
        GhostTextManager existing = ghostTextManagers.get(editor);
        if (existing != null) {
            return existing;
        }
        
        try {
            // First try the adapter approach
            ISourceViewer sourceViewer = editor.getAdapter(ISourceViewer.class);
            
            // If adapter doesn't work, try reflection to get the protected getSourceViewer() method
            // This is necessary because AbstractTextEditor.getSourceViewer() is protected
            if (sourceViewer == null) {
                sourceViewer = getSourceViewerViaReflection(editor);
            }
            
            if (sourceViewer != null) {
                GhostTextManager manager = new GhostTextManager(sourceViewer);
                ghostTextManagers.put(editor, manager);
                return manager;
            } else {
                logger.warn(
                    "Could not obtain ISourceViewer from editor: " + editor.getClass().getName()
                );
            }
        } catch (Exception ex) {
            logger.error("Error creating GhostTextManager", ex);
        }
        return null;
    }
    
    /**
     * Gets the ISourceViewer from an editor using reflection.
     * This is necessary because AbstractTextEditor.getSourceViewer() is protected.
     */
    private ISourceViewer getSourceViewerViaReflection(ITextEditor editor) {
        try {
            // Try to find getSourceViewer() method in the class hierarchy
            java.lang.reflect.Method method = findMethod(editor.getClass(), "getSourceViewer");
            if (method != null) {
                method.setAccessible(true);
                Object result = method.invoke(editor);
                if (result instanceof ISourceViewer) {
                    return (ISourceViewer) result;
                }
            }
        } catch (Exception e) {
            logger.warn(
                "Failed to get ISourceViewer via reflection: " + e.getMessage()
            );
        }
        return null;
    }
    
    /**
     * Finds a method by name in the class hierarchy.
     */
    private java.lang.reflect.Method findMethod(Class<?> clazz, String methodName) {
        while (clazz != null) {
            try {
                return clazz.getDeclaredMethod(methodName);
            } catch (NoSuchMethodException e) {
                clazz = clazz.getSuperclass();
            }
        }
        return null;
    }
    
    /**
     * Parses the completion from direct code response.
     */
    private String parseCompletion(String response) {
        if (response == null || response.isBlank()) {
            return null;
        }

        return response.trim();
    }
}
