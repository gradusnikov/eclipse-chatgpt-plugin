package com.github.gradusnikov.eclipse.assistai.network.subscribers;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.Flow;
import java.util.concurrent.Flow.Subscription;

import org.eclipse.core.runtime.ILog;
import org.eclipse.e4.core.di.annotations.Creatable;

import com.github.gradusnikov.eclipse.assistai.chat.Incoming;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;

@Creatable
@Singleton
public class PrintToFileMessageSubscriber implements Flow.Subscriber<Incoming>
{
    private Flow.Subscription subscription;
    
    @Inject
    private ILog logger;
    
    private Path getFile()
    {
        Path path = Paths.get( System.getProperty( "user.home" ), "assitai.log" );
        return path;
    }
    
    private void write( String str )
    {
        try
        {
            Files.writeString( getFile(), str, StandardOpenOption.CREATE, StandardOpenOption.APPEND );
        }
        catch ( IOException e )
        {
            logger.error( e.getMessage(), e );
        }

    }
    @Override
    public void onSubscribe(Subscription subscription)
    {
        logger.info( "Opening a log file: " + getFile() );        
        this.subscription = subscription;
        write( "\n>--- BEGIN MESSAGE ---\n" );
        subscription.request(1);
    }
    @Override
    public void onNext(Incoming item)
    {
        write( item.payload().toString() );
        subscription.request(1);
    }

    @Override
    public void onError(Throwable throwable)
    {
//        subscription.request(1);
    }

    @Override
    public void onComplete()
    {
        write( "\n--- END MESSAGE ---\n" );
        subscription.request(1);
    }
}
