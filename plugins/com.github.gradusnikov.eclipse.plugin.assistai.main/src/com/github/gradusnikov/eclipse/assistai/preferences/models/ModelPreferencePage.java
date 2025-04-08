package com.github.gradusnikov.eclipse.assistai.preferences.models;

import java.util.Arrays;

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
import com.github.gradusnikov.eclipse.assistai.repository.ModelApiDescriptorUtilities;


public class ModelPreferencePage extends FieldEditorPreferencePage implements IWorkbenchPreferencePage
{
    
    private UISynchronize uiSync;
    private IPropertyChangeListener modelListener = e -> {
        if ( PreferenceConstants.ASSISTAI_DEFINED_MODELS.equals( e.getProperty() ) )
        {
            uiSync.asyncExec( () -> {

            });
        }
    };
    
    public ModelPreferencePage()
    {
        super( GRID );
        setPreferenceStore( Activator.getDefault().getPreferenceStore() );
        setDescription( "Model API settings" );
        
        getPreferenceStore().addPropertyChangeListener( modelListener ); 
    }


    /**
     * Creates the field editors. Field editors are abstractions of the common
     * GUI blocks needed to manipulate various types of preferences. Each field
     * editor knows how to save and restore itself.
     */
    @Override
    public void createFieldEditors()
    {
        
        var preferenceStore = getPreferenceStore();
        
        var modelsJson = preferenceStore.getString( PreferenceConstants.ASSISTAI_DEFINED_MODELS );
        var models =  ModelApiDescriptorUtilities.fromJson( modelsJson );
 
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
    
    
    
    /*
     * (non-Javadoc)
     * 
     * @see
     * org.eclipse.ui.IWorkbenchPreferencePage#init(org.eclipse.ui.IWorkbench)
     */
    @Override
    public void init( IWorkbench workbench )
    {
        // workaroud to get UISynchronize as PreferencePage does not seem to
        // be handled by the eclipse context
        IEclipseContext eclipseContext = workbench.getService( IEclipseContext.class );
        uiSync = eclipseContext.get( UISynchronize.class );
    }
    
    @Override
    public void dispose()
    {
        getPreferenceStore().removePropertyChangeListener( modelListener );
        super.dispose();
    }

}