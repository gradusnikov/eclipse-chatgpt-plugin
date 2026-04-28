package com.github.gradusnikov.eclipse.assistai.mcp.services;

import java.io.ByteArrayInputStream;
import java.nio.charset.Charset;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Objects;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IFileState;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.ILog;
import org.eclipse.core.runtime.IPath;
import org.eclipse.e4.core.di.annotations.Creatable;
import org.eclipse.e4.ui.di.UISynchronize;

import com.github.gradusnikov.eclipse.assistai.services.AiIgnoreService;
import com.github.gradusnikov.eclipse.assistai.tools.ResourceUtilities;

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

    public String getFileHistory( String projectName, String filePath, String maxEntries )
    {
        IFile file = resolveFile( projectName, filePath );

        int limit = 20;
        if ( maxEntries != null && !maxEntries.isBlank() )
        {
            try { limit = Integer.parseInt( maxEntries.trim() ); }
            catch ( NumberFormatException e ) { /* keep default */ }
        }

        try
        {
            IFileState[] history = file.getHistory( null );
            if ( history == null || history.length == 0 )
            {
                return "No local history found for " + filePath;
            }

            StringBuilder sb = new StringBuilder();
            sb.append( "# Local History for " ).append( filePath ).append( "\n\n" );
            sb.append( String.format( "%-6s  %-20s  %s\n", "Index", "Timestamp", "Size" ) );
            sb.append( "-".repeat( 50 ) ).append( "\n" );

            int count = Math.min( history.length, limit );
            for ( int i = 0; i < count; i++ )
            {
                IFileState state = history[i];
                Instant ts = Instant.ofEpochMilli( state.getModificationTime() );
                String size = state.exists() ? formatSize( state.getContents().available() ) : "deleted";
                sb.append( String.format( "%-6d  %-20s  %s\n", i, TIMESTAMP_FMT.format( ts ), size ) );
            }

            if ( history.length > count )
            {
                sb.append( "\n(" ).append( history.length - count ).append( " older entries not shown)\n" );
            }

            return sb.toString();
        }
        catch ( Exception e )
        {
            throw new RuntimeException( "Error reading local history for " + filePath + ": " + e.getMessage(), e );
        }
    }

    public String getFileHistoryContent( String projectName, String filePath, String index )
    {
        IFile file = resolveFile( projectName, filePath );
        int idx = parseIndex( index );

        try
        {
            IFileState[] history = file.getHistory( null );
            if ( history == null || history.length == 0 )
            {
                return "No local history found for " + filePath;
            }
            if ( idx < 0 || idx >= history.length )
            {
                return "Invalid index " + idx + ". Valid range: 0-" + ( history.length - 1 );
            }

            IFileState state = history[idx];
            Instant ts = Instant.ofEpochMilli( state.getModificationTime() );
            String content = new String( ResourceUtilities.readInputStream( state.getContents() ),
                    Charset.forName( file.getCharset() ) );

            StringBuilder sb = new StringBuilder();
            sb.append( "# " ).append( filePath ).append( " @ " ).append( TIMESTAMP_FMT.format( ts ) ).append( "\n\n" );

            String[] lines = content.split( "\n", -1 );
            for ( int i = 0; i < lines.length; i++ )
            {
                sb.append( String.format( "%5d\t%s\n", i + 1, lines[i] ) );
            }

            return sb.toString();
        }
        catch ( Exception e )
        {
            throw new RuntimeException( "Error reading history content: " + e.getMessage(), e );
        }
    }

    public String restoreFileVersion( String projectName, String filePath, String index )
    {
        IFile file = resolveFile( projectName, filePath );
        int idx = parseIndex( index );

        try
        {
            IFileState[] history = file.getHistory( null );
            if ( history == null || history.length == 0 )
            {
                throw new RuntimeException( "No local history found for " + filePath );
            }
            if ( idx < 0 || idx >= history.length )
            {
                throw new RuntimeException( "Invalid index " + idx + ". Valid range: 0-" + ( history.length - 1 ) );
            }

            IFileState state = history[idx];
            Instant ts = Instant.ofEpochMilli( state.getModificationTime() );

            String content = new String( ResourceUtilities.readInputStream( state.getContents() ),
                    Charset.forName( file.getCharset() ) );

            try ( ByteArrayInputStream source = new ByteArrayInputStream(
                    content.getBytes( Charset.forName( file.getCharset() ) ) ) )
            {
                file.setContents( source, IResource.FORCE | IResource.KEEP_HISTORY, null );
            }

            file.getParent().refreshLocal( IResource.DEPTH_ONE, null );

            sync.asyncExec( () -> {
                refreshEditor( file );
            } );

            return "Restored " + filePath + " to version from " + TIMESTAMP_FMT.format( ts ) + " (index " + idx + ")";
        }
        catch ( RuntimeException e )
        {
            throw e;
        }
        catch ( Exception e )
        {
            throw new RuntimeException( "Error restoring file version: " + e.getMessage(), e );
        }
    }

    public String compareWithHistory( String projectName, String filePath, String index )
    {
        IFile file = resolveFile( projectName, filePath );
        int idx = parseIndex( index );

        try
        {
            IFileState[] history = file.getHistory( null );
            if ( history == null || history.length == 0 )
            {
                return "No local history found for " + filePath;
            }
            if ( idx < 0 || idx >= history.length )
            {
                return "Invalid index " + idx + ". Valid range: 0-" + ( history.length - 1 );
            }

            IFileState state = history[idx];
            Instant ts = Instant.ofEpochMilli( state.getModificationTime() );

            String charset = file.getCharset();
            String oldContent = new String( ResourceUtilities.readInputStream( state.getContents() ),
                    Charset.forName( charset ) );
            String newContent = new String( ResourceUtilities.readInputStream( file.getContents() ),
                    Charset.forName( charset ) );

            String[] oldLines = oldContent.split( "\n", -1 );
            String[] newLines = newContent.split( "\n", -1 );

            StringBuilder sb = new StringBuilder();
            sb.append( "# Diff: " ).append( filePath ).append( "\n" );
            sb.append( "# Current vs. " ).append( TIMESTAMP_FMT.format( ts ) ).append( " (index " ).append( idx ).append( ")\n\n" );
            sb.append( "--- " ).append( filePath ).append( " (" ).append( TIMESTAMP_FMT.format( ts ) ).append( ")\n" );
            sb.append( "+++ " ).append( filePath ).append( " (current)\n" );

            appendSimpleDiff( sb, oldLines, newLines );

            return sb.toString();
        }
        catch ( Exception e )
        {
            throw new RuntimeException( "Error comparing with history: " + e.getMessage(), e );
        }
    }

    public long getFileModificationTime( IFile file )
    {
        try
        {
            IFileState[] history = file.getHistory( null );
            if ( history != null && history.length > 0 )
            {
                return history[0].getModificationTime();
            }
        }
        catch ( Exception e )
        {
            logger.warn( "Could not read local history for " + file.getFullPath() );
        }
        return file.getLocalTimeStamp();
    }

    // --- helpers ---

    private IFile resolveFile( String projectName, String filePath )
    {
        Objects.requireNonNull( projectName, "projectName is required" );
        Objects.requireNonNull( filePath, "filePath is required" );

        IProject project = ResourcesPlugin.getWorkspace().getRoot().getProject( projectName );
        if ( !project.exists() || !project.isOpen() )
        {
            throw new RuntimeException( "Project '" + projectName + "' not found or not open" );
        }

        IFile file = project.getFile( IPath.fromPath( java.nio.file.Path.of( filePath ) ) );
        if ( !file.exists() )
        {
            throw new RuntimeException( "File '" + filePath + "' not found in project '" + projectName + "'" );
        }
        aiIgnoreService.assertAccessAllowed( file );
        return file;
    }

    private int parseIndex( String index )
    {
        Objects.requireNonNull( index, "index is required" );
        try
        {
            return Integer.parseInt( index.trim() );
        }
        catch ( NumberFormatException e )
        {
            throw new RuntimeException( "Invalid index: " + index );
        }
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

    private void appendSimpleDiff( StringBuilder sb, String[] oldLines, String[] newLines )
    {
        int maxLen = Math.max( oldLines.length, newLines.length );
        int contextSize = 3;
        boolean inHunk = false;
        int hunkStart = -1;

        for ( int i = 0; i < maxLen; i++ )
        {
            String oldLine = i < oldLines.length ? oldLines[i] : null;
            String newLine = i < newLines.length ? newLines[i] : null;
            boolean different = !Objects.equals( oldLine, newLine );

            if ( different )
            {
                if ( !inHunk )
                {
                    int start = Math.max( 0, i - contextSize );
                    sb.append( String.format( "@@ -%d +%d @@\n", start + 1, start + 1 ) );
                    for ( int c = start; c < i; c++ )
                    {
                        sb.append( " " ).append( c < oldLines.length ? oldLines[c] : "" ).append( "\n" );
                    }
                    inHunk = true;
                    hunkStart = i;
                }
                if ( oldLine != null ) sb.append( "-" ).append( oldLine ).append( "\n" );
                if ( newLine != null ) sb.append( "+" ).append( newLine ).append( "\n" );
            }
            else if ( inHunk )
            {
                if ( i - hunkStart > contextSize * 2 )
                {
                    for ( int c = 0; c < contextSize && ( hunkStart + c ) < i; c++ )
                    {
                        int idx = hunkStart + c;
                        if ( idx < oldLines.length )
                        {
                            sb.append( " " ).append( oldLines[idx] ).append( "\n" );
                        }
                    }
                    inHunk = false;
                }
                else
                {
                    sb.append( " " ).append( oldLine ).append( "\n" );
                    hunkStart = i;
                }
            }
        }
    }

    private String formatSize( long bytes )
    {
        if ( bytes < 1024 ) return bytes + " B";
        if ( bytes < 1024 * 1024 ) return String.format( "%.1f KB", bytes / 1024.0 );
        return String.format( "%.1f MB", bytes / ( 1024.0 * 1024.0 ) );
    }
}
