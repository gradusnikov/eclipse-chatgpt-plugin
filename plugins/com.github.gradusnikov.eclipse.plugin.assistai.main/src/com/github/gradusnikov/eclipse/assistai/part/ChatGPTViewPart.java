package com.github.gradusnikov.eclipse.assistai.part;

import java.io.IOException;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

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
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Menu;
import org.eclipse.swt.widgets.MenuItem;
import org.eclipse.swt.widgets.Text;
import org.eclipse.ui.ISharedImages;
import org.eclipse.ui.PlatformUI;

import com.github.gradusnikov.eclipse.assistai.part.Attachment.UiVisitor;
import com.github.gradusnikov.eclipse.assistai.part.dnd.DropManager;
import com.github.gradusnikov.eclipse.assistai.prompt.PromptParser;

import jakarta.annotation.PostConstruct;
import jakarta.inject.Inject;

public class ChatGPTViewPart
{

    private Browser              browser;

    @Inject
    private UISynchronize        uiSync;

    @Inject
    private ILog                 logger;

    @Inject
    private ChatGPTPresenter     presenter;

    @Inject
    private DropManager          dropManager;

    private LocalResourceManager resourceManager;

    private Text                 inputArea;

    private ScrolledComposite    scrolledComposite;

    private Composite            imagesContainer;

    public ChatGPTViewPart()
    {
    }

    @Focus
    public void setFocus()
    {
        inputArea.setFocus();
    }

    public void clearChatView()
    {
        uiSync.asyncExec( () -> initializeChatView( browser ) );
    }

    public void clearUserInput()
    {
        uiSync.asyncExec( () -> {
            inputArea.setText( "" );
        } );
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

        Composite attachmentsPanel = createAttachmentsPanel( controls );
        inputArea = createUserInput( controls );
        // create components
        Button[] buttons = { 
                createClearChatButton( controls ), 
                createStopButton( controls ),
//                createArrowButton( controls )
                };

        // layout components
        controls.setLayout( new GridLayout( buttons.length, false ) );
        attachmentsPanel.setLayoutData( new GridData( SWT.FILL, SWT.FILL, true, false, buttons.length, 1 ) ); // Full
                                                                                                              // width
        inputArea.setLayoutData( new GridData( SWT.FILL, SWT.FILL, true, true, buttons.length, 1 ) ); // colspan
                                                                                                      // =
                                                                                                      // num
                                                                                                      // of
                                                                                                      // buttons
        for ( var button : buttons )
        {
            button.setLayoutData( new GridData( SWT.FILL, SWT.RIGHT, true, false ) );
        }

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

    private Button createClearChatButton( Composite parent )
    {
        Button button = new Button( parent, SWT.PUSH );
        button.setText( "Clear" );
        try
        {
            Image clearIcon = PlatformUI.getWorkbench().getSharedImages().getImage( org.eclipse.ui.ISharedImages.IMG_ELCL_REMOVE );
            button.setImage( clearIcon );
        }
        catch ( Exception e )
        {
            logger.error( e.getMessage(), e );
        }
        button.addSelectionListener( new SelectionAdapter()
        {
            @Override
            public void widgetSelected( SelectionEvent e )
            {
                presenter.onClear();
            }
        } );
        return button;
    }

    private Button createStopButton( Composite parent )
    {
        Button button = new Button( parent, SWT.PUSH );
        button.setText( "Stop" );

        // Use the built-in 'IMG_ELCL_STOP' icon
        Image stopIcon = PlatformUI.getWorkbench().getSharedImages().getImage( org.eclipse.ui.ISharedImages.IMG_ELCL_STOP );
        button.setImage( stopIcon );

        button.addSelectionListener( new SelectionAdapter()
        {
            @Override
            public void widgetSelected( SelectionEvent e )
            {
                presenter.onStop();
            }
        } );
        return button;
    }


    private Button createArrowButton(Composite parent) {
        // Create an arrow button
        Button arrowButton = new Button(parent, SWT.FLAT | SWT.ARROW | SWT.DOWN);

//        // Create a menu for the button
//        Menu menu = new Menu(parent.getShell(), SWT.POP_UP);
//        arrowButton.addListener(SWT.Selection, event -> {
//            if (!menu.isDisposed()) {
//                Rectangle rect = arrowButton.getBounds();
//                Point pt = new Point(rect.x, rect.y + rect.height);
//                pt = parent.toDisplay(pt);
//                menu.setLocation(pt.x, pt.y);
//                menu.setVisible(true);
//            }
//        });
//
//        // Add items with checkboxes
//        String[] items = {"Item 1", "Item 2", "Item 3"};
//        boolean[] selections = {false, true, false}; // Initial selections
//
//        for (int i = 0; i < items.length; i++) {
//            MenuItem menuItem = new MenuItem(menu, SWT.CHECK);
//            menuItem.setText(items[i]);
//            menuItem.setSelection(selections[i]);
//            final int index = i;
//            menuItem.addSelectionListener(new SelectionAdapter() {
//                @Override
//                public void widgetSelected(SelectionEvent e) {
//                    selections[index] = !selections[index];
//                    menuItem.setSelection(selections[index]);
//                }
//            });
//        }
//
        return arrowButton;
    }


    private Text createUserInput( Composite parent )
    {
        Text inputArea = new Text( parent, SWT.BORDER | SWT.MULTI | SWT.WRAP | SWT.V_SCROLL );
        inputArea.addTraverseListener( new TraverseListener()
        {
            public void keyTraversed( TraverseEvent e )
            {
                if ( e.detail == SWT.TRAVERSE_RETURN && ( e.stateMask & SWT.MODIFIER_MASK ) == 0 )
                {
                    presenter.onSendUserMessage( inputArea.getText() );
                }
            }
        } );
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
            try (InputStream in = FileLocator.toFileURL( new URL( "platform:/plugin/com.github.gradusnikov.eclipse.plugin.assistai.main/css/" + file ) )
                    .openStream())
            {
                css.append( new String( in.readAllBytes(), StandardCharsets.UTF_8 ) );
                css.append( "\n" );
            }
            catch ( IOException e )
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
        String[] jsFiles = { "highlight.min.js", "textview.js" };
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
            browser.execute( "document.getElementById(\"message-" + messageId + "\").innerHTML = '" + fixedHtml + "';hljs.highlightAll();" );
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


	public Object removeMessage( int id )
    {
        // TODO Auto-generated method stub
        return null;
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
            try
            {
                imageDescriptor = Optional.ofNullable( preview ).map( id -> ImageDescriptor.createFromImageDataProvider( zoom -> id ) ).orElse(
                        ImageDescriptor.createFromURL( new URL( "platform:/plugin/com.github.gradusnikov.eclipse.plugin.assistai.main/icons/folder.png" ) ) );
            }
            catch ( MalformedURLException e )
            {
                throw new IllegalStateException( e );
            }

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