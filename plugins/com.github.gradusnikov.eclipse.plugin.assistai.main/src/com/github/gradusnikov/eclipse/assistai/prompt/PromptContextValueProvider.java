package com.github.gradusnikov.eclipse.assistai.prompt;

import java.util.Objects;
import java.util.concurrent.Callable;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.ILog;
import org.eclipse.core.runtime.IPath;
import org.eclipse.e4.core.di.annotations.Creatable;

import com.github.gradusnikov.eclipse.assistai.completion.CompletionContext;
import com.github.gradusnikov.eclipse.assistai.mcp.services.CodeAnalysisService;
import com.github.gradusnikov.eclipse.assistai.mcp.services.ConsoleService;
import com.github.gradusnikov.eclipse.assistai.mcp.services.EditorService;
import com.github.gradusnikov.eclipse.assistai.mcp.services.GitService;
import com.github.gradusnikov.eclipse.assistai.tools.UISynchronizeCallable;

import jakarta.inject.Inject;

@Creatable
public class PromptContextValueProvider 
{
    
    private static final String GIT_DIFF = "gitDiff";
    private static final String CONSOLE_OUTPUT = "consoleOutput";
    private static final String ERRORS = "errors";
    private static final String SELECTED_CONTENT = "selectedContent";
    private static final String CURRENT_FILE_CONTENT = "currentFileContent";
    private static final String CURRENT_FILE_PATH = "currentFilePath";
    private static final String CURRENT_FILE_NAME = "currentFileName";
    private static final String CURRENT_PROJECT_NAME = "currentProjectName";
    
    // Completion-specific context keys
    private static final String FILE_EXTENSION = "fileExtension";
    private static final String CODE_BEFORE_CURSOR = "codeBeforeCursor";
    private static final String CODE_AFTER_CURSOR = "codeAfterCursor";
    private static final String CURSOR_LINE = "cursorLine";
    private static final String CURSOR_COLUMN = "cursorColumn";
    
    @Inject
    ILog logger;
    @Inject
    UISynchronizeCallable uiSync;
	@Inject
	private CodeAnalysisService codeAnalysisService;
	@Inject
	private EditorService editorService;
	@Inject
	private ConsoleService consoleService;
	@Inject
	private GitService gitService;
	
	// Thread-local storage for completion context
	// This allows passing completion-specific context without changing the interface
	private static final ThreadLocal<CompletionContext> completionContextHolder = new ThreadLocal<>();
	
	/**
	 * Sets the completion context for the current thread.
	 * This should be called before creating a completion prompt and cleared after.
	 * 
	 * @param context The completion context, or null to clear
	 */
	public void setCompletionContext(CompletionContext context)
	{
	    completionContextHolder.set(context);
	}
	
	/**
	 * Clears the completion context for the current thread.
	 */
	public void clearCompletionContext()
	{
	    completionContextHolder.remove();
	}
	
	/**
	 * Gets the current completion context, if any.
	 */
	public CompletionContext getCompletionContext()
	{
	    return completionContextHolder.get();
	}
	
	public String getContextValue( String key )
	{
	    Objects.requireNonNull( key );
	    
	    // First check if there's a completion context with this value
	    CompletionContext completionCtx = completionContextHolder.get();
	    if (completionCtx != null)
	    {
	        String completionValue = getCompletionContextValue(key, completionCtx);
	        if (completionValue != null)
	        {
	            return completionValue;
	        }
	    }
	    
	    return switch (  key ) {
            case CURRENT_PROJECT_NAME -> safeGetString( () -> editorService.getCurrentlyOpenedFile().map( IFile::getProject ).map(IProject::getName).orElse( "" ) );
	        case CURRENT_FILE_PATH -> safeGetString( () -> editorService.getCurrentlyOpenedFile().map( IFile::getProjectRelativePath ).map(IPath::toString).orElse( "" ) );
            case CURRENT_FILE_NAME -> safeGetString( () -> editorService.getCurrentlyOpenedFile().map( IFile::getName ).orElse( "" ) );
	        case CURRENT_FILE_CONTENT -> safeGetString(() -> editorService.getCurrentlyOpenedFileContent() );
	        case SELECTED_CONTENT -> safeGetString(() -> editorService.getEditorSelection() );
	        case ERRORS -> safeGetString( () -> codeAnalysisService.getCompilationErrors( getContextValue(CURRENT_PROJECT_NAME), "ERROR", -1 ) );
	        case CONSOLE_OUTPUT -> safeGetString( () -> consoleService.getConsoleOutput( null, 100, true) ) ;
            case GIT_DIFF -> safeGetString( () -> gitService.getCurrentDiff() ) ;
            // Completion-specific keys return empty when no completion context
            case FILE_EXTENSION, CODE_BEFORE_CURSOR, CODE_AFTER_CURSOR, CURSOR_LINE, CURSOR_COLUMN -> "";
	        default -> {
                logger.warn("Unknown context key: " + key);
                yield "";
	        }
	    };
	}
	
	/**
	 * Gets a context value from the completion context.
	 * 
	 * @param key The context key
	 * @param ctx The completion context
	 * @return The value, or null if this key is not completion-specific
	 */
	private String getCompletionContextValue(String key, CompletionContext ctx)
	{
	    return switch (key) {
	        case CURRENT_FILE_NAME -> ctx.fileName();
	        case CURRENT_PROJECT_NAME -> ctx.projectName();
	        case FILE_EXTENSION -> ctx.fileExtension();
	        case CODE_BEFORE_CURSOR -> ctx.codeBeforeCursor();
	        case CODE_AFTER_CURSOR -> ctx.codeAfterCursor();
	        case CURSOR_LINE -> String.valueOf(ctx.cursorLine());
	        case CURSOR_COLUMN -> String.valueOf(ctx.cursorColumn());
	        default -> null; // Not a completion-specific key
	    };
	}
	
    /**
     * Safely executes a supplier function and handles any exceptions.
     * 
     * @param supplier The function that provides the context value
     * @return The context value, or empty string if an error occurs
     */
	private String safeGetString( Callable<String> supplier )
	{
	    try
	    {
	        return uiSync.syncCall( supplier );
	    }
	    catch ( Exception e )
	    {
	        logger.error( e.getMessage(), e );
	        return "";
	    }
	}
	
	
}
