package com.github.gradusnikov.eclipse.assistai.commands;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;

import org.eclipse.e4.core.di.annotations.Creatable;

@Creatable
@Singleton
public class FunctionExecutorProvider
{
    @Inject
    private FunctionCalls functionCalls;
    
    
    public FunctionExecutor get()
    {
        return new FunctionExecutor( functionCalls );
    }
    
}
