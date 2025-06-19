package codingagent.models;

import java.util.ArrayList;
import java.util.List;
import java.util.Scanner;

import com.google.gson.Gson;

import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.model.output.Response;

public class AutoCompletingModel {
	private ProjectPrompt prompt;
	public static final String SYSTEM_ROLE = "You are an Java Assistant which can complete one row for the user in his IDE.\n";
	private String systemPrompt = SYSTEM_ROLE + "{projectPrompt}\n\n";  
			
						
	public static final String SAMPLE_ACTION_PROMT_INTRODUCE = "Below current Java file. Please provide suggestions to replace the mark  {suggest here}\n";
	public static final String SAMPLE_ACTION_PROMT_INSTRUCTION = "Replace \"{suggest here} with suggestion with method suggestRow\n";
	
	private String actionPrompt =
			SAMPLE_ACTION_PROMT_INTRODUCE +
			"""			
			```java
			{currentSource}
			```			 						
			"""
			+ SAMPLE_ACTION_PROMT_INSTRUCTION;

	private String currentSource;
	private String currentRow;

	public ProjectPrompt getPrompt() {
		return prompt;
	}

	public void setPrompt(ProjectPrompt prompt) {
		this.prompt = prompt;
	}

	public String getActionPrompt() {
		return actionPrompt;
	}

	public void setActionPrompt(String actionPrompt) {
		this.actionPrompt = actionPrompt;
	}

	public String getCurrentRow() {
		return currentRow;
	}

	public String getCurrentSource() {
		return currentSource;
	}

	public void setAutoComplete(String source, int rowNumber) {
		try (Scanner in = new Scanner(source)) {
			StringBuilder buffer = new StringBuilder();
			int currentIndex = 0;
			while (in.hasNext()) {
				String row = in.nextLine();
				if (currentIndex == rowNumber) {
					this.currentRow = row;
					buffer.append(row).append("{suggest here}\n");
				} else {
					buffer.append(row).append("\n");
				}
				currentIndex++;
			}
			this.currentSource = buffer.toString();
		}
	}

	
	public List<ChatMessage> buildMessages() {
		List<ChatMessage> messages = new ArrayList<>();
		messages.add(new SystemMessage(buildSystemText()));
		messages.add(new UserMessage(buildUserText()));
		return messages;
	}
	
	public List<ToolSpecification> buildCallback() {		
		JsonObjectSchema param = JsonObjectSchema.builder().addStringProperty("row").description("It will replace the mark {suggest here}").build();		
		ToolSpecification spec = ToolSpecification.builder().parameters(param).name("suggestRow").description("Replace row with the mark {suggest here}").build();		
		return List.of(spec);
	}
	

	String buildSystemText() {
		return systemPrompt.replace("{projectPrompt}", this.prompt == null ? "" : this.prompt.getPrompt());
	}

	String buildUserText() {
		return actionPrompt.replace("{currentSource}", currentSource).replace("{currentRow}", currentRow);
	}
	
	
	public String suggest(ChatLanguageModel model) {
		Response<AiMessage> responses = model.generate(buildMessages(), buildCallback());		
		if(responses.content().toolExecutionRequests() != null && responses.content().toolExecutionRequests().size() >=  1) {			
			SuggestRowParam param  = new Gson().fromJson(responses.content().toolExecutionRequests().get(0).arguments(), SuggestRowParam.class);
			return param.row;
		}				
		return null;
	}
	
}
