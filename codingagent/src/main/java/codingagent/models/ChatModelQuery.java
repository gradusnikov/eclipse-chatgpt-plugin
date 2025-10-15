package codingagent.models;

import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import dev.langchain4j.model.chat.ChatLanguageModel;

public interface ChatModelQuery {

	public static final String SOURCE_FILE_CONTENT = "sourceFileContent";
	public static final String EDITOR_CURSOR_OFFSET = "editorCursorOffset";

	/** 
	 * @return the current prompt
	 */
	String getPrompt();	
	
	/**
	 * To set specialized prompt
	 * @param prompt
	 */
	void setPrompt(String prompt);
	
	
	String getFullResponse();
		
	/**
	 * Complete the current prompt with values
	 * @param valueByField 
	 */
	default void completeThePrompt(Map<String, String> valueByField) {
		var pattern = Pattern.compile("\\$\\{(\\S+)\\}");
        var matcher = pattern.matcher(getPrompt());
        var out = new StringBuilder();
        while (matcher.find()) 
        {
            var key = matcher.group(1);
            String replacement = Optional.ofNullable( valueByField.get(key))
                                         .map(Matcher::quoteReplacement)
                                         .orElse( "" );
            matcher.appendReplacement(out, replacement);
        }
        matcher.appendTail(out);
        setPrompt(out.toString());        
	}
	
	String execute(ChatLanguageModel model);
	
	/**
	 * @return list of fields used in current prompt
	 */
	default public Set<String> getFields() {
		var pattern = Pattern.compile("\\$\\{(\\S+)\\}");
		var matcher = pattern.matcher(getPrompt());		
		Set<String> fields = new HashSet<String>();	
		fields.add(EDITOR_CURSOR_OFFSET);		
		
		while (matcher.find()) {
			fields.add(matcher.group(1));
			;
		}
		return fields;
	}
}