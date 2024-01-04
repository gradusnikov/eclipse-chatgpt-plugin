package com.github.gradusnikov.eclipse.assistai.part;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;

import javax.annotation.PostConstruct;
import javax.inject.Inject;

import org.eclipse.core.runtime.FileLocator;
import org.eclipse.core.runtime.ILog;
import org.eclipse.e4.ui.di.Focus;
import org.eclipse.e4.ui.di.UISynchronize;
import org.eclipse.swt.SWT;
import org.eclipse.swt.browser.Browser;
import org.eclipse.swt.browser.BrowserFunction;
import org.eclipse.swt.custom.SashForm;
import org.eclipse.swt.events.SelectionAdapter;
import org.eclipse.swt.events.SelectionEvent;
import org.eclipse.swt.events.TraverseEvent;
import org.eclipse.swt.events.TraverseListener;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.layout.FillLayout;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Text;
import org.eclipse.ui.PlatformUI;

import com.github.gradusnikov.eclipse.assistai.prompt.PromptParser;


public class ChatGPTViewPart
{

    private Browser browser;

    @Inject
    private UISynchronize uiSync;
    
    @Inject
    private ILog logger;
    
    @Inject
    private ChatGPTPresenter presenter;
    
    private Text inputArea;

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
        uiSync.asyncExec(() ->  initializeChatView( browser ) );
    }
    public void clearUserInput()
    {
        uiSync.asyncExec(() -> {
            inputArea.setText( "" );
        });
    }
    
    @PostConstruct
    public void createControls(Composite parent)
    {
        SashForm sashForm = new SashForm(parent, SWT.VERTICAL);
        sashForm.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
        
        Composite browserContainer = new Composite(sashForm, SWT.NONE);
        browserContainer.setLayout(new FillLayout());
        
        browser = createChatView( browserContainer );

        // Create the JavaScript-to-Java callback
        new CopyCodeFunction(browser, "eclipseFunc");

        // Add controls for the Clear button and the Stop button
        Composite controls = new Composite(sashForm, SWT.NONE);
        controls.setLayout(new GridLayout(2, false)); // Change GridLayout to have 2 columns

        inputArea = createUserInput(controls);
        inputArea.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true, 2, 1)); // colspan = 2

        Button clearButton = createClearChatButton(parent, controls);
        clearButton.setLayoutData(new GridData(SWT.FILL, SWT.RIGHT, true, false));

        Button stopButton = createStopButton(parent, controls);
        stopButton.setLayoutData(new GridData(SWT.FILL, SWT.RIGHT, true, false));

        // Sets the initial weight ratio: 75% browser, 25% controls
        sashForm.setWeights(new int[]{85, 15}); 
    }

    private Button createClearChatButton( Composite parent, Composite controls )
    {
        Button clearButton = new Button(controls, SWT.PUSH);
        clearButton.setText("Clear");
        try
        {
            Image clearIcon = PlatformUI.getWorkbench().getSharedImages().getImage(org.eclipse.ui.ISharedImages.IMG_ELCL_REMOVE);
            clearButton.setImage( clearIcon );
        }
        catch ( Exception e )
        {
            logger.error( e.getMessage(), e );
        }
        clearButton.addSelectionListener(new SelectionAdapter() {
            @Override
            public void widgetSelected(SelectionEvent e) {
                presenter.onClear();
            }
        });
        return clearButton;
    }
    private Button createStopButton(Composite parent, Composite controls)
    {
        Button stopButton = new Button(controls, SWT.PUSH);
        stopButton.setText("Stop");

        // Use the built-in 'IMG_ELCL_STOP' icon
        Image stopIcon = PlatformUI.getWorkbench().getSharedImages().getImage(org.eclipse.ui.ISharedImages.IMG_ELCL_STOP);
        stopButton.setImage(stopIcon);

        stopButton.addSelectionListener(new SelectionAdapter() {
            @Override
            public void widgetSelected(SelectionEvent e) {
                presenter.onStop();
            }
        });
        return stopButton;
    }
    private Text createUserInput( Composite controls )
    {
        Text inputArea = new Text(controls, SWT.BORDER | SWT.MULTI | SWT.WRAP | SWT.V_SCROLL);
        inputArea.addTraverseListener(new TraverseListener() {
            public void keyTraversed(TraverseEvent e) {
                if (e.detail == SWT.TRAVERSE_RETURN && (e.stateMask & SWT.MODIFIER_MASK) == 0) {
                    presenter.onSendUserMessage( inputArea.getText() );
                }
            }
        });
        return inputArea;
    }

    private Browser createChatView( Composite parent )
    {
        Browser browser = new Browser( parent, SWT.EDGE);
        initializeChatView( browser );
        initializeFunctions( browser );
        return browser;
    }

    private void initializeFunctions( Browser browser )
    {
        new CopyCodeFunction( browser, "eclipseCopyCode" );
        new ApplyPatchFunction( browser, "eclipseApplyPatch" );
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
        
        String js  = loadJavaScripts();
        String css = loadCss();
        htmlTemplate = htmlTemplate.replace("${js}", js );
        htmlTemplate = htmlTemplate.replace("${css}", css );
        
        // Initialize the browser with base HTML and CSS
        browser.setText(htmlTemplate);
    }
    /**
     * Loads the CSS files for the ChatGPTViewPart component.
     *
     * @return A concatenated string containing the content of the loaded CSS files.
     */
    private String loadCss()
    {
        StringBuilder css = new StringBuilder();
        String[] cssFiles = {"textview.css", "dark.min.css"};
        for ( String file : cssFiles )
        {
            try ( InputStream in = FileLocator.toFileURL( new URL("platform:/plugin/com.github.gradusnikov.eclipse.plugin.assistai.main/css/" + file) ).openStream() )
            {
                css.append( new String(in.readAllBytes(), StandardCharsets.UTF_8) );
                css.append("\n");
            }
            catch (IOException e)
            {
                throw new RuntimeException(e);
            }
        }
        return css.toString();
    }
    /**
     * Loads the JavaScript files for the ChatGPTViewPart component.
     *
     * @return A concatenated string containing the content of the loaded JavaScript files.
     */
    private String loadJavaScripts()
    {
        String[] jsFiles = {"highlight.min.js"};
        StringBuilder js = new StringBuilder();
        for ( String file : jsFiles )
        {
            try ( InputStream in = FileLocator.toFileURL( new URL("platform:/plugin/com.github.gradusnikov.eclipse.plugin.assistai.main/js/" + file) ).openStream() )
            {
                js.append( new String(in.readAllBytes(), StandardCharsets.UTF_8) );
                js.append("\n");
            }
            catch (IOException e)
            {
                throw new RuntimeException(e);
            }
        }
        return js.toString();
    }

    public void setMessageHtml( String messageId, String messageBody)
    {
        uiSync.asyncExec(() -> {
            PromptParser parser = new PromptParser( messageBody );
            
            String fixedHtml = escapeHtmlQuotes(fixLineBreaks(parser.parseToHtml()));
            // inject and highlight html message
            browser.execute( "document.getElementById(\"message-" + messageId + "\").innerHTML = '" + fixedHtml + "';hljs.highlightAll();");
            // Scroll down
            browser.execute("window.scrollTo(0, document.body.scrollHeight);");
        });
    }
    /**
     * Replaces newline characters with line break escape sequences in the given string.
     *
     * @param html The input string containing newline characters.
     * @return A string with newline characters replaced by line break escape sequences.
     */
    private String fixLineBreaks(String html)
    {
        return html.replace("\n", "\\n").replace("\r", "");
    }
    /**
     * Escapes HTML quotation marks in the given string.
     * 
     * @param html The input string containing HTML.
     * @return A string with escaped quotation marks for proper HTML handling.
     */
    private String escapeHtmlQuotes(String html)
    {
        return html.replace("\"", "\\\"").replace("'", "\\'");
    }

    public void appendMessage(String messageId, String role)
    {
        // 
        String cssClass = "user".equals( role ) ? "chat-bubble me" : "chat-bubble you";
        uiSync.asyncExec(() -> {
            browser.execute("""
                    node = document.createElement("div");
                    node.setAttribute("id", "message-${id}");
                    node.setAttribute("class", "${cssClass}");
                    document.getElementById("content").appendChild(node);
                    	""".replace("${id}", messageId )
                    	   .replace( "${cssClass}", cssClass )
                    	);
            browser.execute(
                    // Scroll down
                    "window.scrollTo(0, document.body.scrollHeight);");
        });
    }

    public Object removeMessage( int id )
    {
        // TODO Auto-generated method stub
        return null;
    }

    public void setInputEnabled( boolean b )
    {
        uiSync.asyncExec(() -> {
            inputArea.setEnabled( b );
        });
    }
    
    /**
     * This function establishes a JavaScript-to-Java callback for the browser, allowing the IDE to copy code.
     * It is invoked from JavaScript when the user interacts with the chat view to copy a code block.
     */    
    private class CopyCodeFunction extends BrowserFunction
    {
        public CopyCodeFunction(Browser browser, String name)
        {
            super(browser, name);
        }

        @Override
        public Object function(Object[] arguments)
        {
            if (arguments.length > 0 && arguments[0] instanceof String)
            {
                String codeBlock = (String) arguments[0];
                presenter.onCopyCode(codeBlock);
            }
            return null;
        }
    }

    /**
     * This function establishes a JavaScript-to-Java callback for the browser, allowing the IDE to copy code.
     * It is invoked from JavaScript when the user interacts with the chat view to copy a code block.
     */    
    private class ApplyPatchFunction extends BrowserFunction
    {
        public ApplyPatchFunction(Browser browser, String name)
        {
            super(browser, name);
        }

        @Override
        public Object function(Object[] arguments)
        {
            if (arguments.length > 0 && arguments[0] instanceof String)
            {
                String codeBlock = (String) arguments[0];
                presenter.onApplyPatch(codeBlock);
            }
            return null;
        }
    }

}