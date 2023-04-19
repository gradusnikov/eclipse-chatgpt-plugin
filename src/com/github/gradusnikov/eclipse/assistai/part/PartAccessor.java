package com.github.gradusnikov.eclipse.assistai.part;

import java.util.Optional;

import javax.inject.Inject;
import javax.inject.Singleton;

import org.eclipse.e4.core.di.annotations.Creatable;
import org.eclipse.e4.ui.model.application.MApplication;
import org.eclipse.e4.ui.model.application.ui.basic.MPart;
import org.eclipse.e4.ui.workbench.modeling.EModelService;

@Creatable
@Singleton
public class PartAccessor
{
    @Inject
    private MApplication application;
    @Inject
    private EModelService modelService; 
    
    public Optional<ChatGPTViewPart> findMessageView() 
    {
        // Find the MessageView by element ID in the application model
        return modelService.findElements(application, "assitai.partdescriptor.chatgptview", MPart.class)
                                           .stream()
                                           .findFirst()
                                           .map( mpart -> mpart.getObject() )
                                           .map( ChatGPTViewPart.class::cast );
    }

    
}
