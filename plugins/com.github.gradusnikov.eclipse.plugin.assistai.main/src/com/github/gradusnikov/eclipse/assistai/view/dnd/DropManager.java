package com.github.gradusnikov.eclipse.assistai.view.dnd;

import org.eclipse.core.runtime.ILog;
import org.eclipse.e4.core.di.annotations.Creatable;
import org.eclipse.swt.dnd.DND;
import org.eclipse.swt.dnd.DropTarget;
import org.eclipse.swt.dnd.DropTargetAdapter;
import org.eclipse.swt.dnd.DropTargetEvent;
import org.eclipse.swt.widgets.Control;

import com.github.gradusnikov.eclipse.assistai.view.dnd.handlers.TransferHandlerFactory;

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
    private ILog                   logger;

    @Inject
    private TransferHandlerFactory transferHandlerFactory;

    private final DropTargetAdapter      dropTargetAdapter;

    public DropManager()
    {
        dropTargetAdapter = new DropTargetAdapterImpl();
    }
    
    
    /** Add DnD capabilities to the given control. */
    public void registerDropTarget( Control targetControl )
    {
        DropTarget dropTarget = new DropTarget( targetControl, DND.DROP_MOVE | DND.DROP_COPY | DND.DROP_LINK | DND.DROP_DEFAULT );
        dropTarget.setTransfer( transferHandlerFactory.getSupportedTransfers());
        dropTarget.addDropListener( dropTargetAdapter );
    }
    
    private class DropTargetAdapterImpl extends DropTargetAdapter
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
            
            transferHandlerFactory.getTransferHandler( event.currentDataType )
                                  .ifPresentOrElse( handler -> handler.handleTransfer( event.data ),
                                        () -> logger.warn( "Unsupported data type: " + event.data.getClass().getName() ) );

        }
    }
}
