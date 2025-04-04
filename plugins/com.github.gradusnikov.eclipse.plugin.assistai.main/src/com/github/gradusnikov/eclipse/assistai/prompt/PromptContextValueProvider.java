package com.github.gradusnikov.eclipse.assistai.prompt;

import java.util.Objects;
import java.util.concurrent.Callable;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.ILog;
import org.eclipse.core.runtime.IPath;
import org.eclipse.e4.core.di.annotations.Creatable;

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
	
	public String getContextValue( String key )
	{
	    Objects.requireNonNull( key );
	    
	    return switch (  key ) {
            case CURRENT_PROJECT_NAME -> safeGetString( () -> editorService.getCurrentlyOpenedFile().map( IFile::getProject ).map(IProject::getName).orElse( "" ) );
	        case CURRENT_FILE_PATH -> safeGetString( () -> editorService.getCurrentlyOpenedFile().map( IFile::getProjectRelativePath ).map(IPath::toString).orElse( "" ) );
            case CURRENT_FILE_NAME -> safeGetString( () -> editorService.getCurrentlyOpenedFile().map( IFile::getName ).orElse( "" ) );
	        case CURRENT_FILE_CONTENT -> safeGetString(() -> editorService.getCurrentlyOpenedFileContent() );
	        case SELECTED_CONTENT -> safeGetString(() -> editorService.getEditorSelection() );
	        case ERRORS -> safeGetString( () -> codeAnalysisService.getCompilationErrors( getContextValue(CURRENT_PROJECT_NAME), "ERROR", -1 ) );
	        case CONSOLE_OUTPUT -> safeGetString( () -> consoleService.getConsoleOutput( null, 100, true) ) ;
            case GIT_DIFF -> safeGetString( () -> gitService.getCurrentDiff() ) ;
	        default -> {
                logger.warn("Unknown context key: " + key);
                yield "";
	        }
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
