package codingagent.factory;

import codingagent.models.ModelApiDescriptor;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.mistralai.MistralAiChatModel;

public class MistralFactory implements ModelFactoryAdapter {

	@Override
	public ChatLanguageModel buildChat(ModelApiDescriptor descriptor) {
		ChatLanguageModel model  = MistralAiChatModel.builder()
                .apiKey(descriptor.apiKey())
                .modelName(descriptor.modelName())
                .build();
		return model;
	}

}
