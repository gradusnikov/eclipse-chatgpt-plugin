package codingagent.factory;

import codingagent.models.ModelApiDescriptor;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.ollama.OllamaChatModel;
import dev.langchain4j.model.ollama.OllamaChatModel.OllamaChatModelBuilder;

/**
 * @see https://docs.langchain4j.dev/integrations/language-models/ollama#what-is-ollama
 */
public class OllamaFactory implements ModelFactoryAdapter {

	@Override
	public ChatLanguageModel buildChat(ModelApiDescriptor apiDescriptor) {
		OllamaChatModelBuilder builder = OllamaChatModel.builder()			
				.baseUrl(apiDescriptor.apiUrl())				
				.modelName(apiDescriptor.modelName())
				.temperature(1.0);
		
		
		
		return builder.build();
	}	
}
