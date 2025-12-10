package com.github.gradusnikov.eclipse.assistai.preferences.models;

import java.util.Arrays;

import org.eclipse.e4.core.contexts.ContextInjectionFactory;
import org.eclipse.e4.core.contexts.IEclipseContext;
import org.eclipse.jface.preference.BooleanFieldEditor;
import org.eclipse.jface.preference.ComboFieldEditor;
import org.eclipse.jface.preference.FieldEditorPreferencePage;
import org.eclipse.jface.preference.IntegerFieldEditor;
import org.eclipse.jface.preference.StringFieldEditor;
import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Group;
import org.eclipse.swt.widgets.Label;
import org.eclipse.ui.IWorkbench;
import org.eclipse.ui.IWorkbenchPreferencePage;

import com.github.gradusnikov.eclipse.assistai.Activator;
import com.github.gradusnikov.eclipse.assistai.models.ModelApiDescriptorRepository;
import com.github.gradusnikov.eclipse.assistai.preferences.PreferenceConstants;


public class ModelPreferencePage extends FieldEditorPreferencePage implements IWorkbenchPreferencePage
{
    
    private ModelApiDescriptorRepository repository;
    
    public ModelPreferencePage()
    {
        super( GRID );
        setPreferenceStore( Activator.getDefault().getPreferenceStore() );
        setDescription( "AssistAI Settings" );
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
        Composite parent = getFieldEditorParent();
        
        // Clear existing children
        Arrays.stream( parent.getChildren() ).forEach( Control::dispose );
        
        // Get model entries for combo boxes
        var models = repository.listModelApiDescriptors();
        String[][] modelEntries = new String[models.size()][2];
        for ( int i = 0; i < models.size(); i++ )
        {
            var model = models.get( i );
            modelEntries[i][0] = String.format("%s - %s", model.apiUrl(), model.modelName() );
            modelEntries[i][1] = model.uid();
        }
        
        // --- Chat Model Section ---
        Group chatGroup = createGroup(parent, "Chat Model");
        Composite chatComposite = createGroupComposite(chatGroup);
        
        ComboFieldEditor modelSelector = new ComboFieldEditor(
            PreferenceConstants.ASSISTAI_CHAT_MODEL, 
            "&Selected Model:", 
            modelEntries, 
            chatComposite
        );
        addField( modelSelector );
        
        // --- Code Completion Section ---
        Group completionGroup = createGroup(parent, "Code Completion");
        Composite completionComposite = createGroupComposite(completionGroup);
        
        BooleanFieldEditor completionEnabled = new BooleanFieldEditor(
            PreferenceConstants.ASSISTAI_COMPLETION_ENABLED,
            "&Enable LLM Code Completion",
            completionComposite
        );
        addField( completionEnabled );
        
        // Add "Use Chat Model" option plus all available models
        String[][] completionModelEntries = new String[models.size() + 1][2];
        completionModelEntries[0][0] = "(Use Chat Model)";
        completionModelEntries[0][1] = "";
        for ( int i = 0; i < models.size(); i++ )
        {
            var model = models.get( i );
            completionModelEntries[i + 1][0] = String.format("%s - %s", model.apiUrl(), model.modelName() );
            completionModelEntries[i + 1][1] = model.uid();
        }
        
        ComboFieldEditor completionModelSelector = new ComboFieldEditor(
            PreferenceConstants.ASSISTAI_COMPLETION_MODEL, 
            "Completion &Model:", 
            completionModelEntries, 
            completionComposite
        );
        addField( completionModelSelector );
        
        IntegerFieldEditor completionTimeout = new IntegerFieldEditor(
            PreferenceConstants.ASSISTAI_COMPLETION_TIMEOUT_SECONDS,
            "&Timeout (seconds):",
            completionComposite
        );
        completionTimeout.setValidRange(1, 60);
        addField( completionTimeout );
        
        StringFieldEditor hotkeyEditor = new StringFieldEditor(
            PreferenceConstants.ASSISTAI_COMPLETION_HOTKEY,
            "&Hotkey:",
            completionComposite
        );
        hotkeyEditor.setEmptyStringAllowed(false);
        addField( hotkeyEditor );
        
        // Adjust layout for the groups
        adjustGroupLayout(chatGroup);
        adjustGroupLayout(completionGroup);
    }
    
    /**
     * Creates a group with a title.
     */
    private Group createGroup(Composite parent, String title) {
        Group group = new Group(parent, SWT.NONE);
        group.setText(title);
        group.setFont(parent.getFont());
        
        GridData gd = new GridData(GridData.FILL_HORIZONTAL);
        gd.horizontalSpan = 2;
        group.setLayoutData(gd);
        
        GridLayout layout = new GridLayout(2, false);
        layout.marginWidth = 10;
        layout.marginHeight = 10;
        group.setLayout(layout);
        
        return group;
    }
    
    /**
     * Creates a composite inside a group for field editors.
     */
    private Composite createGroupComposite(Group group) {
        Composite composite = new Composite(group, SWT.NONE);
        GridLayout layout = new GridLayout(2, false);
        layout.marginWidth = 0;
        layout.marginHeight = 0;
        composite.setLayout(layout);
        
        GridData gd = new GridData(GridData.FILL_HORIZONTAL);
        gd.horizontalSpan = 2;
        composite.setLayoutData(gd);
        
        return composite;
    }
    
    /**
     * Adjusts the layout of a group after field editors have been added.
     */
    private void adjustGroupLayout(Group group) {
        group.layout(true, true);
    }
    
    @Override
    public void dispose()
    {
        super.dispose();
    }

}