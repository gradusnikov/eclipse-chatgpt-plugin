
package com.github.gradusnikov.eclipse.assistai.mcp.services;

import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.runtime.Adapters;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.ILog;
import org.eclipse.e4.core.di.annotations.Creatable;
import org.eclipse.jface.text.BadLocationException;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.ITextSelection;
import org.eclipse.jface.viewers.ISelection;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IFileEditorInput;
import org.eclipse.ui.IWorkbench;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.texteditor.ITextEditor;

import com.github.gradusnikov.eclipse.assistai.mcp.results.Diagnostic;
import com.github.gradusnikov.eclipse.assistai.mcp.results.DiagnosticCode;
import com.github.gradusnikov.eclipse.assistai.resources.ContentRange;
import com.github.gradusnikov.eclipse.assistai.resources.ResourceDescriptor;
import com.github.gradusnikov.eclipse.assistai.resources.ResourceReadResult;
import com.github.gradusnikov.eclipse.assistai.resources.ResourceVersion;
import com.github.gradusnikov.eclipse.assistai.resources.SourceOrigin;
import com.github.gradusnikov.eclipse.assistai.services.AiIgnoreService;
import com.github.gradusnikov.eclipse.assistai.tools.ResourceUtilities;
import com.github.gradusnikov.eclipse.assistai.tools.UISynchronizeCallable;

import jakarta.inject.Inject;



/**
 * Service interface for editor-related operations including
 * retrieving the current file and selection.
 */
@Creatable
public class EditorService 
{
    @Inject
    ILog logger;
    
    @Inject
    UISynchronizeCallable uiSync;
    
    @Inject
    AiIgnoreService aiIgnoreService;
    
    public Optional<IFile> getCurrentlyOpenedFile()
    {
        return getActiveEditor().map( IEditorPart::getEditorInput )
                         .filter( editorInput -> editorInput instanceof IFileEditorInput )
                         .map( IFileEditorInput.class::cast )
                         .map( IFileEditorInput::getFile )
                         .filter( Predicate.not( aiIgnoreService::isExcluded ) );
    }
    
    
    
    /**
     * Reads the file the user is currently looking at.
     * <p>
     * The content is exact. It used to be wrapped in a {@code # Currently Opened File}
     * heading and a {@code === PROJECT: … FILE: … ===} banner with every line
     * zero-padded and numbered, and that decorated text was what went into the
     * resource cache - so the same file cached from a chat attachment and cached from
     * here had two different shapes. The cache stores exact content now; the banner
     * only repeated the uri, name and version the &lt;resources&gt; element already
     * carries as attributes.
     * <p>
     * Having no editor open is a state of the workbench, not a failure of this call,
     * so it comes back as a FAILED result carrying a code rather than as an exception
     * or a string beginning with "Error:".
     */
    public ResourceReadResult readCurrentlyOpenedFile()
    {
        return uiSync.syncCall( () -> {
            Optional<IFile> opened = getCurrentlyOpenedFile();
            if ( opened.isEmpty() )
            {
                return ResourceReadResult.failed( null, null, Diagnostic.fatal(
                        DiagnosticCode.RESOURCE_NOT_FOUND,
                        "No workspace file is open in the active editor, or the open file is excluded"
                                + " from AI processing by .aiignore." ) );
            }

            IFile file = opened.get();
            try
            {
                List<String> lines = ResourceUtilities.readFileLines( file );
                StringBuilder content = new StringBuilder();
                for ( String line : lines )
                {
                    content.append( line ).append( "\n" );
                }

                return new ResourceReadResult(
                        ResourceReadResult.ReadStatus.OK,
                        ResourceDescriptor.fromWorkspaceFile( file, "getCurrentlyOpenedFile" ).uri().toString(),
                        file.getProject().getName(),
                        file.getProjectRelativePath().toString(),
                        ResourceUtilities.getLanguageForFile( file ),
                        ResourceVersion.of( file ),
                        new ContentRange( 1, 1, Math.max( 1, lines.size() ), 1 ),
                        lines.size(),
                        content.toString(),
                        SourceOrigin.WORKSPACE_SOURCE,
                        false,
                        false,
                        List.of(),
                        Diagnostic.none() );
            }
            catch ( IOException | CoreException e )
            {
                logger.error( "Could not read the open editor's file", e );
                return ResourceReadResult.failed( file.getProject().getName(),
                        file.getProjectRelativePath().toString(),
                        Diagnostic.fatal( DiagnosticCode.INTERNAL_ERROR, "Error reading file: " + e.getMessage() ) );
            }
        });
    }

    /**
     * Reads what the user has selected in the active editor.
     * <p>
     * A selection is a range read of the open file, so it is the same
     * {@link ResourceReadResult} its sibling {@link #readCurrentlyOpenedFile()}
     * returns, with {@code returnedRange} carrying the start and end - line
     * <em>and</em> column, which the old rendering could not express at all.
     * <p>
     * That field replaces the arithmetic that produced two off-by-one errors at once:
     * 1-based line numbers were handed to a formatter whose contract was 0-based and
     * which then printed {@code i + 1}, so the excerpt was shifted by one line and
     * labelled with numbers shifted by one again - and a selection touching the file's
     * last line made {@code to == lines.size()} and threw
     * {@code IllegalArgumentException("Illegal line range")} instead of returning
     * anything.
     * <p>
     * Having no editor open is a state of the workbench, and having nothing selected is
     * an ordinary answer: the first is a FAILED result carrying a code, the second an
     * OK result whose {@code returnedRange} is zero-width and whose content is empty.
     * Neither is an exception, which is what the caret case used to be.
     */
    public ResourceReadResult readEditorSelection()
    {
        return uiSync.syncCall( () -> {
            Optional<ITextEditor> activeTextEditor = getActiveTextEditor();
            if ( activeTextEditor.isEmpty() )
            {
                return ResourceReadResult.failed( null, null, Diagnostic.fatal(
                        DiagnosticCode.RESOURCE_NOT_FOUND,
                        "No text editor is active, so there is no selection to read." ) );
            }

            Optional<IFile> opened = getCurrentlyOpenedFile();
            if ( opened.isEmpty() )
            {
                return ResourceReadResult.failed( null, null, Diagnostic.fatal(
                        DiagnosticCode.RESOURCE_NOT_FOUND,
                        "No workspace file is open in the active editor, or the open file is excluded"
                                + " from AI processing by .aiignore." ) );
            }

            ITextEditor textEditor = activeTextEditor.get();
            IFile file = opened.get();
            IDocument document = textEditor.getDocumentProvider().getDocument( textEditor.getEditorInput() );
            ISelection selection = textEditor.getSelectionProvider().getSelection();

            if ( document == null || !( selection instanceof ITextSelection textSelection ) )
            {
                return ResourceReadResult.failed( file.getProject().getName(),
                        file.getProjectRelativePath().toString(), Diagnostic.fatal(
                                DiagnosticCode.RESOURCE_NOT_ACCESSIBLE,
                                "The active editor holds no text document or no text selection." ) );
            }

            try
            {
                // Clamped rather than trusted: an editor with nothing selected reports
                // offset -1, and the caret case must produce a zero-width range rather
                // than a BadLocationException.
                int offset = Math.min( Math.max( 0, textSelection.getOffset() ), document.getLength() );
                int length = Math.max( 0, Math.min( textSelection.getLength(), document.getLength() - offset ) );

                ContentRange range = ContentRange.of( document, offset, length );
                String selectedText = document.get( offset, length );
                int totalLines = document.getNumberOfLines();
                boolean wholeFile = length == document.getLength();

                return new ResourceReadResult(
                        wholeFile ? ResourceReadResult.ReadStatus.OK : ResourceReadResult.ReadStatus.PARTIAL,
                        ResourceDescriptor.fromWorkspaceFile( file, "getEditorSelection" ).uri().toString(),
                        file.getProject().getName(),
                        file.getProjectRelativePath().toString(),
                        ResourceUtilities.getLanguageForFile( file ),
                        ResourceVersion.of( file ),
                        range,
                        totalLines,
                        selectedText,
                        SourceOrigin.WORKSPACE_SOURCE,
                        false,
                        // The selection came back whole. "truncated" means less was
                        // returned than was asked for, not "this is a subset of the
                        // file" - status = PARTIAL already says that.
                        false,
                        List.of(),
                        Diagnostic.none() );
            }
            catch ( BadLocationException e )
            {
                logger.error( "Could not resolve the editor selection", e );
                return ResourceReadResult.failed( file.getProject().getName(),
                        file.getProjectRelativePath().toString(), Diagnostic.fatal(
                                DiagnosticCode.INVALID_RANGE,
                                "The editor selection does not resolve against the document: " + e.getMessage() ) );
            }
        } );
    }
    
    
    public Optional<IEditorPart> getActiveEditor()
    {
        return Optional.ofNullable( PlatformUI.getWorkbench() )
                       .map( IWorkbench::getActiveWorkbenchWindow )
                       .map( IWorkbenchWindow::getActivePage)
                       .map( IWorkbenchPage::getActiveEditor);
    }
    
    /**
     * Gets the code before the cursor in the currently active editor.
     * 
     * @return The code before the cursor, or empty string if not available
     */
    public String getCodeBeforeCursor()
    {
        return getActiveTextEditor()
                .map(editor -> {
                    IDocument document = editor.getDocumentProvider().getDocument(editor.getEditorInput());
                    ISelection selection = editor.getSelectionProvider().getSelection();
                    
                    if (document != null && selection instanceof ITextSelection)
                    {
                        ITextSelection textSelection = (ITextSelection) selection;
                        int offset = textSelection.getOffset();
                        
                        try
                        {
                            return document.get(0, offset);
                        }
                        catch (Exception e)
                        {
                            logger.error("Error getting code before cursor", e);
                        }
                    }
                    return "";
                })
                .orElse("");
    }
    
    /**
     * Gets the code after the cursor in the currently active editor.
     * 
     * @return The code after the cursor, or empty string if not available
     */
    public String getCodeAfterCursor()
    {
        return getActiveTextEditor()
                .map(editor -> {
                    IDocument document = editor.getDocumentProvider().getDocument(editor.getEditorInput());
                    ISelection selection = editor.getSelectionProvider().getSelection();
                    
                    if (document != null && selection instanceof ITextSelection)
                    {
                        ITextSelection textSelection = (ITextSelection) selection;
                        int offset = textSelection.getOffset();
                        int length = document.getLength();
                        
                        try
                        {
                            return document.get(offset, length - offset);
                        }
                        catch (Exception e)
                        {
                            logger.error("Error getting code after cursor", e);
                        }
                    }
                    return "";
                })
                .orElse("");
    }
    
    /**
     * Gets the current cursor line number (1-based).
     * 
     * @return The line number as a string, or empty string if not available
     */
    public String getCursorLine()
    {
        return getActiveTextEditor()
                .map(editor -> {
                    IDocument document = editor.getDocumentProvider().getDocument(editor.getEditorInput());
                    ISelection selection = editor.getSelectionProvider().getSelection();
                    
                    if (document != null && selection instanceof ITextSelection)
                    {
                        ITextSelection textSelection = (ITextSelection) selection;
                        try
                        {
                            // getLine() returns 0-based line number, so add 1
                            int line = document.getLineOfOffset(textSelection.getOffset()) + 1;
                            return String.valueOf(line);
                        }
                        catch (Exception e)
                        {
                            logger.error("Error getting cursor line", e);
                        }
                    }
                    return "";
                })
                .orElse("");
    }
    
    /**
     * Gets the current cursor column number (1-based).
     * 
     * @return The column number as a string, or empty string if not available
     */
    public String getCursorColumn()
    {
        return getActiveTextEditor()
                .map(editor -> {
                    IDocument document = editor.getDocumentProvider().getDocument(editor.getEditorInput());
                    ISelection selection = editor.getSelectionProvider().getSelection();
                    
                    if (document != null && selection instanceof ITextSelection)
                    {
                        ITextSelection textSelection = (ITextSelection) selection;
                        try
                        {
                            int offset = textSelection.getOffset();
                            int lineOffset = document.getLineInformationOfOffset(offset).getOffset();
                            // Column is 1-based
                            int column = offset - lineOffset + 1;
                            return String.valueOf(column);
                        }
                        catch (Exception e)
                        {
                            logger.error("Error getting cursor column", e);
                        }
                    }
                    return "";
                })
                .orElse("");
    }
    
    /**
     * Gets the active text editor.
     * 
     * @return Optional containing the active ITextEditor, or empty if not available
     */
    private Optional<ITextEditor> getActiveTextEditor()
    {
        return getActiveEditor()
                .flatMap( editor -> Adapters.of( editor, ITextEditor.class ) );
    }

}
