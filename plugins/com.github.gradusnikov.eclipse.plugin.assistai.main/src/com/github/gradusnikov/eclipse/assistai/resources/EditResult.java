package com.github.gradusnikov.eclipse.assistai.resources;

import java.util.List;

import org.eclipse.core.resources.IFileState;
import org.eclipse.core.resources.IResource;

import com.github.gradusnikov.eclipse.assistai.mcp.results.Diagnostic;
import com.github.gradusnikov.eclipse.assistai.mcp.results.DiagnosticCode;

/**
 * The outcome of an editing operation.
 * <p>
 * There is no invented undo token. {@link #undoHistoryTimestamp()} is the
 * {@link IFileState#getModificationTime()} of the content the edit displaced, which
 * exists because every write carries {@link IResource#KEEP_HISTORY}. It survives a
 * restart and is the same entry the user sees under Compare With &gt; Local History.
 * <p>
 * The resource this result is addressed to is carried as {@code projectName} plus a
 * project-relative {@code filePath}, for the reason {@link ResourceReadResult} writes
 * down: a {@link ResourceDescriptor} holds an {@code IPath}, which has no serializer
 * registered and therefore went over the wire as that path's private fields, and a
 * workspace-absolute path is not the pair the reading and editing tools accept anyway.
 * <p>
 * {@link #affectedResources()} is everything the operation changed, which for a
 * refactoring is routinely more than one file and not necessarily in one project. Each
 * entry says what happened to it, because "which files do I re-read" and "which no
 * longer exist" are different questions, and carries the version the change left
 * behind, because a refactoring silently invalidates every {@code modificationStamp}
 * the caller holds. There is deliberately no per-entry diff or edit list: that would
 * put a second unbounded content payload in every refactoring result.
 * <p>
 * {@link #unifiedDiff()} is the complete diff when it is the caller's only account of
 * the change - a preview, or an edit the IDE computed such as a format or organize
 * imports. When the caller supplied the text itself the diff would only echo it back,
 * so it is replaced by a single {@code \ diff omitted (+added -removed lines)} line;
 * an unchanged file gives an empty string either way.
 */
public record EditResult(
    EditStatus status,
    String projectName,
    String filePath,
    ResourceVersion versionBefore,
    ResourceVersion versionAfter,
    List<AppliedEdit> edits,
    String unifiedDiff,
    List<AffectedResource> affectedResources,
    EditorReveal editorReveal,
    long undoHistoryTimestamp,
    WorkspaceSync workspaceState,
    List<Diagnostic> diagnostics
)
{
    /**
     * What the edit did to the rest of the IDE, once the write had landed.
     * <p>
     * Structured rather than a status line: these are four independent facts, and a
     * caller checking whether the JDT model kept up should not have to find that in
     * a sentence.
     */
    public record WorkspaceSync(
        boolean savedToDisk,
        boolean cacheUpdated,
        String jdtConsistent
    )
    {
    }

    /** No history entry was recorded for this edit. */
    public static final long NO_UNDO_STATE = -1L;

    public enum EditStatus
    {
        APPLIED,
        /** The content changed, but something secondary did not - see diagnostics. */
        APPLIED_WITH_WARNINGS,
        /** Nothing was written. The caller must re-read and try again. */
        REJECTED,
        /** A preview only: the resource was not touched. */
        PREVIEW
    }

    /** What an operation did to one resource. */
    public enum ChangeKind
    {
        /** The content at this address changed; re-read it. */
        MODIFIED,
        /** Nothing was at this address before. */
        CREATED,
        /** Nothing is at this address any more; re-reading it fails. */
        DELETED,
        /** The resource is at this address because it was moved or renamed to it. */
        MOVED
    }

    /**
     * One resource an operation changed.
     * <p>
     * Addressed exactly as {@link EditResult} itself is - project plus project-relative
     * path - because that is the pair every read and edit tool takes, so the caller's
     * next call needs no transformation, and because a workspace-wide refactoring
     * routinely crosses projects, which a bare path cannot express.
     * <p>
     * A move is reported as two entries: the old address as {@link ChangeKind#DELETED},
     * so a caller holding it learns it is stale, and the new one as
     * {@link ChangeKind#MOVED}.
     *
     * @param version the version the change left behind. Null when there is none to
     *            report - a deleted resource has no version, and a resource whose
     *            version could not be captured means "re-read it"
     */
    public record AffectedResource(
        String projectName,
        String filePath,
        ChangeKind kind,
        ResourceVersion version
    )
    {
        /**
         * Describes a resource as it stands now.
         * <p>
         * Call it after the change has been performed: the version it captures is the
         * one the caller will quote back as {@code expectedModificationStamp}.
         */
        public static AffectedResource of( IResource resource, ChangeKind kind )
        {
            return new AffectedResource(
                    projectNameOf( resource ),
                    pathOf( resource ),
                    kind,
                    resource != null && resource.exists() ? ResourceVersion.of( resource ) : null );
        }
    }

    /** One changed region, before and after. */
    public record AppliedEdit(
        ContentRange oldRange,
        ContentRange newRange,
        int insertedCharacters,
        int deletedCharacters
    )
    {
    }

    /**
     * Where the editor ended up. Part of the contract, not a side effect: an agent
     * that moves the user's cursor should say so, and a user watching the IDE should
     * land on what changed.
     */
    public record EditorReveal(
        boolean opened,
        ContentRange revealedRange,
        EditorPosition caret
    )
    {
        /** Nothing was opened or revealed. */
        public static EditorReveal none()
        {
            return new EditorReveal( false, null, null );
        }
    }

    /** A caret position, in one-based line and column. */
    public record EditorPosition( int line, int column )
    {
    }

    /**
     * A rejection, carrying the reason and the version that caused it.
     * <p>
     * Nothing was changed, so {@code affectedResources} is empty rather than naming
     * the resource the call was about: the list says what moved, not what was asked
     * for.
     */
    public static EditResult rejected( String projectName, String filePath, ResourceVersion current,
                                       Diagnostic diagnostic )
    {
        return new EditResult(
                EditStatus.REJECTED,
                projectName,
                filePath,
                current,
                current,
                List.of(),
                "",
                List.of(),
                EditorReveal.none(),
                NO_UNDO_STATE,
                null,
                List.of( diagnostic ) );
    }

    /** A rejection about a resource that was resolved before the refusal. */
    public static EditResult rejected( IResource resource, ResourceVersion current, Diagnostic diagnostic )
    {
        return rejected( projectNameOf( resource ), pathOf( resource ), current, diagnostic );
    }

    /**
     * A rejection because the resource moved on since the caller read it.
     */
    public static EditResult versionConflict( String projectName, String filePath, ResourceVersion current,
                                              long expectedStamp )
    {
        return rejected( projectName, filePath, current, Diagnostic.retryable(
                DiagnosticCode.VERSION_CONFLICT,
                "Expected modificationStamp " + expectedStamp + ", but the resource is now "
                        + current.modificationStamp() + ". Re-read the resource and recompute the edit." ) );
    }

    /** A version conflict on a resource that was resolved before the refusal. */
    public static EditResult versionConflict( IResource resource, ResourceVersion current, long expectedStamp )
    {
        return versionConflict( projectNameOf( resource ), pathOf( resource ), current, expectedStamp );
    }

    /** Whether the resource was actually written. */
    public boolean changedResource()
    {
        return status == EditStatus.APPLIED || status == EditStatus.APPLIED_WITH_WARNINGS;
    }

    /** The project a resource belongs to, which is how every tool names it. */
    private static String projectNameOf( IResource resource )
    {
        return resource == null || resource.getProject() == null ? null : resource.getProject().getName();
    }

    /**
     * A resource's path relative to its project, which is the form the tools take.
     * Works on a handle whose resource no longer exists, which is what a deleted or
     * moved-from address is.
     */
    private static String pathOf( IResource resource )
    {
        return resource == null ? null : resource.getProjectRelativePath().toString();
    }
}
