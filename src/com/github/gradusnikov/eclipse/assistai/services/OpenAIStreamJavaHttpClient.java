package com.github.gradusnikov.eclipse.assistai.services;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Flow;
import java.util.concurrent.SubmissionPublisher;

import javax.inject.Inject;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.gradusnikov.eclipse.assistai.Activator;
import com.github.gradusnikov.eclipse.assistai.preferences.PreferenceConstants;

public class OpenAIStreamJavaHttpClient {
	
	
    private String API_KEY;// = "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";
    private String API_URL = "https://api.openai.com/v1/chat/completions";
//    private static final String MODEL = "gpt-3.5-turbo";
    private String MODEL;// = "gpt-4";
    
    private SubmissionPublisher<String> publisher;
    
    public OpenAIStreamJavaHttpClient()
    {
    	publisher = new SubmissionPublisher<>();
    	
    }
    public void subscribe(Flow.Subscriber<String> subscriber) {
        publisher.subscribe(subscriber);
    }
    	
    private String getRequestBody(String prompt) {
	    
    	API_KEY = Activator.getDefault().getPreferenceStore().getString(PreferenceConstants.OPENAI_API_KEY);
    	MODEL   = Activator.getDefault().getPreferenceStore().getString(PreferenceConstants.OPENAI_MODEL_NAME );
    	
    	Activator.getDefault().getLog().info("Using API Key: " + API_KEY  );
    	
    	Map<String, Object> requestBody = new LinkedHashMap<>();
	    List<Map<String, Object>> messages = new ArrayList<>();

	    Map<String, Object> systemMessage = new LinkedHashMap<>();
	    systemMessage.put("role", "system");
	    systemMessage.put("content", "You are an expert in sofware engineering, java and python. Just write code, do not explain it, unless you put explanations as code comments. Always use Markdown code blocks when providing code examples. Do not use code blocks when writing in-line code.");
	    messages.add(systemMessage);
	    
	    Map<String, Object> userMessage = new LinkedHashMap<>();
	    userMessage.put("role", "user");
	    userMessage.put("content", prompt);
	    messages.add(userMessage);

	    requestBody.put("model", MODEL);
	    requestBody.put("messages", messages);
	    requestBody.put("temperature", 0.7);
	    requestBody.put("stream", true);

	    ObjectMapper objectMapper = new ObjectMapper();
	    try {
	        return objectMapper.writeValueAsString(requestBody);
	    } catch (JsonProcessingException e) {
	        e.printStackTrace();
	    }

	    return null;
	}
    
    
    public void run( String prompt ) throws IOException, InterruptedException {
        
    	HttpClient client = HttpClient.newHttpClient();
        String requestBody = getRequestBody(prompt);
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(API_URL))
                .header("Authorization", "Bearer " + API_KEY)
                .header("Accept", "text/event-stream")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                .build();
        Activator.getDefault().getLog().info("Sending request.");
        try
        {
        	HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
        	
        	if (response.statusCode() != 200) {
                Activator.getDefault().getLog().error("Request failed: " + response);
        		throw new IOException("Request failed: " + response);
        	}
        	
        	BufferedReader reader = new BufferedReader(new InputStreamReader(response.body(), StandardCharsets.UTF_8));
        	
        	String line;
//System.out.print("\n GPT stream: \n");
        	while ((line = reader.readLine()) != null) {
        		if (line.startsWith("data:")) {
        			String data = line.substring(5).trim();
        			if ("[DONE]".equals(data)) {
//System.out.print("\n");
        				break;
        			} else {
        				ObjectMapper mapper = new ObjectMapper();
        				JsonNode node = mapper.readTree( data ).get("choices").get(0).get("delta");
        				if ( node.has("content") )
        				{
        					String content = node.get("content").asText();
//System.out.print( content );        					
        					publisher.submit(content);
        				}
        			} 
        		}
        	}
        }
        catch (Exception e) {
			publisher.closeExceptionally( e );
        	throw e;
		}
        finally {
			publisher.close();
		}
        
    }

}