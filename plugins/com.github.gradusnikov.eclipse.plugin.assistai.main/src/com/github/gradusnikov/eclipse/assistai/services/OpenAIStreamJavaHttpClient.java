package com.github.gradusnikov.eclipse.assistai.services;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.Flow;
import java.util.concurrent.SubmissionPublisher;
import java.util.function.Supplier;

import javax.inject.Inject;

import org.eclipse.core.runtime.ILog;
import org.eclipse.e4.core.di.annotations.Creatable;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.gradusnikov.eclipse.assistai.commands.FunctionExecutorProvider;
import com.github.gradusnikov.eclipse.assistai.model.ChatMessage;
import com.github.gradusnikov.eclipse.assistai.model.Conversation;
import com.github.gradusnikov.eclipse.assistai.prompt.PromptLoader;

/**
 * A Java HTTP client for streaming requests to OpenAI API.
 * This class allows subscribing to responses received from the OpenAI API and processes the chat completions.
 */
@Creatable
public class OpenAIStreamJavaHttpClient
{
    private SubmissionPublisher<String> publisher;
    
    private Supplier<Boolean> isCancelled = () -> false;
    
    
    @Inject
    private ILog logger;
    
    @Inject
    private PromptLoader promptLoader;
    
    @Inject
    private OpenAIClientConfiguration configuration;
    
    @Inject
    private FunctionExecutorProvider functionExecutor;
    
    public OpenAIStreamJavaHttpClient()
    {
        publisher = new SubmissionPublisher<>();

    }
    
    public void setCancelProvider( Supplier<Boolean> isCancelled )
    {
        this.isCancelled = isCancelled;
    }
    
    /**
     * Subscribes a given Flow.Subscriber to receive String data from OpenAI API responses.
     * @param subscriber the Flow.Subscriber to be subscribed to the publisher
     */
    public synchronized void subscribe(Flow.Subscriber<String> subscriber)
    {
        publisher.subscribe(subscriber);
    }
    /**
     * Returns the JSON request body as a String for the given prompt.
     * @param prompt the user input to be included in the request body
     * @return the JSON request body as a String
     */
    private String getRequestBody(Conversation prompt)
    {
        try
        {
            var objectMapper = new ObjectMapper();
            var requestBody = new LinkedHashMap<String, Object>();
            var messages = new ArrayList<Map<String, Object>>();
    
            var systemMessage = new LinkedHashMap<String, Object> ();
            systemMessage.put("role", "system");
            systemMessage.put("content", promptLoader.createPromptText("system-prompt.txt") );
            messages.add(systemMessage);
    
            for ( ChatMessage message : prompt.messages() )
            {
                var userMessage = new LinkedHashMap<String,Object>();
                userMessage.put("role", message.getRole());
                if ( Objects.nonNull( message.getContent() ) )
                {
                    userMessage.put("content", message.getContent() );
                }
                if ( Objects.nonNull( message.getFunctionCall() ) )
                {
                    var functionCall = new LinkedHashMap<String, Object> ();
                    functionCall.put( "name", message.getFunctionCall().name() );
                    functionCall.put( "arguments", objectMapper.writeValueAsString(  message.getFunctionCall().arguments() ) );
                    
                    userMessage.put( "function_call", functionCall );
                }
                if ( Objects.nonNull( message.getName() ) )
                {
                    userMessage.put( "name", message.getName() );
                }
                messages.add(userMessage);
            }
            requestBody.put("model", configuration.getModelName() );
            requestBody.put("functions", AnnotationToJsonConverter.convertDeclaredFunctionsToJson( functionExecutor.get().getFunctions() ) );
            requestBody.put("messages", messages);
            requestBody.put("temperature", 0.7);
            requestBody.put("stream", true);
    
            String jsonString;
            jsonString = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(requestBody);
            return jsonString;
        }
        catch (JsonProcessingException e)
        {
            throw new RuntimeException( e );
        }
    }
    
    /**
     * Creates and returns a Runnable that will execute the HTTP request to OpenAI API
     * with the given conversation prompt and process the responses.
     * <p>
     * Note: this method does not block and the returned Runnable should be executed
     * to perform the actual HTTP request and processing.
     *
     * @param prompt the conversation to be sent to the OpenAI API
     * @return a Runnable that performs the HTTP request and processes the responses
     */
    public Runnable run( Conversation prompt ) 
    {
    	return () ->  {
    		HttpClient client = HttpClient.newHttpClient();
    		String requestBody = getRequestBody(prompt);
            HttpRequest request = HttpRequest.newBuilder().uri(URI.create(configuration.getApiUrl()))
    				.header("Authorization", "Bearer " + configuration.getApiKey())
    				.header("Accept", "text/event-stream")
    				.header("Content-Type", "application/json")
    				.POST(HttpRequest.BodyPublishers.ofString(requestBody))
    				.build();
    		
    		logger.info("Sending request to ChatGPT.\n\n" + requestBody);
    		
    		try
    		{
    			HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
    			
    			if (response.statusCode() != 200)
    			{
    			    logger.error("Request failed with status code: " + response.statusCode() + " and response body: " + response.body());
    			}
    			try (var inputStream = response.body();
    			     var inputStreamReader = new InputStreamReader(inputStream, StandardCharsets.UTF_8);
    			     var reader = new BufferedReader(inputStreamReader)) 
    			{
    				String line;
    				while ((line = reader.readLine()) != null && !isCancelled.get() )
    				{
    					if (line.startsWith("data:"))
    					{
    					    var data = line.substring(5).trim();
    						if ("[DONE]".equals(data))
    						{
    							break;
    						} 
    						else
    						{
    						    var mapper = new ObjectMapper();
    						    var choice = mapper.readTree(data).get("choices").get(0);
    						    var node =  choice.get("delta");
    							if (node.has("content") )
    							{
    							    var content = node.get("content").asText();
    							    if ( !"null".equals( content ) )
    							    {
    							        publisher.submit(content);
    							    }
    							}
    							if ( node.has( "function_call" ) )
    							{
    							    var functionNode = node.get( "function_call" );
    							    if ( functionNode.has( "name" ) )
    							    {
    							        publisher.submit( String.format( "\"function_call\" : { \n \"name\": \"%s\",\n \"arguments\" :", functionNode.get("name").asText() ) );
    							    }
    							    if ( functionNode.has( "arguments" ) )
    							    {
    							        publisher.submit(node.get("function_call").get("arguments").asText());
    							    }
    							}
    						}
    					}
    				}
    			}
    			if ( isCancelled.get() )
    			{
    				publisher.closeExceptionally( new CancellationException() );
    			}
    		}
    		catch (Exception e)
    		{
    			publisher.closeExceptionally(e);
    			throw new RuntimeException( e );
    		} 
    		finally
    		{
    			publisher.close();
    		}
    	};
    }
}