package com.github.gradusnikov.eclipse.assistai.preferences.prompts;

import java.util.Objects;

import org.eclipse.jface.preference.PreferencePage;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.SashForm;
import org.eclipse.swt.events.SelectionAdapter;
import org.eclipse.swt.events.SelectionEvent;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.List;
import org.eclipse.swt.widgets.Text;
import org.eclipse.ui.IWorkbench;
import org.eclipse.ui.IWorkbenchPreferencePage;

import com.github.gradusnikov.eclipse.assistai.Activator;

public class PromptsPreferencePage extends PreferencePage implements IWorkbenchPreferencePage
{
    private PromptsPreferencePresenter preferencePresenter;
    private List list;
    private Text textArea;
    
    public PromptsPreferencePage()
    {
        setPreferenceStore(Activator.getDefault().getPreferenceStore());
        setDescription("Prompts");
        preferencePresenter = Activator.getDefault().getPromptsPreferncePresenter();
    }
    
    @Override
    public void init( IWorkbench workbench )
    {
    }

    @Override
    protected Control createContents( Composite parent )
    {
        SashForm sashForm = new SashForm(parent, SWT.VERTICAL);
        sashForm.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));

        list = new List( sashForm, SWT.BORDER | SWT.SINGLE | SWT.V_SCROLL );

        textArea = new Text( sashForm, SWT.BORDER | SWT.MULTI | SWT.V_SCROLL | SWT.WRAP );
        var textAreaLayoutData = new GridData( GridData.FILL_BOTH );
        textArea.setLayoutData( textAreaLayoutData );

        // Sets the initial weight ratio
        sashForm.setWeights(new int[]{15, 85}); 
        
        initializeListeners();
        
        preferencePresenter.registerView( this );

        return sashForm;
    }

    private void initializeListeners()
    {
        list.addSelectionListener( new SelectionAdapter()
        {
            @Override
            public void widgetSelected( SelectionEvent e )
            {
                Objects.requireNonNull( preferencePresenter );
                int selectedIndex = list.getSelectionIndex();
                preferencePresenter.setSelectedPrompt( selectedIndex );
            }
        } );
    }

    public void setPrompts( String[] prompts )
    {
        list.setItems( prompts );
    }

    public void setCurrentPrompt( String selectedItem )
    {
        textArea.setText( selectedItem );
    }
    
    @Override
    protected void performApply() 
    {
        // Save the current prompt text to the preference store
        int selectedIndex = list.getSelectionIndex();
        if (selectedIndex != -1) 
        {
            preferencePresenter.savePrompt(selectedIndex, textArea.getText());
        }
        super.performApply();
    }
    
    @Override
    protected void performDefaults()
    {
        int selectedIndex = list.getSelectionIndex();
        if (selectedIndex != -1) 
        {
            preferencePresenter.resetPrompt(selectedIndex);
        }
        super.performDefaults();
    }
}
