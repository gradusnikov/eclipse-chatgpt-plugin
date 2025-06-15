package com.github.gradusnikov.eclipse.assistai.prompt;

public enum Prompts
{
    SYSTEM("system", "system-prompt.md", "System prompt tells the LLM how to behave"),
    DISCUSS("discuss", "discuss-prompt.md", "Discuss the content of the currently opened file"),
    DOCUMENT("doc","document-prompt.md", "Document the selected type or method"),
    FIX_ERRORS("fix", "fix-errors-prompt.md", "Fix errors in the currently opened file"),
    GIT_COMMENT("git_comment","gitcomment-prompt.md", "Generate a git comment"),
    REFACTOR("refactor", "refactor-prompt.md", "Refactor current selection or currently opened file"),
    TEST_CASE("junit", "testcase-prompt.md", "Create a JUnit test case for current file or selection");

    private final String fileName;
    private final String commandName;
    private final String description;
    
    private Prompts( String commandName, String fileName, String description )
    {
        this.commandName = commandName;
        this.fileName = fileName;
        this.description = description;
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
    
    

}
