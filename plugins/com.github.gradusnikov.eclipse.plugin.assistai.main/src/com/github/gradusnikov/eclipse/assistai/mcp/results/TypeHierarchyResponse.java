package com.github.gradusnikov.eclipse.assistai.mcp.results;

import java.util.List;

/**
 * Where a type sits in the inheritance graph.
 * <p>
 * The three relations are three separate lists, not one indented tree: the tool used to
 * return Markdown whose indentation carried the meaning, so a caller wanting "does this
 * implement Runnable" had to count leading spaces under a {@code ## Implemented
 * Interfaces} heading and hope the heading had not been reworded.
 * <p>
 * A type whose source is in the workspace also reports the {@code projectName} and
 * project-relative {@code filePath} the reading and editing tools take, so the answer to
 * "which subtype do I open next" needs no second lookup. A type from a JAR or the JRE
 * reports neither, which is how a caller tells the two apart.
 */
public record TypeHierarchyResponse(
    String typeName,
    Status status,
    List<HierarchyType> superclasses,
    List<HierarchyType> interfaces,
    List<HierarchyType> subtypes,
    String summaryText
)
{
    public enum Status
    {
        /** The hierarchy was computed. */
        OK,
        /** No open Java project knows this type; check the name, or the project's classpath. */
        TYPE_NOT_FOUND
    }

    /**
     * @param fullyQualifiedName member types use {@code $} as the enclosing separator, as JDT reports them
     * @param projectName null when the type is not workspace source
     * @param filePath project-relative, null when the type is not workspace source
     * @param javadoc the type's documentation as Markdown at the requested detail, or null
     *            when none was requested or the type has no comment
     */
    public record HierarchyType(
        String fullyQualifiedName,
        String projectName,
        String filePath,
        String javadoc
    )
    {
        /** Whether this type can be opened and edited, as opposed to living in a JAR. */
        public boolean inWorkspace()
        {
            return projectName != null && filePath != null;
        }
    }

    public static TypeHierarchyResponse notFound( String typeName )
    {
        return new TypeHierarchyResponse( typeName, Status.TYPE_NOT_FOUND, List.of(), List.of(), List.of(),
                "Type '" + typeName + "' was not found in any open Java project." );
    }

    public static TypeHierarchyResponse of( String typeName, List<HierarchyType> superclasses,
            List<HierarchyType> interfaces, List<HierarchyType> subtypes )
    {
        String summary = typeName + ": " + superclasses.size() + " superclasses, "
                + interfaces.size() + " interfaces, " + subtypes.size() + " subtypes.";
        return new TypeHierarchyResponse( typeName, Status.OK, superclasses, interfaces, subtypes, summary );
    }

    /** Whether anything extends or implements this type, which is what a caller asks before changing it. */
    public boolean hasSubtypes()
    {
        return !subtypes.isEmpty();
    }
}
