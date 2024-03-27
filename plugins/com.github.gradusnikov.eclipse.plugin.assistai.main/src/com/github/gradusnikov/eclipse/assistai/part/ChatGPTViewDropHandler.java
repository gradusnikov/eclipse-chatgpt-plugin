package com.github.gradusnikov.eclipse.assistai.part;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Optional;
import java.util.stream.Stream;

import org.apache.commons.io.IOUtils;
import org.eclipse.core.runtime.IAdaptable;
import org.eclipse.core.runtime.ILog;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.IMember;
import org.eclipse.jdt.core.ISourceReference;
import org.eclipse.jdt.core.ITypeRoot;
import org.eclipse.jdt.core.JavaModelException;
import org.eclipse.jface.text.BadLocationException;
import org.eclipse.jface.text.Document;
import org.eclipse.jface.util.LocalSelectionTransfer;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.jface.viewers.StructuredSelection;
import org.eclipse.swt.dnd.DND;
import org.eclipse.swt.dnd.DropTarget;
import org.eclipse.swt.dnd.DropTargetAdapter;
import org.eclipse.swt.dnd.DropTargetEvent;
import org.eclipse.swt.dnd.FileTransfer;
import org.eclipse.swt.dnd.TextTransfer;
import org.eclipse.swt.dnd.Transfer;
import org.eclipse.swt.dnd.URLTransfer;
import org.eclipse.swt.widgets.Control;

import com.github.gradusnikov.eclipse.assistai.part.Attachment.FileContentAttachment;

/**
 * Enables the inputArea to act as a "drop target", so it can accept e.g. files
 * from the Project Explorer.
 */
public class ChatGPTViewDropHandler
{
    private final ChatGPTPresenter presenter;
    private final Control targetControl;

    public ChatGPTViewDropHandler(ChatGPTPresenter presenter, Control targetControl, ILog logger)
    {
        this.presenter = presenter;
        this.targetControl = targetControl;

        DropTarget dropTarget = new DropTarget( targetControl,
                DND.DROP_MOVE | DND.DROP_COPY | DND.DROP_LINK | DND.DROP_DEFAULT );

        Transfer[] types = new Transfer[] { LocalSelectionTransfer.getTransfer(), FileTransfer.getInstance(),
                URLTransfer.getInstance(), TextTransfer.getInstance() };
        dropTarget.setTransfer( types );

        dropTarget.addDropListener( new DropTargetAdapter()
        {
            @Override
            public void drop( DropTargetEvent event )
            {
                // Prevent deleting stuff from the source when the user just moves content
                // instead of using copy.
                if (event.detail == DND.DROP_MOVE)
                {
                    event.detail = DND.DROP_COPY;
                }
                
                if (FileTransfer.getInstance().isSupportedType( event.currentDataType ))
                {
                    String[] files = (String[]) event.data;

                    for (String fileName : files)
                    {
                        File file = new File( fileName );
                        try (InputStream in = new BufferedInputStream( new FileInputStream( file ) ))
                        {
                            presenter.onAttachmentAdded( new FileContentAttachment( file.getName(), -1, -1,
                                    IOUtils.toString( in, "UTF-8" ) ) );
                        } catch (IOException e)
                        {
                            logger.error( e.getMessage(), e );
                        }
                    }
                } else if (LocalSelectionTransfer.getTransfer().isSupportedType( event.currentDataType ))
                {
                    Stream<Object> optSourceRefs = Optional.ofNullable( event.data )
                            .filter( IStructuredSelection.class::isInstance ).map( IStructuredSelection.class::cast )
                            .orElse( StructuredSelection.EMPTY ).stream();
                    optSourceRefs.filter( IAdaptable.class::isInstance ).map( IAdaptable.class::cast )
                            .map( adaptable -> adaptable.getAdapter( ISourceReference.class ) )
                            .filter( source -> source != null ).forEach( ( ISourceReference source ) -> {
                                try
                                {
                                    if (source instanceof IJavaElement)
                                    {
                                        IJavaElement javaElement = (IJavaElement) source;
                                        ICompilationUnit cu = (ICompilationUnit) javaElement
                                                .getAncestor( IJavaElement.COMPILATION_UNIT );
                                        int[] lineRange = toLineNumbers( cu, source );
                                    presenter.onAttachmentAdded(
                                                new FileContentAttachment( cu.getPath().toString(), lineRange[0],
                                                        lineRange[1],
                                            source.getSource() ) );
                                    } else
                                    {
                                        presenter.onAttachmentAdded(
                                                new FileContentAttachment( "unknown", -1, -1, source.getSource() ) );
                                    }
                                } catch (JavaModelException e)
                                {
                                    logger.error( e.getMessage(), e );
                                }
                            } );
                } else if (TextTransfer.getInstance().isSupportedType( event.currentDataType ))
                {
                    System.out.println( "Dropped source text:\n" + (String) event.data );
                } else
                {
                    System.out.println( "todo" );
                }
            }

            private int[] toLineNumbers( ICompilationUnit cu, ISourceReference source )

            {
                try
                {
                    Document document = new Document( cu.getSource() );

                    int startLine = document.getLineOfOffset( source.getSourceRange().getOffset() ) + 1;
                    int endLine = document.getLineOfOffset(
                            source.getSourceRange().getOffset() + source.getSourceRange().getLength() ) + 1;

                    return new int[] { startLine, endLine };
                } catch (JavaModelException | BadLocationException e)
                {
                    logger.error( e.getMessage(), e );
                }

                return new int[] { -1, -1 };
            }

            private String guessFile( ISourceReference source )
            {
                if (source instanceof ITypeRoot)
                {
                    return ((ITypeRoot) source).findPrimaryType().getClassFile().getElementName();
                } else if (source instanceof IMember)
                {
                    return ((IMember) source).getClassFile().getElementName();
                } else
                {
                    return "unknown";
                }
            }
        } );

    }
}
