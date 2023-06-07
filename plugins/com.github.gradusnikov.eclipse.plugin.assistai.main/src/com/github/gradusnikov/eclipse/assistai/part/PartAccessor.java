package com.github.gradusnikov.eclipse.assistai.part;

import java.util.Optional;

import javax.inject.Inject;
import javax.inject.Singleton;

import org.eclipse.e4.core.di.annotations.Creatable;
import org.eclipse.e4.ui.model.application.MApplication;
import org.eclipse.e4.ui.model.application.ui.basic.MPart;
import org.eclipse.e4.ui.workbench.modeling.EModelService;

/**
 * Provides access to the ChatGPTViewPart within the application model.
 * This class is responsible for locating and retrieving the ChatGPTViewPart using the element ID.
 */
@Creatable
@Singleton
public class PartAccessor
{
    @Inject
    private MApplication application;
    @Inject
    private EModelService modelService; 
    
    /**
     * Finds the ChatGPTViewPart in the application model by its element ID.
     *
     * @return an Optional containing the ChatGPTViewPart if found, otherwise an empty Optional
     */
    public Optional<ChatGPTViewPart> findMessageView() 
    {
        // Find the MessageView by element ID in the application model
        return modelService.findElements(application, "assistai.partdescriptor.chatgptview", MPart.class)
                                           .stream()
                                           .findFirst()
                                           .map( mpart -> mpart.getObject() )
                                           .map( ChatGPTViewPart.class::cast );
    }

    
}
