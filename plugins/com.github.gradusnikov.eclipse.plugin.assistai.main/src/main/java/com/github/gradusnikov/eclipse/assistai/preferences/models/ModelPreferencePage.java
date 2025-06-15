package com.github.gradusnikov.eclipse.assistai.preferences.models;

import java.util.Arrays;

import org.eclipse.e4.core.contexts.ContextInjectionFactory;
import org.eclipse.e4.core.contexts.IEclipseContext;
import org.eclipse.e4.ui.di.UISynchronize;
import org.eclipse.jface.preference.ComboFieldEditor;
import org.eclipse.jface.preference.FieldEditorPreferencePage;
import org.eclipse.jface.util.IPropertyChangeListener;
import org.eclipse.swt.widgets.Control;
import org.eclipse.ui.IWorkbench;
import org.eclipse.ui.IWorkbenchPreferencePage;

import com.github.gradusnikov.eclipse.assistai.Activator;
import com.github.gradusnikov.eclipse.assistai.preferences.PreferenceConstants;
import com.github.gradusnikov.eclipse.assistai.repository.ModelApiDescriptorRepository;


public class ModelPreferencePage extends FieldEditorPreferencePage implements IWorkbenchPreferencePage
{
    
    private ModelApiDescriptorRepository repository;
    
    public ModelPreferencePage()
    {
        super( GRID );
        setPreferenceStore( Activator.getDefault().getPreferenceStore() );
        setDescription( "Model API settings" );
    }


    /*
     * (non-Javadoc)
     * 
     * @see
     * org.eclipse.ui.IWorkbenchPreferencePage#init(org.eclipse.ui.IWorkbench)
     */
    @Override
    public void init( IWorkbench workbench )
    {
        IEclipseContext eclipseContext = workbench.getService( IEclipseContext.class );
        repository = ContextInjectionFactory.make( ModelApiDescriptorRepository.class, eclipseContext );
    }


    /**
     * Creates the field editors. Field editors are abstractions of the common
     * GUI blocks needed to manipulate various types of preferences. Each field
     * editor knows how to save and restore itself.
     */
    @Override
    public void createFieldEditors()
    {
        var models =  repository.listModelApiDescriptors();
 
        String[][] entries = new String[models.size()][2];
        for ( int i = 0; i < models.size(); i++ )
        {
            var model = models.get( i );
            entries[i][0] = String.format("%s - %s", model.apiUrl(), model.modelName() );
            entries[i][1] = model.uid();
        }
        
        Arrays.stream( getFieldEditorParent().getChildren() ).forEach( Control::dispose );
        
        ComboFieldEditor modelSelector = new ComboFieldEditor(PreferenceConstants.ASSISTAI_SELECTED_MODEL, "&Selected Model:", entries, getFieldEditorParent());    
        addField( modelSelector );
    }
    
    
    
    @Override
    public void dispose()
    {
        super.dispose();
    }

}