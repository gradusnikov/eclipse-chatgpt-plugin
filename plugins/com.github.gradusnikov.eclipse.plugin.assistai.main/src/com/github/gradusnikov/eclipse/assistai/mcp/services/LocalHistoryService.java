package com.github.gradusnikov.eclipse.assistai.mcp.services;

import java.io.ByteArrayInputStream;
import java.nio.charset.Charset;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Objects;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IFileState;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.ILog;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.e4.core.di.annotations.Creatable;
import org.eclipse.e4.ui.di.UISynchronize;

import com.github.gradusnikov.eclipse.assistai.mcp.results.DiffResponse;
import com.github.gradusnikov.eclipse.assistai.mcp.results.FileHistoryResponse;
import com.github.gradusnikov.eclipse.assistai.resources.ContentRange;
import com.github.gradusnikov.eclipse.assistai.mcp.results.Diagnostic;
import com.github.gradusnikov.eclipse.assistai.mcp.results.DiagnosticCode;
import com.github.gradusnikov.eclipse.assistai.resources.EditResult;
import com.github.gradusnikov.eclipse.assistai.resources.EditResult.AffectedResource;
import com.github.gradusnikov.eclipse.assistai.resources.EditResult.ChangeKind;
import com.github.gradusnikov.eclipse.assistai.resources.EditResult.EditStatus;
import com.github.gradusnikov.eclipse.assistai.resources.EditResult.EditorReveal;
import com.github.gradusnikov.eclipse.assistai.resources.EditResult.WorkspaceSync;
import com.github.gradusnikov.eclipse.assistai.resources.ResourceDescriptor;
import com.github.gradusnikov.eclipse.assistai.resources.ResourceReadResult;
import com.github.gradusnikov.eclipse.assistai.resources.ResourceVersion;
import com.github.gradusnikov.eclipse.assistai.resources.SourceOrigin;
import com.github.gradusnikov.eclipse.assistai.services.AiIgnoreService;
import com.github.gradusnikov.eclipse.assistai.tools.LineOffsets;
import com.github.gradusnikov.eclipse.assistai.tools.ResourceUtilities;
import com.github.gradusnikov.eclipse.assistai.tools.UnifiedDiffs;

import jakarta.inject.Inject;

@Creatable
public class LocalHistoryService
{
    @Inject
    ILog logger;

    @Inject
    UISynchronize sync;

    @Inject
    AiIgnoreService aiIgnoreService;

    private static final DateTimeFormatter TIMESTAMP_FMT = DateTimeFormatter
            .ofPattern( "yyyy-MM-dd HH:mm:ss" )
            .withZone( ZoneId.systemDefault() );

    public FileHistoryResponse getFileHistory( String projectName, String filePath, String maxEntries )
    {
        int limit = 20;
        if ( maxEntries != null && !maxEntries.isBlank() )
        {
            try { limit = Integer.parseInt( maxEntries.trim() ); }
            catch ( NumberFormatException e ) { /* keep default */ }
        }

        try
        {
            IFile file = resolveFile( projectName, filePath );
            return FileHistoryResponse.from( projectName, filePath, file.getHistory( null ), limit );
        }
        catch ( Exception e )
        {
            return FileHistoryResponse.failed( projectName, filePath, diagnosticFor( e ) );
        }
    }

    /**
     * Reads one stored state as a resource, on the same shape as reading the file
     * itself.
     * <p>
     * The content is exact - the previous rendering prefixed every line with its
     * number, which made the result unusable as input to anything and had to be
     * undone by hand before the text could be diffed or restored.
     * {@link SourceOrigin#LOCAL_HISTORY} and {@code readOnly} say why it cannot be
     * written back, and {@code version.historyTimestamp} addresses this same content
     * again after any number of further saves.
     */
    public ResourceReadResult getFileHistoryContent( String projectName, String filePath, String historyTimestamp )
    {
        try
        {
            IFile file = resolveFile( projectName, filePath );
            IFileState state = findHistoryState( file, historyTimestamp );
            String content = new String( ResourceUtilities.readInputStream( state.getContents() ),
                    Charset.forName( file.getCharset() ) );
            int totalLines = LineOffsets.countLines( content );

            return new ResourceReadResult(
                    ResourceReadResult.ReadStatus.OK,
                    historyUri( file, state ),
                    projectName,
                    file.getProjectRelativePath().toString(),
                    ResourceUtilities.getResourceFileType( file ),
                    ResourceVersion.ofHistoryState( state ),
                    new ContentRange( 1, 1, Math.max( 1, totalLines ), 1 ),
                    totalLines,
                    content,
                    SourceOrigin.LOCAL_HISTORY,
                    true,
                    false,
                    List.of(),
                    Diagnostic.none() );
        }
        catch ( Exception e )
        {
            return ResourceReadResult.failed( projectName, filePath, diagnosticFor( e ) );
        }
    }

    /**
     * Restores a stored state, reported as an edit like any other.
     * <p>
     * A restore is a write, so it returns what every write returns: the versions
     * either side of it, the diff it produced, and the history timestamp that undoes
     * it. That last one is what made the old sentence - "run getFileHistory again to
     * find it" - unnecessary: the entry this restore created is a field.
     */
    public EditResult restoreFileVersion( String projectName, String filePath, String historyTimestamp )
    {
        try
        {
            IFile file = resolveFile( projectName, filePath );
            IFileState state = findHistoryState( file, historyTimestamp );
            Charset charset = Charset.forName( file.getCharset() );

            String restored = new String( ResourceUtilities.readInputStream( state.getContents() ), charset );
            String displaced = new String( ResourceUtilities.readInputStream( file.getContents() ), charset );

            ResourceVersion versionBefore = ResourceVersion.of( file );

            IStatus writable = WriteAccessValidator.ensureWritable( file );
            if ( !writable.isOK() )
            {
                return EditResult.rejected( file, versionBefore, Diagnostic.fatal(
                        DiagnosticCode.READ_ONLY_RESOURCE, writable.getMessage() ) );
            }

            try ( ByteArrayInputStream source = new ByteArrayInputStream( restored.getBytes( charset ) ) )
            {
                file.setContents( source, IResource.FORCE | IResource.KEEP_HISTORY, null );
            }

            file.getParent().refreshLocal( IResource.DEPTH_ONE, null );

            // Read after the write: KEEP_HISTORY has by now made the displaced content
            // the newest stored state, which is what undoing this restore returns to.
            IFileState undoState = newestHistoryState( file );

            sync.asyncExec( () -> refreshEditor( file ) );

            UnifiedDiffs.Unified diff = UnifiedDiffs.compare( displaced, restored,
                    UnifiedDiffs.DEFAULT_CONTEXT_LINES );

            // A restore whose stored state matches the current content is a successful
            // no-op, not a fault: the empty unifiedDiff already says nothing changed.
            // Reporting it as a fatal INTERNAL_ERROR beside a status of APPLIED
            // contradicted itself, and a caller branching on INTERNAL_ERROR - the right
            // thing to do - treated a harmless restore as a bug.
            return new EditResult(
                    EditStatus.APPLIED,
                    file.getProject().getName(),
                    file.getProjectRelativePath().toString(),
                    versionBefore,
                    ResourceVersion.of( file ),
                    List.of(),
                    diff.body(),
                    List.of( AffectedResource.of( file, ChangeKind.MODIFIED ) ),
                    EditorReveal.none(),
                    undoState == null ? EditResult.NO_UNDO_STATE : undoState.getModificationTime(),
                    new WorkspaceSync( true, false, "not-applicable" ),
                    Diagnostic.none() );
        }
        catch ( Exception e )
        {
            return EditResult.rejected( projectName, filePath, ResourceVersion.UNKNOWN, diagnosticFor( e ) );
        }
    }

    /**
     * Diffs the current content against a stored state.
     * <p>
     * The two versions compared are fields, so a caller can act on the result without
     * reading the header lines that used to carry them. "No differences" is
     * {@code identical}, not a sentence in place of the diff.
     */
    public DiffResponse compareWithHistory( String projectName, String filePath, String historyTimestamp )
    {
        try
        {
            IFile file = resolveFile( projectName, filePath );
            IFileState state = findHistoryState( file, historyTimestamp );
            Instant ts = Instant.ofEpochMilli( state.getModificationTime() );

            Charset charset = Charset.forName( file.getCharset() );
            String oldContent = new String( ResourceUtilities.readInputStream( state.getContents() ), charset );
            String newContent = new String( ResourceUtilities.readInputStream( file.getContents() ), charset );

            return DiffResponse.of( projectName, file.getProjectRelativePath().toString(),
                    TIMESTAMP_FMT.format( ts ), ResourceVersion.ofHistoryState( state ),
                    "current", ResourceVersion.of( file ),
                    UnifiedDiffs.compare( oldContent, newContent, UnifiedDiffs.DEFAULT_CONTEXT_LINES ) );
        }
        catch ( Exception e )
        {
            return DiffResponse.failed( projectName, filePath, diagnosticFor( e ) );
        }
    }

    public long getFileModificationTime( IFile file )
    {
        IFileState newest = newestHistoryState( file );
        return newest == null ? file.getLocalTimeStamp() : newest.getModificationTime();
    }

    // --- helpers ---

    /**
     * The most recent stored state of a file, or null when it has no history.
     * <p>
     * History is a convenience, not a precondition: a failure to read the history
     * store must not take down the operation that asked.
     */
    private IFileState newestHistoryState( IFile file )
    {
        try
        {
            IFileState[] history = file.getHistory( null );
            return ( history != null && history.length > 0 ) ? history[0] : null;
        }
        catch ( Exception e )
        {
            logger.warn( "Could not read local history for " + file.getFullPath() );
            return null;
        }
    }

    /**
     * A URI naming one stored state.
     * <p>
     * Distinct from the file's own {@code workspace://} URI, so caching a historical
     * read cannot overwrite the cached current content of the same file. The
     * timestamp makes it stable: the same URI always denotes the same bytes.
     */
    private static String historyUri( IFile file, IFileState state )
    {
        // Encoded like every other resource URI: a file whose name contains a space
        // would otherwise produce something URI.create refuses to parse.
        return "history://" + ResourceDescriptor.encodePath( file.getFullPath().toString() )
                + "@" + state.getModificationTime();
    }

    /**
     * A failure that belongs in the result's diagnostics rather than on the stack.
     * <p>
     * These tools return records that declare a {@code diagnostics} list, so throwing
     * meant a client reading {@code structuredContent} - which the advertised
     * {@code outputSchema} tells it to do - received nothing at all on failure and had
     * to fall back to matching English. The service still throws internally, because
     * that keeps the happy path readable; each public method catches at its own
     * boundary and builds the failure in its own shape.
     */
    private static final class HistoryFailure extends RuntimeException
    {
        private static final long serialVersionUID = 1L;

        private final transient Diagnostic diagnostic;

        HistoryFailure( DiagnosticCode code, String message )
        {
            super( message );
            this.diagnostic = Diagnostic.fatal( code, message );
        }

        Diagnostic diagnostic()
        {
            return diagnostic;
        }
    }

    /** Maps anything unclassified onto a diagnostic, so no failure escapes as a throw. */
    private static Diagnostic diagnosticFor( Exception e )
    {
        return e instanceof HistoryFailure failure
                ? failure.diagnostic()
                : Diagnostic.fatal( DiagnosticCode.INTERNAL_ERROR,
                        e.getMessage() == null ? e.toString() : e.getMessage() );
    }

    private IFile resolveFile( String projectName, String filePath )
    {
        Objects.requireNonNull( projectName, "projectName is required" );
        Objects.requireNonNull( filePath, "filePath is required" );

        IProject project = ResourcesPlugin.getWorkspace().getRoot().getProject( projectName );
        if ( !project.exists() )
        {
            throw new HistoryFailure( DiagnosticCode.PROJECT_NOT_FOUND,
                    "Project '" + projectName + "' not found." );
        }
        if ( !project.isOpen() )
        {
            throw new HistoryFailure( DiagnosticCode.RESOURCE_NOT_ACCESSIBLE,
                    "Project '" + projectName + "' is closed." );
        }

        IFile file = project.getFile( IPath.fromPath( java.nio.file.Path.of( filePath ) ) );
        if ( !file.exists() )
        {
            throw new HistoryFailure( DiagnosticCode.RESOURCE_NOT_FOUND,
                    "File '" + filePath + "' not found in project '" + projectName + "'." );
        }
        if ( aiIgnoreService.isExcluded( file ) )
        {
            throw new HistoryFailure( DiagnosticCode.RESOURCE_NOT_ACCESSIBLE,
                    "'" + filePath + "' is excluded from AI processing by .aiignore." );
        }
        return file;
    }

    /**
     * Finds a stored state by its {@link IFileState#getModificationTime()}.
     * <p>
     * States are addressed by timestamp rather than by position in the history array,
     * because that array is newest-first: every save shifts every index, so an agent
     * that inspected index 3, saved, then restored index 3 restored a different
     * version than the one it read. A timestamp identifies the same content forever,
     * or fails loudly once the workspace has pruned it.
     *
     * @param historyTimestamp epoch milliseconds, as reported by
     *            {@link #getFileHistory(String, String, String)}
     */
    private IFileState findHistoryState( IFile file, String historyTimestamp ) throws CoreException
    {
        Objects.requireNonNull( historyTimestamp, "historyTimestamp is required" );

        long timestamp;
        try
        {
            timestamp = Long.parseLong( historyTimestamp.trim() );
        }
        catch ( NumberFormatException e )
        {
            throw new HistoryFailure( DiagnosticCode.INVALID_RANGE, "Invalid historyTimestamp: "
                    + historyTimestamp + ". Use the historyTimestamp field of getFileHistory." );
        }

        IFileState[] history = file.getHistory( null );
        if ( history == null || history.length == 0 )
        {
            throw new HistoryFailure( DiagnosticCode.HISTORY_UNAVAILABLE,
                    "No local history for " + file.getFullPath() + "." );
        }

        for ( IFileState state : history )
        {
            if ( state.getModificationTime() == timestamp )
            {
                return state;
            }
        }

        StringBuilder available = new StringBuilder();
        for ( int i = 0; i < history.length; i++ )
        {
            available.append( i == 0 ? "" : ", " ).append( history[i].getModificationTime() );
        }
        throw new HistoryFailure( DiagnosticCode.RESOURCE_VERSION_EXPIRED,
                "No history state with timestamp " + timestamp + " for " + file.getFullPath()
                        + ". It may have been pruned by the workspace's local history settings."
                        + " Available timestamps: " + available );
    }

    private void refreshEditor( IFile file )
    {
        try
        {
            var workbench = org.eclipse.ui.PlatformUI.getWorkbench();
            var page = workbench.getActiveWorkbenchWindow().getActivePage();
            for ( var ref : page.getEditorReferences() )
            {
                var input = ref.getEditorInput();
                if ( input instanceof org.eclipse.ui.IFileEditorInput fileInput
                        && fileInput.getFile().equals( file ) )
                {
                    var editor = ref.getEditor( false );
                    if ( editor instanceof org.eclipse.ui.texteditor.ITextEditor textEditor )
                    {
                        textEditor.doRevertToSaved();
                    }
                }
            }
        }
        catch ( Exception e )
        {
            logger.warn( "Could not refresh editor for " + file.getName() );
        }
    }
}
