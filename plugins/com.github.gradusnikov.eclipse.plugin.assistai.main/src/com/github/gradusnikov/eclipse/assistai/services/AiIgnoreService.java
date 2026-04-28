package com.github.gradusnikov.eclipse.assistai.services;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IResourceChangeEvent;
import org.eclipse.core.resources.IResourceChangeListener;
import org.eclipse.core.resources.IResourceDelta;
import org.eclipse.core.resources.IResourceDeltaVisitor;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.ILog;
import org.eclipse.e4.core.di.annotations.Creatable;
import org.eclipse.jface.preference.IPreferenceStore;
import org.eclipse.jgit.ignore.FastIgnoreRule;

import com.github.gradusnikov.eclipse.assistai.Activator;
import com.github.gradusnikov.eclipse.assistai.preferences.PreferenceConstants;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

/**
 * Service that enforces AI file access restrictions based on .aiignore files.
 * Uses .gitignore-style pattern matching (via JGit) to determine which files
 * should be excluded from AI processing.
 */
@Creatable
@Singleton
public class AiIgnoreService implements IResourceChangeListener
{
    private static final String DEFAULT_IGNORE_FILENAME = ".aiignore";
    private static final String NOAI_FILENAME = ".noai";

    @Inject
    ILog logger;

    private final Map<IProject, ProjectIgnoreRules> rulesCache = new ConcurrentHashMap<>();

    @PostConstruct
    public void init()
    {
        ResourcesPlugin.getWorkspace().addResourceChangeListener(this, IResourceChangeEvent.POST_CHANGE);
    }

    @PreDestroy
    public void dispose()
    {
        ResourcesPlugin.getWorkspace().removeResourceChangeListener(this);
        rulesCache.clear();
    }

    /**
     * Checks whether the given resource is excluded from AI processing.
     *
     * @param resource the workspace resource to check
     * @return true if the resource is excluded and must not be accessed by AI
     */
    public boolean isExcluded(IResource resource)
    {
        if (resource == null)
        {
            return false;
        }

        IProject project = resource.getProject();
        if (project == null || !project.isOpen())
        {
            return false;
        }

        ProjectIgnoreRules rules = getOrLoadRules(project);

        if (rules.isAllAiDisabled())
        {
            return true;
        }

        if (rules.isEmpty())
        {
            return false;
        }

        String relativePath = resource.getProjectRelativePath().toString();
        if (relativePath.isEmpty())
        {
            return false;
        }

        boolean isDirectory = resource.getType() != IResource.FILE;
        return rules.isIgnored(relativePath, isDirectory);
    }

    /**
     * Checks whether a file path in a given project is excluded from AI processing.
     *
     * @param project the project
     * @param relativePath the path relative to the project root
     * @return true if the path is excluded
     */
    public boolean isExcluded(IProject project, String relativePath)
    {
        if (project == null || relativePath == null || relativePath.isEmpty())
        {
            return false;
        }

        ProjectIgnoreRules rules = getOrLoadRules(project);

        if (rules.isAllAiDisabled())
        {
            return true;
        }

        if (rules.isEmpty())
        {
            return false;
        }

        return rules.isIgnored(relativePath, false);
    }

    /**
     * Asserts that the given resource is accessible by AI.
     * Throws AiAccessDeniedException if the resource is excluded.
     */
    public void assertAccessAllowed(IResource resource)
    {
        if (isExcluded(resource))
        {
            String path = resource.getFullPath().toString();
            throw new AiAccessDeniedException(
                    "Access denied: '" + path + "' is excluded from AI processing by " + getIgnoreFileName() + ".");
        }
    }

    /**
     * Asserts that the given path in a project is accessible by AI.
     */
    public void assertAccessAllowed(IProject project, String relativePath)
    {
        if (isExcluded(project, relativePath))
        {
            throw new AiAccessDeniedException(
                    "Access denied: '" + relativePath + "' in project '" + project.getName()
                            + "' is excluded from AI processing by " + getIgnoreFileName() + ".");
        }
    }

    /**
     * Invalidates the cached rules for a specific project.
     */
    public void invalidateCache(IProject project)
    {
        rulesCache.remove(project);
    }

    @Override
    public void resourceChanged(IResourceChangeEvent event)
    {
        IResourceDelta delta = event.getDelta();
        if (delta == null)
        {
            return;
        }

        try
        {
            delta.accept(new IResourceDeltaVisitor()
            {
                @Override
                public boolean visit(IResourceDelta delta) throws CoreException
                {
                    IResource resource = delta.getResource();
                    if (resource.getType() == IResource.FILE)
                    {
                        String name = resource.getName();
                        if (name.equals(getIgnoreFileName()) || name.equals(NOAI_FILENAME))
                        {
                            invalidateCache(resource.getProject());
                        }
                        return false;
                    }
                    return true;
                }
            });
        }
        catch (CoreException e)
        {
            logger.error("Error processing resource change for AI ignore rules", e);
        }
    }

    private ProjectIgnoreRules getOrLoadRules(IProject project)
    {
        return rulesCache.computeIfAbsent(project, this::loadRules);
    }

    private ProjectIgnoreRules loadRules(IProject project)
    {
        // Check for .noai sentinel file
        IFile noaiFile = project.getFile(NOAI_FILENAME);
        if (noaiFile.exists())
        {
            return ProjectIgnoreRules.allDisabled();
        }

        // Load ignore rules from configured file
        String ignoreFileName = getIgnoreFileName();
        IFile ignoreFile = project.getFile(ignoreFileName);

        if (!ignoreFile.exists())
        {
            // Also check for .aiexclude as a fallback
            if (!DEFAULT_IGNORE_FILENAME.equals(ignoreFileName))
            {
                ignoreFile = project.getFile(DEFAULT_IGNORE_FILENAME);
            }
            if (!ignoreFile.exists())
            {
                ignoreFile = project.getFile(".aiexclude");
            }
            if (!ignoreFile.exists())
            {
                return ProjectIgnoreRules.empty();
            }
        }

        List<FastIgnoreRule> rules = new ArrayList<>();
        List<String> globalPatterns = getGlobalExcludePatterns();
        for (String pattern : globalPatterns)
        {
            if (!pattern.isBlank())
            {
                rules.add(new FastIgnoreRule(pattern.trim()));
            }
        }

        try (InputStream is = ignoreFile.getContents();
             BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8)))
        {
            String line;
            while ((line = reader.readLine()) != null)
            {
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("#"))
                {
                    continue;
                }
                rules.add(new FastIgnoreRule(trimmed));
            }
        }
        catch (IOException | CoreException e)
        {
            logger.error("Error reading AI ignore file: " + e.getMessage(), e);
            return ProjectIgnoreRules.empty();
        }

        return new ProjectIgnoreRules(rules, false);
    }

    private String getIgnoreFileName()
    {
        try
        {
            IPreferenceStore store = Activator.getDefault().getPreferenceStore();
            String configured = store.getString(PreferenceConstants.ASSISTAI_IGNORE_FILENAME);
            if (configured != null && !configured.isBlank())
            {
                return configured.trim();
            }
        }
        catch (Exception e)
        {
            // Fallback to default if preferences unavailable
        }
        return DEFAULT_IGNORE_FILENAME;
    }

    private List<String> getGlobalExcludePatterns()
    {
        List<String> patterns = new ArrayList<>();
        try
        {
            IPreferenceStore store = Activator.getDefault().getPreferenceStore();
            String raw = store.getString(PreferenceConstants.ASSISTAI_GLOBAL_EXCLUDE_PATTERNS);
            if (raw != null && !raw.isBlank())
            {
                for (String line : raw.split("\\n"))
                {
                    String trimmed = line.trim();
                    if (!trimmed.isEmpty() && !trimmed.startsWith("#"))
                    {
                        patterns.add(trimmed);
                    }
                }
            }
        }
        catch (Exception e)
        {
            // Ignore
        }
        return patterns;
    }

    /**
     * Holds the parsed ignore rules for a single project.
     */
    static class ProjectIgnoreRules
    {
        private final List<FastIgnoreRule> rules;
        private final boolean allDisabled;

        ProjectIgnoreRules(List<FastIgnoreRule> rules, boolean allDisabled)
        {
            this.rules = rules;
            this.allDisabled = allDisabled;
        }

        static ProjectIgnoreRules empty()
        {
            return new ProjectIgnoreRules(List.of(), false);
        }

        static ProjectIgnoreRules allDisabled()
        {
            return new ProjectIgnoreRules(List.of(), true);
        }

        boolean isAllAiDisabled()
        {
            return allDisabled;
        }

        boolean isEmpty()
        {
            return rules.isEmpty();
        }

        boolean isIgnored(String path, boolean isDirectory)
        {
            Boolean ignored = null;
            for (FastIgnoreRule rule : rules)
            {
                if (rule.isMatch(path, isDirectory))
                {
                    ignored = !rule.getNegation();
                }
            }
            return Boolean.TRUE.equals(ignored);
        }
    }
}
