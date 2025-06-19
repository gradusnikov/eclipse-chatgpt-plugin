package codingagent.factory;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import dev.langchain4j.model.chat.ChatLanguageModel;

class OllamaFactoryTest {
	@Test
	void testBuildChat() {
		ChatLanguageModel model = FactoryHelper.buildChatLanguageModel();
		
		String answer = model.generate("List top 10 cities in France");		
	    assertTrue(answer.contains("Paris"), "Should contains Paris");

		
	}

	

}
