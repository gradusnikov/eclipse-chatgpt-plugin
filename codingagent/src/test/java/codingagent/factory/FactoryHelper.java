package codingagent.factory;

import java.util.UUID;

import codingagent.models.ModelApiDescriptor;
import dev.langchain4j.model.chat.ChatLanguageModel;

public class FactoryHelper {
	public static final String DEFAULT_LOCALHOST_URL = "http://localhost:11434";

	// Gemma:2b is really fast to load but it's not best model
	public static final String DEFAULT_OLLAMA_MODEL = "llama3.2:3b";

	public static ModelApiDescriptor buildApi() {
		ModelApiDescriptor api = new ModelApiDescriptor(UUID.randomUUID().toString(), ModelFactories.OLLAMA.name(),
				DEFAULT_LOCALHOST_URL, null, DEFAULT_OLLAMA_MODEL, 0, false, true);
		return api;
	}
	
	public static ChatLanguageModel buildChatLanguageModel() {
		ModelApiDescriptor api = FactoryHelper.buildApi();		
		ChatLanguageModel model = ModelFactories.valueOf(api.apiType()).getFactory().buildChat(api);
		return model;
	}

	
}
