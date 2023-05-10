package com.github.gradusnikov.eclipse.assistai.part;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
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
import org.eclipse.swt.events.SelectionAdapter;
import org.eclipse.swt.events.SelectionEvent;
import org.eclipse.swt.events.TraverseEvent;
import org.eclipse.swt.events.TraverseListener;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.layout.FillLayout;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Text;
import org.eclipse.swt.custom.SashForm;


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
        browser.setFocus();
    }
    
    public void clearChatView()
    {
        uiSync.asyncExec(() -> initializeChatView( browser ) );
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

        // Add controls for the Clear button and the text input area
        Composite controls = new Composite(sashForm, SWT.NONE);
        controls.setLayout(new GridLayout(1, false));

        inputArea = createUserInput( controls );
        inputArea.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));

        Button clearButton = createClearChatButton( parent, controls );
        clearButton.setLayoutData(new GridData(SWT.FILL, SWT.RIGHT, true, false));

        sashForm.setWeights(new int[]{85, 15}); // Sets the initial weight ratio: 75% browser, 25% controls
    }

    private Button createClearChatButton( Composite parent, Composite controls )
    {
        Button clearButton = new Button(controls, SWT.PUSH);
        clearButton.setText("Clear");
        try
        {
            URL imageUrl = new URL("platform:/plugin/AssistAI/icons/Sample.png");
            URL fileUrl = FileLocator.toFileURL(imageUrl);
            clearButton.setImage(new Image( parent.getDisplay(), fileUrl.getPath()));
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

    private Text createUserInput( Composite controls )
    {
        Text inputArea = new Text(controls, SWT.BORDER | SWT.MULTI | SWT.WRAP | SWT.V_SCROLL);
        inputArea.addTraverseListener(new TraverseListener() {
            public void keyTraversed(TraverseEvent e) {
                if (e.detail == SWT.TRAVERSE_RETURN && (e.stateMask & SWT.MODIFIER_MASK) == 0) {
                    // Your action here when Enter key is pressed without any modifier (Shift or Ctrl)
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
        return browser;
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
            try (InputStream in = this.getClass().getResourceAsStream( file ) )
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
        String[] jsFiles = {"showdownjs.min.js", "highlight.min.js", "markdown.js"};
        StringBuilder js = new StringBuilder();
        for ( String file : jsFiles )
        {
            try ( InputStream in = this.getClass().getResourceAsStream( file ) )
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

    public void setMessageHtml(int messageId, String messageBody)
    {
        uiSync.asyncExec(() -> {

            String fixedHtml = escapeHtmlQuotes(messageBody);
            fixedHtml = fixLineBreaks(fixedHtml);

            browser.execute("converter = new showdown.Converter();\n" 
                    + "document.getElementById(\"message-" + messageId + "\").innerHTML = replaceCodeBlocks('" + fixedHtml + "');hljs.highlightAll();");
            browser.execute(
                    // Scroll down
                    "window.scrollTo(0, document.body.scrollHeight);");
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
        return html.replace("\n", "\\n");
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

    // A class for JavaScript-to-Java callback
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
                copyCodeToEditor(codeBlock);
            }
            return null;
        }
    }

    private void copyCodeToEditor(String codeBlock)
    {
        System.out.println("Code Block: " + codeBlock);

        // Add your logic to inject the code block to the document at the given caret
        // position
        // The implementation will vary depending on the editor you're using (e.g.
        // SourceViewer, JFace Text, etc.)
    }

    public void appendMessage(int id)
    {
        uiSync.asyncExec(() -> {
            browser.execute("""
                    node = document.createElement("div");
                    node.setAttribute("id", "message-${id}");
                    node.setAttribute("class", "chat-bubble you");
                    document.getElementById("content").appendChild(node);
                    	""".replace("${id}", Integer.toString(id)));
            browser.execute(
                    // Scroll down
                    "window.scrollTo(0, document.body.scrollHeight);");
        });
    }
}