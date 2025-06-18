package codingagent.models;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;

import org.junit.jupiter.api.Test;

import codingagent.factory.FactoryHelper;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.output.Response;

class AutoCompletingModelTest {

	@Test
	void test() {

		AutoCompletingModel autoCompleting = new AutoCompletingModel();
		autoCompleting.setAutoComplete("""
				package codingagent.factory;

				public class Main {
					public static void main(String[] args) {
						int a = 10;
						int b = 20;
						int sum = a + b;
						// Printing the sum
						Syste
					}
				}
				""", 8);

		/*assertEquals("You are assisting system with Java code autocompletion.\n" + "\n", autoCompleting.buildSystemText());
		assertEquals(
				"""
				Below is a snippet of Java code that is incomplete. Please provide suggestions to replace {rowToCompleting}
				```java
				package codingagent.factory;
				
				public class Main {
					public static void main(String[] args) {
						int a = 10;
						int b = 20;
						int sum = a + b;
				
				{rowToCompleting}
					}
				}
				
				```
				
				Bellow the row to complete in place of {rowToCompleting} which start with "		Sy".
				Call the callback method "callToComplete"
				""",
				autoCompleting.buildUserText());
				*/
		
		System.out.println(autoCompleting.buildSystemText());
		System.out.println(autoCompleting.buildUserText());

		ChatLanguageModel model = FactoryHelper.buildChatLanguageModel();
		
		

		Response<AiMessage> responses = model.generate(autoCompleting.buildMessages(), autoCompleting.buildCallback());
		 
		assertTrue(responses.content().toolExecutionRequests().size() >=  1);
		
		System.out.println(responses.content().toolExecutionRequests().get(0).arguments());
		
	}

}
