package com.github.gradusnikov.eclipse.assistai.mcp.results;

import java.util.List;

public record TypeSearchResponse(
    String pattern,
    int totalMatches,
    List<TypeMatch> types,
    boolean truncated,
    String summaryText
)
{
    /**
     * @param javadoc the type's documentation as Markdown at the requested detail, or null
     *            when none was requested or the type has no comment
     */
    public record TypeMatch(
        String fullyQualifiedName,
        String simpleName,
        String packageName,
        String projectName,
        String typeKind,
        String javadoc
    )
    {
    }

    public static TypeSearchResponse of( String pattern, List<TypeMatch> types, int limit )
    {
        boolean truncated = limit > 0 && types.size() > limit;
        List<TypeMatch> shown = truncated ? types.subList( 0, limit ) : types;
        return new TypeSearchResponse( pattern, types.size(), shown, truncated, summarize( types.size(), truncated, shown.size() ) );
    }

    private static String summarize( int total, boolean truncated, int shown )
    {
        if ( total == 0 )
        {
            return "No types found.";
        }
        String summary = total + ( total == 1 ? " type found." : " types found." );
        return truncated ? summary + " Showing the first " + shown + "." : summary;
    }
}
