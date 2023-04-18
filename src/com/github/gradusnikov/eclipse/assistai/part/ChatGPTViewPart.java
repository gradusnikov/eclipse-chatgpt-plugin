package com.github.gradusnikov.eclipse.assistai.part;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import javax.annotation.PostConstruct;
import javax.inject.Inject;

import org.eclipse.e4.core.contexts.IEclipseContext;
import org.eclipse.e4.ui.di.Focus;
import org.eclipse.e4.ui.di.UISynchronize;
import org.eclipse.swt.SWT;
import org.eclipse.swt.browser.Browser;
import org.eclipse.swt.browser.BrowserFunction;
import org.eclipse.swt.layout.FillLayout;
import org.eclipse.swt.widgets.Composite;

public class ChatGPTViewPart
{

    private Browser browser;

    @Inject
    private IEclipseContext context;

    @Inject
    private UISynchronize uiSync;

    public ChatGPTViewPart()
    {
    }

    @Focus
    public void setFocus()
    {
        browser.setFocus();
    }

    @PostConstruct
    public void createControls(Composite parent)
    {
        parent.setLayout(new FillLayout(SWT.HORIZONTAL)); // Set layout of parent composite

        browser = new Browser(parent, SWT.EDGE); // Create SWT browser component

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

        // Create the JavaScript-to-Java callback
        new CopyCodeFunction(browser, "eclipseFunc");
    }

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

    private String fixLineBreaks(String html)
    {
        return html.replace("\n", "\\n");
    }

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