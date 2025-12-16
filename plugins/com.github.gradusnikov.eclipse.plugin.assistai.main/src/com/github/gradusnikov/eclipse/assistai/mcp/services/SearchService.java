package com.github.gradusnikov.eclipse.assistai.mcp.services;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
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
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.e4.core.di.annotations.Creatable;
import org.eclipse.search.core.text.TextSearchEngine;
import org.eclipse.search.core.text.TextSearchMatchAccess;
import org.eclipse.search.core.text.TextSearchRequestor;
import org.eclipse.search.core.text.TextSearchScope;

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

    public record SearchResult(IFile file, int lineNumber, String lineContent)
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
                return file != null && file.isAccessible();
            }

            @Override
            public boolean acceptPatternMatch(TextSearchMatchAccess matchAccess) throws CoreException
            {
                IFile file = matchAccess.getFile();

                // Convert offset -> line number/content by reading the file.
                int matchOffset = matchAccess.getMatchOffset();

                LineInfo lineInfo = getLineInfo(file, matchOffset);
                results.add(new SearchResult(file, lineInfo.lineNumber, lineInfo.lineContent));
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

    private record LineInfo(int lineNumber, String lineContent)
    {

    }

    private static LineInfo getLineInfo(IFile file, int offset)
    {
        if (file == null)
        {
            return new LineInfo(-1, "");
        }

        try
        {
            List<String> lines = ResourceUtilities.readFileLines(file);

            // Compute line number from offset by walking through lines and accounting for '\n'.
            int charCount = 0;
            for (int i = 0; i < lines.size(); i++)
            {
                String line = lines.get(i);
                int nextCharCount = charCount + line.length() + 1;
                if (offset < nextCharCount)
                {
                    return new LineInfo(i + 1, line);
                }
                charCount = nextCharCount;
            }

            return new LineInfo(-1, "");
        }
        catch (CoreException | IOException e)
        {
            return new LineInfo(-1, "");
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
