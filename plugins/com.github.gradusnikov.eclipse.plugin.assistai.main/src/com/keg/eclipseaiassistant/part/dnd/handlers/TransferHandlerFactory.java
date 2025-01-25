package com.keg.eclipseaiassistant.part.dnd.handlers;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import org.eclipse.e4.core.di.annotations.Creatable;
import org.eclipse.swt.dnd.Transfer;
import org.eclipse.swt.dnd.TransferData;

import jakarta.annotation.PostConstruct;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

@Creatable
@Singleton
public class TransferHandlerFactory
{
    @Inject
    private LocalSelectionTransferHandler localSelectionTransferHandler;
    @Inject
    private ImageTransferHandler imageTransferHandler;
    @Inject
    private FileTransferHandler fileTransferHandler;
    @Inject
    private UrlTransferHandler urlTransferHandler;
    @Inject
    private TextTransferHandler textTransferHandler;
    
    
    private  Map<Transfer, ITransferHandler> supportedTransferHandlers;
    
    public TransferHandlerFactory()
    {
    }
    
    @PostConstruct
    private void initialize()
    {
        supportedTransferHandlers = mapSupportedTransferHandlers();
        
    }
    
    private Map<Transfer, ITransferHandler> mapSupportedTransferHandlers()
    {
        var map = new LinkedHashMap<Transfer, ITransferHandler>();
        map.put( localSelectionTransferHandler.getTransferType(), localSelectionTransferHandler );
        map.put( imageTransferHandler.getTransferType(), imageTransferHandler );
        map.put( fileTransferHandler.getTransferType(), fileTransferHandler );
        map.put( urlTransferHandler.getTransferType(), urlTransferHandler );
        map.put( textTransferHandler.getTransferType(), textTransferHandler );
        return map;
    }

    public ITransferHandler createTransferHandlerForType( Transfer type )
    {
        return Optional.ofNullable(  supportedTransferHandlers.get( type ) ).orElseThrow(() -> new IllegalArgumentException("Not supported for " + type ) );
    }
    
    

    public Transfer[] getSupportedTransfers()
    {
        return  supportedTransferHandlers.keySet().toArray( Transfer[]::new );
    }

    public Optional<ITransferHandler> getTransferHandler( TransferData currentDataType )
    {
        return  supportedTransferHandlers.keySet().stream()
        .filter( transferType -> transferType.isSupportedType( currentDataType ) )
        .findFirst()
        .map( this::createTransferHandlerForType );
    }
    
    
}
