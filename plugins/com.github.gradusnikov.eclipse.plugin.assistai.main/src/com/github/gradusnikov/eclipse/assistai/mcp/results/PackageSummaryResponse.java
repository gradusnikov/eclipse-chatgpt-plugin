package com.github.gradusnikov.eclipse.assistai.mcp.results;

import java.util.List;

public record PackageSummaryResponse(
    String packageName,
    String projectName,
    int totalTypes,
    List<TypeSummary> types,
    String summaryText
)
{
    /**
     * @param javadoc the type's documentation as Markdown at the requested detail - by
     *            default its first sentence - or null when none was requested or the type
     *            has no comment of its own; a member's comment is not the type's
     */
    public record TypeSummary(
        String simpleName,
        String typeKind,
        String javadoc,
        int methodCount,
        int fieldCount,
        List<String> superInterfaces
    )
    {
    }

    public static PackageSummaryResponse of( String packageName, String projectName, List<TypeSummary> types )
    {
        String summary = types.isEmpty()
                ? "Package '" + packageName + "' is empty or not found."
                : types.size() + ( types.size() == 1 ? " type" : " types" ) + " in package '" + packageName + "'.";
        return new PackageSummaryResponse( packageName, projectName, types.size(), types, summary );
    }
}
