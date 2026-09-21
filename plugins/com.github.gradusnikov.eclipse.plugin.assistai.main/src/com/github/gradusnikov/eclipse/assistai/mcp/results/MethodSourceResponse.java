package com.github.gradusnikov.eclipse.assistai.mcp.results;

import java.util.List;

import com.github.gradusnikov.eclipse.assistai.resources.ContentRange;
import com.github.gradusnikov.eclipse.assistai.resources.ResourceVersion;
import com.github.gradusnikov.eclipse.assistai.resources.SourceOrigin;

/**
 * The source of one or more named methods of a single Java type.
 * <p>
 * Not a {@link com.github.gradusnikov.eclipse.assistai.resources.ResourceReadResult}:
 * that describes one contiguous read of one resource, and this is several disjoint
 * regions of one file. Concatenating them into a single {@code content} would lose
 * which lines belong to which method - which is exactly what the previous rendering
 * papered over with a {@code // Class.method (lines N-M)} banner in front of each
 * block and a {@code %5d\t} prefix on every line.
 * <p>
 * Each method's {@link MethodSource#source()} is therefore exact text and its
 * {@link MethodSource#range()} carries the line numbers, so a caller can feed
 * {@code startLine}/{@code endLine} straight back to the editing tools instead of
 * parsing them out of a comment.
 * <p>
 * A method that was asked for and does not exist is listed in {@link #notFound()}. It
 * used to be a trailing {@code // Not found: a, b} comment appended to the source,
 * which meant the only way to discover it was to read the code the tool returned.
 *
 * @param sourceOrigin where the text came from. Only {@code WORKSPACE_SOURCE} can be
 *            edited; a library type's attached or decompiled text has no
 *            {@code projectName}, {@code filePath} or known version
 * @param version the version the source was taken at; its {@code modificationStamp} is
 *            what an edit built from this read passes as
 *            {@code expectedModificationStamp}
 * @param notFound the requested method names no method of the type matched, after the
 *            {@code methodSignature} filter has been applied
 */
public record MethodSourceResponse(
    Status status,
    String className,
    String projectName,
    String filePath,
    SourceOrigin sourceOrigin,
    ResourceVersion version,
    List<MethodSource> methods,
    List<String> notFound,
    List<Diagnostic> diagnostics
)
{
    public enum Status
    {
        /** Every requested method was found and returned. */
        OK,
        /** Some requested methods were returned; the rest are in notFound. */
        PARTIAL,
        /** The lookup itself could not be done - see diagnostics. */
        FAILED
    }

    /**
     * One method's source and the lines it occupies.
     *
     * @param parameters the parameter list as it reads in source, without the
     *            enclosing parentheses - the same rendering the caller's
     *            {@code methodSignature} filter is matched against, so an overload can
     *            be told apart from its siblings
     * @param range 1-based and inclusive, covering the Javadoc as well when the caller
     *            asked for it
     * @param source exact text, with no line-number prefixes: the line to attribute
     *            its first line to is {@code range.startLine()}
     */
    public record MethodSource(
        String methodName,
        String parameters,
        ContentRange range,
        String source
    )
    {
        /** How many lines this method costs, which is what a caller budgets against. */
        public int lineCount()
        {
            return range.endLine() - range.startLine() + 1;
        }
    }

    /** Nothing could be read; the reason is a code rather than a sentence. */
    public static MethodSourceResponse failed( String className, Diagnostic diagnostic )
    {
        return new MethodSourceResponse( Status.FAILED, className, null, null, null,
                ResourceVersion.UNKNOWN, List.of(), List.of(), List.of( diagnostic ) );
    }

    /**
     * A successful lookup of workspace source. The status follows from
     * {@code notFound}: a caller that asked for three methods and got two has a partial
     * answer, and should not have to compare two list sizes to notice.
     */
    public static MethodSourceResponse of( String className, String projectName, String filePath,
                                           ResourceVersion version, List<MethodSource> methods,
                                           List<String> notFound )
    {
        return of( className, projectName, filePath, SourceOrigin.WORKSPACE_SOURCE, version, methods, notFound );
    }

    public static MethodSourceResponse of( String className, String projectName, String filePath,
                                           SourceOrigin sourceOrigin, ResourceVersion version,
                                           List<MethodSource> methods, List<String> notFound )
    {
        return new MethodSourceResponse(
                notFound.isEmpty() ? Status.OK : Status.PARTIAL,
                className, projectName, filePath, sourceOrigin, version,
                List.copyOf( methods ), List.copyOf( notFound ), Diagnostic.none() );
    }
}
