package com.github.gradusnikov.eclipse.assistai.part.dnd.handlers;

import java.io.IOException;
import java.nio.file.Files;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.ILog;
import org.eclipse.core.runtime.Platform;
import org.eclipse.core.runtime.content.IContentTypeManager;
import org.eclipse.e4.core.di.annotations.Creatable;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.ISourceReference;
import org.eclipse.jdt.ui.JavaUI;
import org.eclipse.jface.text.Document;
import org.eclipse.jface.text.ITextSelection;
import org.eclipse.jface.viewers.ITreeSelection;
import org.eclipse.swt.dnd.Transfer;
import org.eclipse.swt.graphics.ImageData;
import org.eclipse.ui.texteditor.ITextEditor;

import com.github.gradusnikov.eclipse.assistai.part.Attachment.FileContentAttachment;
import com.github.gradusnikov.eclipse.assistai.part.ChatGPTPresenter;
import com.google.common.collect.Sets;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;

@Creatable
@Singleton
public class LocalSelectionTransferHandler implements ITransferHandler
{
    private static final Transfer TRANSFER = org.eclipse.jface.util.LocalSelectionTransfer.getTransfer();

    @Inject
    private ILog                  logger;
    @Inject
    private ChatGPTPresenter      presenter;

    @Override
    public Transfer getTransferType()
    {
        return TRANSFER;
    }

    @Override
    public void handleTransfer( Object data )
    {
        if ( data != null && data instanceof ITreeSelection )
        {
            ITreeSelection selection = (ITreeSelection) data;
            for( var treePath : selection.getPaths() )
            {
                Object lastElement = treePath.getLastSegment();
                
                if ( lastElement instanceof IFile )
                {
                    handleFile( (IFile) lastElement );
                }
                else if ( lastElement instanceof ICompilationUnit )
                {
                    handleCompilationUnit( (ICompilationUnit) lastElement );
                }
                
            }
        }
        
    }

    private void handleFile( IFile file )
    {
        try
        {
            if ( isTextFile( file ) )
            {
                var documentText = new String( Files.readAllBytes( file.getLocation().toFile().toPath() ), file.getCharset() );
                Document document = new Document(documentText);
                presenter.onAttachmentAdded( new FileContentAttachment( file.getFullPath().toString(), 1, document.getNumberOfLines(), documentText ) );
            }
            else if ( isImageFile( file ) )
            {
                ImageData imageData = new ImageData(  file.getLocation().toFile().toString() );
                presenter.onAttachmentAdded( imageData );
                
            }
        }
        catch ( CoreException | IOException  e )
        {
            logger.error( e.getMessage(), e );
        }
    }

    private void handleCompilationUnit( ICompilationUnit compilationUnit )
    {
        try
        {
            int[] lineRange = getSelectedLineNumbers( compilationUnit );
            presenter.onAttachmentAdded( new FileContentAttachment( compilationUnit.getPath().toString(), lineRange[0], lineRange[1], compilationUnit.getAdapter( ISourceReference.class ).getSource() ) );
        }
        catch ( Exception e )
        {
            logger.error( e.getMessage(), e );
        }
    }

    private boolean isTextFile( IFile file ) throws CoreException
    {
        boolean textFile = false;
        var contentDescription = file.getContentDescription();
        if ( contentDescription != null )
        {
            var contentType = contentDescription.getContentType();
            if ( contentType.isKindOf( Platform.getContentTypeManager().getContentType( IContentTypeManager.CT_TEXT ) ) )
            {
                textFile = true;
            }
        }
        return textFile;
    }
    private boolean isImageFile( IFile file ) throws CoreException
    {
        var supported = Sets.newHashSet( "jpg", "jpeg", "png" );
        return supported.contains(  file.getFileExtension().toLowerCase() );
    }


    private int[] getSelectedLineNumbers( ICompilationUnit compilationUnit )
    {
        try
        {
            // Obtain the IEditorPart for the compilation unit
            var editorPart = JavaUI.openInEditor( compilationUnit );
            if ( editorPart instanceof ITextEditor )
            {
                ITextEditor textEditor = (ITextEditor) editorPart;
                // Get the editor's selection provider
                var selectionProvider = textEditor.getSelectionProvider();
                // Obtain the selection
                var selection = selectionProvider.getSelection();
                if ( selection instanceof ITextSelection )
                {
                    var textSelection = (ITextSelection) selection;
                    // Get the start and end line numbers
                    int startLine = textSelection.getStartLine();
                    int endLine = textSelection.getEndLine();
                    return new int[] { startLine + 1, endLine + 1 }; 
                }
            }
        }
        catch ( Exception e )
        {
            logger.error( e.getMessage(), e );
        }
    
        return new int[] { -1, -1 }; 
    }
}
