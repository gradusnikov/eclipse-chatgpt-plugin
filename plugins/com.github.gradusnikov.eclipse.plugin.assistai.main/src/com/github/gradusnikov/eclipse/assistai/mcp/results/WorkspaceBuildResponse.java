package com.github.gradusnikov.eclipse.assistai.mcp.results;

import java.util.List;

/**
 * A completed invocation of Eclipse's workspace builders, not a Maven launch.
 *
 * @param buildCompleted true only when the workspace build returned normally; a later
 *            cancellation or diagnostic collection failure can still prevent SUCCESS
 * @param problems the post-build marker snapshot, or null if it could not be collected;
 *            counts cover all problem markers, even when details are truncated
 * @param phase the last phase reached, identifying where a failure or cancellation occurred
 * @param diagnostics build/refresh exceptions, including nested Eclipse status messages
 */
public record WorkspaceBuildResponse(
    Status status,
    BuildKind buildKind,
    String scope,
    boolean refreshBeforeBuild,
    boolean buildCompleted,
    Phase phase,
    long durationMillis,
    CompilationProblemsResponse problems,
    List<Diagnostic> diagnostics,
    String summaryText
)
{
    public enum Status
    {
        SUCCESS, COMPLETED_WITH_ERRORS, FAILED, CANCELLED
    }

    /** FULL invokes FULL_BUILD, never CLEAN_BUILD. */
    public enum BuildKind
    {
        INCREMENTAL, FULL
    }

    public enum Phase
    {
        VALIDATION, WAITING, REFRESH, BUILD, DIAGNOSTICS, COMPLETED
    }
}
