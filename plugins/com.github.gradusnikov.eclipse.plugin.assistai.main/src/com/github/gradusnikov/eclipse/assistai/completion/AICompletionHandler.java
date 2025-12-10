package com.github.gradusnikov.eclipse.assistai.completion;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.runtime.ILog;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.core.ToolFactory;
import org.eclipse.jdt.core.formatter.CodeFormatter;
import org.eclipse.jface.text.BadLocationException;
import org.eclipse.jface.text.Document;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.ITextSelection;
import org.eclipse.jface.text.source.ISourceViewer;
import org.eclipse.jface.viewers.ISelection;
import org.eclipse.swt.widgets.Display;
import org.eclipse.text.edits.TextEdit;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IFileEditorInput;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.handlers.HandlerUtil;
import org.eclipse.ui.texteditor.ITextEditor;

import com.github.gradusnikov.eclipse.assistai.Activator;
import com.github.gradusnikov.eclipse.assistai.chat.Conversation;
import com.github.gradusnikov.eclipse.assistai.completion.StreamingCompletionClient.CompletionHandle;
import com.github.gradusnikov.eclipse.assistai.prompt.ChatMessageFactory;

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
    private static final AtomicReference<CompletionHandle> currentRequest = new AtomicReference<>();
    
    private final ILog logger;
    
    public AICompletionHandler()
    {
        this.logger = Activator.getDefault().getLog();    
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
            CompletionHandle existingRequest = currentRequest.getAndSet(null);
            if (existingRequest != null) {
                existingRequest.cancel();
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
        Activator activator = Activator.getDefault();
        CompletionContextBuilder contextBuilder = activator.make(CompletionContextBuilder.class);
        ChatMessageFactory chatMessageFactory = activator.make(ChatMessageFactory.class);
        StreamingCompletionClient streamingClient = activator.make(StreamingCompletionClient.class);
        
        Optional<CompletionContext> contextOpt = contextBuilder.build(textEditor);
        if (contextOpt.isEmpty()) {
            return;
        }
        
        CompletionContext ctx = contextOpt.get();
        if (ctx.codeBeforeCursor().isBlank()) {
            return;
        }
        
        // Use ChatMessageFactory to create the conversation with proper variable substitution
        Conversation conversation = chatMessageFactory.createCompletionConversation(ctx);
        
        // Get cursor offset at the time of request
        int cursorOffset = getCaretOffset(textEditor);
        
        // Buffer for streaming response
        StringBuilder streamingBuffer = new StringBuilder();
        
        // Start streaming
        CompletionHandle handle = streamingClient.startStreaming(
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
            },
            // On complete - format the final completion
            fullResponse -> {
                String completion = parseCompletion(fullResponse);
                if ( Objects.nonNull(completion) ) {
                    // Format the completion on completion
                    String formattedCompletion = formatCompletion(completion, ctx, textEditor);
                    Display.getDefault().asyncExec(() -> {
                        ghostManager.updateGhostText(formattedCompletion);
                    });
                } else {
                    Display.getDefault().asyncExec(() -> {
                        ghostManager.dismissCompletion();
                    });
                }
                currentRequest.set(null);
            },
            // On error
            error -> {
                Display.getDefault().asyncExec(() -> {
                    ghostManager.dismissCompletion();
                });
                currentRequest.set(null);
            }
        );
        
        currentRequest.set(handle);
    }
    
    /**
     * Executes completion with direct insertion (no ghost text).
     * Uses streaming client internally, waits for completion, then inserts.
     */
    private void executeDirectInsertion(ITextEditor textEditor) throws Exception {
        Activator activator = Activator.getDefault();
        CompletionContextBuilder contextBuilder = activator.make(CompletionContextBuilder.class);
        ChatMessageFactory chatMessageFactory = activator.make(ChatMessageFactory.class);
        StreamingCompletionClient streamingClient = activator.make(StreamingCompletionClient.class);
        
        Optional<CompletionContext> contextOpt = contextBuilder.build(textEditor);
        if (contextOpt.isEmpty()) {
            return;
        }
        
        CompletionContext ctx = contextOpt.get();
        if (ctx.codeBeforeCursor().isBlank()) {
            return;
        }
        
        // Use ChatMessageFactory to create the conversation with proper variable substitution
        Conversation conversation = chatMessageFactory.createCompletionConversation(ctx);
        
        // Start streaming and insert when complete
        streamingClient.startStreaming(
            conversation,
            // On chunk - ignore
            chunk -> { },
            // On complete - insert the text
            fullResponse -> {
                String completion = parseCompletion(fullResponse);
                if ( Objects.nonNull(completion) ) {
                    Display.getDefault().asyncExec(() -> {
                        insertText(textEditor, completion);
                    });
                }
            },
            // On error - ignore
            error -> { }
        );
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
    
    /**
     * Formats a code completion snippet using Eclipse's code formatter.
     * The completion is formatted in context by combining it with the code before the cursor,
     * formatting the combined code, and then extracting the formatted completion.
     * 
     * @param completion The raw completion text from the LLM
     * @param ctx The completion context containing code before/after cursor
     * @param editor The text editor (used to get project-specific formatter settings)
     * @return The formatted completion, or the original if formatting fails
     */
    private String formatCompletion(String completion, CompletionContext ctx, ITextEditor editor) {
        if (completion == null || completion.isEmpty()) {
            return completion;
        }
        
        // Only format Java files
        if (!"java".equalsIgnoreCase(ctx.fileExtension())) {
            return completion;
        }
        
        try {
            // Get the project for formatter settings
            Map<String, String> options = getFormatterOptions(editor);
            
            if (options == null) {
                return completion;
            }
            
            // Create the formatter
            CodeFormatter formatter = ToolFactory.createCodeFormatter(options);
            
            // Combine code before cursor with the completion to format in context
            String codeBefore = ctx.codeBeforeCursor();
            String combinedCode = codeBefore + completion;
            
            // Format just the completion part (from offset = codeBefore.length())
            int completionOffset = codeBefore.length();
            int completionLength = completion.length();
            
            // Format as statements (K_STATEMENTS works better for code fragments)
            TextEdit textEdit = formatter.format(
                CodeFormatter.K_STATEMENTS | CodeFormatter.F_INCLUDE_COMMENTS,
                combinedCode,
                completionOffset,
                completionLength,
                getIndentationLevel(codeBefore),
                null
            );
            
            if (textEdit == null) {
                // Try formatting as unknown kind
                textEdit = formatter.format(
                    CodeFormatter.K_UNKNOWN,
                    combinedCode,
                    completionOffset,
                    completionLength,
                    getIndentationLevel(codeBefore),
                    null
                );
            }
            
            if (textEdit == null) {
                // Formatting failed, return original
                return completion;
            }
            
            // Apply the formatting to get the result
            IDocument document = new Document(combinedCode);
            textEdit.apply(document);
            
            // Extract the formatted completion (everything after the original code before cursor)
            String formattedCombined = document.get();
            
            // The formatted code might have different length, so we need to extract the completion part
            // by removing the (possibly reformatted) prefix
            if (formattedCombined.length() > codeBefore.length()) {
                // Find where the completion starts - look for the completion in the formatted result
                String formattedCompletion = formattedCombined.substring(codeBefore.length());
                return formattedCompletion;
            }
            
            return completion;
            
        } catch (Exception e) {
            logger.warn("Failed to format completion: " + e.getMessage());
            return completion;
        }
    }
    
    /**
     * Gets the formatter options for the given editor's project.
     */
    private Map<String, String> getFormatterOptions(ITextEditor editor) {
        try {
            // Try to get project from editor
            if (editor.getEditorInput() instanceof IFileEditorInput) {
                IFile file = ((IFileEditorInput) editor.getEditorInput()).getFile();
                if (file != null && file.getProject() != null) {
                    IJavaProject javaProject = JavaCore.create(file.getProject());
                    if (javaProject != null && javaProject.exists()) {
                        return javaProject.getOptions(true);
                    }
                }
            }
            
            // Fall back to workspace defaults
            return JavaCore.getOptions();
        } catch (Exception e) {
            return JavaCore.getOptions();
        }
    }
    
    /**
     * Calculates the indentation level based on the code before cursor.
     */
    private int getIndentationLevel(String codeBefore) {
        if (codeBefore == null || codeBefore.isEmpty()) {
            return 0;
        }
        
        // Find the last line
        int lastNewline = codeBefore.lastIndexOf('\n');
        String lastLine = (lastNewline >= 0) ? codeBefore.substring(lastNewline + 1) : codeBefore;
        
        // Count leading tabs/spaces
        int indent = 0;
        for (char c : lastLine.toCharArray()) {
            if (c == '\t') {
                indent++;
            } else if (c == ' ') {
                // Assuming 4 spaces = 1 indent level (common default)
                // This will be adjusted by the formatter anyway
            } else {
                break;
            }
        }
        
        return indent;
    }
}
