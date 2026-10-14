package com.github.gradusnikov.eclipse.assistai.mcp.services;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

import org.apache.commons.lang3.exception.ExceptionUtils;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.ILog;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.e4.core.di.annotations.Creatable;
import org.eclipse.search.core.text.TextSearchEngine;
import org.eclipse.search.core.text.TextSearchMatchAccess;
import org.eclipse.search.core.text.TextSearchRequestor;
import org.eclipse.search.core.text.TextSearchScope;

import com.github.gradusnikov.eclipse.assistai.services.AiIgnoreService;
import com.github.gradusnikov.eclipse.assistai.tools.LineOffsets;
import com.github.gradusnikov.eclipse.assistai.tools.LineOffsets.LineInfo;
import com.github.gradusnikov.eclipse.assistai.tools.ResourceUtilities;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;

/**
 * Workspace file search based on Eclipse's {@link TextSearchEngine}.
 */
@Creatable
@Singleton
public class SearchService
{
    private final ILog logger;

    @Inject
    AiIgnoreService aiIgnoreService;

    public record SearchResult(IFile file, int lineNumber, String lineContent)
    {

    }

    public record SearchAndReplaceResult(IFile file, int matchesFound, int replacementsMade)
    {

    }

    @Inject
    public SearchService(ILog logger)
    {
        this.logger = logger;
    }

    /**
     * Plain substring search (not regex).
     */
    public List<SearchResult> fileSearch(String containingText, String... fileNamePatterns)
    {
        if (containingText == null || containingText.isBlank())
        {
            throw new IllegalArgumentException("containingText must not be null/blank");
        }

        return search(Pattern.compile(Pattern.quote(containingText)), fileNamePatterns);
    }

    /**
     * Regex search using Java {@link Pattern} syntax.
     */
    public List<SearchResult> fileSearchRegExp(String pattern, String... fileNamePatterns)
    {
        if (pattern == null || pattern.isBlank())
        {
            throw new IllegalArgumentException("pattern must not be null/blank");
        }

        return search(Pattern.compile(pattern), fileNamePatterns);
    }

    /**
     * Search and replace across the workspace using Eclipse's {@link TextSearchEngine}.
     * <p>
     * This method:
     * <ul>
     * <li>searches only in open projects</li>
     * <li>matches by plain substring (not regex)</li>
     * <li>replaces every occurrence in each matched line</li>
     * </ul>
     *
     * @param containingText Plain text to find
     * @param replacementText Replacement (can be empty but not null)
     * @param fileNamePatterns Optional glob patterns like "*.java" (empty => all files)
     */
    public List<SearchAndReplaceResult> searchAndReplace(String containingText, String replacementText,
            String... fileNamePatterns)
    {
        if (containingText == null || containingText.isBlank())
        {
            throw new IllegalArgumentException("containingText must not be null/blank");
        }
        if (replacementText == null)
        {
            throw new IllegalArgumentException("replacementText must not be null");
        }

        Pattern matchPattern = Pattern.compile(Pattern.quote(containingText));

        IResource[] roots = getOpenProjectsAsRoots();
        if (roots.length == 0)
        {
            return List.of();
        }

        Pattern fileNamePattern = ResourceUtilities.globPatternsToRegex(fileNamePatterns);
        TextSearchScope scope = TextSearchScope.newSearchScope(roots, fileNamePattern, true);
        TextSearchEngine engine = TextSearchEngine.createDefault();

        // Collect all matches first: TextSearchRequestor may be called concurrently.
        Map<IFile, List<LineMatch>> matchesByFile = new LinkedHashMap<>();

        TextSearchRequestor requestor = new TextSearchRequestor()
        {
            @Override
            public boolean acceptFile(IFile file) throws CoreException
            {
                return file != null && file.isAccessible() && !aiIgnoreService.isExcluded(file);
            }

            @Override
            public boolean acceptPatternMatch(TextSearchMatchAccess matchAccess) throws CoreException
            {
                IFile file = matchAccess.getFile();
                int offset = matchAccess.getMatchOffset();
                int length = matchAccess.getMatchLength();

                LineInfo lineInfo = getLineInfo(file, offset);
                synchronized (matchesByFile)
                {
                    matchesByFile.computeIfAbsent(file, f -> new ArrayList<>())
                            .add(new LineMatch(lineInfo.lineNumber(), offset, length));
                }

                return true;
            }
        };

        IProgressMonitor monitor = new NullProgressMonitor();

        try
        {
            engine.search(scope, requestor, matchPattern, monitor);

            if (matchesByFile.isEmpty())
            {
                return List.of();
            }

            List<PendingReplacement> pending = new ArrayList<>();
            for (Map.Entry<IFile, List<LineMatch>> entry : matchesByFile.entrySet())
            {
                IFile file = entry.getKey();

                // ensure deterministic
                int matchesFound = entry.getValue().size();
                pending.add(computeReplacement(file, matchesFound, containingText, replacementText));
            }

            // Every file about to be written is validated in one call, and nothing is
            // written unless all of them may be.
            List<IFile> toWrite = pending.stream()
                    .filter(p -> p.replacements() > 0)
                    .map(PendingReplacement::file)
                    .toList();
            IStatus writable = WriteAccessValidator.ensureWritable(toWrite);
            if (!writable.isOK())
            {
                logger.warn("searchAndReplace wrote nothing: " + writable.getMessage());
            }

            List<SearchAndReplaceResult> results = new ArrayList<>();
            for (PendingReplacement p : pending)
            {
                if (writable.isOK() && p.replacements() > 0)
                {
                    writeFile(p.file(), p.modified());
                }
                results.add(new SearchAndReplaceResult(p.file(), p.matchesFound(),
                        writable.isOK() ? p.replacements() : 0));
            }

            return results;
        }
        catch (Exception e)
        {
            logger.error(e.getMessage(), e);
            throw new RuntimeException("Error searchAndReplace: " + ExceptionUtils.getRootCauseMessage(e), e);
        }
    }

    private static PendingReplacement computeReplacement(IFile file, int matchesFound, String containingText,
            String replacementText) throws CoreException, IOException
    {
        if (file == null || !file.exists())
        {
            return new PendingReplacement(file, matchesFound, 0, null);
        }

        String original = ResourceUtilities.readFileContent(file);

        int replacements = countOccurrences(original, containingText);
        if (replacements == 0)
        {
            return new PendingReplacement(file, matchesFound, 0, null);
        }

        return new PendingReplacement(file, matchesFound, replacements,
                original.replace(containingText, replacementText));
    }

    private static void writeFile(IFile file, String modified) throws CoreException, IOException
    {
        Charset charset = Charset.forName(file.getCharset());
        byte[] bytes = modified.getBytes(charset);
        try (ByteArrayInputStream in = new ByteArrayInputStream(bytes))
        {
            file.setContents(in, IResource.FORCE | IResource.KEEP_HISTORY, null);
        }

        file.refreshLocal(IResource.DEPTH_ZERO, null);
    }

    private static int countOccurrences(String text, String needle)
    {
        if (text == null || text.isEmpty() || needle == null || needle.isEmpty())
        {
            return 0;
        }

        int count = 0;
        int idx = 0;
        while ((idx = text.indexOf(needle, idx)) >= 0)
        {
            count++;
            idx += needle.length();
        }
        return count;
    }

    private record LineMatch(int lineNumber, int offset, int length)
    {

    }

    /** A file's replacement, computed but not yet written. */
    private record PendingReplacement(IFile file, int matchesFound, int replacements, String modified)
    {

    }

    private List<SearchResult> search(Pattern pattern, String... fileNamePatterns)
    {
        Objects.requireNonNull(pattern, "pattern");

        IResource[] roots = getOpenProjectsAsRoots();
        if (roots.length == 0)
        {
            return List.of();
        }

        // Use core TextSearchScope to avoid UI bundle dependency.
        // In this Eclipse version TextSearchScope.newSearchScope expects a regex Pattern.
        Pattern fileNamePattern = ResourceUtilities.globPatternsToRegex(fileNamePatterns);
        TextSearchScope scope = TextSearchScope.newSearchScope(roots, fileNamePattern, true);
        TextSearchEngine engine = TextSearchEngine.createDefault();

        List<SearchResult> results = new ArrayList<>();

        TextSearchRequestor requestor = new TextSearchRequestor()
        {
            @Override
            public boolean acceptFile(IFile file) throws CoreException
            {
                return file != null && file.isAccessible() && !aiIgnoreService.isExcluded(file);
            }

            @Override
            public boolean acceptPatternMatch(TextSearchMatchAccess matchAccess) throws CoreException
            {
                IFile file = matchAccess.getFile();

                // Convert offset -> line number/content by reading the file.
                int matchOffset = matchAccess.getMatchOffset();

                LineInfo lineInfo = getLineInfo(file, matchOffset);
                results.add(new SearchResult(file, lineInfo.lineNumber(), lineInfo.lineContent()));
                return true;
            }
        };

        IProgressMonitor monitor = new NullProgressMonitor();

        try
        {
            engine.search(scope, requestor, pattern, monitor);
            return results;
        }
        catch (Exception e)
        {
            logger.error(e.getMessage(), e);
            throw new RuntimeException("Error searching files: " + ExceptionUtils.getRootCauseMessage(e), e);
        }
    }

    /**
     * Resolves a match offset reported by the search engine to the line holding it.
     * <p>
     * The offset counts every character the engine read, terminators included, so the
     * whole content is handed to the platform's line tracker rather than reconstructed
     * from stripped lines - see {@link LineOffsets} for what goes wrong otherwise.
     */
    private static LineInfo getLineInfo(IFile file, int offset)
    {
        if (file == null)
        {
            return LineOffsets.UNKNOWN;
        }

        try
        {
            return LineOffsets.lineInfoAt(ResourceUtilities.readFileContent(file), offset);
        }
        catch (CoreException | IOException e)
        {
            return LineOffsets.UNKNOWN;
        }
    }

    private static IResource[] getOpenProjectsAsRoots()
    {
        IProject[] projects = ResourcesPlugin.getWorkspace().getRoot().getProjects();
        List<IResource> roots = new ArrayList<>();
        for (IProject project : projects)
        {
            if (project != null && project.exists() && project.isOpen())
            {
                roots.add(project);
            }
        }
        return roots.toArray(IResource[]::new);
    }
}
