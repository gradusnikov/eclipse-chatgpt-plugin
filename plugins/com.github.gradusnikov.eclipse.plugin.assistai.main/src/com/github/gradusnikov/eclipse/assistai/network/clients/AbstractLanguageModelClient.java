package com.github.gradusnikov.eclipse.assistai.network.clients;

import java.util.Objects;

import com.github.gradusnikov.eclipse.assistai.models.ModelApiDescriptor;

public abstract class AbstractLanguageModelClient implements LanguageModelClient
{
    protected ModelApiDescriptor model;

    @Override
    public void setModel( ModelApiDescriptor model )
    {
        Objects.requireNonNull( model );
        this.model = model;
    }

    
    
    
    
}
