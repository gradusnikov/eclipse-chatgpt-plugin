package com.github.gradusnikov.eclipse.assistai.mcp.http;

import java.io.IOException;

import org.apache.catalina.connector.Request;
import org.apache.catalina.connector.Response;
import org.apache.catalina.valves.ValveBase;

import jakarta.servlet.ServletException;

public class JsonUtf8EncodingValve extends ValveBase
{
    public JsonUtf8EncodingValve()
    {
        setAsyncSupported( true );
    }

    @Override
    public void invoke( Request request, Response response ) throws IOException, ServletException
    {
        String contentType = request.getContentType();
        if ( contentType != null && contentType.contains( "application/json" ) && request.getCharacterEncoding() == null )
        {
            request.setCharacterEncoding( "UTF-8" );
        }
        getNext().invoke( request, response );
    }
}
