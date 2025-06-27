package codingagent.factory;

import codingagent.models.ModelApiDescriptor;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.openai.OpenAiChatModel;

public class OpenAiFactory implements ModelFactoryAdapter {

	@Override
	public ChatLanguageModel buildChat(ModelApiDescriptor descriptor) {
		ChatLanguageModel model  = OpenAiChatModel.builder()
                .apiKey(descriptor.apiKey())
                .modelName(descriptor.modelName())
                .temperature(1.0)
                .build();
		
		return model;
	}

}
