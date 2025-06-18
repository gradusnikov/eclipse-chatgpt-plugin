package codingagent.models;

import java.util.ArrayList;
import java.util.List;
import java.util.Scanner;

import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;

public class AutoCompletingModel {
	private ProjectPrompt prompt;
	
	private String systemPrompt = """
			You are an Java IDE which can complete row for the user.
			{projectPrompt}
			""";

	private String actionPrompt = """
			Below is a snippet of Java code. Please provide suggestions to replace {suggest here}
			```java
			{currentSource}
			```

			Call method 'suggestRow' to replace "{suggest here}". 						
			""";

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
		JsonObjectSchema param = JsonObjectSchema.builder().addStringProperty("row").description("Suggested row").build();		
		ToolSpecification spec = ToolSpecification.builder().parameters(param).name("suggestRow").description("Suggest the new row").build();		
		return List.of(spec);
	}
	

	String buildSystemText() {
		return systemPrompt.replace("{projectPrompt}", this.prompt == null ? "" : this.prompt.getPrompt());
	}

	String buildUserText() {
		return actionPrompt.replace("{currentSource}", currentSource).replace("{currentRow}", currentRow);
	}
	
}
