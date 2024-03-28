package com.github.gradusnikov.eclipse.assistai.part.dnd.handlers;

import org.eclipse.swt.dnd.Transfer;

/**
 * Defines a contract for handling DnD data transfer.
 */
public interface ITransferHandler
{
    /**
     * @return The {@link Transfer} type that this handler can handle.
     */
    Transfer getTransferType();

    /**
     * Handles the data transfer.
     * 
     * @param data
     *            The data to handle.
     */
    void handleTransfer( Object data );
}
