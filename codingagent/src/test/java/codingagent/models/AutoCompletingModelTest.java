package codingagent.models;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import codingagent.factory.FactoryHelper;
import dev.langchain4j.model.chat.ChatLanguageModel;

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

		assertEquals(AutoCompletingModel.SYSTEM_ROLE + "\n" + "\n", autoCompleting.buildSystemText());
		assertEquals(
				AutoCompletingModel.SAMPLE_ACTION_PROMT_INTRODUCE +
				"""				
				```java
				package codingagent.factory;
				
				public class Main {
					public static void main(String[] args) {
						int a = 10;
						int b = 20;
						int sum = a + b;
						// Printing the sum
						Syste{suggest here}
					}
				}

				``` 
 		 		""" + AutoCompletingModel.SAMPLE_ACTION_PROMT_INSTRUCTION,
				autoCompleting.buildUserText());				
		
		System.out.println(autoCompleting.buildSystemText());
		System.out.println(autoCompleting.buildUserText());

		ChatLanguageModel model = FactoryHelper.buildChatLanguageModel();
		

		String expect = "System.out.println";
		int countSuccess = 0;
		int countTry = 20;
		for(int x=0;x < countTry;x++) {
			String suggested =  autoCompleting.suggest(model);
			System.out.println("Suggested:" + suggested);
			if(suggested != null && suggested.trim().startsWith(expect)) {
				countSuccess++;
			}
		}
		
		double ratio = ((double)countSuccess)/countTry;
		System.out.println(ratio);
		assertTrue(ratio >= 0.6, "Success ratio :" + ratio);
		
		
	}

}
