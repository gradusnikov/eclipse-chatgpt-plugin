package com.github.gradusnikov.eclipse.assistai.preferences;

import org.eclipse.jface.preference.ComboFieldEditor;
import org.eclipse.jface.preference.FieldEditorPreferencePage;
import org.eclipse.ui.IWorkbench;
import org.eclipse.ui.IWorkbenchPreferencePage;

import com.github.gradusnikov.eclipse.assistai.Activator;


public class ModelPreferencePage extends FieldEditorPreferencePage implements IWorkbenchPreferencePage
{
    public ModelPreferencePage()
    {
        super( GRID );
        setPreferenceStore( Activator.getDefault().getPreferenceStore() );
        setDescription( "Model API settings" );
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
        String modelsJson = preferenceStore.getString( PreferenceConstants.ASSISTAI_DEFINED_MODELS );
        var models =  ModelApiDescriptorUtilities.fromJson( modelsJson );
 
        String[][] entries = new String[models.size()][2];
        for ( int i = 0; i < models.size(); i++ )
        {
            var model = models.get( i );
            entries[i][0] = String.format("%s - %s", model.apiUrl(), model.modelName() );
            entries[i][1] = model.uid();
        }
        // TODO: make this dynamic, otherwise requires closing and re-opening the 
        // preference window whenever the model list changes
        addField( new ComboFieldEditor(PreferenceConstants.ASSISTAI_SELECTED_MODEL, "&Selected Model:", entries, getFieldEditorParent()));
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
    }
    

}