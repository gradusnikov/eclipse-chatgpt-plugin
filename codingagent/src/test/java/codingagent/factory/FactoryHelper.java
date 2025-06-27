package codingagent.factory;

import java.util.UUID;

import codingagent.models.ModelApiDescriptor;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.openai.OpenAiChatModelName;

public class FactoryHelper {
	public static final String DEFAULT_LOCALHOST_URL = "http://localhost:11434";

	// Gemma:2b is really fast to load but it's not best model
	public static final String DEFAULT_OLLAMA_MODEL = "qwen3:8b";

	public static ModelApiDescriptor buildApi() {
		/*		
		ModelApiDescriptor api = new ModelApiDescriptor(UUID.randomUUID().toString(), ModelFactories.OLLAMA.name(),
				DEFAULT_LOCALHOST_URL, null, DEFAULT_OLLAMA_MODEL, 0, false, true);		

		*/
		
		/*
		ModelApiDescriptor api = new ModelApiDescriptor(UUID.randomUUID().toString(), ModelFactories.MISTRAL.name(),
				"default_url", System.getenv("MISTRAL_AI_API_KEY"), DEFAULT_OLLAMA_MODEL, 0, false, true);
				*/
		
		
		ModelApiDescriptor api = new ModelApiDescriptor(UUID.randomUUID().toString(), ModelFactories.OPENAI.name(),
				"default_url", System.getenv("OPENAI_KEY"), "gpt-4.1-nano", 0, false, true);

		
		return api;
	}
	
	public static ChatLanguageModel buildChatLanguageModel() {
		ModelApiDescriptor api = FactoryHelper.buildApi();		
		ChatLanguageModel model = ModelFactories.valueOf(api.apiType()).getFactory().buildChat(api);
		return model;
	}

	
}
