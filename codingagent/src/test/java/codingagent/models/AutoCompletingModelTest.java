package codingagent.models;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import org.junit.jupiter.api.Test;

import codingagent.factory.FactoryHelper;
import dev.langchain4j.model.chat.ChatLanguageModel;

class AutoCompletingModelTest {

	@Test
	void test() {		
		String sourceToCompelte  = """
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
				""";
		String lexem = "Syste";
		String expect = "m.out.println";
		double expectRatio = 0.6;			
		suggestTest(sourceToCompelte, lexem, expect, expectRatio);
	}
	
	@Test
	void test2() {		
		String sourceToCompelte  = """
				package codingagent.factory;
				
				public class Main {
					public static void main(String[] args) {
						List<String> names = List.of("Jérôme", "Hugo", "Marie", "Raphael");
						// Sort and print names
						Syste
						}
				}				
				""";
		String lexem = "// Sort and print names\n";
		String expect = "println";
		double expectRatio = 0.6;			
		suggestTest(sourceToCompelte, lexem, expect, expectRatio);
	}

	private void suggestTest(String sourceToCompelte, String lexem, String expect, double expectRatio) {
		int cursorOffset = sourceToCompelte.indexOf(lexem) + lexem.length();		
		AutoCompletingQuery query = new AutoCompletingQuery();
		query.completeThePrompt(
				Map.of(ChatModelQuery.SOURCE_FILE_CONTENT, sourceToCompelte,
				ChatModelQuery.EDITOR_CURSOR_OFFSET, "" + cursorOffset));		
		
		ChatLanguageModel model = FactoryHelper.buildChatLanguageModel();
		
		System.out.println("Prompt:" + query.getPrompt());
		
		int countSuccess = 0;
		int countTry = 10;
		for(int x=0;x < countTry;x++) {
			String suggested =  query.execute(model);
			System.out.println("Suggested:" + suggested);
			if(suggested != null && suggested.indexOf(expect) >= 0) {
				countSuccess++;
			}
		}
		
		double ratio = ((double)countSuccess)/countTry;
		System.out.println(ratio);
		assertTrue(ratio >= expectRatio, "Success ratio :" + ratio);
	}

}
