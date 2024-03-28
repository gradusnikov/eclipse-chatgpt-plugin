package com.github.gradusnikov.eclipse.assistai.part.dnd;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.core.runtime.ILog;
import org.eclipse.e4.core.contexts.ContextInjectionFactory;
import org.eclipse.e4.core.contexts.IEclipseContext;
import org.eclipse.e4.core.di.annotations.Creatable;
import org.eclipse.swt.dnd.DND;
import org.eclipse.swt.dnd.DropTarget;
import org.eclipse.swt.dnd.DropTargetAdapter;
import org.eclipse.swt.dnd.DropTargetEvent;
import org.eclipse.swt.dnd.Transfer;
import org.eclipse.swt.widgets.Control;

import com.github.gradusnikov.eclipse.assistai.part.dnd.handlers.ITransferHandler;

import jakarta.annotation.PostConstruct;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

/**
 * Controls can register to be enabled to act as a "drop target", so it can
 * accept e.g. files from the Project Explorer.
 */
@Creatable
@Singleton
public class DropManager
{
    @Inject
    private ILog                                  logger;

    @Inject
    IEclipseContext                               context;

    private final Map<Transfer, ITransferHandler> transferTypeHandlers = new HashMap<>();

    private Transfer[]                            supportedTransferTypes;

    private DropTargetAdapter                     dropTargetAdapter;

    @PostConstruct
    public void onInit()
    {
        instantiateTransferHandlers();

        dropTargetAdapter = new DropTargetAdapter()
        {

            @Override
            public void dragEnter( DropTargetEvent event )
            {
                // Always indicate a copy operation.
                event.detail = DND.DROP_COPY;
                event.feedback = DND.FEEDBACK_NONE;
            }

            @Override
            public void dragOperationChanged( DropTargetEvent event )
            {
                // Always indicate a copy operation.
                event.detail = DND.DROP_COPY;
                event.feedback = DND.FEEDBACK_NONE;
            }

            @Override
            public void dragOver( DropTargetEvent event )
            {
                // Always indicate a copy operation.
                event.detail = DND.DROP_COPY;
                event.feedback = DND.FEEDBACK_NONE;
            }

            @Override
            public void drop( DropTargetEvent event )
            {
                // Prevent deleting stuff from the source when the user just
                // moves content
                // instead of using copy.
                if ( event.detail == DND.DROP_MOVE )
                {
                    event.detail = DND.DROP_COPY;
                }

                for ( Transfer transferType : supportedTransferTypes )
                {
                    if ( transferType.isSupportedType( event.currentDataType ) )
                    {
                        ITransferHandler handler = transferTypeHandlers.get( transferType );
                        handler.handleTransfer( event.data );
                        return;
                    }
                }

                logger.error( "Unsupported data type: " + event.data.getClass().getName() );
            }
        };
    }

    private void instantiateTransferHandlers()
    {
        List<Transfer> transferTypesInOrder = new ArrayList<>();

        InputStream transferTypeHandlerFileContent = this.getClass().getClassLoader()
                .getResourceAsStream( "/META-INF/services/com.github.gradusnikov.eclipse.assistai.part.dnd.ITransferTypeHandler" );
        try (BufferedReader reader = new BufferedReader( new InputStreamReader( transferTypeHandlerFileContent ) ))
        {
            String line;
            while ( ( line = reader.readLine() ) != null )
            {
                try
                {
                    Class<?> clazz = Class.forName( line );
                    ITransferHandler instance = (ITransferHandler) ContextInjectionFactory.make( clazz, context );
                    transferTypeHandlers.put( instance.getTransferType(), instance );
                    transferTypesInOrder.add( instance.getTransferType() );
                }
                catch ( ClassNotFoundException e )
                {
                    logger.error( e.getMessage(), e );
                }
            }
        }
        catch ( IOException e1 )
        {
            logger.error( e1.getMessage(), e1 );
        }

        supportedTransferTypes = transferTypesInOrder.toArray( new Transfer[transferTypesInOrder.size()] );
    }

    /** Add DnD capabilities to the given control. */
    public void registerDropTarget( Control targetControl )
    {
        DropTarget dropTarget = new DropTarget( targetControl, DND.DROP_MOVE | DND.DROP_COPY | DND.DROP_LINK | DND.DROP_DEFAULT );
        dropTarget.setTransfer( supportedTransferTypes );
        dropTarget.addDropListener( dropTargetAdapter );
    }
}
