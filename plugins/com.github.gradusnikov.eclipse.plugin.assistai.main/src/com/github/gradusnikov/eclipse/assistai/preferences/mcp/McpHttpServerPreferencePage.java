package com.github.gradusnikov.eclipse.assistai.preferences.mcp;

import java.util.List;
import java.util.stream.Collectors;

import org.eclipse.e4.core.contexts.IEclipseContext;
import org.eclipse.e4.ui.di.UISynchronize;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jface.preference.PreferencePage;
import org.eclipse.swt.SWT;
import org.eclipse.swt.dnd.Clipboard;
import org.eclipse.swt.dnd.TextTransfer;
import org.eclipse.swt.dnd.Transfer;
import org.eclipse.swt.events.SelectionAdapter;
import org.eclipse.swt.events.SelectionEvent;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Group;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Text;
import org.eclipse.ui.IWorkbench;
import org.eclipse.ui.IWorkbenchPreferencePage;

import com.github.gradusnikov.eclipse.assistai.Activator;
import com.github.gradusnikov.eclipse.assistai.mcp.http.HttpMcpServerPreferences;

/**
 * Preference page for HTTP MCP Server settings
 */
public class McpHttpServerPreferencePage extends PreferencePage implements IWorkbenchPreferencePage
{
    private UISynchronize uiSync;
    private McpHttpServerPreferencePresenter presenter;

    private Text hostnameText;
    private Text portText;
    private Text tokenText;
    private Button enabledCheckbox;
    private Button generateTokenButton;
    private Button copyTokenButton;
    private Label statusLabel;
    private Text endpointsText;

    @Override
    public void init(IWorkbench workbench)
    {
        presenter = Activator.getDefault().getHttpMcpServerPreferencePresenter();

        // Get UISynchronize service
        IEclipseContext eclipseContext = workbench.getService(IEclipseContext.class);
        uiSync = eclipseContext.get(UISynchronize.class);
    }

    @Override
    protected Control createContents(Composite parent)
    {
        Composite container = new Composite(parent, SWT.NONE);
        container.setLayout(new GridLayout(1, false));

        // Server Configuration Group
        createServerConfigGroup(container);

        // Server Status Group
        createServerStatusGroup(container);

        // Endpoints Group
        createEndpointsGroup(container);

        // Register with presenter and load initial data
        presenter.registerView(this);

        return container;
    }

    private void createServerConfigGroup(Composite parent)
    {
        Group configGroup = new Group(parent, SWT.NONE);
        configGroup.setText("Server Configuration");
        configGroup.setLayout(new GridLayout(3, false));
        configGroup.setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false));

        // Enabled checkbox
        enabledCheckbox = new Button(configGroup, SWT.CHECK);
        enabledCheckbox.setText("Enable HTTP MCP Server");
        GridData enabledData = new GridData(SWT.FILL, SWT.CENTER, true, false);
        enabledData.horizontalSpan = 3;
        enabledCheckbox.setLayoutData(enabledData);

        // Hostname field
        Label hostnameLabel = new Label(configGroup, SWT.NONE);
        hostnameLabel.setText("Hostname:");

        hostnameText = new Text(configGroup, SWT.BORDER);
        hostnameText.setToolTipText("The hostname or IP address the server will bind to");
        GridData hostnameData = new GridData(SWT.FILL, SWT.CENTER, true, false);
        hostnameData.horizontalSpan = 2;
        hostnameText.setLayoutData(hostnameData);

        // Port field
        Label portLabel = new Label(configGroup, SWT.NONE);
        portLabel.setText("Port:");

        portText = new Text(configGroup, SWT.BORDER);
        portText.setToolTipText("The port number the server will listen on");
        GridData portData = new GridData(SWT.FILL, SWT.CENTER, true, false);
        portData.horizontalSpan = 2;
        portText.setLayoutData(portData);

        // Auth token field
        Label tokenLabel = new Label(configGroup, SWT.NONE);
        tokenLabel.setText("Auth Token:");

        tokenText = new Text(configGroup, SWT.BORDER);
        tokenText.setToolTipText("Authentication token for securing the MCP server");
        tokenText.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        // Token buttons container
        Composite tokenButtonsComposite = new Composite(configGroup, SWT.NONE);
        tokenButtonsComposite.setLayout(new GridLayout(2, true));
        tokenButtonsComposite.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, false, false));

        generateTokenButton = new Button(tokenButtonsComposite, SWT.PUSH);
        generateTokenButton.setText("Generate");
        generateTokenButton.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        generateTokenButton.addSelectionListener(new SelectionAdapter()
        {
            @Override
            public void widgetSelected(SelectionEvent e)
            {
                presenter.onGenerateNewToken();
            }
        });

        copyTokenButton = new Button(tokenButtonsComposite, SWT.PUSH);
        copyTokenButton.setText("Copy");
        copyTokenButton.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        copyTokenButton.addSelectionListener(new SelectionAdapter()
        {
            @Override
            public void widgetSelected(SelectionEvent e)
            {
                String token = tokenText.getText();
                if (!token.isEmpty())
                {
                    Clipboard clipboard = new Clipboard(getShell().getDisplay());
                    try
                    {
                        clipboard.setContents(
                            new Object[] { token },
                            new Transfer[] { TextTransfer.getInstance() });
                        MessageDialog.openInformation(getShell(), "Success", "Token copied to clipboard");
                    }
                    finally
                    {
                        clipboard.dispose();
                    }
                }
            }
        });
    }

    private void createServerStatusGroup(Composite parent)
    {
        Group statusGroup = new Group(parent, SWT.NONE);
        statusGroup.setText("Server Status");
        statusGroup.setLayout(new GridLayout(1, false));
        statusGroup.setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false));

        statusLabel = new Label(statusGroup, SWT.NONE);
        statusLabel.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
    }

    private void createEndpointsGroup(Composite parent)
    {
        Group endpointsGroup = new Group(parent, SWT.NONE);
        endpointsGroup.setText("Enabled Endpoints");
        endpointsGroup.setLayout(new GridLayout(1, false));
        endpointsGroup.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));

        endpointsText = new Text(endpointsGroup, SWT.BORDER | SWT.MULTI | SWT.READ_ONLY | SWT.V_SCROLL | SWT.WRAP);
        endpointsText.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
        endpointsText.setToolTipText("Available Http MCP endpoints.");
    }


    /**
     * Update the server status display
     */
    public void updateServerStatus( boolean isRunning )
    {
        if (uiSync != null )
        {
            uiSync.asyncExec(() ->
            {
                String statusText = isRunning ? "HTTP Server is running" : "HTTP Server is disabled";
                statusLabel.setText(statusText);
            });
        }
    }
    public void updateEnabledEndpoints( List<String> endpoints )
    {
        if (uiSync != null )
        {
            uiSync.asyncExec(() ->
            {
                var endpointsString = endpoints.stream().collect( Collectors.joining("\n") );
                endpointsText.setText( endpointsString );
            });
        }
    }

    @Override
    protected void performApply()
    {
        if (validateInput())
        {
            savePreferences();
            super.performApply();
        }
    }

    @Override
    public boolean performOk()
    {
        if (validateInput())
        {
            savePreferences();
            return super.performOk();
        }
        return false;
    }

    private boolean validateInput()
    {
        // Validate port
        try
        {
            int port = Integer.parseInt(portText.getText().trim());
            if (port < 1 || port > 65535)
            {
                showError("Port must be between 1 and 65535");
                return false;
            }
        }
        catch (NumberFormatException e)
        {
            showError("Port must be a valid number");
            return false;
        }

        // Validate hostname
        String hostname = hostnameText.getText().trim();
        if (hostname.isEmpty())
        {
            showError("Hostname cannot be empty");
            return false;
        }

        return true;
    }

    private void savePreferences()
    {
        int port = Integer.parseInt(portText.getText().trim());
        String hostname = hostnameText.getText().trim();
        String token = tokenText.getText().trim();
        boolean enabled = enabledCheckbox.getSelection();

        presenter.savePreferences(port, hostname, token, enabled);
    }

    @Override
    protected void performDefaults()
    {
        super.performDefaults();
        presenter.onPerformDefaults();
    }

    private void showError(String message)
    {
        MessageDialog.openError(getShell(), "Error", message);
    }

    public void updateHttpMcpPreferences( HttpMcpServerPreferences prefs )
    {
        uiSync.asyncExec(() ->
        {
            hostnameText.setText(prefs.hostname() );
            portText.setText(Integer.toString( prefs.port() ));
            tokenText.setText(prefs.token());
        });
    }

    public void updateHttpMcpEnbled( boolean enabled )
    {
        uiSync.asyncExec(() -> {
            enabledCheckbox.setSelection( enabled );
        } );
        
    }
}
