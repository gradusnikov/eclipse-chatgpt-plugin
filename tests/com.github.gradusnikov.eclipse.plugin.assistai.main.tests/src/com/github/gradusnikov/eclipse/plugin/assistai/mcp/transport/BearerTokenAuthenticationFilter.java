package com.github.gradusnikov.eclipse.plugin.assistai.mcp.transport;

import java.io.IOException;

import org.eclipse.e4.core.di.annotations.Creatable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.FilterConfig;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Servlet filter that validates Bearer token authentication.
 * Checks for the presence and validity of an Authorization header with Bearer token.
 */
public class BearerTokenAuthenticationFilter implements Filter {

//    private static final Logger logger = LoggerFactory.getLogger(BearerTokenAuthenticationFilter.class);
    private final String expectedToken;

    /**
     * Creates a new authentication filter with the expected Bearer token.
     * 
     * @param expectedToken The token that clients must provide for authentication
     */
    public BearerTokenAuthenticationFilter(String expectedToken) 
    {
        this.expectedToken = expectedToken;
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {

        HttpServletRequest httpRequest = (HttpServletRequest) request;
        HttpServletResponse httpResponse = (HttpServletResponse) response;

        String authHeader = httpRequest.getHeader("Authorization");

        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
//            logger.warn("Missing or invalid Authorization header from {}", httpRequest.getRemoteAddr());
            httpResponse.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            httpResponse.setContentType("application/json");
            httpResponse.getWriter().write("{\"error\": \"Missing or invalid Authorization header\"}");
            return;
        }

        String token = authHeader.substring(7); // Remove "Bearer " prefix

        if (!expectedToken.equals(token)) {
//            logger.warn("Invalid bearer token from {}", httpRequest.getRemoteAddr());
            httpResponse.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            httpResponse.setContentType("application/json");
            httpResponse.getWriter().write("{\"error\": \"Invalid token\"}");
            return;
        }

//        logger.debug("Bearer token validated successfully");
        // Token is valid, continue with the request
        chain.doFilter(request, response);
    }

    @Override
    public void init(FilterConfig filterConfig) throws ServletException {
        // No initialization needed
    }

    @Override
    public void destroy() {
        // No cleanup needed
    }
}
