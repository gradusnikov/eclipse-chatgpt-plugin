package com.github.gradusnikov.eclipse.assistai.commands;

import java.net.URL;

import javax.inject.Inject;

import org.eclipse.core.runtime.ILog;
import org.eclipse.e4.core.di.annotations.Creatable;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import com.vladsch.flexmark.html2md.converter.FlexmarkHtmlConverter;

@Creatable
public class ReadWebPageCommand
{
    @Inject
    private ILog logger;
    
    public String readWebPage(String url)
    {
        String content = "";
        try
        {
            URL webPageURL = new URL( url );
            logger.info( "Fetching web page: " + url );
            Document document = Jsoup.parse( webPageURL, 0 );
            
            for ( Element body : document.getElementsByTag( "body" ) )
            {
                String bodyHTML = body.toString();
                var converter = FlexmarkHtmlConverter.builder().build();
                content += converter.convert( bodyHTML );
            }
        }
        catch ( Exception e )
        {
            throw new RuntimeException( e );
        }
        
        return content;
    }
}
