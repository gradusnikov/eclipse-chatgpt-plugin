package com.github.gradusnikov.eclipse.assistai.tools;

import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.e4.core.di.annotations.Creatable;
import org.eclipse.e4.ui.di.UISynchronize;

import jakarta.inject.Inject;

@Creatable
public class UISynchronizeCallable 
{
	@Inject
	public UISynchronize uiSync;

	
	
    public void syncExec(Runnable runnable) 
    {
		uiSync.syncExec(runnable);
	}

	public void asyncExec(Runnable runnable) 
	{
		uiSync.asyncExec(runnable);
	}

	public <T> Future<T> asyncCall(Callable<T> callable )
	{
        CompletableFuture<T> future = new CompletableFuture<>();
        uiSync.asyncExec(() -> {
            try 
            {
                T result = callable.call();
                future.complete(result);
            } 
            catch (Exception e) 
            {
                future.completeExceptionally(e);
            }
        });
        return future;
	}

	/**
     * Executes a task in the UI thread synchronously.
     * 
     * @param <T> The return type of the task
     * @param callable The task to execute
     * @return The result of the task
     */
    public <T> T syncCall(Callable<T> callable) {
        AtomicReference<T> result = new AtomicReference<T>();
        AtomicReference<Exception> exception = new AtomicReference<Exception>();
        
        uiSync.syncExec(() -> {
            try 
            {
                result.set( callable.call() );
            } 
            catch (Exception e) 
            {
                exception.set(e);
            }
        });
        if (Objects.nonNull(exception.get())) 
        {
        	Exception e = exception.get();
            throw new RuntimeException(e.getMessage(), e);
        }
        T typedResult = result.get();
        return typedResult;
    }

}
