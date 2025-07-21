package codingagent.models;

import java.util.function.Supplier;

public enum Prompts
{
    SYSTEM("system", "system-prompt.md", "System prompt tells the LLM how to behave", null),
    AUTOCOMPLETE("autocomplete", "autocomplete-prompt.md", "Autocomplete prompt", AutoCompletingQuery::new),
    DISCUSS("discuss", "discuss-prompt.md", "Discuss the content of the currently opened file", null),
    DOCUMENT("doc","document-prompt.md", "Document the selected type or method", null),
    FIX_ERRORS("fix", "fix-errors-prompt.md", "Fix errors in the currently opened file", null),
    GIT_COMMENT("git_comment","gitcomment-prompt.md", "Generate a git comment", null),
    REFACTOR("refactor", "refactor-prompt.md", "Refactor current selection or currently opened file", null),
    TEST_CASE("junit", "testcase-prompt.md", "Create a JUnit test case for current file or selection", null);
	
    private final String fileName;
    private final String commandName;
    private final String description;    
    private final Supplier<ChatModelQuery> queryBuilder;
    
    private Prompts( String commandName, String fileName, String description, Supplier<ChatModelQuery> queryBuilder )
    {
        this.commandName = commandName;
        this.fileName = fileName;
        this.description = description;
        this.queryBuilder = queryBuilder;
    }
    
    public String getCommandName()
    {
        return commandName;
    }
    
    public String preferenceName()
    {
        return "preference.prompt." + name();
    }

    public String getFileName()
    {
        return fileName;
    }

    public String getDescription()
    {
        return description;
    }
    
    public ChatModelQuery buildQuery() {
    	if(queryBuilder == null) {
    		return null;    		
    	}
    	return queryBuilder.get();    	
    }

}
