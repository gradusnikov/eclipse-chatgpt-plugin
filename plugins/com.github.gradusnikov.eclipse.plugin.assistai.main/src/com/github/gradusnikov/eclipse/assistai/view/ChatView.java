package com.github.gradusnikov.eclipse.assistai.view;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Stream;

import org.apache.commons.lang3.StringUtils;
import org.eclipse.core.runtime.FileLocator;
import org.eclipse.core.runtime.ILog;
import org.eclipse.e4.ui.di.Focus;
import org.eclipse.e4.ui.di.UISynchronize;
import org.eclipse.jface.resource.ImageDescriptor;
import org.eclipse.jface.resource.JFaceResources;
import org.eclipse.jface.resource.LocalResourceManager;
import org.eclipse.swt.SWT;
import org.eclipse.swt.browser.Browser;
import org.eclipse.swt.browser.BrowserFunction;
import org.eclipse.swt.custom.SashForm;
import org.eclipse.swt.custom.ScrolledComposite;
import org.eclipse.swt.dnd.Clipboard;
import org.eclipse.swt.dnd.ImageTransfer;
import org.eclipse.swt.dnd.TextTransfer;
import org.eclipse.swt.events.MenuAdapter;
import org.eclipse.swt.events.MenuEvent;
import org.eclipse.swt.events.MouseAdapter;
import org.eclipse.swt.events.MouseEvent;
import org.eclipse.swt.events.SelectionAdapter;
import org.eclipse.swt.events.SelectionEvent;
import org.eclipse.swt.events.TraverseEvent;
import org.eclipse.swt.events.TraverseListener;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.ImageData;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.layout.FillLayout;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.layout.RowLayout;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Menu;
import org.eclipse.swt.widgets.MenuItem;
import org.eclipse.swt.widgets.Text;
import org.eclipse.swt.widgets.ToolBar;
import org.eclipse.swt.widgets.ToolItem;
import org.eclipse.ui.ISharedImages;
import org.eclipse.ui.PlatformUI;

import com.github.gradusnikov.eclipse.assistai.chat.Attachment;
import com.github.gradusnikov.eclipse.assistai.chat.Attachment.UiVisitor;
import com.github.gradusnikov.eclipse.assistai.preferences.models.ModelApiDescriptor;
import com.github.gradusnikov.eclipse.assistai.prompt.PromptParser;
import com.github.gradusnikov.eclipse.assistai.tools.AssistaiSharedImages;
import com.github.gradusnikov.eclipse.assistai.view.dnd.DropManager;

import jakarta.annotation.PostConstruct;
import jakarta.inject.Inject;

public class ChatView
{

    private Browser              browser;

    @Inject
    private UISynchronize        uiSync;

    @Inject
    private ILog                 logger;

    @Inject
    private ChatViewPresenter     presenter;

    @Inject
    private DropManager          dropManager;

    @Inject
    private AssistaiSharedImages sharedImages;
    
    private LocalResourceManager resourceManager;

    private Text                 inputArea;

    private ScrolledComposite    scrolledComposite;

    private Composite            imagesContainer;
    
	private ToolItem modelDropdownItem;
	
	private ToolBar  actionToolBar;

	private Menu modelMenu;
    
    public ChatView()
    {
    }

    
    @Focus
    public void setFocus()
    {
        inputArea.setFocus();
        presenter.onViewVisible();
    }

    public void clearChatView()
    {
        uiSync.asyncExec( () -> initializeChatView( browser ) );
    }

    public void clearUserInput()
    {
        uiSync.asyncExec( () -> inputArea.setText( "" ) );
    }

    @PostConstruct
    public void createControls( Composite parent )
    {
        resourceManager = new LocalResourceManager( JFaceResources.getResources() );

        SashForm sashForm = new SashForm( parent, SWT.VERTICAL );
        sashForm.setLayoutData( new GridData( SWT.FILL, SWT.FILL, true, true ) );

        Composite browserContainer = new Composite( sashForm, SWT.NONE );
        browserContainer.setLayout( new FillLayout() );

        browser = createChatView( browserContainer );

        // Create the JavaScript-to-Java callback
        new CopyCodeFunction( browser, "eclipseFunc" );

        Composite controls = new Composite( sashForm, SWT.NONE );
        GridLayout controlsLayout = new GridLayout(1, false);
        controlsLayout.marginWidth = 5;
        controlsLayout.marginHeight = 5;
        controls.setLayout(controlsLayout);

        // Create attachments panel at the top
        Composite attachmentsPanel = createAttachmentsPanel( controls );
        attachmentsPanel.setLayoutData( new GridData( SWT.FILL, SWT.FILL, true, false) );
        
        // Create input area with attachment button
        Composite inputContainer = new Composite(controls, SWT.NONE);
        GridLayout inputLayout = new GridLayout(2, false);
        inputLayout.marginWidth = 0;
        inputLayout.marginHeight = 0;
        inputLayout.horizontalSpacing = 5;
        inputContainer.setLayout(inputLayout);
        inputContainer.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
        
        // Create the text input area
        inputArea = createUserInput(inputContainer);
        inputArea.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
        
        
        // Create button bar at the bottom with model selector on left, action buttons on right
        Composite buttonBar = new Composite(controls, SWT.NONE);
        GridLayout buttonBarLayout = new GridLayout(2, false);
        buttonBarLayout.marginHeight = 0;
        buttonBarLayout.marginWidth = 0;
        buttonBar.setLayout(buttonBarLayout);
        buttonBar.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        
        // Left side: Model selector
        
        // Right side: Action buttons - Use ToolBar instead of Composite
        actionToolBar = new ToolBar(buttonBar, SWT.FLAT | SWT.RIGHT);
        actionToolBar.setLayoutData(new GridData(SWT.RIGHT, SWT.CENTER, true, false));
        
        // Add toolbar items instead of buttons
        modelDropdownItem = createModelSelectorComposite(actionToolBar);
        createAttachmentToolItem(actionToolBar);
        createReplayToolItem(actionToolBar);
        createClearChatToolItem(actionToolBar);
        createStopToolItem(actionToolBar);

        // Sets the initial weight ratio: 75% browser, 25% controls
        sashForm.setWeights( new int[] { 70, 30 } );

        // Enable DnD for the controls below the chat view
        dropManager.registerDropTarget( controls );

        clearAttachments();
    }

    private Composite createAttachmentsPanel( Composite parent )
    {
        Composite attachmentsPanel = new Composite( parent, SWT.NONE );
        attachmentsPanel.setLayout( new GridLayout( 1, false ) ); // One column

        scrolledComposite = new ScrolledComposite( attachmentsPanel, SWT.H_SCROLL );
        scrolledComposite.setLayoutData( new GridData( SWT.FILL, SWT.FILL, true, false ) );
        scrolledComposite.setExpandHorizontal( true );
        scrolledComposite.setExpandVertical( true );

        imagesContainer = new Composite( scrolledComposite, SWT.NONE );
        imagesContainer.setLayout( new RowLayout( SWT.HORIZONTAL ) );

        scrolledComposite.setContent( imagesContainer );
        scrolledComposite.setMinSize( imagesContainer.computeSize( SWT.DEFAULT, SWT.DEFAULT ) );

        return attachmentsPanel;
    }

    /**
     * Creates a toolbar item that allows the user to add image attachments to their message.
     * 
     * @param toolbar The parent toolbar
     * @return The created toolbar item
     */
    private ToolItem createAttachmentToolItem(ToolBar toolbar) {
        ToolItem item = new ToolItem(toolbar, SWT.PUSH);
        
        try 
        {
            // Use a suitable icon for attachments - using the add/import icon
            Image attachIcon = PlatformUI.getWorkbench().getSharedImages().getImage(ISharedImages.IMG_OBJ_ADD);
            item.setImage(attachIcon);
        } 
        catch (Exception e) 
        {
            logger.error(e.getMessage(), e);
        }
        
        item.setToolTipText("Add image attachment");
        
        // Add click handler to open file dialog
        item.addSelectionListener(new SelectionAdapter() {
            @Override
            public void widgetSelected(SelectionEvent e) {
                presenter.onAddAttachment();
            }
        });
        
        return item;
    }
    
    /**
     * Creates a toolbar item that allows the user to replay the last conversation
     * using a different model.
     * 
     * @param toolbar The parent toolbar
     * @return The created toolbar item
     */
    private ToolItem createReplayToolItem(ToolBar toolbar) {
        ToolItem item = new ToolItem(toolbar, SWT.PUSH);
        
        try {
            // Use the REDO icon for replay
            Image replayIcon = PlatformUI.getWorkbench().getSharedImages().getImage(ISharedImages.IMG_TOOL_REDO);
            item.setImage(replayIcon);
        } catch (Exception e) {
            logger.error(e.getMessage(), e);
        }
        
        item.setToolTipText("Regenerate response");
        
        item.addSelectionListener(new SelectionAdapter() {
            @Override
            public void widgetSelected(SelectionEvent e) {
                presenter.onReplayLastMessage();
            }
        });
        
        return item;
    }
    
    /**
     * Creates a toolbar item that allows the user to clear the conversation.
     * 
     * @param toolbar The parent toolbar
     * @return The created toolbar item
     */
    private ToolItem createClearChatToolItem(ToolBar toolbar) {
        ToolItem item = new ToolItem(toolbar, SWT.PUSH);
        try {
            // Use the erase/clear icon
            Image clearIcon = PlatformUI.getWorkbench().getSharedImages().getImage(ISharedImages.IMG_ETOOL_CLEAR);
            item.setImage(clearIcon);
        } catch (Exception e) {
            logger.error(e.getMessage(), e);
        }
        item.setToolTipText("Clear conversation");
        item.addSelectionListener(new SelectionAdapter() {
            @Override
            public void widgetSelected(SelectionEvent e) {
                presenter.onClear();
            }
        });
        return item;
    }
    
    /**
     * Creates a toolbar item that allows the user to stop the generation.
     * 
     * @param toolbar The parent toolbar
     * @return The created toolbar item
     */
    private ToolItem createStopToolItem(ToolBar toolbar) {
        ToolItem item = new ToolItem(toolbar, SWT.PUSH);

        // Use the built-in 'IMG_ELCL_STOP' icon
        Image stopIcon = PlatformUI.getWorkbench().getSharedImages().getImage(ISharedImages.IMG_ELCL_STOP);
        item.setImage(stopIcon);
        item.setToolTipText("Stop generation");

        item.addSelectionListener(new SelectionAdapter() {
            @Override
            public void widgetSelected(SelectionEvent e) {
                presenter.onStop();
            }
        });
        return item;
    }
    
    
    /**
     * Creates a model selector composite with model icon, name, and dropdown button
     * 
     * @param parent The parent composite
     * @return The created composite containing the model selector
     */
    private ToolItem createModelSelectorComposite(ToolBar modelToolBar) {
        // Create a dropdown tool item
        ToolItem modelDropdownItem = new ToolItem(modelToolBar, SWT.DROP_DOWN);
        
        modelDropdownItem.setImage( sharedImages.getImage("assistai-16") );
        // Set initial text for the model
        modelDropdownItem.setText("Undefined");
        modelDropdownItem.setToolTipText("Select AI Model");
        
        return modelDropdownItem;
    }
    
    
    
    private Text createUserInput( Composite parent )
    {
        Text inputArea = new Text( parent, SWT.BORDER | SWT.MULTI | SWT.WRAP | SWT.V_SCROLL );
        
        // Set a prompt message
        inputArea.setMessage("Type a message or question here... (Press Enter to send)");
        
        // Add a listener for Enter key to send the message
        inputArea.addTraverseListener( new TraverseListener()
        {
            public void keyTraversed( TraverseEvent e )
            {
                if ( e.detail == SWT.TRAVERSE_RETURN && ( e.stateMask & SWT.MODIFIER_MASK ) == 0 )
                {
                    // Only send if there's actual text to send
                    String text = inputArea.getText().trim();
                    if (!text.isEmpty()) {
                        presenter.onSendUserMessage( text );
                    }
                }
            }
        } );
        
        // Add a key listener to handle Shift+Enter for newlines
        inputArea.addListener(SWT.KeyDown, event -> {
            // If Shift+Enter is pressed, insert a newline instead of sending
            if (event.keyCode == SWT.CR && (event.stateMask & SWT.SHIFT) != 0) {
                int caretPosition = inputArea.getCaretPosition();
                inputArea.insert("\n");
                inputArea.setSelection(caretPosition + 1);
                event.doit = false; // Prevent default behavior
            }
        });
        
        createCustomMenu( inputArea );
        return inputArea;
    }

    /**
     * Dynamically creates and assigns a custom context menu to the input area.
     * <p>
     * This method constructs a context menu with "Cut", "Copy", and "Paste"
     * actions for the text input area. The "Paste" action is conditionally
     * enabled based on the current content of the clipboard: it's enabled if
     * the clipboard contains either text or image data. When triggered, the
     * "Paste" action checks the clipboard content type and handles it
     * accordingly - pasting text directly into the input area or invoking a
     * custom handler for image data.
     *
     * @param inputArea
     *            The Text widget to which the custom context menu will be
     *            attached.
     */
    private void createCustomMenu( Text inputArea )
    {
        Menu menu = new Menu( inputArea );
        inputArea.setMenu( menu );
        menu.addMenuListener( new MenuAdapter()
        {
            @Override
            public void menuShown( MenuEvent e )
            {
                // Dynamically adjust the context menu
                MenuItem[] items = menu.getItems();
                for ( MenuItem item : items )
                {
                    item.dispose();
                }
                // Add Cut, Copy, Paste items
                addMenuItem( menu, "Cut", () -> inputArea.cut() );
                addMenuItem( menu, "Copy", () -> inputArea.copy() );
                MenuItem pasteItem = addMenuItem( menu, "Paste", () -> handlePasteOperation() );
                // Enable or disable paste based on clipboard content
                Clipboard clipboard = new Clipboard( Display.getCurrent() );
                boolean enablePaste = clipboard.getContents( TextTransfer.getInstance() ) != null
                        || clipboard.getContents( ImageTransfer.getInstance() ) != null;
                pasteItem.setEnabled( enablePaste );
                clipboard.dispose();
            }
        } );
    }

    private MenuItem addMenuItem( Menu parent, String text, Runnable action )
    {
        MenuItem item = new MenuItem( parent, SWT.NONE );
        item.setText( text );
        item.addListener( SWT.Selection, e -> action.run() );
        return item;
    }

    private void handlePasteOperation()
    {
        Clipboard clipboard = new Clipboard( Display.getCurrent() );

        if ( clipboard.getContents( ImageTransfer.getInstance() ) != null )
        {
            ImageData imageData = (ImageData) clipboard.getContents( ImageTransfer.getInstance() );
            presenter.onAttachmentAdded( imageData );
        }
        else
        {
            String textData = (String) clipboard.getContents( TextTransfer.getInstance() );
            if ( textData != null )
            {
                inputArea.insert( textData ); // Manually insert text at the
                                              // current caret position
            }

        }
    }

    private Browser createChatView( Composite parent )
    {
        Browser browser = new Browser( parent, SWT.EDGE );
        initializeChatView( browser );
        initializeFunctions( browser );
        return browser;
    }

    private void initializeFunctions( Browser browser )
    {
        new CopyCodeFunction( browser, "eclipseCopyCode" );
        new ApplyPatchFunction( browser, "eclipseApplyPatch" );
        new DiffCodeFunction( browser, "eclipseDiffCode" );
        new InsertCodeFunction( browser, "eclipseInsertCode" );
        new NewFileFunction( browser, "eclipseNewFile" );
    }

    private void initializeChatView( Browser browser )
    {
        String htmlTemplate = """
                <html>
                    <style>${css}</style>
                    <script>${js}</script>
                    <body>
                            <div id="content">
                            </div>
                    </body>
                </html>
                """;

        String js = loadJavaScripts();
        String css = loadCss();
        htmlTemplate = htmlTemplate.replace( "${js}", js );
        htmlTemplate = htmlTemplate.replace( "${css}", css );

        // Initialize the browser with base HTML and CSS
        browser.setText( htmlTemplate );
    }

    /**
     * Loads the CSS files for the ChatGPTViewPart component.
     *
     * @return A concatenated string containing the content of the loaded CSS
     *         files.
     */
    private String loadCss()
    {
        StringBuilder css = new StringBuilder();
        String[] cssFiles = { "textview.css", "dark.min.css", "fa6.all.min.css" };
        for ( String file : cssFiles )
        {
            try (InputStream in = FileLocator.toFileURL( new URI( "platform:/plugin/com.github.gradusnikov.eclipse.plugin.assistai.main/css/" + file ).toURL() )
                    .openStream())
            {
                css.append( new String( in.readAllBytes(), StandardCharsets.UTF_8 ) );
                css.append( "\n" );
            }
            catch ( IOException | URISyntaxException e )
            {
                throw new RuntimeException( e );
            }
        }
        return css.toString();
    }

    /**
     * Loads the JavaScript files for the ChatGPTViewPart component.
     *
     * @return A concatenated string containing the content of the loaded
     *         JavaScript files.
     */
    private String loadJavaScripts()
    {
        String[] jsFiles = { "highlight.min.js", "MathJax/es5/tex-mml-svg.js", "textview.js" };
        StringBuilder js = new StringBuilder();
        for ( String file : jsFiles )
        {
            try (InputStream in = FileLocator.toFileURL( new URL( "platform:/plugin/com.github.gradusnikov.eclipse.plugin.assistai.main/js/" + file ) )
                    .openStream())
            {
                js.append( new String( in.readAllBytes(), StandardCharsets.UTF_8 ) );
                js.append( "\n" );
            }
            catch ( IOException e )
            {
                throw new RuntimeException( e );
            }
        }
        return js.toString();
    }

    public void setMessageHtml( String messageId, String messageBody )
    {
        uiSync.asyncExec( () -> {
            PromptParser parser = new PromptParser( messageBody );

            String fixedHtml = escapeHtmlQuotes( fixLineBreaks( parser.parseToHtml() ) );
            // inject and highlight html message
            browser.execute( "document.getElementById(\"message-" + messageId + "\").innerHTML = '" + fixedHtml + "';renderCode();" );
            // Scroll down
            browser.execute( "window.scrollTo(0, document.body.scrollHeight);" );
        } );
    }

    /**
     * Replaces newline characters with line break escape sequences in the given
     * string.
     *
     * @param html
     *            The input string containing newline characters.
     * @return A string with newline characters replaced by line break escape
     *         sequences.
     */
    private String fixLineBreaks( String html )
    {
        return html.replace( "\n", "\\n" ).replace( "\r", "" );
    }

    /**
     * Escapes HTML quotation marks in the given string.
     * 
     * @param html
     *            The input string containing HTML.
     * @return A string with escaped quotation marks for proper HTML handling.
     */
    private String escapeHtmlQuotes( String html )
    {
        return html.replace( "\"", "\\\"" ).replace( "'", "\\'" );
    }

    public void appendMessage( String messageId, String role )
    {
        //
        String cssClass = "user".equals( role ) ? "chat-bubble me" : "chat-bubble you";
        uiSync.asyncExec( () -> {
            browser.execute( """
                    node = document.createElement("div");
                    node.setAttribute("id", "message-${id}");
                    node.setAttribute("class", "${cssClass}");
                    document.getElementById("content").appendChild(node);
                    	""".replace( "${id}", messageId ).replace( "${cssClass}", cssClass ) );
            browser.execute(
                    // Scroll down
                    "window.scrollTo(0, document.body.scrollHeight);" );
        } );
    }

	
	// Add a method to hide the tool use message
	public void hideMessage(String messageId) 
	{
	    uiSync.asyncExec(() -> {
	        browser.execute("""
	                var node = document.getElementById("message-${id}");
	                if(node) {
	                    node.classList.add("hidden");
	                }
	                """.replace("${id}", messageId));
	    });
	}


	public void removeMessage( String messageId )
    {
	    uiSync.asyncExec(() -> {
	        browser.execute("""
	                var node = document.getElementById("message-${id}");
	                if(node) {
	                    node.remove();
	                }
	                """.replace("${id}", messageId));
	    });
    }

    public void clearAttachments()
    {
        setAttachments( Collections.emptyList() );
    }

    public void setAttachments( List<Attachment> attachments )
    {
        uiSync.asyncExec( () -> {
            // Dispose of existing children to avoid memory leaks and remove old
            // images
            for ( var child : imagesContainer.getChildren() )
            {
                child.dispose();
            }

            imagesContainer.setLayout( new RowLayout( SWT.HORIZONTAL ) );

            if ( attachments.isEmpty() )
            {
                scrolledComposite.setVisible( false );
                ( (GridData) scrolledComposite.getLayoutData() ).heightHint = 0;
            }
            else
            {
                AttachmentVisitor attachmentVisitor = new AttachmentVisitor();

                // There are images to display, add them to the imagesContainer
                for ( var attachment : attachments )
                {
                    attachment.accept( attachmentVisitor );
                }
                scrolledComposite.setVisible( true );
                imagesContainer.setSize( imagesContainer.computeSize( SWT.DEFAULT, SWT.DEFAULT ) );
                ( (GridData) scrolledComposite.getLayoutData() ).heightHint = SWT.DEFAULT;
            }
            // Refresh the layout
            updateLayout( imagesContainer );
        } );
    }

    public void setAvailableModels(List<ModelApiDescriptor> availableModels, String selected ) 
    {
    	uiSync.asyncExec( () -> {
        	// update menu button with model name and icon
        	availableModels.stream()
        				   .filter( model -> model.uid().equals(selected) )
        				   .map( model -> new ModelApiDescriptorDecorator(model, selected))
        				   .findFirst()
        				   .ifPresent( model -> {
        					   uiSync.asyncExec( () -> {
        						   modelDropdownItem.setText( model.getDisplayName() );
        						   modelDropdownItem.setImage( model.getDisplayIcon() );
        						   updateLayout( actionToolBar );
        					   });
        				   });
    		
    	});
    	// clean previous
    	if ( Objects.nonNull(modelMenu) )
    	{
    		Menu oldMenu = modelMenu;
    		Stream.of( modelMenu.getListeners(SWT.Selection) ).forEach( l -> oldMenu.removeListener(SWT.Selection, l) );
    		oldMenu.dispose();
    	}
    	// Re-create the dropdown menu
        modelMenu = new Menu(actionToolBar.getShell(), SWT.POP_UP);
        
        // Add selection listener to handle dropdown arrow clicks
        modelDropdownItem.addListener(SWT.Selection, event -> {
            // Check if the dropdown arrow was clicked
            if (event.detail == SWT.ARROW) 
            {
                // Position the menu below the tool item
                Rectangle rect = modelDropdownItem.getBounds();
                Point pt = new Point(rect.x, rect.y + rect.height);
                pt = actionToolBar.toDisplay(pt);
                
                // Rebuild the menu each time to ensure it reflects current state
                for (MenuItem item : modelMenu.getItems()) 
                {
                    item.dispose();
                }
                
                availableModels.stream()
                				.map( model -> new ModelApiDescriptorDecorator(model, selected) )
                				.forEach( model -> createModelMenuItem(modelMenu, model) );
                modelMenu.setLocation(pt.x, pt.y);
                modelMenu.setVisible(true);
            } 
            else 
            {
                // Main part of button was clicked
            }
        });		
	}

	private void createModelMenuItem(final Menu modelMenu, ModelApiDescriptorDecorator model ) 
	{
		MenuItem menuItem = new MenuItem(modelMenu, SWT.CHECK);
		menuItem.setText(model.getDisplayName());
		menuItem.setSelection(model.isSelected());
		menuItem.addSelectionListener( new SelectionAdapter() {
		    @Override
		    public void widgetSelected(SelectionEvent e) {
		    	presenter.onModelSelected(model.getModelId());
		    }
		});
	}

	public void updateLayout( Composite composite )
	{
	    if ( composite != null )
	    {
	        composite.layout();
	        updateLayout( composite.getParent() );
	    }
	}


	public void setInputEnabled( boolean b )
	{
	    uiSync.asyncExec( () -> {
	        inputArea.setEnabled( b );
	    } );
	}

	private Image createSelectedImage( Image originalImage )
	{
	    // Create a new image that is a copy of the original
	    Image tintedImage = new Image( Display.getCurrent(), originalImage.getBounds() );
	
	    // Create a GC to draw on the tintedImage
	    GC gc = new GC( tintedImage );
	
	    // Draw the original image onto the new image
	    gc.drawImage( originalImage, 0, 0 );
	
	    // Set alpha value for the overlay (128 is half-transparent)
	    gc.setAlpha( 128 );
	
	    // Get the system selection color
	    Color selectionColor = Display.getCurrent().getSystemColor( SWT.COLOR_LIST_SELECTION );
	
	    // Fill the image with the selection color overlay
	    gc.setBackground( selectionColor );
	    gc.fillRectangle( tintedImage.getBounds() );
	
	    // Dispose the GC to free up system resources
	    gc.dispose();
	
	    return tintedImage;
	}

	private class AttachmentVisitor implements UiVisitor
    {
        private Label imageLabel;

        @Override
        public void add( ImageData preview, String caption )
        {
            imageLabel = new Label( imagesContainer, SWT.NONE );
            // initially nothing is selected
            imageLabel.setData( "selected", false );
            imageLabel.setToolTipText( caption );

            ImageDescriptor imageDescriptor;
            imageDescriptor = Optional.ofNullable( preview )
            						  .map( id -> ImageDescriptor.createFromImageDataProvider( zoom -> id ) )
            						  .orElse(sharedImages.getImageDescriptor("folder")  );

            Image scaledImage = resourceManager.createImageWithDefault( imageDescriptor );
            Image selectedImage = createSelectedImage( scaledImage );

            imageLabel.setImage( scaledImage );

            imageLabel.addDisposeListener( l -> {
                resourceManager.destroy( imageDescriptor );
                selectedImage.dispose();
            } );

            // Add mouse listener to handle selection
            imageLabel.addMouseListener( new MouseAdapter()
            {
                @Override
                public void mouseUp( MouseEvent e )
                {
                    boolean isSelected = (boolean) imageLabel.getData( "selected" );
                    imageLabel.setData( "selected", !isSelected );

                    if ( isSelected )
                    {
                        imageLabel.setImage( scaledImage );
                    }
                    else
                    {
                        // If it was not selected, apply an overlay
                        Image selectedImage = createSelectedImage( scaledImage );
                        imageLabel.setImage( selectedImage );
                        // Dispose the tinted image when the label is
                        // disposed
                        imageLabel.addDisposeListener( l -> selectedImage.dispose() );
                    }
                    imagesContainer.layout();
                }
            } );
        }
    }

    private class ModelApiDescriptorDecorator
	{
		private final ModelApiDescriptor descriptor;
		private final String selectedUid;
		public ModelApiDescriptorDecorator( ModelApiDescriptor descriptor, String selectedUid )
		{
			this.descriptor = descriptor;
			this.selectedUid = selectedUid;
		}
		public String getDisplayName()
		{
			return StringUtils.abbreviate( descriptor.modelName(), 20);
		}
		public Image getDisplayIcon()
		{
			return switch( descriptor.apiUrl() ) {
				case String s when s.contains( "anthropic" ) -> sharedImages.getImage("claude-ai-icon-16");
				case String s when s.contains( "openai" ) -> sharedImages.getImage("chatgpt-icon-16");
				case String s when s.contains( "grok" ) -> sharedImages.getImage("grok-icon-16");
				case String s when s.contains( "google" ) -> sharedImages.getImage("google-gemini-icon-16");
				case String s when s.contains( "deepseek" ) -> sharedImages.getImage("deepseek-logo-icon-16");
				default -> sharedImages.getImage("assistai-16");
			};
			
		}
		public String getModelId()
		{
			return descriptor.uid();
		}
		public boolean isSelected() {
			return descriptor.uid().equals(selectedUid);
		}
	}

	/**
     * This function establishes a JavaScript-to-Java callback for the browser,
     * allowing the IDE to copy code. It is invoked from JavaScript when the
     * user interacts with the chat view to copy a code block.
     */
    private class CopyCodeFunction extends BrowserFunction
    {
        public CopyCodeFunction( Browser browser, String name )
        {
            super( browser, name );
        }

        @Override
        public Object function( Object[] arguments )
        {
            if ( arguments.length > 0 && arguments[0] instanceof String )
            {
                String codeBlock = (String) arguments[0];
                presenter.onCopyCode( codeBlock );
            }
            return null;
        }
    }
    
    /**
     * This function establishes a JavaScript-to-Java callback for the browser,
     * allowing the IDE to copy code. It is invoked from JavaScript when the
     * user interacts with the chat view to copy a code block.
     */
    private class ApplyPatchFunction extends BrowserFunction
    {
        public ApplyPatchFunction( Browser browser, String name )
        {
            super( browser, name );
        }
        @Override
        public Object function( Object[] arguments )
        {
            if ( arguments.length > 0 && arguments[0] instanceof String )
            {
                String codeBlock = (String) arguments[0];
                presenter.onApplyPatch( codeBlock );
            }
            return null;
        }
    }
    private class InsertCodeFunction extends BrowserFunction
    {
        public InsertCodeFunction( Browser browser, String name )
        {
            super( browser, name );
        }
        @Override
        public Object function( Object[] arguments )
        {
            if ( arguments.length > 0 && arguments[0] instanceof String )
            {
                String codeBlock = (String) arguments[0];
                presenter.onInsertCode( codeBlock );
            }
            return null;
        }
    }
    private class DiffCodeFunction extends BrowserFunction
    {
        public DiffCodeFunction( Browser browser, String name )
        {
            super( browser, name );
        }
        @Override
        public Object function( Object[] arguments )
        {
            if ( arguments.length > 0 && arguments[0] instanceof String )
            {
                String codeBlock = (String) arguments[0];
                presenter.onDiffCode( codeBlock );
            }
            return null;
        }
    }
    private class NewFileFunction extends BrowserFunction
    {
        public NewFileFunction( Browser browser, String name )
        {
            super( browser, name );
        }
        @Override
        public Object function( Object[] arguments )
        {
            if ( arguments.length > 0 && Arrays.stream( arguments ).allMatch( s -> s instanceof String ) )
            {
                String codeBlock = (String) arguments[0];
                String lang      = (String) arguments[1];
                presenter.onNewFile( codeBlock, lang );
            }
            return null;
        }
    }
}
