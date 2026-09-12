package com.github.gradusnikov.eclipse.assistai.mcp.services;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Set;
import java.util.Deque;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import org.apache.commons.lang3.exception.ExceptionUtils;
import org.eclipse.core.commands.ParameterizedCommand;
import org.eclipse.core.resources.IContainer;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IFileState;
import org.eclipse.core.resources.IFolder;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IWorkspaceRoot;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.ILog;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.e4.core.di.annotations.Creatable;
import org.eclipse.e4.ui.di.UISynchronize;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IField;
import org.eclipse.jdt.core.ILocalVariable;
import org.eclipse.jdt.core.IMethod;
import org.eclipse.jdt.core.ITypeParameter;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.IPackageFragment;
import org.eclipse.jdt.core.IPackageFragmentRoot;
import org.eclipse.jdt.core.ISourceRange;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.core.JavaModelException;
import org.eclipse.jdt.core.ToolFactory;
import org.eclipse.jdt.core.formatter.CodeFormatter;
import org.eclipse.jdt.core.manipulation.OrganizeImportsOperation;
import org.eclipse.jdt.core.manipulation.OrganizeImportsOperation.IChooseImportQuery;
import org.eclipse.jdt.core.refactoring.IJavaRefactorings;
import org.eclipse.jdt.core.refactoring.descriptors.MoveDescriptor;
import org.eclipse.jdt.core.refactoring.descriptors.RenameJavaElementDescriptor;
import org.eclipse.jdt.core.search.TypeNameMatch;
import org.eclipse.jface.bindings.Binding;
import org.eclipse.jface.bindings.keys.KeySequence;
import org.eclipse.jface.bindings.keys.KeyStroke;
import org.eclipse.jface.text.BadLocationException;
import org.eclipse.jface.text.Document;
import org.eclipse.jface.text.FindReplaceDocumentAdapter;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.TextUtilities;
import org.eclipse.ltk.core.refactoring.Change;
import org.eclipse.ltk.core.refactoring.CompositeChange;
import org.eclipse.swt.SWT;
import org.eclipse.ltk.core.refactoring.Refactoring;
import org.eclipse.ltk.core.refactoring.RefactoringContribution;
import org.eclipse.ltk.core.refactoring.RefactoringCore;
import org.eclipse.ltk.core.refactoring.RefactoringStatus;
import org.eclipse.text.edits.MalformedTreeException;
import org.eclipse.text.edits.TextEdit;
import org.eclipse.ui.IEditorInput;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IFileEditorInput;
import org.eclipse.ui.IWorkbench;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PartInitException;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.ide.IDE;
import org.eclipse.ui.handlers.IHandlerService;
import org.eclipse.ui.keys.IBindingService;
import org.eclipse.ui.part.FileEditorInput;
import org.eclipse.ui.texteditor.ITextEditor;
import org.eclipse.jface.text.BadLocationException;
import org.eclipse.jface.text.Document;
import org.eclipse.jface.text.FindReplaceDocumentAdapter;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.IRegion;
import org.eclipse.text.edits.MalformedTreeException;
import org.eclipse.text.edits.MultiTextEdit;
import org.eclipse.text.edits.ReplaceEdit;

import com.github.gradusnikov.eclipse.assistai.completion.CompletionContext;
import com.github.gradusnikov.eclipse.assistai.resources.ContentRange;
import com.github.gradusnikov.eclipse.assistai.mcp.results.Diagnostic;
import com.github.gradusnikov.eclipse.assistai.mcp.results.DiagnosticCode;
import com.github.gradusnikov.eclipse.assistai.mcp.results.LineDelimiterPreference;
import com.github.gradusnikov.eclipse.assistai.mcp.results.LineDelimiterPreference;
import com.github.gradusnikov.eclipse.assistai.resources.EditResult;
import com.github.gradusnikov.eclipse.assistai.resources.EditResult.AffectedResource;
import com.github.gradusnikov.eclipse.assistai.resources.EditResult.AppliedEdit;
import com.github.gradusnikov.eclipse.assistai.resources.EditResult.ChangeKind;
import com.github.gradusnikov.eclipse.assistai.resources.EditResult.EditStatus;
import com.github.gradusnikov.eclipse.assistai.resources.EditResult.EditorPosition;
import com.github.gradusnikov.eclipse.assistai.resources.EditResult.EditorReveal;
import com.github.gradusnikov.eclipse.assistai.resources.EditResult.WorkspaceSync;

import com.github.gradusnikov.eclipse.assistai.resources.Occurrence;
import com.github.gradusnikov.eclipse.assistai.resources.ResourceCache;
import com.github.gradusnikov.eclipse.assistai.resources.ResourceVersion;
import com.github.gradusnikov.eclipse.assistai.resources.TextEditRequest;
import com.github.gradusnikov.eclipse.assistai.tools.UnifiedDiffs;
import com.github.gradusnikov.eclipse.assistai.services.AiIgnoreService;
import com.github.gradusnikov.eclipse.assistai.tools.ResourceUtilities;

import jakarta.inject.Inject;

@Creatable
public class CodeEditingService
{
    @Inject
    ILog                logger;

    @Inject
    UISynchronize       sync;

    @Inject
    CodeAnalysisService codeAnalysisService;

    @Inject
    AiIgnoreService     aiIgnoreService;

    @Inject
    ResourceCache       resourceCache;

    /**
     * Creates a folder and any missing folders above it.
     * <p>
     * A folder has no content, so this cannot go through {@link #applyTextEdits}: it
     * keeps its own mechanism and returns an {@link EditResult} only so a caller
     * branches on one shape. Idempotent - a folder that already exists is reported
     * with {@code versionBefore} equal to {@code versionAfter}, which says nothing
     * moved.
     */
    public EditResult createDirectories( String projectName, String directoryPath )
    {
        Objects.requireNonNull( projectName );
        Objects.requireNonNull( directoryPath );

        if ( projectName.isEmpty() )
        {
            throw new IllegalArgumentException( "Error: Project name cannot be empty." );
        }
        if ( directoryPath.isEmpty() )
        {
            throw new IllegalArgumentException( "Error: Directory path cannot be empty." );
        }

        try
        {
            IWorkspaceRoot root = ResourcesPlugin.getWorkspace().getRoot();
            IProject project = root.getProject( projectName );

            if ( !project.exists() )
            {
                throw new RuntimeException( "Error: Project '" + projectName + "' does not exist." );
            }
            if ( !project.isOpen() )
            {
                project.open( null );
            }

            String normalizedPath = directoryPath;
            while ( normalizedPath.startsWith( "/" ) || normalizedPath.startsWith( "\\" ) )
            {
                normalizedPath = normalizedPath.substring( 1 );
            }
            if ( normalizedPath.isEmpty() )
            {
                throw new RuntimeException( "Error: Invalid directory path. Path cannot be empty after normalization." );
            }

            IFolder folder = project.getFolder( normalizedPath );
            if ( folder.exists() )
            {
                // Nothing changed, so nothing is affected: an empty list is what says so.
                ResourceVersion current = ResourceVersion.of( folder );
                return resourceRelocated( folder, current, List.of(), EditorReveal.none(),
                        new WorkspaceSync( true, false, "not-applicable" ), Diagnostic.none() );
            }

            ResourceUtilities.createFolderHierarchy( folder );
            folder.getParent().refreshLocal( IResource.DEPTH_INFINITE, null );

            return resourceRelocated( folder, ResourceVersion.UNKNOWN,
                    List.of( AffectedResource.of( folder, ChangeKind.CREATED ) ), EditorReveal.none(),
                    new WorkspaceSync( true, false, "not-applicable" ), Diagnostic.none() );
        }
        catch ( CoreException e )
        {
            throw new RuntimeException( e );
        }
    }


    /**
     * Restores a file from the newest state in Eclipse's local history.
     * <p>
     * Undo is a text change like any other, so the restored content is written
     * through {@link #applyTextEdits} as one minimal replacement: it produces a diff
     * of what was rolled back and reveals it, rather than only saying that it
     * happened. Local history rather than an in-memory stack, so this still works
     * after a restart and is the same state the user sees under Compare With &gt;
     * Local History.
     * <p>
     * No staleness check: undoing means "restore whatever the newest stored state
     * is", which is precisely a caller not claiming to know the current content.
     */
    public EditResult undoEdit( String projectName, String filePath )
    {
        Objects.requireNonNull( projectName );
        Objects.requireNonNull( filePath );

        IFile file = resolveEditableFile( projectName, filePath );
        ResourceVersion before = ResourceVersion.of( file );

        try
        {
            IFileState[] history = file.getHistory( null );
            if ( history == null || history.length == 0 )
            {
                return EditResult.rejected( file, before, Diagnostic.fatal(
                        DiagnosticCode.HISTORY_UNAVAILABLE,
                        "No local history is stored for '" + filePath + "', so there is nothing to undo." ) );
            }

            Charset charset = Charset.forName( file.getCharset() );
            String restored = new String( ResourceUtilities.readInputStream( history[0].getContents() ), charset );
            String current = ResourceUtilities.readFileContent( file );

            TextEditRequest edit = minimalReplacement( new Document( current ), current, restored );

            return applyTextEdits( projectName, filePath, IResource.NULL_STAMP, List.of( edit ), false, true );
        }
        catch ( CoreException | IOException | BadLocationException e )
        {
            return internalFailure( file, e );
        }
    }


    /**
     * Inserts content before a line of an existing file.
     * <p>
     * An insertion is a replacement of an empty range at the start of {@code atLine},
     * so it goes through {@link #applyTextEdits} like every other text change.
     *
     * @param atLine the 1-based line to insert before; one past the last line appends
     * @param expectedModificationStamp the stamp the caller read, or
     *            {@link IResource#NULL_STAMP} to skip the staleness check
     * @param preview when true, reports what would change without writing
     */
    public EditResult insertIntoFile( String projectName, String filePath, String content, int atLine,
                                      long expectedModificationStamp, boolean preview )
    {
        Objects.requireNonNull( projectName );
        Objects.requireNonNull( filePath );

        IFile file = resolveEditableFile( projectName, filePath );

        try
        {
            IDocument document = new Document( ResourceUtilities.readFileContent( file ) );
            int lineCount = contentLineCount( document );
            if ( atLine < 1 || atLine > lineCount + 1 )
            {
                return invalidRange( file, "Line " + atLine + " is outside " + filePath
                        + ", which has " + lineCount + " line(s). Insert before line 1 to " + ( lineCount + 1 )
                        + ", where " + ( lineCount + 1 ) + " appends." );
            }

            // Inserted text becomes whole lines of the file, so it has to end like one.
            String inserted = content == null ? "" : content;
            if ( !inserted.endsWith( "\n" ) )
            {
                inserted = inserted + "\n";
            }

            int offset = lineStartOffset( document, atLine );
            TextEditRequest edit = new TextEditRequest( ContentRange.of( document, offset, 0 ), "", inserted );

            return applyTextEdits( projectName, filePath, expectedModificationStamp, List.of( edit ), preview );
        }
        catch ( CoreException | IOException | BadLocationException e )
        {
            return internalFailure( file, e );
        }
    }


    /**
     * Does the actual work of refreshing an editor.
     */
    private void refreshEditor( IFile file )
    {
        try
        {
            file.getParent().refreshLocal( IResource.DEPTH_ONE, null );

            Optional.ofNullable( PlatformUI.getWorkbench() ).map( IWorkbench::getActiveWorkbenchWindow ).map( IWorkbenchWindow::getActivePage )
                    .ifPresent( page -> {
                        // Try to find an editor for this file
                        Arrays.stream( page.getEditorReferences() ).map( ref -> ref.getEditor( false ) ).filter( Objects::nonNull ).filter( editor -> {
                            IEditorInput input = editor.getEditorInput();
                            return input instanceof IFileEditorInput && file.equals( ( (IFileEditorInput) input ).getFile() );
                        } ).findFirst().ifPresent( editor -> {
                            try
                            {
                                // Found the editor, now refresh it
                                IEditorInput input = editor.getEditorInput();
                                if ( editor instanceof ITextEditor )
                                {
                                    ( (ITextEditor) editor ).getDocumentProvider().resetDocument( input );
                                }
                            }
                            catch ( Exception e )
                            {
                                throw new RuntimeException( e );
                            }
                        } );
                    } );
        }
        catch ( Exception e )
        {
            logger.error( "Error refreshing editor: " + e.getMessage() );
        }
    }

    /** What an edit produced: the resulting version and how the IDE caught up. */
    record EditSynchronization( ResourceVersion version, WorkspaceSync workspaceState )
    {
    }

    /**
     * Completes an edit and reports the resulting resource version.
     *
     * @param undoState the local-history state this edit displaced, or null when the
     *            edit created the file or history was unavailable
     */
    private EditSynchronization synchronizeAfterEdit( IFile file, int revealLine, IFileState undoState ) throws CoreException
    {
        WorkspaceSync workspaceState = synchronizeAfterEditInternal( file, revealLine );
        // Captured after the barrier, so it reflects the state the edit left behind.
        return new EditSynchronization( ResourceVersion.of( file ), workspaceState );
    }

    private WorkspaceSync synchronizeAfterEditInternal( IFile file, int revealLine ) throws CoreException
    {
        file.refreshLocal( IResource.DEPTH_ZERO, null );
        ResourcesPlugin.getWorkspace().checkpoint( false );

        String jdtState = "not-applicable";
        IJavaElement javaElement = JavaCore.create( file );
        if ( javaElement instanceof ICompilationUnit compilationUnit )
        {
            compilationUnit.makeConsistent( new NullProgressMonitor() );
            jdtState = Boolean.toString( compilationUnit.isConsistent() );
        }

        boolean cached = resourceCache.get( file ).isPresent();
        resourceCache.resourceChanged( file.getFullPath() );

        sync.syncExec( () -> {
            safeOpenEditor( file );
            refreshEditor( file );
            revealLineInEditor( file, revealLine );
        } );

        return new WorkspaceSync( file.isSynchronized( IResource.DEPTH_ZERO ), cached, jdtState );
    }

    /**
     * * Generates a diff between proposed code and an existing file in the
     * project.
     * 
     * @param projectName
     *            The name of the project containing the file
     * @param filePath
     *            The path to the file relative to the project root
     * @param proposedCode
     *            The new/updated code being proposed
     * @param contextLines
     *            Number of context lines to include in the diff
     * @return A formatted string containing the diff and a summary of changes
     */
    public String generateCodeDiff( String projectName, String filePath, String proposedCode, Integer contextLines )
    {
        Objects.requireNonNull( projectName );
        Objects.requireNonNull( filePath );

        if ( projectName.isEmpty() )
        {
            throw new IllegalArgumentException( "Error: Project name cannot be empty." );
        }
        if ( filePath.isEmpty() )
        {
            throw new IllegalArgumentException( "Error: File path cannot be empty." );
        }

        if ( contextLines == null || contextLines < 0 )
        {
            contextLines = 3; // Default context lines
        }
        try
        {
            // Get the project
            IProject project = ResourcesPlugin.getWorkspace().getRoot().getProject( projectName );
            if ( !project.exists() )
            {
                throw new RuntimeException( "Error: Project '" + projectName + "' not found." );
            }

            if ( !project.isOpen() )
            {
                throw new RuntimeException( "Error: Project '" + projectName + "' is closed." );
            }

            // Get the file
            IResource resource = project.findMember( filePath );
            if ( resource == null || !resource.exists() )
            {
                throw new RuntimeException( "Error: File '" + filePath + "' not found in project '" + projectName + "'." );
            }

            // Check if the resource is a file
            if ( ! ( resource instanceof IFile ) )
            {
                throw new RuntimeException( "Error: Resource '" + filePath + "' is not a file." );
            }

            IFile file = (IFile) resource;

            // Try to refresh the editor if the file is open
            sync.syncExec( () -> {
                safeOpenEditor( file );
                refreshEditor( file );
            } );

            // Read the original file content
            String originalContent = ResourceUtilities.readFileContent( file );

            // JGit does the diffing; UnifiedDiffs owns the formatter setup and the
            // convention that identical sides produce an empty string.
            return UnifiedDiffs.diff( originalContent, "/" + filePath, proposedCode, "/" + filePath, contextLines );
        }
        catch ( Exception e )
        {
            logger.error( e.getMessage(), e );
            throw new RuntimeException( "Error generating diff: " + ExceptionUtils.getRootCauseMessage( e ) );
        }
    }

    /**
     * Formats the given code string according to the current Eclipse formatter
     * settings. This is equivalent to pressing Ctrl+Shift+F in the Eclipse
     * editor.
     * 
     * @param code
     *            The unformatted code string
     * @param projectName
     *            Optional project name to use project-specific formatter
     *            settings
     * @return The formatted code string
     */
    public String formatCode( String code, String projectName )
    {
        Objects.requireNonNull( code, "Code cannot be null" );
        try
        {
            // Get formatting options - first try project-specific settings if
            // project is
            // provided
            Map<String, String> options;

            if ( projectName != null && !projectName.isEmpty() )
            {
                IProject project = ResourcesPlugin.getWorkspace().getRoot().getProject( projectName );
                if ( project.exists() && project.isOpen() )
                {
                    IJavaProject javaProject = JavaCore.create( project );
                    options = javaProject.getOptions( true );
                }
                else
                {
                    // Fall back to workspace defaults if project doesn't exist
                    // or is closed
                    options = JavaCore.getOptions();
                }
            }
            else
            {
                // Use workspace defaults
                options = JavaCore.getOptions();
            }

            // Create formatter with the options
            CodeFormatter formatter = ToolFactory.createCodeFormatter( options );

            // Format the code
            TextEdit textEdit = formatter.format( CodeFormatter.K_COMPILATION_UNIT | CodeFormatter.F_INCLUDE_COMMENTS, code, 0, code.length(), 0, lineDelimiterFor( code, projectName ) );

            if ( textEdit == null )
            {
                // If formatting failed, return the original code
                logger.warn( "Code formatting failed - returning unformatted code" );
                return code;
            }

            // Apply the formatting changes
            IDocument document = new Document( code );
            textEdit.apply( document );

            // Return the formatted code
            return document.get();
        }
        catch ( MalformedTreeException | BadLocationException e )
        {
            logger.error( "Error during code formatting: " + e.getMessage(), e );
            throw new RuntimeException( "Error formatting code: " + e.getMessage(), e );
        }
    }

    /** The delimiter the code already uses, or the configured one for code without a line break. */
    private String lineDelimiterFor( String code, String projectName )
    {
        String used = TextUtilities.determineLineDelimiter( code, null );
        return used != null ? used : getLineDelimiterPreference( projectName ).delimiter();
    }

    /**
     * Formats a whole file.
     * <p>
     * A Java file is formatted by JDT here and the result written through
     * {@link #applyTextEdits} as one minimal replacement, so it takes the same write
     * path as any other edit and the diff shows exactly what the formatter touched.
     * <p>
     * Any other file type is formatted by the formatter its registered editor
     * contributes, which performs and saves the change itself through the editor's
     * own command. There is no text edit to route, so that path keeps its mechanism
     * and only reports the outcome in the same shape.
     */
    public EditResult formatFile( String projectName, String filePath )
    {
        Objects.requireNonNull( projectName );
        Objects.requireNonNull( filePath );

        IFile file = resolveEditableFile( projectName, filePath );
        ResourceVersion before = ResourceVersion.of( file );

        try
        {
            String originalContent = ResourceUtilities.readFileContent( file );

            if ( "java".equalsIgnoreCase( file.getFileExtension() ) )
            {
                String formatted = formatCode( originalContent, projectName );
                TextEditRequest edit = minimalReplacement( new Document( originalContent ), originalContent, formatted );
                return applyTextEdits( projectName, filePath, IResource.NULL_STAMP, List.of( edit ), false, true );
            }

            formatUsingRegisteredEditor( file );
            String formatted = ResourceUtilities.readFileContent( file );

            EditSynchronization synchronization = synchronizeAfterEdit( file, 1, currentHistoryState( file ) );
            List<Diagnostic> diagnostics = new ArrayList<>();

            // The editor already wrote; this only describes what it did, so that a
            // caller reads the same fields whichever formatter ran.
            TextEditRequest describedAs = minimalReplacement( new Document( originalContent ), originalContent, formatted );
            List<AppliedEdit> applied = formatted.equals( originalContent )
                    ? List.of()
                    : List.of( new AppliedEdit( describedAs.range(),
                            ContentRange.wholeDocument( new Document( formatted ) ),
                            describedAs.replacement().length(),
                            describedAs.expectedText().length() ) );

            return new EditResult(
                    diagnostics.isEmpty() ? EditStatus.APPLIED : EditStatus.APPLIED_WITH_WARNINGS,
                    file.getProject().getName(),
                    file.getProjectRelativePath().toString(),
                    before,
                    synchronization.version(),
                    applied,
                    UnifiedDiffs.diff( originalContent, filePath, formatted, filePath, UnifiedDiffs.DEFAULT_CONTEXT_LINES ),
                    applied.isEmpty() ? List.of() : List.of( AffectedResource.of( file, ChangeKind.MODIFIED ) ),
                    describeReveal( file, new ContentRange( 1, 1, 1, 1 ), diagnostics ),
                    EditResult.NO_UNDO_STATE,
                    synchronization.workspaceState(),
                    diagnostics );
        }
        catch ( Exception e )
        {
            logger.error( e.getMessage(), e );
            return EditResult.rejected( file, before, Diagnostic.fatal(
                    DiagnosticCode.FORMATTER_FAILED, ExceptionUtils.getRootCauseMessage( e ) ) );
        }
    }


    /**
     * Invokes the active editor's context-sensitive Format action and saves the
     * result. This resolves the same platform binding used by Ctrl/Cmd+Shift+F
     * so any installed editor can supply the formatter.
     */
    protected String formatUsingRegisteredEditor( IFile file ) throws Exception
    {
        AtomicReference<String> formatterCommand = new AtomicReference<>();
        AtomicReference<Exception> failure = new AtomicReference<>();

        sync.syncExec( () -> {
            try
            {
                if ( !PlatformUI.isWorkbenchRunning() )
                {
                    throw new IllegalStateException( "The Eclipse workbench is not running." );
                }

                IWorkbenchWindow window = PlatformUI.getWorkbench().getActiveWorkbenchWindow();
                if ( window == null || window.getActivePage() == null )
                {
                    throw new IllegalStateException( "No active Eclipse workbench window is available." );
                }

                IWorkbenchPage page = window.getActivePage();
                IEditorPart editor = IDE.openEditor( page, file );
                page.activate( editor );
                editor.setFocus();
                ITextEditor textEditor = editor instanceof ITextEditor directEditor ? directEditor : editor.getAdapter( ITextEditor.class );
                if ( textEditor != null )
                {
                    textEditor.selectAndReveal( 0, 0 );
                }

                IBindingService bindingService = editor.getSite().getService( IBindingService.class );
                KeySequence formatKeys = KeySequence.getInstance( KeyStroke.getInstance( SWT.MOD1 | SWT.SHIFT, 'F' ) );
                Binding binding = bindingService == null ? null : bindingService.getPerfectMatch( formatKeys );
                ParameterizedCommand command = binding == null ? null : binding.getParameterizedCommand();
                if ( !isFormatCommand( command ) )
                {
                    throw new IllegalStateException( "The editor for '" + file.getName() + "' does not contribute a Format command." );
                }

                IHandlerService handlerService = editor.getSite().getService( IHandlerService.class );
                if ( handlerService == null )
                {
                    throw new IllegalStateException( "The editor for '" + file.getName() + "' does not provide a command handler." );
                }

                handlerService.executeCommand( command, null );
                editor.doSave( new NullProgressMonitor() );
                formatterCommand.set( command.getId() );
            }
            catch ( Exception e )
            {
                failure.set( e );
            }
        } );

        if ( failure.get() != null )
        {
            throw failure.get();
        }
        file.refreshLocal( IResource.DEPTH_ZERO, null );
        return formatterCommand.get();
    }

    static boolean isFormatCommand( ParameterizedCommand command )
    {
        if ( command == null )
        {
            return false;
        }
        if ( command.getId().toLowerCase( Locale.ROOT ).contains( "format" ) )
        {
            return true;
        }
        try
        {
            return command.getName().toLowerCase( Locale.ROOT ).contains( "format" );
        }
        catch ( Exception e )
        {
            return false;
        }
    }

    /**
     * Creates a file, with any missing parent folders, and opens it.
     * <p>
     * A creation cannot go through {@link #applyTextEdits}: there is no resource yet
     * to check a stamp against and no document to place a range in. It keeps its own
     * mechanism - {@link IFile#create} - and returns an {@link EditResult} only so
     * that a caller branches on one shape whatever it asked for. The whole content is
     * reported as a single inserting edit, and {@code versionBefore} is
     * {@link ResourceVersion#UNKNOWN} because there was no version before.
     */
    public EditResult createFileAndOpen( String projectName, String filePath, String content )
    {
        Objects.requireNonNull( projectName );
        Objects.requireNonNull( filePath );

        if ( projectName.isEmpty() )
        {
            throw new IllegalArgumentException( "Error: Project name cannot be empty." );
        }
        if ( filePath.isEmpty() )
        {
            throw new IllegalArgumentException( "Error: File path cannot be empty." );
        }

        String created = content == null ? "" : content;

        try
        {
            IWorkspaceRoot root = ResourcesPlugin.getWorkspace().getRoot();
            IProject project = root.getProject( projectName );

            if ( !project.exists() )
            {
                throw new RuntimeException( "Error: Project '" + projectName + "' does not exist." );
            }
            if ( !project.isOpen() )
            {
                throw new RuntimeException( "Error: Project '" + projectName + "' is closed." );
            }

            String normalizedPath = filePath;
            while ( normalizedPath.startsWith( "/" ) || normalizedPath.startsWith( "\\" ) )
            {
                normalizedPath = normalizedPath.substring( 1 );
            }
            if ( normalizedPath.isEmpty() )
            {
                throw new RuntimeException( "Error: Invalid file path. Path cannot be empty after normalization." );
            }

            final IFile file = project.getFile( normalizedPath );
            if ( file.exists() )
            {
                throw new RuntimeException( "Error: File '" + normalizedPath + "' already exists in project '" + projectName + "'." );
            }

            IContainer parent = file.getParent();
            if ( parent instanceof IFolder && !parent.exists() )
            {
                ResourceUtilities.createFolderHierarchy( (IFolder) parent );
            }

            ByteArrayInputStream source = new ByteArrayInputStream(
                    created.getBytes( Charset.forName( project.getDefaultCharset() ) ) );
            file.create( source, true, null );

            aiIgnoreService.assertAccessAllowed( file );

            EditSynchronization synchronization = synchronizeAfterEdit( file, 1, null );
            ContentRange newRange = ContentRange.wholeDocument( new Document( created ) );

            List<Diagnostic> diagnostics = new ArrayList<>();
            EditorReveal reveal = describeReveal( file, newRange, diagnostics );

            return new EditResult(
                    diagnostics.isEmpty() ? EditStatus.APPLIED : EditStatus.APPLIED_WITH_WARNINGS,
                    file.getProject().getName(),
                    file.getProjectRelativePath().toString(),
                    ResourceVersion.UNKNOWN,
                    synchronization.version(),
                    List.of( new AppliedEdit( new ContentRange( 1, 1, 1, 1 ), newRange, created.length(), 0 ) ),
                    UnifiedDiffs.omitted( UnifiedDiffs.compare( "", created, 0 ), "the content is the one supplied" ),
                    List.of( AffectedResource.of( file, ChangeKind.CREATED ) ),
                    reveal,
                    EditResult.NO_UNDO_STATE,
                    synchronization.workspaceState(),
                    diagnostics );
        }
        catch ( CoreException | BadLocationException e )
        {
            throw new RuntimeException( e );
        }
    }


    /**
     * Safely opens a file in the editor, handling null cases, and brings the
     * editor into focus.
     * 
     * @param file
     *            The file to open
     */
    private void safeOpenEditor( IFile file )
    {
        Optional.ofNullable( PlatformUI.getWorkbench() ).map( IWorkbench::getActiveWorkbenchWindow ).map( IWorkbenchWindow::getActivePage ).ifPresent( page -> {
            try
            {
                // Open the editor and get the editor reference
                var editor = IDE.openEditor( page, file );
                // Set focus to the editor
                if ( editor != null )
                {
                    editor.setFocus();
                }
            }
            catch ( PartInitException e )
            {
                // Log but don't propagate
                logger.error( e.getMessage(), e );
            }
        } );
    }

    /**
     * Opens a file in the editor and scrolls to the specified line, placing the
     * cursor at the beginning of that line.
     * 
     * @param file
     *            The file to open
     * @param lineNumber
     *            The 1-based line number to reveal
     */
    private void revealLineInEditor( IFile file, int lineNumber )
    {
        Optional.ofNullable( PlatformUI.getWorkbench() ).map( IWorkbench::getActiveWorkbenchWindow ).map( IWorkbenchWindow::getActivePage ).ifPresent( page -> {
            try
            {
                var editor = IDE.openEditor( page, file );
                if ( editor instanceof ITextEditor )
                {
                    var textEditor = (ITextEditor) editor;
                    var provider = textEditor.getDocumentProvider();
                    var document = provider.getDocument( textEditor.getEditorInput() );
                    if ( document != null && lineNumber > 0 )
                    {
                        // Convert 1-based line to 0-based for IDocument
                        int line = Math.min( lineNumber - 1, document.getNumberOfLines() - 1 );
                        int offset = document.getLineOffset( line );
                        textEditor.selectAndReveal( offset, 0 );
                    }
                    editor.setFocus();
                }
                else if ( editor != null )
                {
                    editor.setFocus();
                }
            }
            catch ( Exception e )
            {
                logger.error( "Error revealing line in editor: " + e.getMessage() );
            }
        } );
    }

    /**
     * Replaces a range of whole lines with new content.
     * <p>
     * The range becomes a single text edit applied by {@link #applyTextEdits}, so the
     * file is written once and the caller gets the same optional staleness check
     * every other editing tool has.
     *
     * @param startLine the first line to replace, 1-based and inclusive
     * @param endLine the last line to replace, 1-based and inclusive
     * @param expectedModificationStamp the stamp the caller read, or
     *            {@link IResource#NULL_STAMP} to skip the staleness check
     * @param preview when true, reports what would change without writing
     */
    public EditResult replaceLines( String projectName, String filePath, String replacementContent,
                                    int startLine, int endLine, long expectedModificationStamp, boolean preview )
    {
        return replaceLineRange( projectName, filePath, replacementContent, startLine, endLine,
                expectedModificationStamp, preview );
    }

    /**
     * The one implementation behind {@code replaceLines} and {@code deleteLinesInFile}:
     * both name a range of whole lines and hand it text to stand in their place.
     */
    private EditResult replaceLineRange( String projectName, String filePath, String replacementContent,
                                         int startLine, int endLine, long expectedModificationStamp, boolean preview )
    {
        Objects.requireNonNull( projectName );
        Objects.requireNonNull( filePath );

        String replacement = replacementContent == null ? "" : replacementContent;
        IFile file = resolveEditableFile( projectName, filePath );

        try
        {
            IDocument document = new Document( ResourceUtilities.readFileContent( file ) );
            EditResult rangeProblem = validateLineRange( file, filePath, startLine, endLine,
                    contentLineCount( document ) );
            if ( rangeProblem != null )
            {
                return rangeProblem;
            }

            // Replacement text stands in for whole lines, so it has to end like one.
            if ( !replacement.isEmpty() && !replacement.endsWith( "\n" ) )
            {
                replacement = replacement + "\n";
            }

            ContentRange range = wholeLineRange( document, startLine, endLine );
            IRegion region = range.toRegion( document );
            TextEditRequest edit = new TextEditRequest( range,
                    document.get( region.getOffset(), region.getLength() ), replacement );

            return applyTextEdits( projectName, filePath, expectedModificationStamp, List.of( edit ), preview );
        }
        catch ( CoreException | IOException | BadLocationException e )
        {
            return internalFailure( file, e );
        }
    }


    /**
     * Renames a file within its own directory.
     * <p>
     * A rename cannot go through {@link #applyTextEdits}: the content does not
     * change, its name does. It keeps {@link IResource#move} and returns an
     * {@link EditResult} only so a caller branches on one shape. {@code resource} in
     * the result is the renamed file, which is what the caller addresses next.
     * <p>
     * For Java types prefer {@code refactorRenameJavaType}: this does not update
     * references.
     */
    public EditResult renameFile( String projectName, String filePath, String newFileName )
    {
        Objects.requireNonNull( projectName );
        Objects.requireNonNull( filePath );
        Objects.requireNonNull( newFileName );

        if ( newFileName.isEmpty() )
        {
            throw new IllegalArgumentException( "Error: New file name cannot be empty." );
        }

        IFile file = resolveEditableFile( projectName, filePath );
        ResourceVersion before = ResourceVersion.of( file );

        try
        {
            IWorkspaceRoot root = ResourcesPlugin.getWorkspace().getRoot();
            IContainer parent = file.getParent();
            IPath newPath = parent.getFullPath().append( newFileName );

            IFile newFile = root.getFile( newPath );
            if ( newFile.exists() )
            {
                throw new RuntimeException( "Error: A file named '" + newFileName + "' already exists in the same directory." );
            }

            IPath previousPath = file.getFullPath();
            file.move( newPath, IResource.FORCE | IResource.KEEP_HISTORY, null );
            parent.refreshLocal( IResource.DEPTH_ONE, null );
            // Nothing may still be served from the cache under the old name.
            resourceCache.resourceChanged( previousPath );

            EditSynchronization synchronization = synchronizeAfterEdit( newFile, 1, null );
            List<Diagnostic> diagnostics = new ArrayList<>();
            EditorReveal reveal = describeReveal( newFile, new ContentRange( 1, 1, 1, 1 ), diagnostics );

            // The old name holds nothing now, which a caller that still has it needs
            // to be told, so it is listed beside the new one.
            return resourceRelocated( newFile, before,
                    List.of( AffectedResource.of( file, ChangeKind.DELETED ),
                            AffectedResource.of( newFile, ChangeKind.MOVED ) ),
                    reveal, synchronization.workspaceState(), diagnostics );
        }
        catch ( CoreException e )
        {
            throw new RuntimeException( e );
        }
    }


    /**
     * Extracts a nested Java type into a new top-level compilation unit using
     * Eclipse's Move Type to New File refactoring.
     *
     * @param projectName
     *            The name of the project containing the Java file
     * @param filePath
     *            The path to the Java file relative to the project root
     * @param nestedTypeName
     *            The nested type name relative to the compilation unit (for
     *            example, "Outer.Inner")
     * @return A status message indicating success or failure
     */
    public EditResult refactorExtractTypeToNewFile( String projectName, String filePath, String nestedTypeName )
    {
        Objects.requireNonNull( projectName );
        Objects.requireNonNull( filePath );
        Objects.requireNonNull( nestedTypeName );

        if ( projectName.isEmpty() )
        {
            throw new IllegalArgumentException( "Error: Project name cannot be empty." );
        }
        if ( filePath.isEmpty() || !filePath.endsWith( ".java" ) )
        {
            throw new IllegalArgumentException( "Error: File path must identify a Java file." );
        }
        if ( nestedTypeName.isEmpty() )
        {
            throw new IllegalArgumentException( "Error: Nested type name cannot be empty." );
        }

        try
        {
            IProject project = ResourcesPlugin.getWorkspace().getRoot().getProject( projectName );
            if ( !project.exists() )
            {
                throw new RuntimeException( "Error: Project '" + projectName + "' does not exist." );
            }
            if ( !project.isOpen() )
            {
                throw new RuntimeException( "Error: Project '" + projectName + "' is closed." );
            }
            if ( !JavaCore.create( project ).exists() )
            {
                throw new RuntimeException( "Error: Project '" + projectName + "' is not a Java project." );
            }

            IFile file = project.getFile( IPath.fromPath( Path.of( filePath ) ) );
            if ( !file.exists() )
            {
                throw new RuntimeException( "Error: File '" + filePath + "' does not exist in project '" + projectName + "'." );
            }

            IJavaElement javaElement = JavaCore.create( file );
            if ( ! ( javaElement instanceof ICompilationUnit compilationUnit ) )
            {
                throw new RuntimeException( "Error: Could not resolve Java compilation unit for file '" + filePath + "'." );
            }

            IType nestedType = findNestedType( compilationUnit, nestedTypeName );
            if ( nestedType == null )
            {
                throw new RuntimeException( "Error: Nested type '" + nestedTypeName + "' was not found in file '" + filePath + "'." );
            }

            sync.syncExec( () -> {
                IWorkbenchWindow window = PlatformUI.isWorkbenchRunning() ? PlatformUI.getWorkbench().getActiveWorkbenchWindow() : null;
                IWorkbenchPage page = window != null ? window.getActivePage() : null;
                if ( page != null )
                {
                    IEditorPart editor = page.findEditor( new FileEditorInput( file ) );
                    if ( editor != null )
                    {
                        page.closeEditor( editor, true );
                    }
                }
            } );

            Refactoring refactoring = createMoveTypeToNewFileRefactoring( nestedType );
            IProgressMonitor monitor = new NullProgressMonitor();
            RefactoringStatus status = refactoring.checkInitialConditions( monitor );
            EditResult precondition = refusedPrecondition( file, status );
            if ( precondition != null )
            {
                return precondition;
            }

            status = refactoring.checkFinalConditions( monitor );
            precondition = refusedPrecondition( file, status );
            if ( precondition != null )
            {
                return precondition;
            }

            Change change = refactoring.createChange( monitor );
            // Read the change set before performing it: afterwards the tree is the undo.
            PendingChanges pending = pendingChanges( change );
            change.perform( monitor );
            project.refreshLocal( IResource.DEPTH_INFINITE, monitor );

            IFile extractedFile = project.getFile( file.getProjectRelativePath().removeLastSegments( 1 ).append( nestedType.getElementName() + ".java" ) );
            // The refactoring rewrote the source file, created the new one, and updated
            // references across the workspace. It reports the extracted file - what the
            // caller addresses next - and all three kinds of change in
            // affectedResources, so the source file no longer has to be guessed at.
            resourceCache.resourceChanged( file.getFullPath() );
            EditSynchronization synchronization = synchronizeAfterEdit( extractedFile, 1, null );
            List<Diagnostic> diagnostics = new ArrayList<>();
            EditorReveal reveal = describeReveal( extractedFile, new ContentRange( 1, 1, 1, 1 ), diagnostics );

            return resourceRelocated( extractedFile, ResourceVersion.UNKNOWN,
                    affectedBy( pending, extractedFile, ChangeKind.CREATED ),
                    reveal, synchronization.workspaceState(), diagnostics );
        }
        catch ( CoreException | ReflectiveOperationException e )
        {
            throw new RuntimeException( "Error during extract type refactoring: " + ExceptionUtils.getRootCauseMessage( e ), e );
        }
    }

    private IType findNestedType( ICompilationUnit compilationUnit, String nestedTypeName ) throws JavaModelException
    {
        IType match = null;
        for ( IType type : compilationUnit.getAllTypes() )
        {
            if ( type.getDeclaringType() != null
                    && ( nestedTypeName.equals( type.getTypeQualifiedName( '.' ) ) || nestedTypeName.equals( type.getElementName() ) ) )
            {
                if ( match != null )
                {
                    throw new IllegalArgumentException(
                            "Error: Nested type name '" + nestedTypeName + "' is ambiguous. Use its qualified name, for example 'Outer.Inner'." );
                }
                match = type;
            }
        }
        return match;
    }

    private Refactoring createMoveTypeToNewFileRefactoring( IType nestedType ) throws ReflectiveOperationException
    {
        var jdtUiBundle = org.eclipse.core.runtime.Platform.getBundle( "org.eclipse.jdt.ui" );
        if ( jdtUiBundle == null )
        {
            throw new IllegalStateException( "Error: The Eclipse JDT UI bundle is not available." );
        }

        Class<?> preferencesClass = jdtUiBundle.loadClass( "org.eclipse.jdt.internal.ui.preferences.JavaPreferencesSettings" );
        Method getCodeGenerationSettings = preferencesClass.getDeclaredMethod( "getCodeGenerationSettings", IJavaProject.class );
        getCodeGenerationSettings.setAccessible( true );
        Object codeGenerationSettings = getCodeGenerationSettings.invoke( null, nestedType.getJavaProject() );

        Class<?> refactoringClass = jdtUiBundle.loadClass( "org.eclipse.jdt.internal.corext.refactoring.structure.MoveInnerToTopRefactoring" );
        Constructor<?> constructor = refactoringClass.getDeclaredConstructor( IType.class, codeGenerationSettings.getClass() );
        constructor.setAccessible( true );
        Object refactoring = constructor.newInstance( nestedType, codeGenerationSettings );
        if ( refactoring instanceof Refactoring result )
        {
            return result;
        }

        throw new IllegalStateException( "Error: Eclipse did not create a Move Type to New File refactoring." );
    }

    /**
     * Renames a Java compilation unit (class/interface/enum) using Eclipse's
     * refactoring mechanism. This updates the class name, file name, and all
     * references throughout the project.
     * 
     * @param projectName
     *            The name of the project containing the Java file
     * @param filePath
     *            The path to the Java file relative to the project root
     * @param newTypeName
     *            The new name for the type (without .java extension)
     * @return A status message indicating success or failure
     */
    public EditResult refactorRenameJavaType( String projectName, String filePath, String newTypeName )
    {
        Objects.requireNonNull( projectName );
        Objects.requireNonNull( filePath );
        Objects.requireNonNull( newTypeName );

        if ( projectName.isEmpty() )
        {
            throw new IllegalArgumentException( "Error: Project name cannot be empty." );
        }
        if ( filePath.isEmpty() )
        {
            throw new IllegalArgumentException( "Error: File path cannot be empty." );
        }
        if ( newTypeName.isEmpty() )
        {
            throw new IllegalArgumentException( "Error: New type name cannot be empty." );
        }

        // Remove .java extension if provided
        if ( newTypeName.endsWith( ".java" ) )
        {
            newTypeName = newTypeName.substring( 0, newTypeName.length() - 5 );
        }

        final String finalNewTypeName = newTypeName;

        try
        {
            IWorkspaceRoot root = ResourcesPlugin.getWorkspace().getRoot();
            IProject project = root.getProject( projectName );

            if ( !project.exists() )
            {
                throw new RuntimeException( "Error: Project '" + projectName + "' does not exist." );
            }
            if ( !project.isOpen() )
            {
                throw new RuntimeException( "Error: Project '" + projectName + "' is closed." );
            }

            // Get the Java project
            IJavaProject javaProject = JavaCore.create( project );
            if ( javaProject == null || !javaProject.exists() )
            {
                throw new RuntimeException( "Error: Project '" + projectName + "' is not a Java project." );
            }

            IPath path = IPath.fromPath( Path.of( filePath ) );
            IFile file = project.getFile( path );

            if ( !file.exists() )
            {
                throw new RuntimeException( "Error: File '" + filePath + "' does not exist in project '" + projectName + "'." );
            }

            if ( !filePath.endsWith( ".java" ) )
            {
                throw new RuntimeException( "Error: File '" + filePath + "' is not a Java file. Use renameFile for non-Java files." );
            }

            // Get the compilation unit
            IJavaElement javaElement = JavaCore.create( file );
            if ( ! ( javaElement instanceof ICompilationUnit ) )
            {
                throw new RuntimeException( "Error: Could not resolve Java compilation unit for file '" + filePath + "'." );
            }

            ICompilationUnit compilationUnit = (ICompilationUnit) javaElement;

            // Get the primary type
            IType primaryType = compilationUnit.findPrimaryType();
            if ( primaryType == null )
            {
                throw new RuntimeException( "Error: Could not find primary type in file '" + filePath + "'." );
            }

            String oldTypeName = primaryType.getElementName();

            // Close the editor if the file is open (to avoid conflicts)
            sync.syncExec( () -> {
                // Off the UI thread - a test's inline UISynchronize - there is no active window.
                IWorkbenchWindow window = PlatformUI.getWorkbench().getActiveWorkbenchWindow();
                IWorkbenchPage page = window == null ? null : window.getActivePage();
                if ( page != null )
                {
                    IEditorPart editor = page.findEditor( new FileEditorInput( file ) );
                    if ( editor != null )
                    {
                        page.closeEditor( editor, true ); // save before closing
                    }
                }
            } );

            // Create the rename refactoring descriptor
            RefactoringContribution contribution = RefactoringCore.getRefactoringContribution( IJavaRefactorings.RENAME_TYPE );
            RenameJavaElementDescriptor descriptor = (RenameJavaElementDescriptor) contribution.createDescriptor();

            descriptor.setJavaElement( primaryType );
            descriptor.setNewName( finalNewTypeName );
            descriptor.setUpdateReferences( true );
            descriptor.setUpdateSimilarDeclarations( false );
            descriptor.setUpdateTextualOccurrences( false );

            // Create and validate the refactoring
            RefactoringStatus status = new RefactoringStatus();
            Refactoring refactoring = descriptor.createRefactoring( status );

            EditResult precondition = refusedPrecondition( file, status );
            if ( precondition != null )
            {
                return precondition;
            }

            IProgressMonitor monitor = new NullProgressMonitor();
            precondition = refusedPrecondition( file, refactoring.checkInitialConditions( monitor ) );
            if ( precondition != null )
            {
                return precondition;
            }

            precondition = refusedPrecondition( file, refactoring.checkFinalConditions( monitor ) );
            if ( precondition != null )
            {
                return precondition;
            }

            ResourceVersion before = ResourceVersion.of( file );

            // Perform the refactoring
            Change change = refactoring.createChange( monitor );
            // Read the change set before performing it: afterwards the tree is the undo.
            PendingChanges pending = pendingChanges( change );
            change.perform( monitor );

            // Refresh the project
            project.refreshLocal( IResource.DEPTH_INFINITE, monitor );

            // Build the new file path to open
            String newFilePath = filePath.replace( oldTypeName + ".java", finalNewTypeName + ".java" );
            IFile newFile = project.getFile( IPath.fromPath( Path.of( newFilePath ) ) );

            // The refactoring renamed the file and updated every reference to it. It
            // reports the renamed file - what the caller addresses next - and every
            // file whose references it rewrote in affectedResources.
            resourceCache.resourceChanged( file.getFullPath() );
            EditSynchronization synchronization = synchronizeAfterEdit( newFile, 1, null );
            List<Diagnostic> diagnostics = new ArrayList<>();
            EditorReveal reveal = describeReveal( newFile, new ContentRange( 1, 1, 1, 1 ), diagnostics );

            return resourceRelocated( newFile, before, affectedBy( pending, newFile, ChangeKind.MOVED ),
                    reveal, synchronization.workspaceState(), diagnostics );
        }
        catch ( CoreException e )
        {
            throw new RuntimeException( "Error during refactoring: " + ExceptionUtils.getRootCauseMessage( e ), e );
        }
    }

    /**
     * Renames whatever Java element sits at a position in a source file - a method,
     * field, enum constant, local variable, parameter, type parameter or type - using
     * the matching Eclipse rename refactoring, so every reference follows.
     * <p>
     * The element is selected the way the IDE selects it: the position may be on the
     * declaration or on any reference to it, in which case the declaration is what
     * gets renamed.
     *
     * @param projectName
     *            The name of the project containing the Java file
     * @param filePath
     *            The path to the Java file relative to the project root
     * @param line
     *            1-based line of the identifier
     * @param column
     *            1-based column of the identifier
     * @param newName
     *            The new name
     * @param expectedElementName
     *            Optional: the simple name the caller expects at that position. When
     *            it is not what is there, nothing is renamed
     * @param updateReferences
     *            Whether references are rewritten too
     * @param updateTextualOccurrences
     *            Whether occurrences in comments and strings are rewritten as well
     *            (types and fields only)
     * @param updateGettersAndSetters
     *            When a field is renamed, whether its getter and setter are renamed
     *            with it
     */
    public EditResult refactorRenameJavaElement( String projectName, String filePath, int line, int column, String newName,
                                                 String expectedElementName, boolean updateReferences,
                                                 boolean updateTextualOccurrences, boolean updateGettersAndSetters )
    {
        Objects.requireNonNull( projectName );
        Objects.requireNonNull( filePath );
        Objects.requireNonNull( newName );

        if ( projectName.isEmpty() )
        {
            throw new IllegalArgumentException( "Error: Project name cannot be empty." );
        }
        if ( filePath.isEmpty() || !filePath.endsWith( ".java" ) )
        {
            throw new IllegalArgumentException( "Error: File path must identify a Java file." );
        }
        if ( newName.isEmpty() )
        {
            throw new IllegalArgumentException( "Error: New name cannot be empty." );
        }
        if ( line < 1 || column < 1 )
        {
            throw new IllegalArgumentException( "Error: Line and column are 1-based." );
        }

        try
        {
            IProject project = ResourcesPlugin.getWorkspace().getRoot().getProject( projectName );
            if ( !project.exists() )
            {
                throw new RuntimeException( "Error: Project '" + projectName + "' does not exist." );
            }
            if ( !project.isOpen() )
            {
                throw new RuntimeException( "Error: Project '" + projectName + "' is closed." );
            }
            if ( !JavaCore.create( project ).exists() )
            {
                throw new RuntimeException( "Error: Project '" + projectName + "' is not a Java project." );
            }

            IFile file = project.getFile( IPath.fromPath( Path.of( filePath ) ) );
            if ( !file.exists() )
            {
                throw new RuntimeException( "Error: File '" + filePath + "' does not exist in project '" + projectName + "'." );
            }

            IJavaElement javaElement = JavaCore.create( file );
            if ( ! ( javaElement instanceof ICompilationUnit compilationUnit ) )
            {
                throw new RuntimeException( "Error: Could not resolve Java compilation unit for file '" + filePath + "'." );
            }

            // Select the element under the position the way the editor does.
            IDocument document = new Document( compilationUnit.getSource() );
            if ( line > document.getNumberOfLines() )
            {
                return EditResult.rejected( file, ResourceVersion.of( file ),
                        Diagnostic.fatal( DiagnosticCode.VALIDATION_ERROR,
                                "Line " + line + " is beyond the end of '" + filePath + "', which has "
                                        + document.getNumberOfLines() + " lines." ) );
            }
            int offset = document.getLineOffset( line - 1 ) + column - 1;
            IJavaElement[] selected = compilationUnit.codeSelect( offset, 0 );
            if ( selected.length == 0 )
            {
                return EditResult.rejected( file, ResourceVersion.of( file ),
                        Diagnostic.fatal( DiagnosticCode.VALIDATION_ERROR,
                                "There is no Java element at line " + line + ", column " + column + " of '" + filePath
                                        + "'. Put the position on the identifier itself." ) );
            }
            IJavaElement element = selected[0];
            String elementName = element.getElementName();

            if ( expectedElementName != null && !expectedElementName.isBlank() && !expectedElementName.equals( elementName ) )
            {
                return EditResult.rejected( file, ResourceVersion.of( file ),
                        Diagnostic.fatal( DiagnosticCode.VALIDATION_ERROR,
                                "The element at line " + line + ", column " + column + " is " + describeElement( element )
                                        + ", not '" + expectedElementName + "'. Nothing was renamed." ) );
            }
            if ( element.getAncestor( IJavaElement.COMPILATION_UNIT ) == null )
            {
                return EditResult.rejected( file, ResourceVersion.of( file ),
                        Diagnostic.fatal( DiagnosticCode.VALIDATION_ERROR,
                                describeElement( element ) + " is not defined in source and cannot be renamed." ) );
            }

            String refactoringId = renameRefactoringId( element );
            if ( refactoringId == null )
            {
                return EditResult.rejected( file, ResourceVersion.of( file ),
                        Diagnostic.fatal( DiagnosticCode.VALIDATION_ERROR,
                                describeElement( element ) + " is not something this tool renames. Use "
                                        + "refactorRenamePackage for packages." ) );
            }

            // Close the editor if the file is open (to avoid conflicts)
            sync.syncExec( () -> {
                // Off the UI thread - a test's inline UISynchronize - there is no active window.
                IWorkbenchWindow window = PlatformUI.getWorkbench().getActiveWorkbenchWindow();
                IWorkbenchPage page = window == null ? null : window.getActivePage();
                if ( page != null )
                {
                    IEditorPart editor = page.findEditor( new FileEditorInput( file ) );
                    if ( editor != null )
                    {
                        page.closeEditor( editor, true ); // save before closing
                    }
                }
            } );

            RefactoringContribution contribution = RefactoringCore.getRefactoringContribution( refactoringId );
            RenameJavaElementDescriptor descriptor = (RenameJavaElementDescriptor) contribution.createDescriptor();
            descriptor.setJavaElement( element );
            descriptor.setNewName( newName );
            descriptor.setUpdateReferences( updateReferences );
            if ( element instanceof IType )
            {
                descriptor.setUpdateSimilarDeclarations( false );
                descriptor.setUpdateTextualOccurrences( updateTextualOccurrences );
            }
            else if ( element instanceof IField field && !field.isEnumConstant() )
            {
                descriptor.setUpdateTextualOccurrences( updateTextualOccurrences );
                descriptor.setRenameGetters( updateGettersAndSetters );
                descriptor.setRenameSetters( updateGettersAndSetters );
            }

            RefactoringStatus status = new RefactoringStatus();
            Refactoring refactoring = descriptor.createRefactoring( status );

            EditResult precondition = refusedPrecondition( file, status );
            if ( precondition != null )
            {
                return precondition;
            }

            IProgressMonitor monitor = new NullProgressMonitor();
            precondition = refusedPrecondition( file, refactoring.checkInitialConditions( monitor ) );
            if ( precondition != null )
            {
                return precondition;
            }

            precondition = refusedPrecondition( file, refactoring.checkFinalConditions( monitor ) );
            if ( precondition != null )
            {
                return precondition;
            }

            ResourceVersion before = ResourceVersion.of( file );

            Change change = refactoring.createChange( monitor );
            // Read the change set before performing it: afterwards the tree is the undo.
            PendingChanges pending = pendingChanges( change );
            change.perform( monitor );
            project.refreshLocal( IResource.DEPTH_INFINITE, monitor );

            // Renaming the file's own top-level type renames the file too; every other
            // rename rewrites it in place. Either way the result is addressed to the file
            // as it stands now, with every rewritten file listed in affectedResources.
            IFile primary = file;
            ChangeKind primaryKind = ChangeKind.MODIFIED;
            if ( !file.exists() )
            {
                primary = ( (IContainer) file.getParent() ).getFile( IPath.fromOSString( newName + ".java" ) );
                primaryKind = ChangeKind.MOVED;
            }

            resourceCache.resourceChanged( file.getFullPath() );
            EditSynchronization synchronization = synchronizeAfterEdit( primary, line, null );
            List<Diagnostic> diagnostics = new ArrayList<>();
            EditorReveal reveal = describeReveal( primary, new ContentRange( line, 1, line, 1 ), diagnostics );

            return resourceRelocated( primary, before, affectedBy( pending, primary, primaryKind ),
                    reveal, synchronization.workspaceState(), diagnostics );
        }
        catch ( CoreException | BadLocationException e )
        {
            throw new RuntimeException( "Error during refactoring: " + ExceptionUtils.getRootCauseMessage( e ), e );
        }
    }

    /**
     * The rename refactoring that applies to an element, or null for one that has
     * none (a package, an import, an initializer).
     */
    private static String renameRefactoringId( IJavaElement element ) throws JavaModelException
    {
        if ( element instanceof IMethod )
        {
            return IJavaRefactorings.RENAME_METHOD;
        }
        if ( element instanceof IField field )
        {
            return field.isEnumConstant() ? IJavaRefactorings.RENAME_ENUM_CONSTANT : IJavaRefactorings.RENAME_FIELD;
        }
        if ( element instanceof ILocalVariable )
        {
            return IJavaRefactorings.RENAME_LOCAL_VARIABLE;
        }
        if ( element instanceof ITypeParameter )
        {
            return IJavaRefactorings.RENAME_TYPE_PARAMETER;
        }
        if ( element instanceof IType )
        {
            return IJavaRefactorings.RENAME_TYPE;
        }
        return null;
    }

    /**
     * An element as a message names it: its kind and name, plus the type that holds
     * it when there is one, so a wrong-position message says what was found there.
     */
    private static String describeElement( IJavaElement element )
    {
        String kind = switch ( element.getElementType() )
        {
            case IJavaElement.METHOD -> "the method";
            case IJavaElement.FIELD -> "the field";
            case IJavaElement.LOCAL_VARIABLE -> ( (ILocalVariable) element ).isParameter() ? "the parameter" : "the local variable";
            case IJavaElement.TYPE_PARAMETER -> "the type parameter";
            case IJavaElement.TYPE -> "the type";
            case IJavaElement.PACKAGE_FRAGMENT -> "the package";
            default -> "the element";
        };
        IJavaElement holder = element.getAncestor( IJavaElement.TYPE );
        if ( holder != null && holder != element )
        {
            return kind + " '" + element.getElementName() + "' in " + holder.getElementName();
        }
        return kind + " '" + element.getElementName() + "'";
    }

    /**
     * Moves a Java compilation unit to a different package using Eclipse's
     * refactoring mechanism. This updates the package declaration and all
     * references throughout the workspace.
     * 
     * @param projectName
     *            The name of the project containing the Java file
     * @param filePath
     *            The path to the Java file relative to the project root
     * @param targetPackage
     *            The fully qualified name of the target package (e.g.,
     *            "com.example.newpackage")
     * @param targetProjectName
     *            The project that should receive the file, or null to look for the
     *            package in this project and every project on its build path
     * @param targetSourceFolder
     *            The project-relative source folder that should receive the file, or
     *            null to use the one that already holds the package - or, for a package
     *            that exists nowhere yet, the file's own
     * @return The moved file and everything the refactoring rewrote, or a rejection
     *         naming the candidate folders when the package name alone is ambiguous
     */
    public EditResult refactorMoveJavaType( String projectName, String filePath, String targetPackage,
                                            String targetProjectName, String targetSourceFolder )
    {
        Objects.requireNonNull( projectName );
        Objects.requireNonNull( filePath );
        Objects.requireNonNull( targetPackage );

        if ( projectName.isEmpty() )
        {
            throw new IllegalArgumentException( "Error: Project name cannot be empty." );
        }
        if ( filePath.isEmpty() )
        {
            throw new IllegalArgumentException( "Error: File path cannot be empty." );
        }

        try
        {
            IWorkspaceRoot root = ResourcesPlugin.getWorkspace().getRoot();
            IProject project = root.getProject( projectName );

            if ( !project.exists() )
            {
                throw new RuntimeException( "Error: Project '" + projectName + "' does not exist." );
            }
            if ( !project.isOpen() )
            {
                throw new RuntimeException( "Error: Project '" + projectName + "' is closed." );
            }

            // Get the Java project
            IJavaProject javaProject = JavaCore.create( project );
            if ( javaProject == null || !javaProject.exists() )
            {
                throw new RuntimeException( "Error: Project '" + projectName + "' is not a Java project." );
            }

            IPath path = IPath.fromPath( Path.of( filePath ) );
            IFile file = project.getFile( path );

            if ( !file.exists() )
            {
                throw new RuntimeException( "Error: File '" + filePath + "' does not exist in project '" + projectName + "'." );
            }

            if ( !filePath.endsWith( ".java" ) )
            {
                throw new RuntimeException( "Error: File '" + filePath + "' is not a Java file." );
            }

            // Get the compilation unit
            IJavaElement javaElement = JavaCore.create( file );
            if ( ! ( javaElement instanceof ICompilationUnit ) )
            {
                throw new RuntimeException( "Error: Could not resolve Java compilation unit for file '" + filePath + "'." );
            }

            ICompilationUnit compilationUnit = (ICompilationUnit) javaElement;
            IType primaryType = compilationUnit.findPrimaryType();

            if ( primaryType == null )
            {
                throw new RuntimeException( "Error: Could not find primary type in file '" + filePath + "'." );
            }

            String typeName = primaryType.getElementName();



            // Where the file goes. The package may already live in another project on the
            // build path, or in several places, and a caller who said nothing more is told
            // about that choice rather than surprised by it.
            TargetPackage target = resolveTargetPackage( javaProject, compilationUnit, targetPackage,
                    targetProjectName, targetSourceFolder );
            if ( target.problem() != null )
            {
                return EditResult.rejected( file, ResourceVersion.of( file ), target.problem() );
            }
            IPackageFragment targetPackageFragment = target.fragment();

            // Close the editor if the file is open
            sync.syncExec( () -> {
                // Off the UI thread - a test's inline UISynchronize - there is no active window.
                IWorkbenchWindow window = PlatformUI.getWorkbench().getActiveWorkbenchWindow();
                IWorkbenchPage page = window == null ? null : window.getActivePage();
                if ( page != null )
                {
                    IEditorPart editor = page.findEditor( new FileEditorInput( file ) );
                    if ( editor != null )
                    {
                        page.closeEditor( editor, true );
                    }
                }
            } );

            // Create the move refactoring descriptor
            RefactoringContribution contribution = RefactoringCore.getRefactoringContribution( IJavaRefactorings.MOVE );
            MoveDescriptor descriptor = (MoveDescriptor) contribution.createDescriptor();

            descriptor.setDestination( targetPackageFragment );
            descriptor.setMoveResources( new IFile[0], new IFolder[0], new ICompilationUnit[] { compilationUnit } );
            descriptor.setUpdateReferences( true );
            descriptor.setUpdateQualifiedNames( false );

            // Create and validate the refactoring
            RefactoringStatus status = new RefactoringStatus();
            Refactoring refactoring = descriptor.createRefactoring( status );

            EditResult precondition = refusedPrecondition( file, status );
            if ( precondition != null )
            {
                return precondition;
            }

            IProgressMonitor monitor = new NullProgressMonitor();
            precondition = refusedPrecondition( file, refactoring.checkInitialConditions( monitor ) );
            if ( precondition != null )
            {
                return precondition;
            }

            precondition = refusedPrecondition( file, refactoring.checkFinalConditions( monitor ) );
            if ( precondition != null )
            {
                return precondition;
            }

            ResourceVersion before = ResourceVersion.of( file );

            // Perform the refactoring
            Change change = refactoring.createChange( monitor );
            // Read the change set before performing it: afterwards the tree is the undo.
            PendingChanges pending = pendingChanges( change );
            change.perform( monitor );

            // Refresh both ends: the destination may be another project.
            project.refreshLocal( IResource.DEPTH_INFINITE, monitor );
            IResource targetFolder = targetPackageFragment.getResource();
            if ( targetFolder != null && !targetFolder.getProject().equals( project ) )
            {
                targetFolder.getProject().refreshLocal( IResource.DEPTH_INFINITE, monitor );
            }

            // The moved file is wherever the target package's folder is, which is not
            // necessarily in this project, let alone under the file's old source folder.
            IFile newFile = ( (IContainer) targetFolder ).getFile( IPath.fromOSString( typeName + ".java" ) );

            // The refactoring moved the file and updated every reference to it. It
            // reports the moved file - what the caller addresses next - and every file
            // whose references it rewrote in affectedResources.
            resourceCache.resourceChanged( file.getFullPath() );
            EditSynchronization synchronization = synchronizeAfterEdit( newFile, 1, null );
            List<Diagnostic> diagnostics = new ArrayList<>();
            EditorReveal reveal = describeReveal( newFile, new ContentRange( 1, 1, 1, 1 ), diagnostics );

            return resourceRelocated( newFile, before, affectedBy( pending, newFile, ChangeKind.MOVED ),
                    reveal, synchronization.workspaceState(), diagnostics );
        }
        catch ( CoreException e )
        {
            throw new RuntimeException( "Error during refactoring: " + ExceptionUtils.getRootCauseMessage( e ), e );
        }
    }

    /**
     * Moves a type to a package, letting {@link #resolveTargetPackage} decide where that
     * package is - see the five-argument form for what that means.
     */
    public EditResult refactorMoveJavaType( String projectName, String filePath, String targetPackage )
    {
        return refactorMoveJavaType( projectName, filePath, targetPackage, null, null );
    }

    /**
     * Renames a Java package using Eclipse's refactoring mechanism. This
     * renames the package directory, updates all package declarations in
     * contained files, and updates all references throughout the workspace.
     * 
     * @param projectName
     *            The name of the project containing the package
     * @param packageName
     *            The current fully qualified package name (e.g.,
     *            "com.example.oldpackage")
     * @param newPackageName
     *            The new package name (can be just the last segment or full
     *            path)
     * @return A status message indicating success or failure
     */
    public EditResult refactorRenamePackage( String projectName, String packageName, String newPackageName )
    {
        Objects.requireNonNull( projectName );
        Objects.requireNonNull( packageName );
        Objects.requireNonNull( newPackageName );

        if ( projectName.isEmpty() )
        {
            throw new IllegalArgumentException( "Error: Project name cannot be empty." );
        }
        if ( packageName.isEmpty() )
        {
            throw new IllegalArgumentException( "Error: Package name cannot be empty." );
        }
        if ( newPackageName.isEmpty() )
        {
            throw new IllegalArgumentException( "Error: New package name cannot be empty." );
        }

        try
        {
            IWorkspaceRoot root = ResourcesPlugin.getWorkspace().getRoot();
            IProject project = root.getProject( projectName );

            if ( !project.exists() )
            {
                throw new RuntimeException( "Error: Project '" + projectName + "' does not exist." );
            }
            if ( !project.isOpen() )
            {
                throw new RuntimeException( "Error: Project '" + projectName + "' is closed." );
            }

            // Get the Java project
            IJavaProject javaProject = JavaCore.create( project );
            if ( javaProject == null || !javaProject.exists() )
            {
                throw new RuntimeException( "Error: Project '" + projectName + "' is not a Java project." );
            }

            // Find the package
            IPackageFragment packageFragment = findPackage( javaProject, packageName );
            if ( packageFragment == null )
            {
                throw new RuntimeException( "Error: Package '" + packageName + "' not found in project '" + projectName + "'." );
            }

            // Close all editors for files in this package
            sync.syncExec( () -> {
                try
                {
                    // Off the UI thread - a test's inline UISynchronize - there is no active window.
                    IWorkbenchWindow window = PlatformUI.getWorkbench().getActiveWorkbenchWindow();
                    IWorkbenchPage page = window == null ? null : window.getActivePage();
                    if ( page != null )
                    {
                        for ( ICompilationUnit cu : packageFragment.getCompilationUnits() )
                        {
                            IFile file = (IFile) cu.getResource();
                            IEditorPart editor = page.findEditor( new FileEditorInput( file ) );
                            if ( editor != null )
                            {
                                page.closeEditor( editor, true );
                            }
                        }
                    }
                }
                catch ( JavaModelException e )
                {
                    logger.error( "Error closing editors: " + e.getMessage() );
                }
            } );

            // Create the rename refactoring descriptor
            RefactoringContribution contribution = RefactoringCore.getRefactoringContribution( IJavaRefactorings.RENAME_PACKAGE );
            RenameJavaElementDescriptor descriptor = (RenameJavaElementDescriptor) contribution.createDescriptor();

            descriptor.setJavaElement( packageFragment );
            descriptor.setNewName( newPackageName );
            descriptor.setUpdateReferences( true );
            descriptor.setUpdateTextualOccurrences( false );
            descriptor.setUpdateHierarchy( true );

            // Create and validate the refactoring
            RefactoringStatus status = new RefactoringStatus();
            Refactoring refactoring = descriptor.createRefactoring( status );

            IResource packageResource = packageFragment.getResource();
            EditResult precondition = refusedPrecondition( packageResource, status );
            if ( precondition != null )
            {
                return precondition;
            }

            IProgressMonitor monitor = new NullProgressMonitor();
            precondition = refusedPrecondition( packageResource, refactoring.checkInitialConditions( monitor ) );
            if ( precondition != null )
            {
                return precondition;
            }

            precondition = refusedPrecondition( packageResource, refactoring.checkFinalConditions( monitor ) );
            if ( precondition != null )
            {
                return precondition;
            }

            ResourceVersion before = ResourceVersion.of( packageResource );

            // Perform the refactoring
            Change change = refactoring.createChange( monitor );
            // Read the change set before performing it: afterwards the tree is the undo.
            PendingChanges pending = pendingChanges( change );
            change.perform( monitor );

            // Refresh the project
            project.refreshLocal( IResource.DEPTH_INFINITE, monitor );

            // The refactoring rewrote every compilation unit in the package and every
            // reference to it. It reports the renamed package folder - the thing that
            // moved - and lists the files it rewrote, wherever they live, in
            // affectedResources.
            IPackageFragment renamedPackage = findPackage( javaProject, newPackageName );
            IResource renamed = renamedPackage != null ? renamedPackage.getResource() : null;
            if ( renamed == null )
            {
                return EditResult.rejected( project, before,
                        Diagnostic.fatal( DiagnosticCode.INTERNAL_ERROR,
                                "The refactoring reported success, but package '" + newPackageName
                                        + "' cannot be found in project '" + projectName + "'." ) );
            }

            List<Diagnostic> diagnostics = new ArrayList<>();
            EditorReveal reveal = EditorReveal.none();
            if ( renamedPackage.getCompilationUnits().length > 0
                    && renamedPackage.getCompilationUnits()[0].getResource() instanceof IFile renamedFile )
            {
                reveal = describeReveal( renamedFile, new ContentRange( 1, 1, 1, 1 ), diagnostics );
            }

            return resourceRelocated( renamed, before, affectedBy( pending, renamed, ChangeKind.MOVED ),
                    reveal, new WorkspaceSync( true, false, "not-applicable" ), diagnostics );
        }
        catch ( CoreException e )
        {
            throw new RuntimeException( "Error during refactoring: " + ExceptionUtils.getRootCauseMessage( e ), e );
        }
    }

    /**
     * Organizes imports in a Java file using Eclipse's organize imports
     * mechanism. This removes unused imports and sorts existing imports
     * according to project settings. It does not add missing imports.
     * 
     * @param projectName
     *            The name of the project containing the Java file
     * @param filePath
     *            The path to the Java file relative to the project root
     * @return A status message indicating success or failure with details of
     *         changes made
     */
    public EditResult organizeImports( String projectName, String filePath )
    {
        Objects.requireNonNull( projectName );
        Objects.requireNonNull( filePath );

        if ( projectName.isEmpty() )
        {
            throw new IllegalArgumentException( "Error: Project name cannot be empty." );
        }
        if ( filePath.isEmpty() )
        {
            throw new IllegalArgumentException( "Error: File path cannot be empty." );
        }

        try
        {
            IWorkspaceRoot root = ResourcesPlugin.getWorkspace().getRoot();
            IProject project = root.getProject( projectName );

            if ( !project.exists() )
            {
                throw new RuntimeException( "Error: Project '" + projectName + "' does not exist." );
            }
            if ( !project.isOpen() )
            {
                throw new RuntimeException( "Error: Project '" + projectName + "' is closed." );
            }

            // Get the Java project
            IJavaProject javaProject = JavaCore.create( project );
            if ( javaProject == null || !javaProject.exists() )
            {
                throw new RuntimeException( "Error: Project '" + projectName + "' is not a Java project." );
            }

            IPath path = IPath.fromPath( Path.of( filePath ) );
            IFile file = project.getFile( path );

            if ( !file.exists() )
            {
                throw new RuntimeException( "Error: File '" + filePath + "' does not exist in project '" + projectName + "'." );
            }

            if ( !filePath.endsWith( ".java" ) )
            {
                throw new RuntimeException( "Error: File '" + filePath + "' is not a Java file." );
            }

            // Get the compilation unit
            IJavaElement javaElement = JavaCore.create( file );
            if ( ! ( javaElement instanceof ICompilationUnit ) )
            {
                throw new RuntimeException( "Error: Could not resolve Java compilation unit for file '" + filePath + "'." );
            }

            ICompilationUnit compilationUnit = (ICompilationUnit) javaElement;

            // Refresh the editor if the file is open
            sync.syncExec( () -> {
                safeOpenEditor( file );
                refreshEditor( file );
            } );

            // Get the original imports for comparison
            String originalSource = compilationUnit.getSource();
            ResourceVersion before = ResourceVersion.of( file );

            // Create a choose import query that automatically selects the first
            // option
            // This handles cases where there are multiple types with the same
            // simple name
            IChooseImportQuery chooseImportQuery = new IChooseImportQuery()
            {
                @Override
                public TypeNameMatch[] chooseImports( TypeNameMatch[][] openChoices, ISourceRange[] ranges )
                {
                    // Automatically choose the first option for each ambiguous
                    // import
                    TypeNameMatch[] result = new TypeNameMatch[openChoices.length];
                    for ( int i = 0; i < openChoices.length; i++ )
                    {
                        if ( openChoices[i].length > 0 )
                        {
                            result[i] = openChoices[i][0];
                        }
                    }
                    return result;
                }
            };

            // Create and run the organize imports operation
            IProgressMonitor monitor = new NullProgressMonitor();
            OrganizeImportsOperation operation = new OrganizeImportsOperation(
                    compilationUnit,
                    null,  // astRoot - created automatically
                    true,  // ignoreLowerCaseNames
                    true,  // save
                    true,  // allowSyntaxErrors
                    chooseImportQuery );

            // OrganizeImportsOperation.run() saves through ICompilationUnit.save(), which is a
            // no-op for a working copy - and the editor opened above makes this unit one. Take
            // the edit and write it through the same path as every other edit instead.
            String newSource = organizedSource( operation, originalSource, monitor );
            if ( !originalSource.equals( newSource ) )
            {
                TextEditRequest edit = minimalReplacement( new Document( originalSource ), originalSource, newSource );
                return applyTextEdits( projectName, filePath, IResource.NULL_STAMP, List.of( edit ), false, true );
            }

            EditSynchronization synchronization = synchronizeAfterEdit( file, 1, currentHistoryState( file ) );
            List<Diagnostic> diagnostics = new ArrayList<>();

            // Nothing to organize: report the untouched file in the fields a caller reads for any other edit.
            List<AppliedEdit> applied = List.of();

            return new EditResult(
                    diagnostics.isEmpty() ? EditStatus.APPLIED : EditStatus.APPLIED_WITH_WARNINGS,
                    file.getProject().getName(),
                    file.getProjectRelativePath().toString(),
                    before,
                    synchronization.version(),
                    applied,
                    UnifiedDiffs.diff( originalSource, filePath, newSource, filePath, UnifiedDiffs.DEFAULT_CONTEXT_LINES ),
                    applied.isEmpty() ? List.of() : List.of( AffectedResource.of( file, ChangeKind.MODIFIED ) ),
                    describeReveal( file, new ContentRange( 1, 1, 1, 1 ), diagnostics ),
                    EditResult.NO_UNDO_STATE,
                    synchronization.workspaceState(),
                    diagnostics );
        }
        catch ( CoreException | BadLocationException e )
        {
            throw new RuntimeException( "Error during organize imports: " + ExceptionUtils.getRootCauseMessage( e ), e );
        }
    }

    /** The source with imports organized, or the original when the operation finds nothing to change. */
    private static String organizedSource( OrganizeImportsOperation operation, String originalSource, IProgressMonitor monitor )
            throws CoreException, BadLocationException
    {
        TextEdit edit = operation.createTextEdit( monitor );
        if ( edit == null )
        {
            return originalSource;
        }
        IDocument document = new Document( originalSource );
        edit.apply( document );
        return document.get();
    }

    /**
     * Organizes imports in every Java file of a package.
     * <p>
     * The package folder is the resource this result is addressed to - the same thing
     * {@code refactorRenamePackage} reports - and every compilation unit whose source
     * actually changed is an entry in {@link EditResult#affectedResources()}, carrying
     * the version an edit to that file must now quote. There is no diff and no edit
     * list: one per file would be an unbounded second payload.
     * <p>
     * A file this could not organize is one {@link Diagnostic}, naming it. The count
     * it used to report - "Processed 8 file(s)" of a ten-file package - was the only
     * trace two failures left behind, and the caller was never told which two.
     *
     * @param projectName
     *            The name of the project containing the package
     * @param packageName
     *            The fully qualified package name (e.g.,
     *            "com.example.mypackage")
     */
    public EditResult organizeImportsInPackage( String projectName, String packageName )
    {
        Objects.requireNonNull( projectName );
        Objects.requireNonNull( packageName );

        if ( projectName.isEmpty() )
        {
            throw new IllegalArgumentException( "Error: Project name cannot be empty." );
        }
        if ( packageName.isEmpty() )
        {
            throw new IllegalArgumentException( "Error: Package name cannot be empty." );
        }

        try
        {
            IWorkspaceRoot root = ResourcesPlugin.getWorkspace().getRoot();
            IProject project = root.getProject( projectName );

            if ( !project.exists() )
            {
                throw new RuntimeException( "Error: Project '" + projectName + "' does not exist." );
            }
            if ( !project.isOpen() )
            {
                throw new RuntimeException( "Error: Project '" + projectName + "' is closed." );
            }

            // Get the Java project
            IJavaProject javaProject = JavaCore.create( project );
            if ( javaProject == null || !javaProject.exists() )
            {
                throw new RuntimeException( "Error: Project '" + projectName + "' is not a Java project." );
            }

            // Find the package
            IPackageFragment packageFragment = findPackage( javaProject, packageName );
            if ( packageFragment == null )
            {
                throw new RuntimeException( "Error: Package '" + packageName + "' not found in project '" + projectName + "'." );
            }

            IResource packageResource = packageFragment.getResource();
            ResourceVersion before = ResourceVersion.of( packageResource );
            ICompilationUnit[] compilationUnits = packageFragment.getCompilationUnits();

            // Create a choose import query
            IChooseImportQuery chooseImportQuery = new IChooseImportQuery()
            {
                @Override
                public TypeNameMatch[] chooseImports( TypeNameMatch[][] openChoices, ISourceRange[] ranges )
                {
                    TypeNameMatch[] result = new TypeNameMatch[openChoices.length];
                    for ( int i = 0; i < openChoices.length; i++ )
                    {
                        if ( openChoices[i].length > 0 )
                        {
                            result[i] = openChoices[i][0];
                        }
                    }
                    return result;
                }
            };

            IProgressMonitor monitor = new NullProgressMonitor();
            List<AffectedResource> changed = new ArrayList<>();
            List<Diagnostic> diagnostics = new ArrayList<>();
            IFile firstChangedFile = null;
            int failed = 0;

            for ( ICompilationUnit cu : compilationUnits )
            {
                try
                {
                    String originalSource = cu.getSource();

                    OrganizeImportsOperation operation = new OrganizeImportsOperation( cu, null, true, true, true, chooseImportQuery );

                    String newSource = organizedSource( operation, originalSource, monitor );

                    if ( !originalSource.equals( newSource ) && cu.getResource() instanceof IFile changedFile )
                    {
                        TextEditRequest edit = minimalReplacement( new Document( originalSource ), originalSource, newSource );
                        EditResult written = applyTextEdits( projectName, changedFile.getProjectRelativePath().toString(),
                                IResource.NULL_STAMP, List.of( edit ), false );
                        if ( written.status() == EditStatus.REJECTED )
                        {
                            throw new IllegalStateException( written.diagnostics().get( 0 ).message() );
                        }
                        changed.add( AffectedResource.of( changedFile, ChangeKind.MODIFIED ) );
                        if ( firstChangedFile == null )
                        {
                            firstChangedFile = changedFile;
                        }
                    }
                }
                catch ( Exception e )
                {
                    // One diagnostic per file, naming it. A count of what succeeded says
                    // nothing about which files the caller still has to deal with.
                    failed++;
                    diagnostics.add( Diagnostic.fatal( DiagnosticCode.INTERNAL_ERROR,
                            "Could not organize imports in " + cu.getElementName() + ": "
                                    + ExceptionUtils.getRootCauseMessage( e ) ) );
                }
            }

            EditorReveal reveal = firstChangedFile == null
                    ? EditorReveal.none()
                    : describeReveal( firstChangedFile, new ContentRange( 1, 1, 1, 1 ), diagnostics );

            // Every file failing is not a partial success: nothing was written at all,
            // and the caller has to act on the diagnostics rather than on the result.
            EditStatus status = failed > 0 && failed == compilationUnits.length
                    ? EditStatus.REJECTED
                    : diagnostics.isEmpty() ? EditStatus.APPLIED : EditStatus.APPLIED_WITH_WARNINGS;

            return new EditResult(
                    status,
                    projectName,
                    packageResource.getProjectRelativePath().toString(),
                    before,
                    ResourceVersion.of( packageResource ),
                    List.of(),
                    "",
                    List.copyOf( changed ),
                    reveal,
                    EditResult.NO_UNDO_STATE,
                    new WorkspaceSync( true, false, "not-applicable" ),
                    diagnostics );
        }
        catch ( CoreException e )
        {
            throw new RuntimeException( "Error during organize imports: " + ExceptionUtils.getRootCauseMessage( e ), e );
        }
    }

    /**
     * Moves a file or folder to a different directory in the same project.
     * <p>
     * A move cannot go through {@link #applyTextEdits}: the content does not change,
     * its address does, and there is no range to express that in. It keeps
     * {@link IResource#move} and returns an {@link EditResult} only so a caller
     * branches on one shape. {@code resource} in the result is the destination,
     * because that is what the caller addresses next.
     * <p>
     * For Java files prefer {@code refactorMoveJavaType}: this does not update
     * references.
     */
    public EditResult moveResource( String projectName, String sourcePath, String targetPath )
    {
        Objects.requireNonNull( projectName );
        Objects.requireNonNull( sourcePath );
        Objects.requireNonNull( targetPath );

        if ( projectName.isEmpty() )
        {
            throw new IllegalArgumentException( "Error: Project name cannot be empty." );
        }
        if ( sourcePath.isEmpty() )
        {
            throw new IllegalArgumentException( "Error: Source path cannot be empty." );
        }
        if ( targetPath.isEmpty() )
        {
            throw new IllegalArgumentException( "Error: Target path cannot be empty." );
        }

        try
        {
            IWorkspaceRoot root = ResourcesPlugin.getWorkspace().getRoot();
            IProject project = root.getProject( projectName );

            if ( !project.exists() )
            {
                throw new RuntimeException( "Error: Project '" + projectName + "' does not exist." );
            }
            if ( !project.isOpen() )
            {
                throw new RuntimeException( "Error: Project '" + projectName + "' is closed." );
            }

            String normalizedSource = sourcePath;
            while ( normalizedSource.startsWith( "/" ) || normalizedSource.startsWith( "\\" ) )
            {
                normalizedSource = normalizedSource.substring( 1 );
            }

            String normalizedTarget = targetPath;
            while ( normalizedTarget.startsWith( "/" ) || normalizedTarget.startsWith( "\\" ) )
            {
                normalizedTarget = normalizedTarget.substring( 1 );
            }

            IResource sourceResource = project.findMember( normalizedSource );
            if ( sourceResource == null || !sourceResource.exists() )
            {
                throw new RuntimeException( "Error: Resource '" + sourcePath + "' does not exist in project '" + projectName + "'." );
            }

            if ( sourceResource instanceof IFile sourceFile )
            {
                aiIgnoreService.assertAccessAllowed( sourceFile );
            }

            if ( sourceResource instanceof IFile && sourcePath.endsWith( ".java" ) )
            {
                logger.warn( "Moving Java file without refactoring - references will not be updated. Consider using refactorMoveJavaType instead." );
            }

            IFolder targetFolder = project.getFolder( normalizedTarget );
            if ( !targetFolder.exists() )
            {
                ResourceUtilities.createFolderHierarchy( targetFolder );
            }

            // Close the editor on the source: once moved, it would show content that
            // is no longer at that address.
            if ( sourceResource instanceof IFile sourceFile )
            {
                sync.syncExec( () -> {
                    // Off the UI thread - a test's inline UISynchronize - there is no active window.
                    IWorkbenchWindow window = PlatformUI.getWorkbench().getActiveWorkbenchWindow();
                    IWorkbenchPage page = window == null ? null : window.getActivePage();
                    if ( page != null )
                    {
                        IEditorPart editor = page.findEditor( new FileEditorInput( sourceFile ) );
                        if ( editor != null )
                        {
                            page.closeEditor( editor, true );
                        }
                    }
                } );
            }

            String resourceName = sourceResource.getName();
            IPath destinationPath = targetFolder.getFullPath().append( resourceName );

            IResource existingResource = root.findMember( destinationPath );
            if ( existingResource != null && existingResource.exists() )
            {
                throw new RuntimeException( "Error: A resource named '" + resourceName + "' already exists at the destination." );
            }

            ResourceVersion before = ResourceVersion.of( sourceResource );
            IPath previousPath = sourceResource.getFullPath();

            sourceResource.move( destinationPath, IResource.FORCE | IResource.KEEP_HISTORY, new NullProgressMonitor() );

            sourceResource.getParent().refreshLocal( IResource.DEPTH_ONE, null );
            targetFolder.refreshLocal( IResource.DEPTH_ONE, null );
            // The old address no longer holds anything, so nothing may still be
            // served from the cache under it.
            resourceCache.resourceChanged( previousPath );

            // Two entries: the address that no longer holds anything, so a caller
            // holding it learns not to re-read it, and the one that now does.
            List<AffectedResource> affected = List.of(
                    AffectedResource.of( sourceResource, ChangeKind.DELETED ),
                    AffectedResource.of( root.findMember( destinationPath ), ChangeKind.MOVED ) );

            if ( sourceResource instanceof IFile )
            {
                IFile newFile = root.getFile( destinationPath );
                EditSynchronization synchronization = synchronizeAfterEdit( newFile, 1, null );
                List<Diagnostic> diagnostics = new ArrayList<>();
                EditorReveal reveal = describeReveal( newFile, new ContentRange( 1, 1, 1, 1 ), diagnostics );
                return resourceRelocated( newFile, before, affected, reveal,
                        synchronization.workspaceState(), diagnostics );
            }

            IResource moved = root.findMember( destinationPath );
            return resourceRelocated( moved, before, affected, EditorReveal.none(),
                    new WorkspaceSync( true, false, "not-applicable" ), Diagnostic.none() );
        }
        catch ( CoreException e )
        {
            throw new RuntimeException( "Error during move: " + ExceptionUtils.getRootCauseMessage( e ), e );
        }
    }


    /**
     * Finds a package fragment in the Java project.
     */
    private IPackageFragment findPackage( IJavaProject javaProject, String packageName ) throws JavaModelException
    {
        for ( IPackageFragmentRoot root : javaProject.getPackageFragmentRoots() )
        {
            if ( root.getKind() == IPackageFragmentRoot.K_SOURCE )
            {
                IPackageFragment fragment = root.getPackageFragment( packageName );
                if ( fragment != null && fragment.exists() )
                {
                    return fragment;
                }
            }
        }
        return null;
    }

    /** Where a moved type goes: the package fragment, or the reason none could be chosen. */
    private record TargetPackage( IPackageFragment fragment, Diagnostic problem )
    {
        static TargetPackage of( IPackageFragment fragment )
        {
            return new TargetPackage( fragment, null );
        }

        static TargetPackage refused( DiagnosticCode code, String message )
        {
            return new TargetPackage( null, Diagnostic.fatal( code, message ) );
        }
    }

    /**
     * Resolves the package a type is moved into.
     * <p>
     * A package name alone is ambiguous whenever more than one source folder can hold
     * it, and those folders are routinely in different projects: the package the caller
     * means is usually the one that already exists, on the build path of the project the
     * file is in. The old lookup searched only the file's own project and, finding
     * nothing there, created the package in whichever source folder came first on the
     * classpath - a resources folder, in the case that was reported (issue 159). So the
     * package is looked for in the file's project and every project it can see: exactly
     * one match is taken, several are refused with the candidates listed, and none means
     * it is created beside the file, in the file's own source folder, unless the caller
     * named a project or folder.
     */
    private TargetPackage resolveTargetPackage( IJavaProject source, ICompilationUnit unit, String packageName,
                                                String targetProjectName, String targetSourceFolder )
            throws CoreException
    {
        boolean explicitProject = targetProjectName != null && !targetProjectName.isBlank();
        boolean explicitFolder = targetSourceFolder != null && !targetSourceFolder.isBlank();

        List<IJavaProject> scope = explicitProject
                ? List.of( javaProjectNamed( targetProjectName.trim() ) )
                : visibleProjects( source );
        List<IPackageFragmentRoot> roots = new ArrayList<>();
        for ( IJavaProject candidate : scope )
        {
            for ( IPackageFragmentRoot root : candidate.getPackageFragmentRoots() )
            {
                if ( root.getKind() == IPackageFragmentRoot.K_SOURCE && !root.isArchive()
                        && ( !explicitFolder || isSourceFolder( root, targetSourceFolder ) ) )
                {
                    roots.add( root );
                }
            }
        }
        if ( roots.isEmpty() )
        {
            return TargetPackage.refused( DiagnosticCode.RESOURCE_NOT_FOUND, explicitFolder
                    ? "No source folder '" + targetSourceFolder + "' in " + describe( scope ) + "."
                    : "No source folder in " + describe( scope ) + " to receive package '" + packageName + "'." );
        }

        List<IPackageFragmentRoot> holding = new ArrayList<>();
        for ( IPackageFragmentRoot root : roots )
        {
            IPackageFragment fragment = root.getPackageFragment( packageName );
            if ( fragment != null && fragment.exists() )
            {
                holding.add( root );
            }
        }
        if ( holding.size() == 1 )
        {
            return TargetPackage.of( holding.get( 0 ).getPackageFragment( packageName ) );
        }
        if ( holding.size() > 1 )
        {
            return TargetPackage.refused( DiagnosticCode.AMBIGUOUS_MATCH,
                    "Package '" + packageName + "' exists in " + holding.size() + " source folders: " + describe( holding )
                            + ". Pass targetProjectName and/or targetSourceFolder to say which one." );
        }

        // The package exists nowhere yet. Without a say from the caller it is created
        // beside the file, in the file's own source folder.
        IPackageFragmentRoot destination;
        if ( !explicitProject && !explicitFolder )
        {
            destination = (IPackageFragmentRoot) unit.getAncestor( IJavaElement.PACKAGE_FRAGMENT_ROOT );
        }
        else if ( roots.size() == 1 )
        {
            destination = roots.get( 0 );
        }
        else
        {
            // Several folders could take it. One that already holds Java sources is a
            // source folder in more than name; a resources folder is not.
            List<IPackageFragmentRoot> withSources = roots.stream().filter( CodeEditingService::holdsJavaSources ).toList();
            if ( withSources.size() != 1 )
            {
                return TargetPackage.refused( DiagnosticCode.AMBIGUOUS_MATCH,
                        "Package '" + packageName + "' does not exist yet and " + roots.size()
                                + " source folders could receive it: " + describe( roots )
                                + ". Pass targetSourceFolder to say which one." );
            }
            destination = withSources.get( 0 );
        }
        return TargetPackage.of( destination.createPackageFragment( packageName, true, new NullProgressMonitor() ) );
    }

    private static IJavaProject javaProjectNamed( String name )
    {
        IProject project = ResourcesPlugin.getWorkspace().getRoot().getProject( name );
        if ( !project.exists() || !project.isOpen() )
        {
            throw new RuntimeException( "Error: Project '" + name + "' does not exist or is closed." );
        }
        IJavaProject javaProject = JavaCore.create( project );
        if ( javaProject == null || !javaProject.exists() )
        {
            throw new RuntimeException( "Error: Project '" + name + "' is not a Java project." );
        }
        return javaProject;
    }

    /** The project itself, then every project on its build path, transitively, each once. */
    private static List<IJavaProject> visibleProjects( IJavaProject source ) throws JavaModelException
    {
        List<IJavaProject> visible = new ArrayList<>();
        Deque<IJavaProject> pending = new ArrayDeque<>();
        pending.add( source );
        while ( !pending.isEmpty() )
        {
            IJavaProject next = pending.poll();
            if ( visible.stream().anyMatch( seen -> seen.getElementName().equals( next.getElementName() ) ) )
            {
                continue;
            }
            visible.add( next );
            for ( String required : next.getRequiredProjectNames() )
            {
                IProject project = ResourcesPlugin.getWorkspace().getRoot().getProject( required );
                if ( project.isAccessible() )
                {
                    IJavaProject javaProject = JavaCore.create( project );
                    if ( javaProject.exists() )
                    {
                        pending.add( javaProject );
                    }
                }
            }
        }
        return visible;
    }

    private static boolean isSourceFolder( IPackageFragmentRoot root, String projectRelativeFolder )
    {
        IResource resource = root.getResource();
        String wanted = projectRelativeFolder.trim().replace( '\\', '/' ).replaceAll( "^/+|/+$", "" );
        return resource != null && resource.getProjectRelativePath().toString().equals( wanted );
    }

    private static boolean holdsJavaSources( IPackageFragmentRoot root )
    {
        try
        {
            for ( IJavaElement child : root.getChildren() )
            {
                if ( child instanceof IPackageFragment fragment && fragment.getCompilationUnits().length > 0 )
                {
                    return true;
                }
            }
        }
        catch ( JavaModelException e )
        {
            // Unreadable: not a folder to put sources in.
        }
        return false;
    }

    /** Source folders as "project/folder", projects by name - for a diagnostic. */
    private static String describe( List<?> elements )
    {
        List<String> names = new ArrayList<>();
        for ( Object element : elements )
        {
            if ( element instanceof IPackageFragmentRoot root )
            {
                IResource resource = root.getResource();
                names.add( root.getJavaProject().getElementName() + "/"
                        + ( resource == null ? root.getElementName() : resource.getProjectRelativePath().toString() ) );
            }
            else if ( element instanceof IJavaProject project )
            {
                names.add( project.getElementName() );
            }
        }
        return String.join( ", ", names );
    }

    /**
     * Deletes a file.
     * <p>
     * A deletion cannot go through {@link #applyTextEdits}: there is no document left
     * to place a range in, and no version afterwards. It keeps its own mechanism -
     * {@link IFile#delete} with {@link IResource#KEEP_HISTORY}, so the content stays
     * recoverable - and returns an {@link EditResult} only so that a caller branches
     * on one shape whatever it asked for.
     */
    public EditResult deleteFile( String projectName, String filePath )
    {
        Objects.requireNonNull( projectName );
        Objects.requireNonNull( filePath );

        IFile file = resolveEditableFile( projectName, filePath );
        ResourceVersion before = ResourceVersion.of( file );

        try
        {
            String removed = ResourceUtilities.readFileContent( file );
            ContentRange oldRange = ContentRange.wholeDocument( new Document( removed ) );

            // Close the editor first: leaving one open on a resource that no longer
            // exists leaves the user looking at content that is gone.
            sync.syncExec( () -> {
                // Off the UI thread - a test's inline UISynchronize - there is no active window.
                IWorkbenchWindow window = PlatformUI.getWorkbench().getActiveWorkbenchWindow();
                IWorkbenchPage page = window == null ? null : window.getActivePage();
                if ( page != null )
                {
                    IEditorPart editor = page.findEditor( new FileEditorInput( file ) );
                    if ( editor != null )
                    {
                        page.closeEditor( editor, false );
                    }
                }
            } );

            boolean cached = resourceCache.get( file ).isPresent();

            // KEEP_HISTORY so an agent-deleted file stays recoverable from Eclipse's
            // local history; the plain delete( boolean, .. ) overload keeps nothing.
            file.delete( IResource.FORCE | IResource.KEEP_HISTORY, null );

            IContainer parent = file.getParent();
            parent.refreshLocal( IResource.DEPTH_ONE, null );
            resourceCache.resourceChanged( file.getFullPath() );

            IFileState undoState = currentHistoryState( file );
            AppliedEdit applied = new AppliedEdit( oldRange, new ContentRange( 1, 1, 1, 1 ), 0, removed.length() );

            return new EditResult(
                    EditStatus.APPLIED,
                    file.getProject().getName(),
                    file.getProjectRelativePath().toString(),
                    before,
                    ResourceVersion.UNKNOWN,
                    List.of( applied ),
                    UnifiedDiffs.omitted( UnifiedDiffs.compare( removed, "", 0 ), "the removed content is in Local History" ),
                    List.of( AffectedResource.of( file, ChangeKind.DELETED ) ),
                    EditorReveal.none(),
                    undoState != null ? undoState.getModificationTime() : EditResult.NO_UNDO_STATE,
                    new WorkspaceSync( true, cached, "not-applicable" ),
                    Diagnostic.none() );
        }
        catch ( CoreException | IOException | BadLocationException e )
        {
            return internalFailure( file, e );
        }
    }


    /**
     * The line delimiter Eclipse is configured to write in this project.
     *
     * @param projectName may be null or blank to ask the workspace rather than a
     *            project
     */
    public LineDelimiterPreference getLineDelimiterPreference( String projectName )
    {
        if ( projectName == null || projectName.isBlank() )
        {
            return LineDelimiterPreference.of( null );
        }
        IProject project = ResourcesPlugin.getWorkspace().getRoot().getProject( projectName );
        return LineDelimiterPreference.of( project.exists() ? project : null );
    }

    /**
     * Rewrites a file so every line ends with the delimiter Eclipse is configured to
     * use, leaving the text itself untouched.
     * <p>
     * A file with mixed delimiters is not a cosmetic problem. {@code applyPatch} splits
     * on any delimiter and rejoins with one, so patching such a file rewrites every
     * line and buries a three-line change in a whole-file diff; and a search that
     * counts characters to find a line lands in a different place depending on which
     * delimiter it met. Normalising once makes both behave.
     * <p>
     * The target comes from the preference rather than from the file's own majority
     * delimiter, so the result matches what the Java editor would write - see
     * {@link LineDelimiterPreference}. A file that is already consistent with it is
     * left alone and reported as {@code APPLIED} with an empty diff and no affected
     * resource, because nothing was written.
     *
     * @param expectedModificationStamp the stamp the caller read, or
     *            {@link IResource#NULL_STAMP} to skip the staleness check
     * @param preview when true, reports what would change without writing
     */
    public EditResult normalizeLineDelimiters( String projectName, String filePath,
                                               long expectedModificationStamp, boolean preview )
    {
        Objects.requireNonNull( projectName );
        Objects.requireNonNull( filePath );

        IFile file = resolveEditableFile( projectName, filePath );

        try
        {
            String original = ResourceUtilities.readFileContent( file );
            String target = getLineDelimiterPreference( projectName ).delimiter();
            String normalized = withLineDelimiter( original, target );

            if ( original.equals( normalized ) )
            {
                // Not a diagnostic: being already correct is the outcome the caller
                // wanted, and a no-op that reports a fault invites a pointless retry.
                return unchanged( file );
            }

            return replaceFileContent( projectName, filePath, normalized, expectedModificationStamp, preview );
        }
        catch ( CoreException | IOException e )
        {
            return internalFailure( file, e );
        }
    }

    /**
     * Rewrites every line terminator as {@code delimiter}, preserving the text and
     * whether the file ends with a terminator at all.
     * <p>
     * The split is done by {@link IDocument}'s line tracker, which already knows
     * {@code \n}, {@code \r\n} and a lone {@code \r} and mixtures of them. A regex on
     * {@code \R} would also match a form feed and a few other Unicode separators, and
     * would silently turn them into line breaks.
     */
    private static String withLineDelimiter( String content, String delimiter )
    {
        IDocument document = new Document( content );
        StringBuilder out = new StringBuilder( content.length() );
        int lines = document.getNumberOfLines();

        for ( int line = 0; line < lines; line++ )
        {
            try
            {
                IRegion region = document.getLineInformation( line );
                out.append( document.get( region.getOffset(), region.getLength() ) );
                // Null for the last line when the file does not end with a terminator,
                // which is exactly when none should be added.
                if ( document.getLineDelimiter( line ) != null )
                {
                    out.append( delimiter );
                }
            }
            catch ( BadLocationException e )
            {
                // The tracker gave us the line count; asking it for those lines cannot
                // be out of range.
                throw new IllegalStateException( "Line " + line + " vanished from its own document", e );
            }
        }
        return out.toString();
    }

    /** A write that was not needed. Nothing changed, so nothing is reported as changed. */
    private EditResult unchanged( IFile file )
    {
        ResourceVersion version = ResourceVersion.of( file );
        return new EditResult(
                EditStatus.APPLIED,
                file.getProject().getName(),
                file.getProjectRelativePath().toString(),
                version,
                version,
                List.of(),
                "",
                List.of(),
                EditorReveal.none(),
                EditResult.NO_UNDO_STATE,
                new WorkspaceSync( true, false, "not-applicable" ),
                Diagnostic.none() );
    }

    /**
     * Replaces the whole content of a file.
     * <p>
     * Expressed as one replacement of the whole document and applied by
     * {@link #applyTextEdits}, so a wholesale rewrite is written the same way, and
     * reported in the same shape, as a one-word change.
     *
     * @param expectedModificationStamp the stamp the caller read, or
     *            {@link IResource#NULL_STAMP} to skip the staleness check
     * @param preview when true, reports what would change without writing
     */
    public EditResult replaceFileContent( String projectName, String filePath, String content,
                                          long expectedModificationStamp, boolean preview )
    {
        Objects.requireNonNull( projectName );
        Objects.requireNonNull( filePath );
        Objects.requireNonNull( content );

        IFile file = resolveEditableFile( projectName, filePath );

        try
        {
            String original = ResourceUtilities.readFileContent( file );
            IDocument document = new Document( original );
            TextEditRequest edit = new TextEditRequest( ContentRange.wholeDocument( document ), original, content );

            return applyTextEdits( projectName, filePath, expectedModificationStamp, List.of( edit ), preview );
        }
        catch ( CoreException | IOException | BadLocationException e )
        {
            return internalFailure( file, e );
        }
    }


    /**
     * Deletes a range of whole lines.
     * <p>
     * A deletion is a replacement of those lines with nothing, so it goes through
     * {@link #applyTextEdits} like every other text change: one write, one
     * local-history entry, and the same optional staleness check.
     *
     * @param expectedModificationStamp the stamp the caller read, or
     *            {@link IResource#NULL_STAMP} to skip the staleness check
     * @param preview when true, reports what would change without writing
     */
    public EditResult deleteLinesInFile( String projectName, String filePath, int startLine, int endLine,
                                         long expectedModificationStamp, boolean preview )
    {
        return replaceLineRange( projectName, filePath, "", startLine, endLine, expectedModificationStamp, preview );
    }


    /**
     * Applies a unified diff to a file.
     * <p>
     * The patch is parsed and applied in memory by this class's own hunk machinery,
     * which is deliberately kept rather than replaced by platform code - see
     * {@code docs/structured-output-rollout-plan.md} for why. What changed is where
     * the result goes: the patched content is written through
     * {@link #applyTextEdits} as one minimal replacement, so a patch shares the
     * single write path, the optional staleness check, and the one local-history
     * entry with every other editing tool.
     *
     * @param showDialog when true, hands the patch to Eclipse's Apply Patch wizard
     *            for the user to review and writes nothing here, so the result is a
     *            {@link EditStatus#PREVIEW}
     * @param expectedModificationStamp the stamp the caller read, or
     *            {@link IResource#NULL_STAMP} to skip the staleness check
     * @param preview when true, reports what the patch would change without writing
     */
    public EditResult applyPatch( String projectName, String filePath, String patch, boolean showDialog,
                                  long expectedModificationStamp, boolean preview )
    {
        Objects.requireNonNull( projectName, "Project name cannot be null" );
        Objects.requireNonNull( filePath, "File path cannot be null" );
        Objects.requireNonNull( patch, "Patch content cannot be null" );

        IFile file = resolveEditableFile( projectName, filePath );
        ResourceVersion before = ResourceVersion.of( file );

        if ( patch.isBlank() )
        {
            return EditResult.rejected( file, before,
                    Diagnostic.fatal( DiagnosticCode.INVALID_RANGE, "The patch is empty." ) );
        }

        try
        {
            String originalContent = ResourceUtilities.readFileContent( file );
            String lineDelimiter = detectLineDelimiter( originalContent );
            boolean hasTrailingDelimiter = endsWithLineDelimiter( originalContent );

            String patchedContent;
            try
            {
                // Every hunk is validated and applied in memory before the file is touched.
                List<String> patchedLines = applyUnifiedDiff( splitLines( originalContent ), patch );
                patchedContent = String.join( lineDelimiter, patchedLines );
                if ( hasTrailingDelimiter && !patchedLines.isEmpty() )
                {
                    patchedContent += lineDelimiter;
                }
            }
            catch ( IllegalArgumentException e )
            {
                // The patch itself is malformed. Sending it again unchanged cannot help.
                return EditResult.rejected( file, before,
                        Diagnostic.fatal( DiagnosticCode.INVALID_RANGE, e.getMessage() ) );
            }
            catch ( RuntimeException e )
            {
                // A hunk's context is not in the file. Re-reading and recomputing the
                // patch is exactly what fixes that, so it is retryable rather than fatal.
                return EditResult.rejected( file, before,
                        Diagnostic.retryable( DiagnosticCode.TEXT_NOT_FOUND, e.getMessage() ) );
            }

            if ( showDialog )
            {
                // The wizard opens asynchronously so the call returns without waiting
                // for the user. Nothing is written here: whether the patch lands is the
                // user's decision, which is what PREVIEW says.
                String fullPatch = buildFullUnifiedDiff( filePath, patch );
                sync.asyncExec( () -> {
                    var patchHelper = new com.github.gradusnikov.eclipse.assistai.view.ApplyPatchWizardHelper();
                    patchHelper.showApplyPatchWizardDialog( fullPatch, filePath, projectName );
                } );
                return new EditResult( EditStatus.PREVIEW,
                        file.getProject().getName(), file.getProjectRelativePath().toString(),
                        before, before, List.of(),
                        UnifiedDiffs.diff( originalContent, filePath, patchedContent, filePath,
                                UnifiedDiffs.DEFAULT_CONTEXT_LINES ),
                        List.of(),
                        EditorReveal.none(), EditResult.NO_UNDO_STATE, null, Diagnostic.none() );
            }

            IDocument document = new Document( originalContent );
            TextEditRequest edit = minimalReplacement( document, originalContent, patchedContent );

            return applyTextEdits( projectName, filePath, expectedModificationStamp, List.of( edit ), preview );
        }
        catch ( CoreException | IOException | BadLocationException e )
        {
            return internalFailure( file, e );
        }
    }


    private List<String> splitLines( String content )
    {
        if ( content.isEmpty() )
        {
            return new java.util.ArrayList<>();
        }
        List<String> lines = new java.util.ArrayList<>( java.util.Arrays.asList( content.split( "\\R", -1 ) ) );
        if ( endsWithLineDelimiter( content ) )
        {
            lines.remove( lines.size() - 1 );
        }
        return lines;
    }

    private String detectLineDelimiter( String content )
    {
        int newline = content.indexOf( '\n' );
        if ( newline >= 0 )
        {
            return newline > 0 && content.charAt( newline - 1 ) == '\r' ? "\r\n" : "\n";
        }
        return content.indexOf( '\r' ) >= 0 ? "\r" : System.lineSeparator();
    }

    private boolean endsWithLineDelimiter( String content )
    {
        return content.endsWith( "\n" ) || content.endsWith( "\r" );
    }

    /**
     * Builds a full unified diff string with proper file headers, ensuring the
     * patch content has --- and +++ headers.
     */
    private String buildFullUnifiedDiff( String filePath, String patch )
    {
        StringBuilder fullPatch = new StringBuilder();
        // Check if the patch already has file headers
        boolean hasHeaders = false;
        for ( String line : patch.split( "\n" ) )
        {
            if ( line.startsWith( "---" ) || line.startsWith( "+++" ) )
            {
                hasHeaders = true;
                break;
            }
            if ( line.startsWith( "@@" ) )
            {
                break; // reached hunks without finding headers
            }
        }
        if ( !hasHeaders )
        {
            // Use paths without a/ b/ prefix so Eclipse's patch dialog
            // can match them relative to the project root
            fullPatch.append( "--- " ).append( filePath ).append( "\n" );
            fullPatch.append( "+++ " ).append( filePath ).append( "\n" );
        }
        fullPatch.append( patch );
        return fullPatch.toString();
    }

    /**
     * Parses a unified diff and applies it to the given list of original lines.
     * Supports multiple hunks. Uses context lines for fuzzy matching when line
     * numbers don't match exactly (e.g., due to prior edits shifting lines).
     *
     * @param originalLines
     *            The original file content as a list of lines
     * @param patch
     *            The unified diff content
     * @return The patched file content as a list of lines
     */
    private List<String> applyUnifiedDiff( List<String> originalLines, String patch )
    {
        List<String> result = new java.util.ArrayList<>( originalLines );
        var hunks = parseHunks( patch );

        // Apply hunks in reverse order so line number offsets don't shift
        java.util.Collections.reverse( hunks );

        for ( var hunk : hunks )
        {
            result = applyHunk( result, hunk );
        }

        return result;
    }

    /**
     * Represents a single hunk from a unified diff.
     */
    private static class DiffHunk
    {
        int          originalStart;                           // 1-based line
                                                              // number in
                                                              // original file

        int          originalCount;                           // number of lines
                                                              // from original

        List<String> hunkLines = new java.util.ArrayList<>(); // all lines in
                                                              // the hunk with
                                                              // their prefixes
    }

    /**
     * Parses unified diff content into a list of DiffHunk objects.
     */
    private List<DiffHunk> parseHunks( String patch )
    {
        var hunks = new java.util.ArrayList<DiffHunk>();
        var lines = patch.split( "\\R" );
        DiffHunk currentHunk = null;

        for ( String line : lines )
        {
            // Skip file headers
            if ( line.startsWith( "---" ) || line.startsWith( "+++" ) )
            {
                continue;
            }

            // Parse hunk header: @@ -start,count +start,count @@
            if ( line.startsWith( "@@" ) )
            {
                currentHunk = new DiffHunk();
                hunks.add( currentHunk );

                // Parse the original file range: @@ -start,count +start,count
                // @@
                var matcher = java.util.regex.Pattern.compile( "@@ -(\\d+)(?:,(\\d+))? \\+(\\d+)(?:,(\\d+))? @@.*" ).matcher( line );
                if ( !matcher.matches() )
                {
                    throw new IllegalArgumentException( "Error: Invalid unified diff hunk header: " + line );
                }
                currentHunk.originalStart = Integer.parseInt( matcher.group( 1 ) );
                currentHunk.originalCount = matcher.group( 2 ) != null ? Integer.parseInt( matcher.group( 2 ) ) : 1;
                continue;
            }

            if ( currentHunk != null )
            {
                if ( line.startsWith( " " ) || line.startsWith( "-" ) || line.startsWith( "+" ) )
                {
                    currentHunk.hunkLines.add( line );
                }
                // Handle lines that are just empty (context lines with no
                // trailing space)
                else if ( line.isEmpty() )
                {
                    currentHunk.hunkLines.add( " " );
                }
            }
        }

        if ( hunks.isEmpty() )
        {
            throw new IllegalArgumentException( "Error: Patch contains no unified diff hunks." );
        }

        return hunks;
    }

    /**
     * Applies a single hunk to the file content. Uses context lines for fuzzy
     * matching to find the correct position.
     */
    private List<String> applyHunk( List<String> lines, DiffHunk hunk )
    {
        // Build the expected original block (context + removed lines)
        var expectedLines = new java.util.ArrayList<String>();
        for ( String hunkLine : hunk.hunkLines )
        {
            if ( hunkLine.startsWith( " " ) || hunkLine.startsWith( "-" ) )
            {
                expectedLines.add( hunkLine.substring( 1 ) );
            }
        }
        if ( expectedLines.size() != hunk.originalCount )
        {
            throw new IllegalArgumentException( "Error: Hunk at line " + hunk.originalStart + " declares " + hunk.originalCount
                    + " original line(s), but contains " + expectedLines.size() + "." );
        }


        // Try to find the matching position
        int matchPos = findMatchPosition( lines, expectedLines, hunk.originalStart - 1 );

        if ( matchPos < 0 )
        {
            throw new RuntimeException( "Error: Could not find matching context for hunk at line " + hunk.originalStart
                    + ". The file may have been modified since the diff was generated." );
        }

        // Build the replacement block (context + added lines)
        var replacementLines = new java.util.ArrayList<String>();
        for ( String hunkLine : hunk.hunkLines )
        {
            if ( hunkLine.startsWith( " " ) )
            {
                replacementLines.add( hunkLine.substring( 1 ) );
            }
            else if ( hunkLine.startsWith( "+" ) )
            {
                replacementLines.add( hunkLine.substring( 1 ) );
            }
            // '-' lines are skipped (they are removed)
        }

        // Replace the matched range with the new content
        var result = new java.util.ArrayList<String>();
        // Add lines before the match
        for ( int i = 0; i < matchPos; i++ )
        {
            result.add( lines.get( i ) );
        }
        // Add replacement lines
        result.addAll( replacementLines );
        // Add lines after the matched block
        for ( int i = matchPos + expectedLines.size(); i < lines.size(); i++ )
        {
            result.add( lines.get( i ) );
        }

        return result;
    }

    /**
     * Finds the position in the file where the expected lines match. First
     * tries the exact position from the hunk header, then searches nearby
     * positions (fuzzy matching) in case the file has shifted.
     *
     * @param lines
     *            The current file lines
     * @param expectedLines
     *            The lines expected at the match position (context + removed)
     * @param hintPosition
     *            The position suggested by the hunk header (0-based)
     * @return The 0-based position where the match was found, or -1 if not
     *         found
     */
    private int findMatchPosition( List<String> lines, List<String> expectedLines, int hintPosition )
    {
        if ( expectedLines.isEmpty() )
        {
            // Pure insertion hunk â use the hint position directly
            return Math.max( 0, Math.min( hintPosition, lines.size() ) );
        }

        // Try exact position first
        if ( matchesAt( lines, expectedLines, hintPosition ) )
        {
            return hintPosition;
        }

        // Search nearby (within 50 lines in each direction)
        int maxSearchDistance = 50;
        for ( int offset = 1; offset <= maxSearchDistance; offset++ )
        {
            // Try below
            if ( matchesAt( lines, expectedLines, hintPosition + offset ) )
            {
                return hintPosition + offset;
            }
            // Try above
            if ( matchesAt( lines, expectedLines, hintPosition - offset ) )
            {
                return hintPosition - offset;
            }
        }

        return -1;
    }

    /**
     * Checks if the expected lines match the file content at the given
     * position.
     */
    private boolean matchesAt( List<String> lines, List<String> expectedLines, int position )
    {
        if ( position < 0 || position + expectedLines.size() > lines.size() )
        {
            return false;
        }
        for ( int i = 0; i < expectedLines.size(); i++ )
        {
            if ( !lines.get( position + i ).equals( expectedLines.get( i ) ) )
            {
                return false;
            }
        }
        return true;
    }

    /**
     * Formats a code completion snippet using Eclipse's code formatter. The
     * completion is formatted in context by combining it with the code before
     * the cursor, formatting the combined code, and then extracting the
     * formatted completion.
     * 
     * @param completion
     *            The raw completion text from the LLM
     * @param ctx
     *            The completion context containing code before/after cursor
     * @param editor
     *            The text editor (used to get project-specific formatter
     *            settings)
     * @return The formatted completion, or the original if formatting fails
     */
    public String formatCompletion( String completion, CompletionContext ctx, ITextEditor editor )
    {
        if ( completion == null || completion.isEmpty() )
        {
            return completion;
        }

        // Only format Java files
        if ( !"java".equalsIgnoreCase( ctx.fileExtension() ) )
        {
            return completion;
        }

        try
        {
            // Get the project for formatter settings
            Map<String, String> options = getFormatterOptionsForEditor( editor );

            if ( options == null )
            {
                return completion;
            }

            // Create the formatter
            CodeFormatter formatter = ToolFactory.createCodeFormatter( options );

            // Combine code before cursor with the completion to format in
            // context
            String codeBefore = ctx.codeBeforeCursor();
            String combinedCode = codeBefore + completion;

            // Format just the completion part (from offset =
            // codeBefore.length())
            int completionOffset = codeBefore.length();
            int completionLength = completion.length();

            // Format as statements (K_STATEMENTS works better for code
            // fragments)
            TextEdit textEdit = formatter.format( CodeFormatter.K_STATEMENTS | CodeFormatter.F_INCLUDE_COMMENTS, combinedCode, completionOffset,
                    completionLength, getIndentationLevel( codeBefore ), lineDelimiterFor( combinedCode, null ) );

            if ( textEdit == null )
            {
                // Try formatting as unknown kind
                textEdit = formatter.format( CodeFormatter.K_UNKNOWN, combinedCode, completionOffset, completionLength, getIndentationLevel( codeBefore ),
                        lineDelimiterFor( combinedCode, null ) );
            }

            if ( textEdit == null )
            {
                // Formatting failed, return original
                return completion;
            }

            // Apply the formatting to get the result
            IDocument document = new Document( combinedCode );
            textEdit.apply( document );

            // Extract the formatted completion (everything after the original
            // code before cursor)
            String formattedCombined = document.get();

            // The formatted code might have different length, so we need to
            // extract the completion part
            // by removing the (possibly reformatted) prefix
            if ( formattedCombined.length() > codeBefore.length() )
            {
                // Find where the completion starts - look for the completion in
                // the formatted result
                String formattedCompletion = formattedCombined.substring( codeBefore.length() );
                return formattedCompletion;
            }

            return completion;

        }
        catch ( Exception e )
        {
            logger.warn( "Failed to format completion: " + e.getMessage() );
            return completion;
        }
    }

    /**
     * Gets the formatter options for the given editor's project.
     */
    private Map<String, String> getFormatterOptionsForEditor( ITextEditor editor )
    {
        try
        {
            // Try to get project from editor
            if ( editor.getEditorInput() instanceof IFileEditorInput )
            {
                IFile file = ( (IFileEditorInput) editor.getEditorInput() ).getFile();
                if ( file != null && file.getProject() != null )
                {
                    IJavaProject javaProject = JavaCore.create( file.getProject() );
                    if ( javaProject != null && javaProject.exists() )
                    {
                        return javaProject.getOptions( true );
                    }
                }
            }

            // Fall back to workspace defaults
            return JavaCore.getOptions();
        }
        catch ( Exception e )
        {
            return JavaCore.getOptions();
        }
    }

    /**
     * Calculates the indentation level based on the code before cursor.
     */
    private int getIndentationLevel( String codeBefore )
    {
        if ( codeBefore == null || codeBefore.isEmpty() )
        {
            return 0;
        }

        // Find the last line
        int lastNewline = codeBefore.lastIndexOf( '\n' );
        String lastLine = ( lastNewline >= 0 ) ? codeBefore.substring( lastNewline + 1 ) : codeBefore;

        // Count leading tabs/spaces
        int indent = 0;
        for ( char c : lastLine.toCharArray() )
        {
            if ( c == '\t' )
            {
                indent++;
            }
            else if ( c == ' ' )
            {
                // Assuming 4 spaces = 1 indent level (common default)
                // This will be adjusted by the formatter anyway
            }
            else
            {
                break;
            }
        }

        return indent;
    }

    /**
     * Applies a set of replacements to a file as one transaction.
     * <p>
     * The change itself is performed by the platform's {@link org.eclipse.text.edits.TextEdit}
     * tree rather than by arithmetic here, which is what makes it atomic: a
     * {@link MultiTextEdit} rejects overlapping children outright, shifts later edits
     * as earlier ones change the length, and applies all of them or none. The file is
     * written once, so the whole batch produces exactly one local-history entry and
     * one undo point.
     *
     * @param expectedModificationStamp the stamp the caller read, or
     *            {@link IResource#NULL_STAMP} to skip the staleness check
     * @param preview when true, computes the result and the diff without writing
     */
    public EditResult applyTextEdits( String projectName, String filePath, long expectedModificationStamp,
                                      List<TextEditRequest> requestedEdits, boolean preview )
    {
        // The caller wrote these edits, so a diff would only echo them back; a preview is
        // the one case where seeing the outcome is the point, and it keeps the diff.
        return applyTextEdits( projectName, filePath, expectedModificationStamp, requestedEdits, preview, false );
    }

    /**
     * @param showDiff whether an applied result carries the diff - the only account
     *            of an edit the IDE computed, such as a format or organize imports
     */
    private EditResult applyTextEdits( String projectName, String filePath, long expectedModificationStamp,
                                       List<TextEditRequest> requestedEdits, boolean preview, boolean showDiff )
    {
        Objects.requireNonNull( projectName );
        Objects.requireNonNull( filePath );
        Objects.requireNonNull( requestedEdits );

        IFile file = resolveEditableFile( projectName, filePath );
        ResourceVersion before = ResourceVersion.of( file );

        if ( !before.matches( expectedModificationStamp ) )
        {
            return EditResult.versionConflict( file, before, expectedModificationStamp );
        }

        if ( requestedEdits.isEmpty() )
        {
            return EditResult.rejected( file, before, Diagnostic.fatal(
                    DiagnosticCode.INVALID_RANGE, "No edits were supplied." ) );
        }

        try
        {
            String original = ResourceUtilities.readFileContent( file );
            IDocument document = new Document( original );

            MultiTextEdit root = new MultiTextEdit();
            List<ContentRange> oldRanges = new ArrayList<>();
            List<ReplaceEdit> children = new ArrayList<>();

            for ( TextEditRequest requested : requestedEdits )
            {
                IRegion region;
                try
                {
                    region = requested.range().toRegion( document );
                }
                catch ( BadLocationException e )
                {
                    return EditResult.rejected( file, before, Diagnostic.fatal(
                            DiagnosticCode.INVALID_RANGE,
                            "Range " + requested.range() + " is outside " + filePath + "." ) );
                }

                if ( requested.expectedText() != null )
                {
                    String actual = document.get( region.getOffset(), region.getLength() );
                    if ( !actual.equals( requested.expectedText() ) )
                    {
                        return EditResult.rejected( file, before, Diagnostic.retryable(
                                DiagnosticCode.TEXT_NOT_FOUND,
                                "Range " + requested.range() + " holds " + quoteForMessage( actual )
                                        + ", not the expected " + quoteForMessage( requested.expectedText() )
                                        + ". Re-read the resource and recompute the edit." ) );
                    }
                }

                ReplaceEdit child = new ReplaceEdit( region.getOffset(), region.getLength(), requested.replacement() );
                oldRanges.add( ContentRange.of( document, region.getOffset(), region.getLength() ) );
                children.add( child );
                root.addChild( child );
            }

            try
            {
                // UPDATE_REGIONS leaves each child reporting where it ended up, which
                // is how the new ranges below are read back.
                root.apply( document, org.eclipse.text.edits.TextEdit.UPDATE_REGIONS );
            }
            catch ( MalformedTreeException e )
            {
                return EditResult.rejected( file, before, Diagnostic.fatal(
                        DiagnosticCode.OVERLAPPING_EDITS,
                        "The requested edits overlap, so they cannot be applied as one transaction: "
                                + e.getMessage() ) );
            }
            catch ( BadLocationException e )
            {
                return EditResult.rejected( file, before, Diagnostic.fatal(
                        DiagnosticCode.INVALID_RANGE, "An edit fell outside " + filePath + ": " + e.getMessage() ) );
            }

            String updated = document.get();
            String diff = showDiff || preview
                    ? UnifiedDiffs.diff( original, filePath, updated, filePath, UnifiedDiffs.DEFAULT_CONTEXT_LINES )
                    : UnifiedDiffs.omitted( UnifiedDiffs.compare( original, updated, 0 ), "the change is the one requested" );

            List<AppliedEdit> applied = new ArrayList<>();
            for ( int i = 0; i < children.size(); i++ )
            {
                ReplaceEdit child = children.get( i );
                ContentRange oldRange = oldRanges.get( i );
                ContentRange newRange = ContentRange.of( document, child.getOffset(), child.getLength() );
                applied.add( new AppliedEdit( oldRange, newRange, child.getLength(),
                        requestedEdits.get( i ).range().isEmpty() ? 0 : oldRangeLength( oldRange, document ) ) );
            }

            ContentRange combined = combinedRange( applied );

            if ( preview )
            {
                // Nothing was written, so nothing is affected: the diff says what would
                // change, and affectedResources says what did.
                return new EditResult( EditStatus.PREVIEW,
                        file.getProject().getName(), file.getProjectRelativePath().toString(),
                        before, before, applied, diff, List.of(),
                        EditorReveal.none(), EditResult.NO_UNDO_STATE, null, Diagnostic.none() );
            }

            try ( ByteArrayInputStream source = new ByteArrayInputStream(
                    updated.getBytes( Charset.forName( file.getCharset() ) ) ) )
            {
                file.setContents( source, IResource.FORCE | IResource.KEEP_HISTORY, null );
            }

            // After the write: this is the content the batch replaced.
            IFileState undoState = currentHistoryState( file );
            EditSynchronization synchronization = synchronizeAfterEdit( file, combined.startLine(), undoState );

            List<Diagnostic> diagnostics = new ArrayList<>();
            EditorReveal reveal = describeReveal( file, combined, diagnostics );

            return new EditResult(
                    diagnostics.isEmpty() ? EditStatus.APPLIED : EditStatus.APPLIED_WITH_WARNINGS,
                    file.getProject().getName(),
                    file.getProjectRelativePath().toString(),
                    before,
                    synchronization.version(),
                    applied,
                    diff,
                    List.of( AffectedResource.of( file, ChangeKind.MODIFIED ) ),
                    reveal,
                    undoState != null ? undoState.getModificationTime() : EditResult.NO_UNDO_STATE,
                    synchronization.workspaceState(),
                    diagnostics );
        }
        catch ( CoreException | IOException | BadLocationException e )
        {
            logger.error( e.getMessage(), e );
            return EditResult.rejected( file, before, Diagnostic.fatal(
                    DiagnosticCode.INTERNAL_ERROR, ExceptionUtils.getRootCauseMessage( e ) ) );
        }
    }

    /**
     * Replaces occurrences of a literal string, refusing to guess when there is more
     * than one.
     * <p>
     * Matches are located with {@link FindReplaceDocumentAdapter}, the same search the
     * editor's Find/Replace uses, rather than by {@link String#indexOf}. The previous
     * behaviour was {@code String.replace}, which silently edited every occurrence and
     * reported only that "the string was replaced" - so a caller asking to change one
     * line could change five without ever learning it.
     *
     * @param occurrence which match to act on; {@link Occurrence#UNIQUE} rejects
     *            ambiguity instead of resolving it
     * @param occurrenceIndex the 1-based match to take when {@code occurrence} is
     *            {@link Occurrence#INDEX}
     */
    public EditResult replaceString( String projectName, String filePath, String oldString, String newString,
                                     Integer startLine, Integer endLine, long expectedModificationStamp,
                                     Occurrence occurrence, Integer occurrenceIndex, boolean preview )
    {
        Objects.requireNonNull( oldString, "oldString" );
        if ( oldString.isEmpty() )
        {
            throw new IllegalArgumentException( "Error: oldString cannot be empty." );
        }
        String replacement = newString == null ? "" : newString;
        Occurrence mode = occurrence == null ? Occurrence.UNIQUE : occurrence;

        IFile file = resolveEditableFile( projectName, filePath );
        ResourceVersion before = ResourceVersion.of( file );

        if ( !before.matches( expectedModificationStamp ) )
        {
            return EditResult.versionConflict( file, before, expectedModificationStamp );
        }

        try
        {
            IDocument document = new Document( ResourceUtilities.readFileContent( file ) );
            List<IRegion> matches = findOccurrences( document, oldString, startLine, endLine );

            if ( matches.isEmpty() )
            {
                return EditResult.rejected( file, before, Diagnostic.fatal(
                        DiagnosticCode.TEXT_NOT_FOUND,
                        "The specified string was not found in the file"
                                + describeSearchRange( startLine, endLine ) + ": "
                                + quoteForMessage( oldString ) + "." ) );
            }

            List<IRegion> selected = selectOccurrences( matches, mode, occurrenceIndex );
            if ( selected == null )
            {
                return EditResult.rejected( file, before, Diagnostic.fatal(
                        DiagnosticCode.AMBIGUOUS_MATCH,
                        matches.size() + " occurrences of " + quoteForMessage( oldString ) + " in " + filePath
                                + " at " + describeMatches( document, matches )
                                + ". Narrow the search with startLine/endLine, or pass occurrence="
                                + "FIRST, LAST, INDEX or ALL to say which you mean." ) );
            }

            List<TextEditRequest> edits = new ArrayList<>();
            for ( IRegion match : selected )
            {
                edits.add( new TextEditRequest(
                        ContentRange.of( document, match.getOffset(), match.getLength() ),
                        oldString,
                        replacement ) );
            }

            return applyTextEdits( projectName, filePath, expectedModificationStamp, edits, preview );
        }
        catch ( CoreException | IOException | BadLocationException e )
        {
            logger.error( e.getMessage(), e );
            return EditResult.rejected( file, before, Diagnostic.fatal(
                    DiagnosticCode.INTERNAL_ERROR, ExceptionUtils.getRootCauseMessage( e ) ) );
        }
    }

    /**
     * Every literal match of {@code needle}, optionally confined to a line range.
     */
    private List<IRegion> findOccurrences( IDocument document, String needle, Integer startLine, Integer endLine )
            throws BadLocationException
    {
        int totalLines = document.getNumberOfLines();

        // A start line past the end must be refused, not clamped: clamping would widen
        // the search to lines the caller deliberately excluded and edit them.
        if ( startLine != null && startLine > totalLines )
        {
            throw new RuntimeException( "Error: Start line " + startLine
                    + " is beyond the end of the file (total lines: " + totalLines + ")." );
        }
        if ( startLine != null && endLine != null && startLine > endLine )
        {
            throw new RuntimeException( "Error: Start line cannot be greater than end line." );
        }

        int searchStart = 0;
        int searchEnd = document.getLength();
        if ( startLine != null )
        {
            searchStart = document.getLineOffset( Math.max( 1, startLine ) - 1 );
        }
        if ( endLine != null )
        {
            int line = Math.min( endLine, totalLines );
            if ( line >= 1 )
            {
                searchEnd = document.getLineOffset( line - 1 ) + document.getLineLength( line - 1 );
            }
        }

        FindReplaceDocumentAdapter finder = new FindReplaceDocumentAdapter( document );
        List<IRegion> matches = new ArrayList<>();
        int from = searchStart;
        while ( from <= searchEnd )
        {
            IRegion match = finder.find( from, needle, true, true, false, false );
            if ( match == null || match.getOffset() + match.getLength() > searchEnd )
            {
                break;
            }
            matches.add( match );
            // A zero-length match cannot happen for a non-empty literal, but guard so a
            // future regex mode cannot spin here.
            from = match.getOffset() + Math.max( 1, match.getLength() );
        }
        return matches;
    }

    /**
     * @return the matches to edit, or null when the request is ambiguous and the
     *         caller must choose
     */
    private List<IRegion> selectOccurrences( List<IRegion> matches, Occurrence mode, Integer occurrenceIndex )
    {
        switch ( mode )
        {
            case UNIQUE:
                return matches.size() == 1 ? matches : null;
            case FIRST:
                return List.of( matches.get( 0 ) );
            case LAST:
                return List.of( matches.get( matches.size() - 1 ) );
            case ALL:
                return matches;
            case INDEX:
                if ( occurrenceIndex == null || occurrenceIndex < 1 || occurrenceIndex > matches.size() )
                {
                    throw new IllegalArgumentException( "Error: occurrenceIndex must be between 1 and "
                            + matches.size() + "." );
                }
                return List.of( matches.get( occurrenceIndex - 1 ) );
            default:
                return null;
        }
    }

    private String describeSearchRange( Integer startLine, Integer endLine )
    {
        if ( startLine == null && endLine == null )
        {
            return "";
        }
        return " within lines " + ( startLine == null ? 1 : startLine ) + "-"
                + ( endLine == null ? "end" : endLine );
    }

    /** Lists where the matches are, so the caller can pick one without re-reading. */
    private String describeMatches( IDocument document, List<IRegion> matches ) throws BadLocationException
    {
        StringBuilder sb = new StringBuilder();
        int shown = Math.min( matches.size(), 10 );
        for ( int i = 0; i < shown; i++ )
        {
            ContentRange range = ContentRange.of( document, matches.get( i ).getOffset(), matches.get( i ).getLength() );
            sb.append( i == 0 ? "" : ", " ).append( "#" ).append( i + 1 ).append( " " ).append( range );
        }
        if ( matches.size() > shown )
        {
            sb.append( ", and " ).append( matches.size() - shown ).append( " more" );
        }
        return sb.toString();
    }

    /** The span covering every applied edit, so one reveal shows the whole change. */
    private ContentRange combinedRange( List<AppliedEdit> applied )
    {
        if ( applied.isEmpty() )
        {
            return new ContentRange( 1, 1, 1, 1 );
        }
        ContentRange first = applied.get( 0 ).newRange();
        int startLine = first.startLine();
        int startColumn = first.startColumn();
        int endLine = first.endLine();
        int endColumn = first.endColumn();
        for ( AppliedEdit edit : applied )
        {
            ContentRange range = edit.newRange();
            if ( range.startLine() < startLine || ( range.startLine() == startLine && range.startColumn() < startColumn ) )
            {
                startLine = range.startLine();
                startColumn = range.startColumn();
            }
            if ( range.endLine() > endLine || ( range.endLine() == endLine && range.endColumn() > endColumn ) )
            {
                endLine = range.endLine();
                endColumn = range.endColumn();
            }
        }
        return new ContentRange( startLine, startColumn, endLine, endColumn );
    }

    private int oldRangeLength( ContentRange range, IDocument document )
    {
        try
        {
            IRegion region = range.toRegion( document );
            return region.getLength();
        }
        catch ( BadLocationException e )
        {
            return 0;
        }
    }

    /**
     * Brings the editor to the changed range and reports where it landed.
     * <p>
     * A reveal that fails is a warning, never a failure: the content is already
     * written, and telling the caller the edit failed would invite a destructive retry.
     */
    private EditorReveal describeReveal( IFile file, ContentRange range, List<Diagnostic> diagnostics )
    {
        AtomicBoolean opened = new AtomicBoolean( false );
        try
        {
            sync.syncExec( () -> {
                safeOpenEditor( file );
                revealLineInEditor( file, range.startLine() );
                opened.set( true );
            } );
        }
        catch ( RuntimeException e )
        {
            diagnostics.add( Diagnostic.fatal( DiagnosticCode.EDITOR_REVEAL_FAILED,
                    "The edit was applied, but the editor could not be revealed: " + e.getMessage() ) );
            return EditorReveal.none();
        }
        return new EditorReveal( opened.get(), range,
                new EditorPosition( range.endLine(), range.endColumn() ) );
    }

    /** A short, single-line rendering of text for a diagnostic message. */
    private static String quoteForMessage( String text )
    {
        String flattened = text.replace( "\r\n", "\\n" ).replace( "\n", "\\n" ).replace( "\t", "\\t" );
        if ( flattened.length() > 60 )
        {
            flattened = flattened.substring( 0, 57 ) + "...";
        }
        return "\"" + flattened + "\"";
    }

    /**
     * The offset at which a 1-based line starts. A line one past the last resolves to
     * the end of the document, which is how an append is addressed.
     */
    private static int lineStartOffset( IDocument document, int line ) throws BadLocationException
    {
        if ( line >= 1 && line - 1 < document.getNumberOfLines() )
        {
            return document.getLineOffset( line - 1 );
        }
        return document.getLength();
    }

    /**
     * The range covering lines {@code startLine} to {@code endLine} inclusive,
     * together with the delimiter that ends {@code endLine}.
     * <p>
     * The delimiter has to be part of the range: leaving it out would splice the
     * replacement onto the following line, and including it is what lets an empty
     * replacement actually remove the lines rather than blank them.
     */
    private static ContentRange wholeLineRange( IDocument document, int startLine, int endLine ) throws BadLocationException
    {
        int start = lineStartOffset( document, startLine );
        int end = lineStartOffset( document, endLine + 1 );
        return ContentRange.of( document, start, Math.max( 0, end - start ) );
    }

    /**
     * The number of lines the file actually holds.
     * <p>
     * {@link IDocument#getNumberOfLines()} counts the empty line that follows a
     * trailing delimiter, which is not a line any caller of a line-based tool means
     * when it says the file has n lines.
     */
    private static int contentLineCount( IDocument document ) throws BadLocationException
    {
        if ( document.getLength() == 0 )
        {
            return 0;
        }
        int lines = document.getNumberOfLines();
        return document.getLineLength( lines - 1 ) == 0 ? lines - 1 : lines;
    }

    /**
     * The smallest replacement that turns {@code original} into {@code updated}.
     * <p>
     * The common prefix and suffix are trimmed off, so a change to one part of a file
     * is reported - and revealed in the editor - as that part rather than as a
     * rewrite of everything.
     */
    private static TextEditRequest minimalReplacement( IDocument document, String original, String updated )
            throws BadLocationException
    {
        int limit = Math.min( original.length(), updated.length() );
        int prefix = 0;
        while ( prefix < limit && original.charAt( prefix ) == updated.charAt( prefix ) )
        {
            prefix++;
        }
        int suffix = 0;
        while ( suffix < limit - prefix
                && original.charAt( original.length() - 1 - suffix ) == updated.charAt( updated.length() - 1 - suffix ) )
        {
            suffix++;
        }
        int length = original.length() - prefix - suffix;
        return new TextEditRequest( ContentRange.of( document, prefix, length ),
                original.substring( prefix, prefix + length ),
                updated.substring( prefix, updated.length() - suffix ) );
    }

    /**
     * The result of a change that created or moved a resource rather than editing
     * text inside one.
     * <p>
     * Such a tool cannot go through {@link #applyTextEdits}: there is no single
     * document whose ranges the change can be expressed in. It keeps its own
     * mechanism and reports in the same shape only so a caller branches once - with
     * no edits and no diff, because the content did not change, only where it lives.
     *
     * @param moved the resource as it stands now, which is what a caller addresses next
     * @param affected every resource the change touched, {@code moved} among them.
     *            Empty when the call changed nothing at all
     */
    private EditResult resourceRelocated( IResource moved, ResourceVersion before, List<AffectedResource> affected,
                                          EditorReveal reveal, WorkspaceSync workspaceState, List<Diagnostic> diagnostics )
    {
        return new EditResult(
                diagnostics.isEmpty() ? EditStatus.APPLIED : EditStatus.APPLIED_WITH_WARNINGS,
                moved.getProject().getName(),
                moved.getProjectRelativePath().toString(),
                before,
                ResourceVersion.of( moved ),
                List.of(),
                "",
                affected,
                reveal,
                EditResult.NO_UNDO_STATE,
                workspaceState,
                diagnostics );
    }

    /**
     * A refactoring's refusal to proceed, as a diagnostic rather than an exception.
     * <p>
     * A failed precondition is exactly the kind of failure a caller can act on - it
     * says what about the code makes the refactoring unsafe - so it belongs in a
     * field with a code, not in prose thrown out of the call.
     *
     * @return a rejection, or null when the refactoring may go ahead
     */
    private EditResult refusedPrecondition( IResource resource, RefactoringStatus status )
    {
        if ( status == null || !status.hasFatalError() )
        {
            return null;
        }
        return EditResult.rejected( resource, ResourceVersion.of( resource ),
                Diagnostic.fatal( DiagnosticCode.REFACTORING_PRECONDITION_FAILED,
                        status.getMessageMatchingSeverity( RefactoringStatus.FATAL ) ) );
    }

    /** A rejection carrying a range the file cannot satisfy. */
    private EditResult invalidRange( IFile file, String message )
    {
        return EditResult.rejected( file, ResourceVersion.of( file ),
                Diagnostic.fatal( DiagnosticCode.INVALID_RANGE, message ) );
    }

    /**
     * Checks a 1-based inclusive line range against the file.
     * <p>
     * Anything the file cannot satisfy is refused rather than clamped. A caller that
     * names a range means that range: quietly widening or narrowing it edits lines
     * the caller deliberately excluded, and says nothing about having done so.
     *
     * @return a rejection, or null when the range is usable
     */
    private EditResult validateLineRange( IFile file, String filePath, int startLine, int endLine, int lineCount )
    {
        if ( startLine < 1 )
        {
            return invalidRange( file, "Start line must be at least 1, was " + startLine + "." );
        }
        if ( endLine < startLine )
        {
            return invalidRange( file, "End line " + endLine + " precedes start line " + startLine + "." );
        }
        if ( startLine > lineCount )
        {
            return invalidRange( file, "Start line " + startLine + " is beyond the end of the file: "
                    + filePath + " has " + lineCount + " line(s)." );
        }
        if ( endLine > lineCount )
        {
            return invalidRange( file, "End line " + endLine + " is beyond the end of the file: "
                    + filePath + " has " + lineCount + " line(s)." );
        }
        return null;
    }

    /** The rejection an unexpected failure inside an editing tool produces. */
    private EditResult internalFailure( IFile file, Exception e )
    {
        logger.error( e.getMessage(), e );
        return EditResult.rejected( file, ResourceVersion.of( file ),
                Diagnostic.fatal( DiagnosticCode.INTERNAL_ERROR, ExceptionUtils.getRootCauseMessage( e ) ) );
    }

    /** What a refactoring is about to change, and which of it was there beforehand. */
    private record PendingChanges( List<IResource> resources, Set<IPath> existedBefore )
    {
    }

    /**
     * The resources a refactoring is about to change, read out of its change tree.
     * <p>
     * Must be called <em>before</em> {@link Change#perform}: afterwards the tree has
     * been replaced by the undo change and describes the reverse operation. It walks
     * the leaves' {@link Change#getModifiedElement()} rather than asking
     * {@link Change#getAffectedObjects()}, which is documented to return null whenever
     * a change cannot work the set out - precisely the case where it would be needed.
     */
    private static PendingChanges pendingChanges( Change change )
    {
        LinkedHashMap<IPath, IResource> touched = new LinkedHashMap<>();
        collectModifiedResources( change, touched );

        Set<IPath> existed = new HashSet<>();
        touched.forEach( ( path, resource ) -> {
            if ( resource.exists() )
            {
                existed.add( path );
            }
        } );
        return new PendingChanges( List.copyOf( touched.values() ), existed );
    }

    private static void collectModifiedResources( Change change, LinkedHashMap<IPath, IResource> into )
    {
        if ( change == null )
        {
            return;
        }
        IResource resource = modifiedResource( change.getModifiedElement() );
        if ( resource != null )
        {
            into.putIfAbsent( resource.getFullPath(), resource );
        }
        // A composite carries no element of its own, so its children are the answer.
        if ( change instanceof CompositeChange composite )
        {
            for ( Change child : composite.getChildren() )
            {
                collectModifiedResources( child, into );
            }
        }
    }

    /** The workspace resource a change names, whether it names it as a Java element or not. */
    private static IResource modifiedResource( Object element )
    {
        if ( element instanceof IResource resource )
        {
            return resource;
        }
        if ( element instanceof IJavaElement javaElement )
        {
            // Null for anything with no file behind it - a type inside a JAR.
            return javaElement.getResource();
        }
        return null;
    }

    /**
     * What a performed refactoring changed, in the form the result reports it.
     * <p>
     * Call after {@link Change#perform}, so that every version is the one the change
     * left behind rather than the one the caller already holds. The kind is derived
     * rather than guessed: a resource the refactoring named and that is no longer at
     * its address was moved away or deleted, and either way re-reading that address
     * now fails - which is the thing a caller most needs to be told.
     *
     * @param primary the resource the result is addressed to, listed first. Its kind
     *            is the operation's own - a rename moved it, an extract created it -
     *            because the tree alone cannot tell a move from a deletion
     */
    private static List<AffectedResource> affectedBy( PendingChanges pending, IResource primary, ChangeKind primaryKind )
    {
        LinkedHashMap<IPath, AffectedResource> byPath = new LinkedHashMap<>();
        if ( primary != null )
        {
            byPath.put( primary.getFullPath(), AffectedResource.of( primary, primaryKind ) );
        }
        for ( IResource resource : pending.resources() )
        {
            IPath path = resource.getFullPath();
            if ( byPath.containsKey( path ) )
            {
                continue;
            }
            ChangeKind kind = !resource.exists() ? ChangeKind.DELETED
                    : pending.existedBefore().contains( path ) ? ChangeKind.MODIFIED : ChangeKind.CREATED;
            byPath.put( path, AffectedResource.of( resource, kind ) );
        }
        return List.copyOf( byPath.values() );
    }

    /**
     * Resolves a file that must exist and be writable, applying the same access rules
     * as the other editing entry points.
     */
    private IFile resolveEditableFile( String projectName, String filePath )
    {
        IWorkspaceRoot root = ResourcesPlugin.getWorkspace().getRoot();
        IProject project = root.getProject( projectName );
        if ( !project.exists() )
        {
            throw new RuntimeException( "Error: Project '" + projectName + "' does not exist." );
        }
        if ( !project.isOpen() )
        {
            throw new RuntimeException( "Error: Project '" + projectName + "' is closed." );
        }
        IFile file = project.getFile( IPath.fromPath( Path.of( filePath ) ) );
        if ( !file.exists() )
        {
            throw new RuntimeException( "Error: File '" + filePath + "' does not exist in project '" + projectName + "'." );
        }
        aiIgnoreService.assertAccessAllowed( file );
        return file;
    }

    /**
     * The local-history state an edit just displaced.
     * <p>
     * Must be read <em>after</em> the write: every write carries
     * {@link IResource#KEEP_HISTORY}, so the content it replaced becomes the newest
     * stored state at that point. Read before the write this would return the
     * <em>previous</em> edit's content, which is not what undoing this one restores.
     * It is the same state {@link #undoEdit} restores from.
     *
     * @return the most recent stored state, or null when the file has no history yet
     */
    private IFileState currentHistoryState( IFile file )
    {
        try
        {
            IFileState[] history = file.getHistory( null );
            return ( history != null && history.length > 0 ) ? history[0] : null;
        }
        catch ( CoreException e )
        {
            // History is a convenience, not a precondition: an edit must not fail
            // because the history store could not be read.
            logger.warn( "Could not read local history for " + file.getFullPath() );
            return null;
        }
    }
}
