package codingagent.models;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import codingagent.factory.FactoryHelper;
import codingagent.utils.SourceUtils;
import dev.langchain4j.model.chat.ChatLanguageModel;

class AutoCompletingModelTest {

	@Test
	void test() {
		String prompt = """
				You are an Java Assistant which can complete one row for the user in his IDE.

				Below current Java file. Please provide suggestions to replace the mark  [suggest here]
				```java
				{currentFileContentWithSuggestHere}
				```
				Replace [suggest here] with suggestion with method suggestReplacing

				""";
		
		String source = SourceUtils.inject("""
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
				""", new Cursor(8, 7), AutoCompletingQuery.SUGGEST_HERE_MARK);

		AutoCompletingQuery autoCompleting = new AutoCompletingQuery();
		 
		autoCompleting.setQuery(prompt.replace("{currentFileContentWithSuggestHere}", source));

		System.out.println(autoCompleting.getQuery());
		
		assertEquals(				
				"""	
				You are an Java Assistant which can complete one row for the user in his IDE.

				Below current Java file. Please provide suggestions to replace the mark  [suggest here]
				```java
				package codingagent.factory;
				
				public class Main {
					public static void main(String[] args) {
						int a = 10;
						int b = 20;
						int sum = a + b;
						// Printing the sum
						Syste[suggest here]
					}
				}
				
				```
				Replace [suggest here] with suggestion with method suggestReplacing
							
				""",
				autoCompleting.getQuery());				
		
				

		ChatLanguageModel model = FactoryHelper.buildChatLanguageModel();
		

		String expect = "System.out.println";
		int countSuccess = 0;
		int countTry = 5;
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
