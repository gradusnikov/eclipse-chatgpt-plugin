package com.github.gradusnikov.eclipse.assistai.mcp.results;

import java.util.List;

/**
 * The members of a Java type and where each one starts and ends.
 * <p>
 * An outline is never the answer on its own: the caller reads it to decide which single
 * member to fetch next. That is why every entry carries both line numbers rather than
 * only the one the old fixed-width listing printed - {@code    95: private int count} -
 * which left a caller wanting one method to guess its extent from the start of the next
 * one, and gave fields no range at all.
 * <p>
 * {@code projectName} and {@code filePath} are the pair {@code readProjectResource} and
 * the editing tools take, so a member can be read with
 * {@code readProjectResource(projectName, filePath, startLine, endLine)} without a
 * second lookup.
 * <p>
 * Each entry also carries its Javadoc, rendered the way the IDE's hover renders it - by
 * default the first sentence, which is what turns a list of signatures into an overview
 * of what the members do. The caller chooses the level of detail; see {@code Member}.
 */
public record ClassOutlineResponse(
    String typeName,
    Status status,
    String projectName,
    String filePath,
    Member declaration,
    List<Member> fields,
    List<Member> methods,
    List<Member> innerTypes,
    String summaryText
)
{
    public enum Status
    {
        /** The outline was produced. */
        OK,
        /** No open Java project knows this type. */
        TYPE_NOT_FOUND,
        /** The type is a class file with no attached source; use getSource, which decompiles. */
        NO_SOURCE,
        /** The file is excluded from AI processing by .aiignore. */
        ACCESS_DENIED
    }

    /**
     * A declaration and the lines it occupies.
     *
     * @param name the element name, unqualified
     * @param label the declaration as it reads in source - annotations, modifiers,
     *            types and, for a method, its parameters - with no body
     * @param startLine 1-based, inclusive, counted by the platform's line tracker so a
     *            CRLF file reports the same lines as an LF one
     * @param endLine 1-based, inclusive; equal to {@code startLine} for a one-line member
     * @param javadoc the member's documentation as Markdown at the requested detail - the
     *            first sentence, or the whole comment - or null when none was requested
     *            or the member has none, its own or inherited
     * @param javadocInherited the member has no comment of its own and {@code javadoc} is
     *            its supertype's, which is what a caller deciding whether to document the
     *            member needs to know. Only methods inherit
     */
    public record Member(
        String name,
        String label,
        int startLine,
        int endLine,
        String javadoc,
        boolean javadocInherited
    )
    {
        /** How many lines reading this member costs, which is what a caller budgets against. */
        public int lineCount()
        {
            return endLine - startLine + 1;
        }
    }

    public static ClassOutlineResponse failed( String typeName, Status status, String summary )
    {
        return new ClassOutlineResponse( typeName, status, null, null, null,
                List.of(), List.of(), List.of(), summary );
    }

    public static ClassOutlineResponse of( String typeName, String projectName, String filePath,
            Member declaration, List<Member> fields, List<Member> methods, List<Member> innerTypes )
    {
        String summary = typeName + ": " + fields.size() + " fields, " + methods.size() + " methods, "
                + innerTypes.size() + " inner types, lines " + declaration.startLine() + "-"
                + declaration.endLine() + ".";

        return new ClassOutlineResponse( typeName, Status.OK, projectName, filePath, declaration,
                fields, methods, innerTypes, summary );
    }
}
