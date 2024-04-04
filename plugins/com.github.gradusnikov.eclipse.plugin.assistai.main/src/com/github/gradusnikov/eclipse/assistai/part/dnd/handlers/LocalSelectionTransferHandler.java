package com.github.gradusnikov.eclipse.assistai.part.dnd.handlers;

import java.util.Optional;
import java.util.stream.Stream;

import org.eclipse.core.runtime.IAdaptable;
import org.eclipse.core.runtime.ILog;
import org.eclipse.e4.core.di.annotations.Creatable;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.ISourceReference;
import org.eclipse.jdt.core.JavaModelException;
import org.eclipse.jface.text.BadLocationException;
import org.eclipse.jface.text.Document;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.jface.viewers.StructuredSelection;
import org.eclipse.swt.dnd.Transfer;

import com.github.gradusnikov.eclipse.assistai.part.Attachment.FileContentAttachment;
import com.github.gradusnikov.eclipse.assistai.part.ChatGPTPresenter;

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
        Stream<Object> optSourceRefs = Optional.ofNullable( data ).filter( IStructuredSelection.class::isInstance ).map( IStructuredSelection.class::cast )
                .orElse( StructuredSelection.EMPTY ).stream();
        optSourceRefs.filter( IAdaptable.class::isInstance ).map( IAdaptable.class::cast ).map( adaptable -> adaptable.getAdapter( ISourceReference.class ) )
                .filter( source -> source != null ).forEach( ( ISourceReference source ) -> {
                    try
                    {
                        if ( source instanceof IJavaElement )
                        {
                            IJavaElement javaElement = (IJavaElement) source;
                            ICompilationUnit cu = (ICompilationUnit) javaElement.getAncestor( IJavaElement.COMPILATION_UNIT );
                            int[] lineRange = toLineNumbers( cu, source );
                            presenter.onAttachmentAdded( new FileContentAttachment( cu.getPath().toString(), lineRange[0], lineRange[1], source.getSource() ) );
                        }
                        else
                        {
                            presenter.onAttachmentAdded( new FileContentAttachment( "unknown", -1, -1, source.getSource() ) );
                        }
                    }
                    catch ( JavaModelException e )
                    {
                        logger.error( e.getMessage(), e );
                    }
                } );
    }

    private int[] toLineNumbers( ICompilationUnit cu, ISourceReference source )
    {
        try
        {
            Document document = new Document( cu.getSource() );

            int startLine = document.getLineOfOffset( source.getSourceRange().getOffset() ) + 1;
            int endLine = document.getLineOfOffset( source.getSourceRange().getOffset() + source.getSourceRange().getLength() ) + 1;

            return new int[] { startLine, endLine };
        }
        catch ( JavaModelException | BadLocationException e )
        {
            logger.error( e.getMessage(), e );
        }

        return new int[] { -1, -1 };
    }
}
