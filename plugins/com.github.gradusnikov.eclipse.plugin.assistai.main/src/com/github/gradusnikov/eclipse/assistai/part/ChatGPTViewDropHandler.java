package com.github.gradusnikov.eclipse.assistai.part;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Optional;
import java.util.stream.Stream;

import org.apache.commons.io.IOUtils;
import org.apache.tika.Tika;
import org.apache.tika.parser.txt.CharsetDetector;
import org.eclipse.core.runtime.IAdaptable;
import org.eclipse.core.runtime.ILog;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.ISourceReference;
import org.eclipse.jdt.core.JavaModelException;
import org.eclipse.jface.resource.ImageDescriptor;
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
import org.eclipse.swt.dnd.ImageTransfer;
import org.eclipse.swt.dnd.TextTransfer;
import org.eclipse.swt.dnd.Transfer;
import org.eclipse.swt.dnd.URLTransfer;
import org.eclipse.swt.graphics.ImageData;
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

    private final Tika tika = new Tika();

    public ChatGPTViewDropHandler(ChatGPTPresenter presenter, Control targetControl, ILog logger)
    {
        this.presenter = presenter;
        this.targetControl = targetControl;

        DropTarget dropTarget = new DropTarget( targetControl,
                DND.DROP_MOVE | DND.DROP_COPY | DND.DROP_LINK | DND.DROP_DEFAULT );

        Transfer[] types = new Transfer[] { LocalSelectionTransfer.getTransfer(), FileTransfer.getInstance(),
                URLTransfer.getInstance(), TextTransfer.getInstance(), ImageTransfer.getInstance() };
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

                if (LocalSelectionTransfer.getTransfer().isSupportedType( event.currentDataType ))
                {
                    Stream<Object> optSourceRefs = Optional.ofNullable( event.data )
                            .filter( IStructuredSelection.class::isInstance )
                            .map( IStructuredSelection.class::cast )
                            .orElse( StructuredSelection.EMPTY )
                            .stream();
                    optSourceRefs.filter( IAdaptable.class::isInstance )
                            .map( IAdaptable.class::cast )
                            .map( adaptable -> adaptable.getAdapter( ISourceReference.class ) )
                            .filter( source -> source != null )
                            .forEach( ( ISourceReference source ) -> {
                                try
                                {
                                    if (source instanceof IJavaElement)
                                    {
                                        IJavaElement javaElement = (IJavaElement) source;
                                        ICompilationUnit cu = (ICompilationUnit) javaElement
                                                .getAncestor( IJavaElement.COMPILATION_UNIT );
                                        int[] lineRange = toLineNumbers( cu, source );
                                        presenter.onAttachmentAdded( new FileContentAttachment( cu.getPath().toString(),
                                                lineRange[0], lineRange[1], source.getSource() ) );
                                    }
                                    else
                                    {
                                        presenter.onAttachmentAdded(
                                                new FileContentAttachment( "unknown", -1, -1, source.getSource() ) );
                                    }
                                }
                                catch (JavaModelException e)
                                {
                                    logger.error( e.getMessage(), e );
                                }
                            } );
                }
                else if (ImageTransfer.getInstance().isSupportedType( event.currentDataType ))
                {
                    ImageData image = (ImageData) event.data;
                    presenter.onAttachmentAdded( image );
                }
                else if (FileTransfer.getInstance().isSupportedType( event.currentDataType ))
                {
                    String[] files = (String[]) event.data;

                    for (String fullFileName : files)
                    {
                        File file = new File( fullFileName );

                        String contentType;
                        try
                        {
                            // First try cheaply the Java 7 way
                            contentType = Files.probeContentType( file.toPath() );

                            // Fallback to Apache Tika
                            if (contentType == null)
                            {
                                contentType = tika.detect( file );
                            }
                        }
                        catch (IOException e)
                        {
                            contentType = "unknown";
                        }

                        if (contentType.startsWith( "image" ))
                        {
                            try (InputStream in = new BufferedInputStream( new FileInputStream( file ) ))
                            {
                                handleImage( presenter, file.toURI().toURL() );
                            }
                            catch (IOException e)
                            {
                                logger.error( e.getMessage(), e );
                            }
                        }
                        else if (contentType.startsWith( "text" ))
                        {
                            try (InputStream in = new BufferedInputStream( new FileInputStream( file ) ))
                            {
                                handleText( presenter, file.getName(), in );
                            }
                            catch (IOException e)
                            {
                                logger.error( e.getMessage(), e );
                            }
                        }
                        else
                        {
                            logger.error( "Unsupported file type: " + contentType );
                        }
                    }
                }
                else if (URLTransfer.getInstance().isSupportedType( event.currentDataType ))
                {
                    String data = (String) event.data;
                    try
                    {
                        URL url = new URI( data ).toURL();
                        String contentType;
                        try (InputStream in = url.openStream())
                        {
                            byte[] contentBytes = new byte[4096];
                            IOUtils.read( in, contentBytes );
                            contentType = tika.detect( contentBytes );
                        }

                        if (contentType.startsWith( "image" ))
                        {
                            handleImage( presenter, url );
                        }
                        else if (contentType.startsWith( "text" ))
                        {
                            handleText( presenter, guessFile( url ), url.openStream() );
                        }
                    }
                    catch (URISyntaxException | IOException e)
                    {
                        logger.error( e.getMessage(), e );
                    }
                }
                else if (TextTransfer.getInstance().isSupportedType( event.currentDataType ))
                {
                    presenter.onAttachmentAdded( new FileContentAttachment( "unknown", -1, -1, (String) event.data ) );
                }
                else
                {
                    logger.error( "Unsupported data type: " + event.data.getClass().getName() );
                }
            }

            private void handleText( ChatGPTPresenter presenter, String fileName, InputStream in )
                    throws IOException, UnsupportedEncodingException
            {
                // Load file content into string, guessing the file encoding
                byte[] fileContent = IOUtils.toByteArray( in );
                String charsetName = new CharsetDetector()
                        .setText( Arrays.copyOf( fileContent, Math.min( fileContent.length, 4096 ) ) )
                        .detect()
                        .getName();
                String textContent = new String( fileContent, charsetName );
                Document document = new Document( textContent );
                presenter.onAttachmentAdded(
                        new FileContentAttachment( fileName, 1, document.getNumberOfLines(), textContent ) );
            }

            private void handleImage( ChatGPTPresenter presenter, URL url )
            {
                ImageDescriptor imageDescriptor = ImageDescriptor.createFromURL( url );
                ImageData imageData = imageDescriptor.getImageData( 100 );
                presenter.onAttachmentAdded( imageData );
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
                }
                catch (JavaModelException | BadLocationException e)
                {
                    logger.error( e.getMessage(), e );
                }

                return new int[] { -1, -1 };
            }

            private String guessFile( URL url )
            {
                String path = url.getPath();
                String[] parts = path.split( "/" );
                if (parts.length > 0)
                {
                    return parts[parts.length - 1];
                }
                else
                {
                    return "unknown";
                }
            }
        } );
    }
}
