package com.github.gradusnikov.eclipse.assistai.preferences.models;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

import org.eclipse.e4.core.contexts.IEclipseContext;
import org.eclipse.e4.ui.di.UISynchronize;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jface.preference.PreferencePage;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.SashForm;
import org.eclipse.swt.events.SelectionAdapter;
import org.eclipse.swt.events.SelectionEvent;
import org.eclipse.swt.layout.FormAttachment;
import org.eclipse.swt.layout.FormData;
import org.eclipse.swt.layout.FormLayout;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Combo;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Group;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Scale;
import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.TableColumn;
import org.eclipse.swt.widgets.TableItem;
import org.eclipse.swt.widgets.Text;
import org.eclipse.ui.IWorkbench;
import org.eclipse.ui.IWorkbenchPreferencePage;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.gradusnikov.eclipse.assistai.Activator;
import com.github.gradusnikov.eclipse.assistai.models.ModelApiDescriptor;


public class ModelListPreferencePage extends PreferencePage implements IWorkbenchPreferencePage
{
    private UISynchronize uiSync;
    
    private ModelListPreferencePresenter presenter;
    
    private Table      modelTable;

    private Text       apiUrl;

    private Text       apiKey;

    private Text       connectionTimeout;

    private Text       requestTimeout;

    private Combo      modelName;

    private Button     queryModelsButton;

    private Button     withVision;

    private Button     withFunctionCalls;

    private Scale      withTemperature;

    private Label      temperatureLabel;

    private Group      form;

    private Button     addButton;

    private Button     removeButton;

    @Override
    public void init( IWorkbench workbench )
    {
        presenter = Activator.getDefault().getModelsPreferencePresenter();
        
        // workaroud to get UISynchronize as PreferencePage does not seem to
        // be handled by the eclipse context
        IEclipseContext eclipseContext = workbench.getService( IEclipseContext.class );
        uiSync = eclipseContext.get( UISynchronize.class );
    }

    @Override
    protected Control createContents( Composite parent )
    {
        // Change orientation to HORIZONTAL for side-by-side layout
        var sashForm = new SashForm( parent, SWT.VERTICAL );
        sashForm.setLayoutData( new GridData( GridData.FILL_BOTH ) );

        // Composite for list and buttons
        Composite listButtonsComposite = new Composite( sashForm, SWT.NONE );
        listButtonsComposite.setLayout( new GridLayout( 2, false ) );

        modelTable = new Table(listButtonsComposite, SWT.BORDER | SWT.SINGLE | SWT.V_SCROLL ); 
        modelTable.setLayoutData( new GridData( SWT.FILL, SWT.FILL, true, true ) );
        modelTable.setHeaderVisible( true );
        Stream.of( "Url", "Model Name" ).forEach( columnName -> {
           TableColumn column = new TableColumn(modelTable, SWT.NULL);
           column.setText(columnName);
        });
                
        // Composite for buttons to align them vertically
        Composite buttonComposite = new Composite( listButtonsComposite, SWT.NONE );
        buttonComposite.setLayout( new GridLayout( 1, false ) );
        buttonComposite.setLayoutData( new GridData( SWT.FILL, SWT.TOP, false, false ) );

        addButton = new Button( buttonComposite, SWT.NONE );
        addButton.setText( "Add" );
        addButton.setLayoutData( new GridData( SWT.FILL, SWT.CENTER, true, false ) );

        removeButton = new Button( buttonComposite, SWT.NONE );
        removeButton.setText( "Remove" );
        removeButton.setLayoutData( new GridData( SWT.FILL, SWT.CENTER, true, false ) );

        // Model details in the bottom part of the sash form
        createModelDetails( sashForm );

        // Adjust the weights to allocate space (e.g., 30% for list and buttons,
        // 70% for details)
        sashForm.setWeights( new int[] { 1, 2 } );

        presenter.registerView( this );

        initializeListeners();
        clearModelDetails();

        return sashForm;
    }

    @Override
    protected void performApply()
    {
        int selectedIndex = modelTable.getSelectionIndex();
        ModelApiDescriptor updatedModel = new ModelApiDescriptor(
                "",
                "openai", 
                apiUrl.getText(), 
                apiKey.getText(), 
                parseTimeout(connectionTimeout.getText(), 10),
                parseTimeout(requestTimeout.getText(), 30),
                modelName.getText(),
                withTemperature.getSelection(), 
                withVision.getSelection(), 
                withFunctionCalls.getSelection() );
        presenter.saveModel( selectedIndex, updatedModel );
        super.performApply();
    }

    @Override
    protected void performDefaults()
    {
        super.performDefaults();
        presenter.onPerformDefaults();
    }

    private void initializeListeners()
    {
        modelTable.addSelectionListener( new SelectionAdapter()
        {
            @Override
            public void widgetSelected( SelectionEvent e )
            {
                Objects.requireNonNull( presenter );
                int selectedIndex = modelTable.getSelectionIndex();
                presenter.setSelectedModel( selectedIndex );
            }
        } );    
        addButton.addListener( SWT.Selection, e -> presenter.addModel() );
        removeButton.addListener( SWT.Selection, e -> presenter.removeModel( modelTable.getSelectionIndex() ) );
        queryModelsButton.addListener( SWT.Selection, e -> queryAvailableModels() );
        
    }

    private Composite createModelDetails( Composite parent )
    {
        
        form = new Group( parent, SWT.NULL );
        form.setText( "Model API" );
        FormLayout formLayout = new FormLayout();
        form.setLayout( formLayout );

        apiUrl = addTextField( form, "API Url:");
        apiKey = addTextField( form, "API Key:");
        connectionTimeout = addTextField( form, "Connection Timeout (s):");
        requestTimeout = addTextField( form, "Request Timeout (s):");
        modelName = addComboField( form, "Model Name:");
        queryModelsButton = new Button( form, SWT.PUSH );
        queryModelsButton.setText( "Query available models" );
        addFormControl( queryModelsButton, form, "" );
        withVision = addCheckField( form, "With Vision:");
        withFunctionCalls = addCheckField( form, "With Function Calls:");
        withTemperature = addScaleField( form, "Temperature");

        return form;
    }

    private Scale addScaleField( Composite form, String labelText)
    {
        Scale scale = new Scale( form, SWT.NONE );
        scale.setMinimum( -1 );
        scale.setMaximum( 10 );
        scale.setIncrement( 1 );
        scale.setPageIncrement( 1 );
        scale.setToolTipText( "Default, or 0.0 through 1.0" );
        temperatureLabel = addFormControl( scale, form, labelText );
        updateTemperatureLabel( scale );
        scale.addSelectionListener( new SelectionAdapter()
        {
            @Override
            public void widgetSelected( SelectionEvent e )
            {
                updateTemperatureLabel( scale );
            }
        } );
        return scale;
    }

    private void updateTemperatureLabel( Scale scale )
    {
        if ( temperatureLabel == null || temperatureLabel.isDisposed()
                || scale == null || scale.isDisposed() )
        {
            return;
        }

        temperatureLabel.setText( temperatureLabelText( scale.getSelection() ) );
        temperatureLabel.getParent().layout();
    }

    static String temperatureLabelText( int selection )
    {
        return selection < 0
                ? "Temperature: Default"
                : "Temperature: " + Float.toString( selection / 10.0f );
    }

    private Button addCheckField( Composite form, String labelText)
    {
        Button button = new Button( form, SWT.CHECK );
        addFormControl( button, form, labelText);
        return button;
    }

    private Text addTextField( Composite form, String labelText)
    {
        Text text = new Text( form, SWT.BORDER );
        addFormControl( text, form, labelText);
        return text;
    }

    private Combo addComboField( Composite form, String labelText)
    {
        Combo combo = new Combo( form, SWT.BORDER | SWT.DROP_DOWN );
        addFormControl( combo, form, labelText );
        return combo;
    }

    private void queryAvailableModels()
    {
        String url = apiUrl.getText().trim();
        String key = apiKey.getText().trim();
        int connectTimeoutSeconds = parseTimeout( connectionTimeout.getText(), 10 );
        int requestTimeoutSeconds = parseTimeout( requestTimeout.getText(), 30 );
        if ( url.isEmpty() )
        {
            MessageDialog.openWarning( getShell(), "Query Models", "Please enter an API URL first." );
            return;
        }

        queryModelsButton.setEnabled( false );
        Thread.ofVirtual().start( () -> {
            try
            {
                URI modelsUri = toModelsUri( url );
                HttpClient client = HttpClient.newBuilder()
                        .connectTimeout( Duration.ofSeconds( connectTimeoutSeconds ) )
                        .build();
                HttpRequest.Builder requestBuilder = HttpRequest.newBuilder( modelsUri )
                        .timeout( Duration.ofSeconds( requestTimeoutSeconds ) )
                        .header( "Accept", "application/json" )
                        .GET();
                if ( !key.isEmpty() )
                {
                    requestBuilder.header( "Authorization", "Bearer " + key );
                }
                HttpResponse<String> response = client.send( requestBuilder.build(),
                        HttpResponse.BodyHandlers.ofString() );
                if ( response.statusCode() < 200 || response.statusCode() >= 300 )
                {
                    throw new IllegalStateException( "HTTP " + response.statusCode() + ": "
                            + abbreviate( response.body(), 500 ) );
                }

                JsonNode data = new ObjectMapper().readTree( response.body() ).path( "data" );
                if ( !data.isArray() )
                {
                    throw new IllegalStateException( "The response does not contain a 'data' model array." );
                }
                List<String> models = new java.util.ArrayList<>();
                for ( JsonNode node : data )
                {
                    String id = node.path( "id" ).asText();
                    if ( !id.isBlank() )
                    {
                        models.add( id );
                    }
                }
                models.sort( String::compareToIgnoreCase );
                uiSync.asyncExec( () -> showQueriedModels( models ) );
            }
            catch ( Exception exception )
            {
                uiSync.asyncExec( () -> showQueryError( exception ) );
            }
        } );
    }

    private void showQueriedModels( List<String> models )
    {
        if ( queryModelsButton.isDisposed() )
        {
            return;
        }
        String currentModel = modelName.getText();
        modelName.setItems( models.toArray( String[]::new ) );
        modelName.setText( currentModel );
        queryModelsButton.setEnabled( true );
        if ( models.isEmpty() )
        {
            MessageDialog.openInformation( getShell(), "Query Models", "The API returned no models." );
        }
        else
        {
            modelName.setFocus();
            modelName.setListVisible( true );
        }
    }

    private void showQueryError( Exception exception )
    {
        if ( queryModelsButton.isDisposed() )
        {
            return;
        }
        queryModelsButton.setEnabled( true );
        String message = exception.getMessage();
        MessageDialog.openError( getShell(), "Unable to Query Models",
                message == null || message.isBlank() ? exception.getClass().getSimpleName() : message );
    }

    private static URI toModelsUri( String apiUrl )
    {
        URI uri = URI.create( apiUrl );
        String path = uri.getPath() == null ? "" : uri.getPath().replaceFirst( "/+$", "" );
        path = path.replaceFirst( "/(?:chat/completions|responses|completions|models)$", "" );
        if ( !path.endsWith( "/v1" ) )
        {
            path += "/v1";
        }
        return URI.create( uri.getScheme() + "://" + uri.getRawAuthority() + path + "/models" );
    }

    private static String abbreviate( String text, int maxLength )
    {
        if ( text == null || text.length() <= maxLength )
        {
            return text;
        }
        return text.substring( 0, maxLength ) + "...";
    }

    private Label addFormControl( Control control, Composite form, String labelText)
    {
        Label label = new Label( form, SWT.NONE );
        label.setText( labelText );
        FormData labelData = new FormData();
        Control[] children = form.getChildren();
        if ( children.length == 2 )
        {
            // First control, so attach it to the top of the form
            labelData.top = new FormAttachment( 0, 10 );
        }
        else
        {
            // Attach it below the last control
            Control lastControl = children[children.length-3];
            labelData.top = new FormAttachment( lastControl, 10 );
        }
        labelData.left = new FormAttachment( 0, 10 );
        label.setLayoutData( labelData );

        FormData textData = new FormData();
        textData.left = new FormAttachment( 0, 150 );
        textData.right = new FormAttachment( 100, -10 );
        textData.top = new FormAttachment( label, -2, SWT.TOP );
        control.setLayoutData( textData );
        return label;
    }

    public void showModels( java.util.List<ModelApiDescriptor> models )
    {
        uiSync.asyncExec( () -> {
            modelTable.removeAll();
            modelTable.clearAll();
            modelTable.deselectAll();
            models.stream().forEach( this::addToModelList );
            Arrays.stream( modelTable.getColumns() ).forEach( TableColumn::pack );
            modelTable.redraw();
            modelTable.update();
        } );
    }
    
    private void addToModelList( ModelApiDescriptor item )
    {
        TableItem tableItem = new TableItem( modelTable, SWT.NULL );
        tableItem.setText( 0, item.apiUrl() );
        tableItem.setText( 1, item.modelName() );
    }

    public void showModelDetails( ModelApiDescriptor modelApiDescriptor )
    {
        uiSync.asyncExec( () -> {
            apiUrl.setText( modelApiDescriptor.apiUrl() );
            apiKey.setText( modelApiDescriptor.apiKey() );
            connectionTimeout.setText( String.valueOf(modelApiDescriptor.connectionTimeoutSeconds()) );
            requestTimeout.setText( String.valueOf(modelApiDescriptor.requestTimeoutSeconds()) );
            modelName.setText( modelApiDescriptor.modelName() );
            withTemperature.setSelection( modelApiDescriptor.temperature() );
            updateTemperatureLabel( withTemperature );
            withVision.setSelection( modelApiDescriptor.vision() );
            withFunctionCalls.setSelection( modelApiDescriptor.functionCalling() );
        } );
        setDetailsEditable( true );
    }

    public void clearModelDetails()
    {
        uiSync.asyncExec( () -> {
            apiUrl.setText( "" );
            apiKey.setText( "" );
            connectionTimeout.setText( "10" );
            requestTimeout.setText( "30" );
            modelName.setText( "" );
            withTemperature.setSelection( 0 );
            updateTemperatureLabel( withTemperature );
            withVision.setSelection( false );
            withFunctionCalls.setSelection( false );
        } );
        setDetailsEditable( false );
    }
    
    public void setDetailsEditable( boolean editable )
    {
        uiSync.asyncExec( () -> {            
            Arrays.stream( form.getChildren() )
                  .forEach( control -> control.setEnabled( editable ) );
            if ( editable )
            {
                apiUrl.forceFocus();
                form.redraw();
                form.update();
            }
        } );
    }

    public void clearModelSelection()
    {
        uiSync.asyncExec( () -> {
            modelTable.deselectAll();
        } );
    }

    private static int parseTimeout(String text, int defaultValue)
    {
        try {
            int value = Integer.parseInt(text.trim());
            return value > 0 ? value : defaultValue;
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }
}
