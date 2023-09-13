package com.github.gradusnikov.eclipse.assistai.services;

import javax.inject.Inject;
import javax.inject.Singleton;

import org.eclipse.e4.core.di.annotations.Creatable;

import com.github.gradusnikov.eclipse.assistai.handlers.functions.FunctionCalls;

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
