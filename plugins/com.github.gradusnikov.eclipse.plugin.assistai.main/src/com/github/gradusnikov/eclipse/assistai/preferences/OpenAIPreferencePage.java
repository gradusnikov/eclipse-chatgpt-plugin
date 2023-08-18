package com.github.gradusnikov.eclipse.assistai.preferences;

import org.eclipse.jface.preference.FieldEditorPreferencePage;
import org.eclipse.jface.preference.StringFieldEditor;
import org.eclipse.ui.IWorkbench;
import org.eclipse.ui.IWorkbenchPreferencePage;

import com.github.gradusnikov.eclipse.assistai.Activator;


public class OpenAIPreferencePage extends FieldEditorPreferencePage implements IWorkbenchPreferencePage
{
    public OpenAIPreferencePage()
    {
        super( GRID );
        setPreferenceStore( Activator.getDefault().getPreferenceStore() );
        setDescription( "OpenAI API settings\nIn case If you are using Azure OpenAI Subscription, please make sure you enter your full Azure OpenAI deployment URL including the version.\ni.e.: https://your-endpoint-base.openai.azure.com/openai/deployments/your-azure-model-name/completions?api-version=2022-12-01" );
    }

    /**
     * Creates the field editors. Field editors are abstractions of the common
     * GUI blocks needed to manipulate various types of preferences. Each field
     * editor knows how to save and restore itself.
     */
    public void createFieldEditors()
    {
        addField(new StringFieldEditor(PreferenceConstants.OPENAI_API_BASE, "&Open AI API Base or Azure AI Endpoint:", getFieldEditorParent()));
        addField( new StringFieldEditor( PreferenceConstants.OPENAI_API_KEY, "&Open AI API Key:", getFieldEditorParent() ) );
        addField( new StringFieldEditor( PreferenceConstants.OPENAI_MODEL_NAME, "&Model Name", getFieldEditorParent() ) );
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