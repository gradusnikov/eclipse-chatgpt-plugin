package com.github.gradusnikov.eclipse.assistai.mcp.results;

import java.util.List;

/**
 * The rendered documentation of a Java type and of the members it declares.
 * <p>
 * The type's own comment and each member's are separate fields. The tool used to return
 * one Markdown body with every member's declaration pasted after its comment - and for
 * workspace source the comment was pasted raw, comment markers and all - so a caller had
 * to split the text apart to learn what one method does. Each member now carries the
 * same declaration label {@code getClassOutline} prints, so the two tools agree on how a
 * member reads.
 * <p>
 * The miss stays a status rather than a sentence in the body. {@code "JavaDoc is not
 * available for X"} used to occupy the answer slot, so a type with no documentation, a
 * misspelled type name and a type whose real documentation happens to contain that
 * sentence were three indistinguishable results, and each needs a different next move:
 * read the source, fix the name, or use what came back.
 *
 * @param projectName the project the type resolved in, or null when none did. Several
 *            projects can resolve the same name; this is the one that answered
 * @param javadoc the type's own documentation as Markdown, rendered the way the IDE's
 *            hover renders it. Null when the type has no comment
 * @param members the fields, methods and member types the type declares - or only those
 *            named by the caller - each with its declaration and its documentation
 */
public record JavaDocResponse(
    Status status,
    String typeName,
    String projectName,
    String javadoc,
    List<MemberJavadoc> members,
    List<Diagnostic> diagnostics
)
{
    public enum Status
    {
        /** The type was found and it, or something in it, is documented. */
        OK,
        /**
         * The type was found and nothing in it carries a Javadoc comment, its own or
         * inherited. An ordinary state, not a fault: the answer is to read the source.
         */
        NO_JAVADOC,
        /** The type was found but declares no member of the requested name - see diagnostics. */
        MEMBER_NOT_FOUND,
        /** No open Java project resolves the name - see diagnostics. */
        TYPE_NOT_FOUND
    }

    /**
     * One member's documentation.
     *
     * @param name the element name, unqualified; overloads share it
     * @param label the declaration as {@code getClassOutline} prints it, which is what
     *            tells overloads apart
     * @param javadoc the documentation as Markdown, or null when the member has none, its
     *            own or inherited
     * @param javadocInherited the member has no comment of its own and {@code javadoc} is
     *            its supertype's. Only methods inherit
     */
    public record MemberJavadoc(
        String name,
        String label,
        String javadoc,
        boolean javadocInherited
    )
    {
    }

    public static JavaDocResponse of( Status status, String typeName, String projectName, String javadoc,
            List<MemberJavadoc> members )
    {
        return new JavaDocResponse( status, typeName, projectName, javadoc, members, Diagnostic.none() );
    }

    public static JavaDocResponse notFound( String typeName, Diagnostic diagnostic )
    {
        return new JavaDocResponse( Status.TYPE_NOT_FOUND, typeName, null, null, List.of(), List.of( diagnostic ) );
    }

    public static JavaDocResponse memberNotFound( String typeName, String projectName, Diagnostic diagnostic )
    {
        return new JavaDocResponse( Status.MEMBER_NOT_FOUND, typeName, projectName, null, List.of(),
                List.of( diagnostic ) );
    }
}
