package com.github.gradusnikov.eclipse.assistai.preferences.mcp;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import org.eclipse.e4.core.contexts.IEclipseContext;
import org.eclipse.e4.ui.di.UISynchronize;
import org.eclipse.jface.dialogs.Dialog;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jface.preference.PreferencePage;
import org.eclipse.jface.viewers.ArrayContentProvider;
import org.eclipse.jface.viewers.CheckboxTableViewer;
import org.eclipse.jface.viewers.ColumnLabelProvider;
import org.eclipse.jface.viewers.TableViewer;
import org.eclipse.jface.viewers.TableViewerColumn;
import org.eclipse.jface.window.Window;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.SashForm;
import org.eclipse.swt.events.SelectionAdapter;
import org.eclipse.swt.events.SelectionEvent;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.layout.FormAttachment;
import org.eclipse.swt.layout.FormData;
import org.eclipse.swt.layout.FormLayout;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Group;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.Text;
import org.eclipse.ui.IWorkbench;
import org.eclipse.ui.IWorkbenchPreferencePage;

import com.github.gradusnikov.eclipse.assistai.Activator;
import com.github.gradusnikov.eclipse.assistai.mcp.McpServerDescriptor;
import com.github.gradusnikov.eclipse.assistai.mcp.McpServerDescriptor.EnvironmentVariable;
import com.github.gradusnikov.eclipse.assistai.mcp.McpServerDescriptor.McpServerDescriptorWithStatus;

/**
 * Preference page for MCP Server settings
 */
public class McpServerPreferencePage extends PreferencePage implements IWorkbenchPreferencePage
{

    private UISynchronize                uiSync;

    private McpServerPreferencePresenter presenter;

    private CheckboxTableViewer          serverTableViewer;

    private Table                        serverTable;

    private Text                         nameText;

    private Text                         commandText;

    private TableViewer                  envTableViewer;

    private Table                        envTable;

    private Group                        form;
    
    private Label                        nameLabel;
    
    private Label                        commandLabel;

    private Button                       addButton;

    private Button                       removeButton;

    private Button                       addEnvButton;

    private Button                       removeEnvButton;

    private Button                       editEnvButton;

    private List<EnvironmentVariable>    currentEnvVars = new ArrayList<>();

    @Override
    public void init( IWorkbench workbench )
    {
        presenter = Activator.getDefault().getMCPServerPreferencePresenter();

        // Get UISynchronize service
        IEclipseContext eclipseContext = workbench.getService( IEclipseContext.class );
        uiSync = eclipseContext.get( UISynchronize.class );
    }

    @Override
    protected Control createContents(Composite parent) {
        // Create a vertical SashForm for the layout
        var sashForm = new SashForm(parent, SWT.VERTICAL);
        sashForm.setLayoutData(new GridData(GridData.FILL_BOTH));

        // Composite for the server list and buttons
        Composite listButtonsComposite = new Composite(sashForm, SWT.NONE);
        listButtonsComposite.setLayout(new GridLayout(2, false));

        // Create CheckboxTableViewer
        serverTableViewer = CheckboxTableViewer.newCheckList(listButtonsComposite, SWT.BORDER | SWT.FULL_SELECTION);
        serverTable = serverTableViewer.getTable();
        serverTable.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
        serverTable.setHeaderVisible(true);
        serverTable.setLinesVisible(true);

        // Setup table columns
        createServerTableColumns(serverTableViewer);

        // Set content provider
        serverTableViewer.setContentProvider(ArrayContentProvider.getInstance());

        // Button composite for Add/Remove
        Composite buttonComposite = new Composite(listButtonsComposite, SWT.NONE);
        buttonComposite.setLayout(new GridLayout(1, false));
        buttonComposite.setLayoutData(new GridData(SWT.FILL, SWT.TOP, false, false));

        addButton = new Button(buttonComposite, SWT.NONE);
        addButton.setText("Add");
        addButton.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        removeButton = new Button(buttonComposite, SWT.NONE);
        removeButton.setText("Remove");
        removeButton.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        // Create server details form
        createServerDetailsForm(sashForm);

        // Set SashForm weights
        sashForm.setWeights(new int[]{1, 2});

        // Register with presenter
        presenter.registerView(this);

        // Initialize listeners
        initializeListeners(serverTableViewer);
        initializeDetailsListeners();
        clearServerDetails();

        return sashForm;
    }

    private void createServerTableColumns(CheckboxTableViewer checkboxTableViewer) {
        // Enabled column
        TableViewerColumn useCol = new TableViewerColumn(checkboxTableViewer, SWT.NONE);
        useCol.getColumn().setText("Use");
        useCol.getColumn().setWidth(45);
        useCol.setLabelProvider(new ColumnLabelProvider() {
            @Override
            public String getText(Object element) {
                return "";
            }
        });
        // Name column
        TableViewerColumn nameCol = new TableViewerColumn(checkboxTableViewer, SWT.NONE);
        nameCol.getColumn().setText("Name");
        nameCol.getColumn().setWidth(200);
        nameCol.setLabelProvider(new ColumnLabelProvider() {
            @Override
            public String getText(Object element) {
                var descriptor = (McpServerDescriptorWithStatus) element;
                var displayedName = descriptor.descriptor().name();
                if ( descriptor.descriptor().builtIn() )
                {
                    displayedName += " [built-in]";
                }
                return displayedName;
            }
        });

        // Status column
        TableViewerColumn statusCol = new TableViewerColumn(checkboxTableViewer, SWT.NONE);
        statusCol.getColumn().setText("Status");
        statusCol.getColumn().setWidth(100);
        statusCol.setLabelProvider(new ColumnLabelProvider() {
            @Override
            public String getText(Object element) {
                return ((McpServerDescriptorWithStatus) element).status().name();
            }
        });
    }

    private void initializeListeners(CheckboxTableViewer checkboxTableViewer) {
        // Server table selection listener
        serverTable.addSelectionListener(new SelectionAdapter() {
            @Override
            public void widgetSelected(SelectionEvent e) {
                Objects.requireNonNull(presenter);
                int selectedIndex = serverTable.getSelectionIndex();
                presenter.setSelectedServer(selectedIndex);
            }
        });

        // Add and remove server buttons
        addButton.addListener(SWT.Selection, e -> presenter.addServer());
        removeButton.addListener(SWT.Selection, e -> presenter.removeServer(serverTable.getSelectionIndex()));

        // Handle checkbox state changes
        checkboxTableViewer.addCheckStateListener(event -> {
            boolean checked = event.getChecked();
            var element = event.getElement();
            int index = ((List<?>) serverTableViewer.getInput()).indexOf(element);
            if (index != -1) {
                presenter.toggleServerEnabled(index, checked);
            }
        });
    }

    private void createServerDetailsForm( Composite parent )
    {
        form = new Group( parent, SWT.NULL );
        form.setText( "MCP Server Details" );
        FormLayout formLayout = new FormLayout();
        form.setLayout( formLayout );

        // Server name label and field
        nameLabel = new Label(form, SWT.NONE);
        nameLabel.setText("Name:");
        FormData nameLabelData = new FormData();
        nameLabelData.top = new FormAttachment(0, 10);
        nameLabelData.left = new FormAttachment(0, 10);
        nameLabel.setLayoutData(nameLabelData);
        
        nameText = new Text(form, SWT.BORDER);
        nameText.setToolTipText("Server name should contain only letters, numbers, underscores and hyphens ([a-zA-Z0-9_-]). Names must be unique.");
        FormData nameTextData = new FormData();
        nameTextData.top = new FormAttachment(nameLabel, 0, SWT.CENTER);
        nameTextData.left = new FormAttachment(0, 150);
        nameTextData.right = new FormAttachment(100, -10);
        nameText.setLayoutData(nameTextData);
        
        // Server command label and field
        commandLabel = new Label(form, SWT.NONE);
        commandLabel.setText("Command:");
        FormData commandLabelData = new FormData();
        commandLabelData.top = new FormAttachment(nameText, 10);
        commandLabelData.left = new FormAttachment(0, 10);
        commandLabel.setLayoutData(commandLabelData);
        
        commandText = new Text(form, SWT.BORDER);
        commandText.setToolTipText("Command to start the MCP server. Example: npx -y @modelcontextprotocol/server-everything dir");
        FormData commandTextData = new FormData();
        commandTextData.top = new FormAttachment(commandLabel, 0, SWT.CENTER);
        commandTextData.left = new FormAttachment(0, 150);
        commandTextData.right = new FormAttachment(100, -10);
        commandText.setLayoutData(commandTextData);

        // Environment variables label
        Label envLabel = new Label( form, SWT.NONE );
        envLabel.setText( "Environment Variables:" );
        FormData envLabelData = new FormData();
        envLabelData.top = new FormAttachment( commandText, 20 );
        envLabelData.left = new FormAttachment( 0, 10 );
        envLabel.setLayoutData( envLabelData );

        // Environment variables table and buttons
        Composite envComposite = new Composite( form, SWT.NONE );
        GridLayout envLayout = new GridLayout( 2, false );
        envComposite.setLayout( envLayout );

        FormData envCompositeData = new FormData();
        envCompositeData.top = new FormAttachment( envLabel, 5 );
        envCompositeData.left = new FormAttachment( 0, 10 );
        envCompositeData.right = new FormAttachment( 100, -10 );
        envCompositeData.bottom = new FormAttachment( 100, -10 );
        envComposite.setLayoutData( envCompositeData );

        // Create environment variables table
        envTableViewer = new TableViewer( envComposite, SWT.BORDER | SWT.FULL_SELECTION | SWT.V_SCROLL );
        envTable = envTableViewer.getTable();
        envTable.setHeaderVisible( true );
        envTable.setLinesVisible( true );

        GridData envTableData = new GridData( SWT.FILL, SWT.FILL, true, true );
        envTable.setLayoutData( envTableData );

        // Environment variables table columns
        TableViewerColumn envNameCol = new TableViewerColumn( envTableViewer, SWT.NONE );
        envNameCol.getColumn().setText( "Name" );
        envNameCol.getColumn().setWidth( 150 );
        envNameCol.setLabelProvider( new ColumnLabelProvider()
        {
            @Override
            public String getText( Object element )
            {
                return ( (EnvironmentVariable) element ).name();
            }
        } );

        TableViewerColumn envValueCol = new TableViewerColumn( envTableViewer, SWT.NONE );
        envValueCol.getColumn().setText( "Value" );
        envValueCol.getColumn().setWidth( 250 );
        envValueCol.setLabelProvider( new ColumnLabelProvider()
        {
            @Override
            public String getText( Object element )
            {
                return ( (EnvironmentVariable) element ).value();
            }
        } );

        envTableViewer.setContentProvider( ArrayContentProvider.getInstance() );

        // Buttons for environment variables
        Composite envButtonsComposite = new Composite( envComposite, SWT.NONE );
        envButtonsComposite.setLayout( new GridLayout( 1, false ) );
        envButtonsComposite.setLayoutData( new GridData( SWT.FILL, SWT.TOP, false, false ) );

        addEnvButton = new Button( envButtonsComposite, SWT.NONE );
        addEnvButton.setText( "Add" );
        addEnvButton.setLayoutData( new GridData( SWT.FILL, SWT.CENTER, true, false ) );

        editEnvButton = new Button( envButtonsComposite, SWT.NONE );
        editEnvButton.setText( "Edit" );
        editEnvButton.setLayoutData( new GridData( SWT.FILL, SWT.CENTER, true, false ) );

        removeEnvButton = new Button( envButtonsComposite, SWT.NONE );
        removeEnvButton.setText( "Remove" );
        removeEnvButton.setLayoutData( new GridData( SWT.FILL, SWT.CENTER, true, false ) );
    }

    private Text addTextField( Composite form, String labelText )
    {
        Label label = new Label( form, SWT.NONE );
        label.setText( labelText );
        FormData labelData = new FormData();
        Control[] children = form.getChildren();
        if ( children.length == 0 )
        {
            // First control, attach to top
            labelData.top = new FormAttachment( 0, 10 );
        }
        else
        {
            // Attach below the last control
            labelData.top = new FormAttachment( children[children.length - 1], 10 );
        }
        labelData.left = new FormAttachment( 0, 10 );
        label.setLayoutData( labelData );

        Text text = new Text( form, SWT.BORDER );
        FormData textData = new FormData();
        textData.left = new FormAttachment( 0, 150 );
        textData.right = new FormAttachment( 100, -10 );
        textData.top = new FormAttachment( label, 0, SWT.CENTER );
        text.setLayoutData( textData );
        return text;
    }

    private void initializeDetailsListeners()
    {
        // Environment variables buttons
        addEnvButton.addListener( SWT.Selection, e -> {
            String[] result = openEnvironmentVariableDialog( "Add Environment Variable", "", "" );
            if ( result != null )
            {
                currentEnvVars.add( new EnvironmentVariable( result[0], result[1] ) );
                envTableViewer.setInput( currentEnvVars );
                envTableViewer.refresh();
            }
        } );

        editEnvButton.addListener( SWT.Selection, e -> {
            int selectedIndex = envTable.getSelectionIndex();
            if ( selectedIndex >= 0 )
            {
                EnvironmentVariable selectedVar = currentEnvVars.get( selectedIndex );
                String[] result = openEnvironmentVariableDialog( "Edit Environment Variable", selectedVar.name(), selectedVar.value() );
                if ( result != null )
                {
                    currentEnvVars.set( selectedIndex, new EnvironmentVariable( result[0], result[1] ) );
                    envTableViewer.setInput( currentEnvVars );
                    envTableViewer.refresh();
                }
            }
        } );

        removeEnvButton.addListener( SWT.Selection, e -> {
            int selectedIndex = envTable.getSelectionIndex();
            if ( selectedIndex >= 0 )
            {
                presenter.removeServer( selectedIndex );
            }
        } );
    }

    /**
     * Open a dialog to add or edit an environment variable
     * 
     * @param title
     *            the dialog title
     * @param initialName
     *            the initial name value
     * @param initialValue
     *            the initial value
     * @return array with name and value or null if canceled
     */
    private String[] openEnvironmentVariableDialog(String title, String initialName, String initialValue) 
    {
        EnvironmentVariableDialog dialog = new EnvironmentVariableDialog(getShell(), initialName, initialValue);
        if (dialog.open() == Window.OK) 
        {
            return dialog.getValues();
        }
        return null;
    }

    @Override
    protected void performApply()
    {
        int selectedIndex = serverTable.getSelectionIndex();

        // Validate form
        String serverName = nameText.getText().trim();
        if (serverName.isEmpty())
        {
            showError("Server name cannot be empty");
            return;
        }
        
        // Validate server name format (letters, numbers, underscores, hyphens)
        if (!serverName.matches("[a-zA-Z0-9_-]+"))
        {
            showError("Server name can only contain letters, numbers, underscores and hyphens");
            return;
        }

        if (commandText.getText().trim().isEmpty())
        {
            showError("Command cannot be empty");
            return;
        }

        // Create updated server descriptor
        McpServerDescriptor updatedServer = new McpServerDescriptor( "", nameText.getText(), commandText.getText(), currentEnvVars, true,  false ); 

        presenter.saveServer( selectedIndex, updatedServer );
        super.performApply();
    }

    @Override
    protected void performDefaults()
    {
        super.performDefaults();
        presenter.onPerformDefaults();
    }

    /**
     * Show the list of MCP servers
     * 
     * @param servers
     *            the servers to show
     */
    public void showServers(List<McpServerDescriptorWithStatus> servers) 
    {
        uiSync.asyncExec(() -> {
            serverTableViewer.setInput(servers);
            serverTableViewer.refresh();
            
            // Set the checked state for each server based on its enabled status
            for (McpServerDescriptorWithStatus server : servers) {
                serverTableViewer.setChecked(server, server.descriptor().enabled());
            }
        });
    }

    /**
     * Show the details of a server
     * 
     * @param serverDescriptor
     *            the server descriptor
     */
    public void showServerDetails( McpServerDescriptor serverDescriptor )
    {
        uiSync.asyncExec( () -> {
            nameText.setText( serverDescriptor.name() );
            commandText.setText( serverDescriptor.command() );

            // Update environment variables
            currentEnvVars = new ArrayList<>( serverDescriptor.environmentVariables() );
            envTableViewer.setInput( currentEnvVars );
            envTableViewer.refresh();
        } );
    }

    /**
     * Clear the server details form
     */
    public void clearServerDetails()
    {
        uiSync.asyncExec( () -> {
            nameText.setText( "" );
            commandText.setText( "" );

            // Clear environment variables
            currentEnvVars.clear();
            envTableViewer.setInput( currentEnvVars );
            envTableViewer.refresh();
        } );
    }

    /**
     * Set whether the remove button is enabled
     * @param enabled
     */
    public void setRemoveEditable( boolean enabled )
    {
        removeButton.setEnabled( enabled );
    }

    /**
     * Set whether the details form is editable
     * 
     * @param editable
     *            whether the form is editable
     */
    public void setDetailsEditable( boolean editable )
    {
        uiSync.asyncExec( () -> {
            // Enable/disable all form controls
            nameLabel.setEnabled(editable);
            nameText.setEnabled(editable);
            commandLabel.setEnabled(editable);
            commandText.setEnabled(editable);

            // Additionally, enable/disable the environment variable controls
            if ( envTableViewer != null )
            {
                envTable.setEnabled( editable );
                addEnvButton.setEnabled( editable );
                editEnvButton.setEnabled( editable );
                removeEnvButton.setEnabled( editable );
            }

            if ( editable )
            {
                nameText.forceFocus();
                form.redraw();
                form.update();
            }
        } );
    }

    /**
     * Clear the server selection
     */
    public void clearServerSelection()
    {
        uiSync.asyncExec( () -> {
            serverTable.deselectAll();
        } );
    }

    /**
     * Show an error message
     * 
     * @param message
     *            the error message
     */
    public void showError( String message )
    {
        uiSync.asyncExec( () -> {
            MessageDialog.openError( getShell(), "Error", message );
        } );
    }
    
    
    public class EnvironmentVariableDialog extends Dialog {
        private String name;
        private String value;
        private Text nameText;
        private Text valueText;

        public EnvironmentVariableDialog(Shell parentShell, String initialName, String initialValue) 
        {
            super(parentShell);
            this.name = initialName;
            this.value = initialValue;
            setTitle( "Enviromnet variable" );
        }

        @Override
        protected Control createDialogArea(Composite parent)
        {
            Composite container = (Composite) super.createDialogArea(parent);
            container.setLayout(new GridLayout(2, false));

            Label nameLabel = new Label(container, SWT.NONE);
            nameLabel.setText("Name:");
            nameText = new Text(container, SWT.BORDER);
            nameText.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
            nameText.setText(name != null ? name : "");

            Label valueLabel = new Label(container, SWT.NONE);
            valueLabel.setText("Value:");
            valueText = new Text(container, SWT.BORDER);
            valueText.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
            valueText.setText(value != null ? value : "");

            return container;
        }

        @Override
        protected void okPressed() {
            name = nameText.getText();
            value = valueText.getText();
            super.okPressed();
        }
        @Override
        protected Point getInitialSize() 
        {
            return new Point(400, 200); 
        }


        public String[] getValues() {
            return new String[] { name, value };
        }
    }
}