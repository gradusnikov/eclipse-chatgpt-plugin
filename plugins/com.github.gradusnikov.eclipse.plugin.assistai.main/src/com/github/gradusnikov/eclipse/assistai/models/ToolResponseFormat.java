package com.github.gradusnikov.eclipse.assistai.models;

/**
 * Selects which representation of an MCP tool result is sent back to the model:
 * Plain sends text only, JSON sends structured content only, and Both sends both.
 */
public enum ToolResponseFormat
{
    PLAIN( "Plain" ),
    JSON( "JSON" ),
    BOTH( "Both" );

    private final String displayName;

    /**
     * Creates a response format with the label displayed in the model preferences.
     *
     * @param displayName The label shown in the user interface
     */
    ToolResponseFormat( String displayName )
    {
        this.displayName = displayName;
    }

    /**
     * Returns the label displayed in the model preferences.
     *
     * @return The user-facing label
     */
    public String displayName()
    {
        return displayName;
    }

    /**
     * Resolves a response format from its user-facing label.
     *
     * @param displayName The label to resolve
     *
     * @return The matching response format, or {@link #PLAIN} if the label is unknown
     */
    public static ToolResponseFormat fromDisplayName( String displayName )
    {
        for ( ToolResponseFormat format : values() )
        {
            if ( format.displayName.equals( displayName ) )
            {
                return format;
            }
        }
        return PLAIN;
    }
}
