package com.github.gradusnikov.eclipse.assistai.prompt;

public enum Prompts
{
    SYSTEM("system-prompt.md", "System"),
    DISCUSS("discuss-prompt.md", "Discuss"),
    DOCUMENT("document-prompt.md", "Document"),
    FIX_ERRORS("fix-errors-prompt.md", "Fix Errors"),
    GIT_COMMENT("gitcomment-prompt.md", "Git Comment"),
    REFACTOR("refactor-prompt.md", "Refactor"),
    TEST_CASE("testcase-prompt.md", "JUnit Test case");

    private final String fileName;
    private final String description;
    
    private Prompts( String fileName, String description )
    {
        this.fileName = fileName;
        this.description = description;
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
