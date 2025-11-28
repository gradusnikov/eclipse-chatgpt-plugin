package com.github.gradusnikov.eclipse.assistai.mcp.servers;

import org.openqa.selenium.WebDriver;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeOptions;
import jakarta.inject.Inject;
import org.eclipse.core.runtime.ILog;
import org.eclipse.e4.core.di.annotations.Creatable;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import com.github.gradusnikov.eclipse.assistai.mcp.annotations.McpServer;
import com.github.gradusnikov.eclipse.assistai.mcp.annotations.Tool;
import com.github.gradusnikov.eclipse.assistai.mcp.annotations.ToolParam;
import com.vladsch.flexmark.html2md.converter.FlexmarkHtmlConverter;

@Creatable
@McpServer(name = "webpage-reader")
public class ReadWebPageMcpServer
{
    @Inject
    private ILog logger;

    @Tool(name="readWebPage", description="Reads the content of the given web site and returns its content as a markdown text.", type="object")
    public String readWebPage(
            @ToolParam(name="url", description="A web site URL", required=true) String url)
    {
        String content = "";
        WebDriver driver = null;
        try
        {
            ChromeOptions options = new ChromeOptions();
            options.addArguments( "--headless" ); // Run Chrome in headless mode
            options.addArguments( "--disable-gpu" );
            options.addArguments( "--window-size=1920,1200" );
            options.addArguments( "--ignore-certificate-errors" );
            options.addArguments( "--silent" );

            driver = new ChromeDriver( options );
            logger.info( "Fetching web page: " + url );

            driver.get( url );
            // You may need to wait for the page to load or for JavaScript to
            // execute

            String pageSource = driver.getPageSource();

            Document document = Jsoup.parse( pageSource );

            for ( Element body : document.getElementsByTag( "body" ) )
            {
                String bodyHTML = body.toString();
                var converter = FlexmarkHtmlConverter.builder().build();
                content += converter.convert( bodyHTML );
            }
            logger.info( "Web page content " + url + "\n\n" + content );
        }
        catch ( Exception e )
        {
            throw new RuntimeException( e );
        }
        finally
        {
            if ( driver != null )
            {
                driver.quit(); // Close the browser
            }
        }

        return content;
    }
}
