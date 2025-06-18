package codingagent.factory;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;

import org.junit.jupiter.api.Test;

import codingagent.models.ModelApiDescriptor;
import dev.langchain4j.model.chat.ChatLanguageModel;

class OllamaFactoryTest {

	private static final String DEFAULT_LOCALHOST_URL = "http://localhost:11434";
	private static final String DEFAULT_OLLAMA_MODEL = "gemma:2b";

	@Test
	void testBuildChat() {
		ModelApiDescriptor api = new ModelApiDescriptor(
				UUID.randomUUID().toString(), 
				ModelFactories.OLLAMA.name(), 
				DEFAULT_LOCALHOST_URL, 				 
				null,
				DEFAULT_OLLAMA_MODEL,				
				0, 
				false, 
				true);
		
		ChatLanguageModel model = ModelFactories.valueOf(api.apiType()).getFactory().buildChat(api);
		String answer = model.generate("List top 10 cities in France");		
	    assertTrue(answer.contains("Paris"), "Should contains Paris");		
	}

}
