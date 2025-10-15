package codingagent.models;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.output.Response;

/**
 * Query for suggesting code. 2 use cases: Use suggestSource to build a prompt
 * with source code
 */
public class AutoCompletingQuery implements ChatModelQuery {

	private String prompt;
	private String source;
	private String fullResponse;

	public AutoCompletingQuery() {
		try {
			prompt = new String(AutoCompletingQuery.class
					.getResourceAsStream("/codingagent/prompts/autocomplete-prompt.md").readAllBytes(),
					StandardCharsets.UTF_8);
		} catch (IOException e) {
			e.printStackTrace();
		}
	}

	public String getPrompt() {
		return prompt;
	}

	public void setPrompt(String prompt) {
		this.prompt = prompt;
	}

	private void suggestSource(String source, int editorCursorOffset) {
		this.source = source.substring(0, editorCursorOffset);
		prompt = prompt.replace("${" + SOURCE_FILE_CONTENT + "}", source);
	}

	@Override
	public void completeThePrompt(Map<String, String> valueByField) {
		suggestSource(valueByField.get(SOURCE_FILE_CONTENT), Integer.valueOf(valueByField.get(EDITOR_CURSOR_OFFSET)));
	}

	public List<ChatMessage> buildMessages() {
		List<ChatMessage> messages = new ArrayList<>();

		messages.add(new UserMessage(prompt));
		return messages;
	}

	public String execute(ChatLanguageModel model) {
		Response<AiMessage> responses = model.generate(buildMessages());

		String response = responses.content().text();

		this.fullResponse = response;
		System.out.println("Response:" + response);

		String sourceSeek = this.source;
		while (response.indexOf(sourceSeek) < 0) {
			if (sourceSeek.length() <= 1) {
				return null;
			}
			sourceSeek = sourceSeek.substring(1);
		}

		int i = response.indexOf(sourceSeek);
		i += sourceSeek.length();

		String suggested = response.substring(i);

		int openBrace = 0;
		i = 0;
		while (openBrace >= 0 && i < suggested.length()) {
			if (suggested.charAt(i) == '{') {
				openBrace++;
			} else if (suggested.charAt(i) == '}') {
				openBrace--;
			}
			i++;
		}
		return suggested.substring(0, i - 1);
	}

	public String getFullResponse() {
		return fullResponse;
	}

	public String getSourceFile() {
		final String marker = "```";
		int startJava = this.fullResponse.indexOf(marker) + marker.length();
		startJava = this.fullResponse.indexOf("\n", startJava) + "\n".length();

		int endJava = this.fullResponse.indexOf(marker, startJava);
		try {
			return fullResponse.substring(startJava, endJava);
		} catch (Exception e) {
			e.printStackTrace();
			return "Failed to identify source in response :\n" + fullResponse;
		}
	}

}
