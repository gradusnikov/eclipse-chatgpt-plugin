package com.github.gradusnikov.eclipse.assistai.tools;

import java.util.Objects;
import java.util.concurrent.Callable;

import org.eclipse.swt.widgets.Display;

public class EclipseUIUtilities
{
    /**
     * Executes a task in the UI thread synchronously.
     * 
     * @param <T>
     *            The return type of the task
     * @param callable
     *            The task to execute
     * @return The result of the task
     */
    public <T> T syncExec( Callable<T> callable )
    {
        final Object[]    result = new Object[1];
        final Exception[] exception = new Exception[1];

        Display.getDefault().syncExec( () -> {
            try
            {
                result[0] = callable.call();
            }
            catch ( Exception e )
            {
                exception[0] = e;
            }
        } );

        if ( exception[0] != null )
        {
            throw new RuntimeException( "Error in UI thread execution", exception[0] );
        }

        @SuppressWarnings( "unchecked" )
        T typedResult = (T) result[0];
        return typedResult;
    }
    
    public static void asyncExec(Runnable callable)
    {
        // If we're on the UI thread, refresh directly
    	var display = Display.getCurrent();
    	if ( Objects.isNull(display) )
    	{
    		callable.run();
    	}
	    else 
	    {
	        // Otherwise, queue it on the UI thread
	        display.asyncExec(() -> callable.run());
	    }    	
    }

}
