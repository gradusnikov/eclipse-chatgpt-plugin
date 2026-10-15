package com.github.gradusnikov.eclipse.assistai.mcp.results;

import java.util.List;

/**
 * Methods of one class that have no references anywhere in the workspace and are not
 * recognizable entry points - the question asked before deleting code.
 * <p>
 * The scan is scoped to a single class so it stays fast: JDT's index answers "who
 * references this method" per element, and asking it for the handful of methods one
 * class declares is cheap, where asking it for every method in a project is not.
 * <p>
 * Every candidate is a <em>heuristic</em>, never a proof. Reflection, dependency
 * injection, serialization, non-Java callers (JSP, Spring XML, scripting) and public
 * API meant for consumers outside the workspace can all keep a method that this tool
 * reports as unreferenced. The reference count is exact; "therefore dead" is not.
 * <p>
 * Each candidate carries the same location triple {@link ReferencesResponse.Reference}
 * and {@link CallHierarchyResponse.CallNode} do, so a caller can open it without a
 * follow-up search.
 */
public record DeadCodeResponse(
    Status status,
    String className,
    int methodsScanned,
    int totalCandidates,
    boolean truncated,
    List<Candidate> candidates,
    List<Diagnostic> diagnostics,
    String summaryText
)
{
    public enum Status
    {
        OK,
        /** No open Java project resolves the class name. */
        TYPE_NOT_FOUND,
        /** The scan could not run; see diagnostics. */
        FAILED
    }

    /**
     * One unreferenced method.
     *
     * @param signature the parameter list as it reads in source, without parentheses -
     *            the same rendering {@code getClassOutline} and {@code getMethodSource}
     *            use, so an overload named here can be fed back to them
     * @param lineNumber 1-based, or -1 when there is no source to resolve it against
     */
    public record Candidate(
        String methodName,
        String declaringType,
        String signature,
        String projectName,
        String filePath,
        int lineNumber
    )
    {
    }

    /** A scan that ran, however few candidates it found. */
    public static DeadCodeResponse of( String className, int methodsScanned, List<Candidate> candidates,
                                       boolean truncated, int totalCandidates, List<Diagnostic> diagnostics )
    {
        String summary = candidates.isEmpty()
                ? "No unreferenced methods found in " + className + " (" + methodsScanned + " scanned). "
                        + "Note: this is a heuristic - reflection and non-Java callers are not seen."
                : totalCandidates + ( totalCandidates == 1 ? " method in " : " methods in " ) + className
                        + ( totalCandidates == 1 ? " has" : " have" )
                        + " no workspace references and are not recognized entry points"
                        + ( truncated ? " (showing " + candidates.size() + ")" : "" ) + ". "
                        + "These are candidates only: reflection, dependency injection, serialization and "
                        + "non-Java callers can still use a method reported here.";

        return new DeadCodeResponse( Status.OK, className, methodsScanned, totalCandidates, truncated,
                candidates, diagnostics, summary );
    }

    /** Nothing was scanned, and the status says why. */
    public static DeadCodeResponse failed( String className, Status status, Diagnostic diagnostic )
    {
        return new DeadCodeResponse( status, className, 0, 0, false, List.of(),
                List.of( diagnostic ), diagnostic.message() );
    }
}
