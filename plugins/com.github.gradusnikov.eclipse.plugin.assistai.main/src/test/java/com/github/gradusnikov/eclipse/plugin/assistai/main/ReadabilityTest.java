package com.github.gradusnikov.eclipse.plugin.assistai.main;




import static org.hamcrest.CoreMatchers.containsStringIgnoringCase;
import static org.junit.jupiter.api.Assertions.*;

import org.hamcrest.CoreMatchers;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.junit.jupiter.api.Test;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeOptions;

import com.vladsch.flexmark.html2md.converter.FlexmarkHtmlConverter;


public class ReadabilityTest
{
    @Test
    public void testHtmlExtractor()
    {

        try {
//            String articleUrl = "https://medium.com/towards-artificial-intelligence/ai-drift-in-retrieval-augmented-generation-and-how-to-control-it-25119cd7ddfd";
            String articleUrl = "https://www.whatismybrowser.com/detect/is-javascript-enabled";

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

                driver.get( articleUrl );
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
                assertTrue( content.contains("JavaScript is enabled in your web browser. Congratulations"));
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

            
            
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
