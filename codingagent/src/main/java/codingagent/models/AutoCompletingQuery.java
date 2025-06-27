package codingagent.models;

import java.util.ArrayList;
import java.util.List;
import java.util.Scanner;

import com.google.gson.Gson;

import codingagent.utils.SourceUtils;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.model.output.Response;

public class AutoCompletingQuery {
	public static final String SUGGEST_HERE_MARK = "[suggest here]";
	public static final String SUGGEST_ROW_METHOD = "suggestReplacing";
	
	public String query;
	
	public String getQuery() {
		return query;
	}

	public void setQuery(String query) {
		this.query = query;
	}

	public List<ChatMessage> buildMessages() {
		List<ChatMessage> messages = new ArrayList<>();		
		messages.add(new UserMessage(query));
		return messages;
	}

	public List<ToolSpecification> buildCallback() {
		JsonObjectSchema param = JsonObjectSchema.builder().addStringProperty("row")
				.description("It will replace the mark {suggest here}").build();
		ToolSpecification spec = ToolSpecification.builder().parameters(param).name("suggestRow")
				.description("Replace row with the mark {suggest here}").build();
		return List.of(spec);
	}

	public String suggest(ChatLanguageModel model) {
		Response<AiMessage> responses = model.generate(buildMessages(), buildCallback());
		if (responses.content().toolExecutionRequests() != null
				&& responses.content().toolExecutionRequests().size() >= 1) {
			SuggestRowParam param = new Gson().fromJson(responses.content().toolExecutionRequests().get(0).arguments(),
					SuggestRowParam.class);
			return param.row;
		}
		return null;
	}

}
