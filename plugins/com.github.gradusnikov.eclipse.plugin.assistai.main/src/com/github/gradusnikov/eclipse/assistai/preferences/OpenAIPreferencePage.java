package com.github.gradusnikov.eclipse.assistai.preferences;

import org.eclipse.jface.preference.FieldEditorPreferencePage;
import org.eclipse.jface.preference.IntegerFieldEditor;
import org.eclipse.jface.preference.ScaleFieldEditor;
import org.eclipse.jface.preference.StringFieldEditor;
import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Group;
import org.eclipse.swt.widgets.Label;
import org.eclipse.ui.IWorkbench;
import org.eclipse.ui.IWorkbenchPreferencePage;

import com.github.gradusnikov.eclipse.assistai.Activator;


public class OpenAIPreferencePage extends FieldEditorPreferencePage implements IWorkbenchPreferencePage
{
    public OpenAIPreferencePage()
    {
        super( GRID );
        setPreferenceStore( Activator.getDefault().getPreferenceStore() );
        setDescription( "OpenAI API settings" );
    }

    /**
     * Creates the field editors. Field editors are abstractions of the common
     * GUI blocks needed to manipulate various types of preferences. Each field
     * editor knows how to save and restore itself.
     */
    public void createFieldEditors()
    {
        addField(new StringFieldEditor(PreferenceConstants.OPENAI_API_BASE, "&Open AI API Base:", getFieldEditorParent()));
        addField(new StringFieldEditor(PreferenceConstants.OPENAI_API_END_POINT, "&Open AI API End Point:", getFieldEditorParent()));
        addField( new StringFieldEditor( PreferenceConstants.OPENAI_API_KEY, "&Open AI API Key:", getFieldEditorParent() ) );
        addField( new StringFieldEditor( PreferenceConstants.OPENAI_MODEL_NAME, "&Model Name", getFieldEditorParent() ) );
    

         // Temperature Scale Field Editor
         ScaleFieldEditor temperatureEditor = new ScaleFieldEditor(
             PreferenceConstants.OPENAI_MODEL_TEMPERATURE, // Preference key
             "&Model Temperature:", // Label
             getFieldEditorParent(), 
             0,  // Minimum value
             10, // Maximum value (scaled for 0-1 range with 0.1 increments)
             1,  // Increment value
             1   // Page increment (irrelevant for a scale)
         );
         addField(temperatureEditor);
    }

    /*
     * (non-Javadoc)
     * 
     * @see
     * org.eclipse.ui.IWorkbenchPreferencePage#init(org.eclipse.ui.IWorkbench)
     */
    public void init( IWorkbench workbench )
    {
    }
    

}