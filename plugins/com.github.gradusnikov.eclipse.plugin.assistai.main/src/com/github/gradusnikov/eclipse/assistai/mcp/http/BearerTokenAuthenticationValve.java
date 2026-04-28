package com.github.gradusnikov.eclipse.assistai.mcp.http;

import java.io.IOException;

import org.apache.catalina.connector.Request;
import org.apache.catalina.connector.Response;
import org.apache.catalina.valves.ValveBase;

import jakarta.servlet.ServletException;

public class BearerTokenAuthenticationValve extends ValveBase
{
    private final String expectedToken;

    public BearerTokenAuthenticationValve( String expectedToken )
    {
        this.expectedToken = expectedToken;
        setAsyncSupported( true );
    }

    @Override
    public void invoke( Request request, Response response ) throws IOException, ServletException
    {
        String authHeader = request.getHeader( "Authorization" );

        if ( authHeader == null || !authHeader.startsWith( "Bearer " ) )
        {
            response.setStatus( 401 );
            response.setContentType( "application/json" );
            response.getWriter().write( "{\"error\": \"Missing or invalid Authorization header\"}" );
            return;
        }

        String token = authHeader.substring( 7 );

        if ( !expectedToken.equals( token ) )
        {
            response.setStatus( 401 );
            response.setContentType( "application/json" );
            response.getWriter().write( "{\"error\": \"Invalid token\"}" );
            return;
        }

        getNext().invoke( request, response );
    }
}
