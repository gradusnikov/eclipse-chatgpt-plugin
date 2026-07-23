package com.github.gradusnikov.eclipse.assistai.mcp.operations;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.eclipse.debug.core.DebugEvent;
import org.eclipse.debug.core.DebugPlugin;
import org.eclipse.debug.core.IDebugEventSetListener;
import org.eclipse.debug.core.ILaunch;
import org.eclipse.debug.core.IStreamListener;
import org.eclipse.debug.core.model.IProcess;
import org.eclipse.debug.core.model.IStreamMonitor;
import org.eclipse.debug.core.model.IStreamsProxy;

/**
 * Feeds the stdout/stderr of a launched process into an {@link Operation}'s output
 * buffer, and makes cancelling the operation terminate the launch.
 * <p>
 * Interrupting the tool thread does not stop a JVM the tool merely started, so a
 * launch backed operation must register the launch as a cancel hook - otherwise
 * "cancel" would leave the process running exactly as a client timeout does today.
 */
public final class ProcessOutputSource
{
    private ProcessOutputSource()
    {
    }

    /**
     * Attaches to every process of the launch, now and as they appear. A launch does
     * not always have its process yet when {@code launch()} returns, so waiting only
     * for the ones present would lose the start of the output.
     */
    public static void attach( Operation operation, ILaunch launch )
    {
        if ( operation == null || launch == null )
        {
            return;
        }

        Set<IProcess> attached = ConcurrentHashMap.newKeySet();
        attachExisting( operation, launch, attached );

        IDebugEventSetListener listener = events -> {
            for ( DebugEvent event : events )
            {
                if ( event.getKind() == DebugEvent.CREATE && event.getSource() instanceof IProcess process
                        && launch.equals( process.getLaunch() ) && attached.add( process ) )
                {
                    attachProcess( operation, process );
                }
            }
        };
        DebugPlugin.getDefault().addDebugEventListener( listener );
        operation.addCompletionHook( () -> DebugPlugin.getDefault().removeDebugEventListener( listener ) );
        operation.addCancelHook( () -> terminate( launch ) );
    }

    private static void attachExisting( Operation operation, ILaunch launch, Set<IProcess> attached )
    {
        for ( IProcess process : launch.getProcesses() )
        {
            if ( attached.add( process ) )
            {
                attachProcess( operation, process );
            }
        }
    }

    private static void attachProcess( Operation operation, IProcess process )
    {
        IStreamsProxy proxy = process.getStreamsProxy();
        if ( proxy == null )
        {
            return;
        }
        listen( operation, proxy.getOutputStreamMonitor(), false );
        listen( operation, proxy.getErrorStreamMonitor(), true );
    }

    private static void listen( Operation operation, IStreamMonitor monitor, boolean error )
    {
        if ( monitor == null )
        {
            return;
        }
        IStreamListener listener = ( text, source ) -> operation.output().append( text, error );
        // Seed and subscribe under the monitor's lock, so output arriving between the
        // two is neither lost nor duplicated.
        synchronized ( monitor )
        {
            operation.output().append( monitor.getContents(), error );
            monitor.addListener( listener );
        }
        operation.addCompletionHook( () -> monitor.removeListener( listener ) );
    }

    private static void terminate( ILaunch launch )
    {
        try
        {
            if ( launch.canTerminate() )
            {
                launch.terminate();
            }
        }
        catch ( Exception e )
        {
            throw new RuntimeException( "Could not terminate launch: " + e.getMessage(), e );
        }
    }
}
