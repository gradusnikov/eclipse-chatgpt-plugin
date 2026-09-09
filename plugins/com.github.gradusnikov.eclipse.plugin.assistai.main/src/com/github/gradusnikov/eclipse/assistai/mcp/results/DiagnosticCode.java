package com.github.gradusnikov.eclipse.assistai.mcp.results;

/**
 * Why a tool could not do what was asked, in a form a caller can branch on.
 * <p>
 * The point of the enum is that a client never has to read prose to decide what to do
 * next. "Project not found" and "the test JVM never reported" both used to arrive as a
 * sentence beginning with {@code "Error:"}, so the only way to tell a typo in a project
 * name from a launch that hung was to match on wording - which changes.
 * <p>
 * Messages stay readable and stay beside the code; they are for a person, the code is
 * for the caller's logic.
 * <p>
 * One enum covers reading, editing, history and test runs rather than one per area: a
 * caller handling {@code VERSION_CONFLICT} handles it the same way whichever tool
 * raised it, and two parallel enums with overlapping members is what this replaced.
 */
public enum DiagnosticCode
{
    // --- resources ---

    /** No resource of that name, or no project of that name in the workspace. */
    RESOURCE_NOT_FOUND,

    /** The resource exists but cannot be read - a closed project, an .aiignore rule. */
    RESOURCE_NOT_ACCESSIBLE,

    /** The destination of a create, rename or move is already occupied. */
    RESOURCE_ALREADY_EXISTS,

    /** Attached source, a decompiled class, a history state - readable, never writable. */
    READ_ONLY_RESOURCE,

    /** The requested line or offset range does not exist in the resource. */
    INVALID_RANGE,

    /** The resource changed after the caller read it; re-read and recompute. */
    VERSION_CONFLICT,

    /** The requested history state has been pruned by the workspace. */
    RESOURCE_VERSION_EXPIRED,

    /** The workspace copy and the file on disk have diverged. */
    RESOURCE_OUT_OF_SYNC,

    /** Local history could not be read, or holds nothing for this resource. */
    HISTORY_UNAVAILABLE,

    // --- edits ---

    /** The text to replace does not occur in the resource. */
    TEXT_NOT_FOUND,

    /** More than one match, and the caller did not say which to take. */
    AMBIGUOUS_MATCH,

    /** Two edits in one batch cover the same characters. */
    OVERLAPPING_EDITS,

    /** The edit would leave Java source the model cannot parse. */
    INVALID_JAVA_EDIT,

    /** JDT refused the refactoring - a name collision, a binary reference. */
    REFACTORING_PRECONDITION_FAILED,

    /** The edit applied, but the editor could not be brought to it. */
    EDITOR_REVEAL_FAILED,

    /** The content was written, but the formatter would not run over it. */
    FORMATTER_FAILED,

    /**
     * A patch did not apply. Distinct from {@link #INVALID_JAVA_EDIT}, which is about
     * the result not parsing as Java; this is about the hunks not matching.
     */
    PATCH_APPLY_FAILED,

    // --- version control ---

    /** Applying or popping a stash, or a merge, left conflict markers in the tree. */
    MERGE_CONFLICT,

    /**
     * Local changes block a checkout. Distinct from {@link #RESOURCE_OUT_OF_SYNC},
     * which is the workspace disagreeing with the file on disk.
     */
    CHECKOUT_CONFLICT,

    /**
     * A branch was not deleted because it is not merged. The one failure here whose
     * remedy is mechanical - retry with force - which is why it is worth its own code.
     */
    BRANCH_NOT_MERGED,

    /** A branch, tag, commit or other revision expression that resolves to nothing. */
    REVISION_NOT_FOUND,

    /**
     * The repository is in a state the operation does not allow: mid-merge, mid-rebase,
     * or - for a continue, skip or abort - not mid-rebase at all.
     */
    WRONG_REPOSITORY_STATE,

    /**
     * Uncommitted local changes stopped an operation before it started. The remedy is
     * mechanical - commit or stash them - which is why it is worth its own code.
     */
    UNCOMMITTED_CHANGES,

    /**
     * The remote refused a push, most often because it has commits the local branch
     * does not. Pull first, or force.
     */
    PUSH_REJECTED,

    /** The remote could not be reached, or refused the credentials. */
    REMOTE_OPERATION_FAILED,

    // --- projects and launches ---

    /** No open project of that name in the workspace. */
    PROJECT_NOT_FOUND,

    /** The project exists but resolves no type of that name. */
    TEST_CLASS_NOT_FOUND,

    /** The project exists but has no source package of that name. */
    TEST_PACKAGE_NOT_FOUND,

    /**
     * The PDE JUnit launch configuration type is not installed, so plug-in tests
     * cannot be launched at all - a different problem from a test that fails.
     */
    PDE_LAUNCH_TYPE_MISSING,

    /** A saved launch configuration was named but no configuration has that name. */
    LAUNCH_CONFIGURATION_NOT_FOUND,

    /** Another operation holds the workspace; retrying later can succeed. */
    WORKSPACE_LOCKED,

    /**
     * We stopped waiting. Distinct from {@link #WORKSPACE_LOCKED}: nothing is known to
     * hold the resource, the operation simply had not finished in the time allowed, and
     * it may well still be running.
     */
    OPERATION_TIMED_OUT,

    /** The launch could not be assembled because required bundles did not resolve. */
    DEPENDENCY_RESOLUTION_FAILED,

    /**
     * The run ended - or was abandoned - without the JUnit session reporting results.
     * A crashed test JVM and a hung one both land here.
     */
    TEST_RESULTS_NOT_REPORTED,

    /** Coverage was requested but no coverage tooling (EclEmma/JaCoCo) is installed. */
    COVERAGE_UNAVAILABLE,

    // --- everything else ---

    // --- validation ---

    /** The caller supplied arguments that cannot be combined (e.g. methodName with multiple classNames). */
    VALIDATION_ERROR,

    // --- everything else ---

    /** Anything unclassified. The message carries what is known. */
    INTERNAL_ERROR
}
