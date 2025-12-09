package com.github.gradusnikov.eclipse.assistai.view;

import java.net.URI;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.ILog;
import org.eclipse.core.runtime.IPath;
import org.eclipse.e4.core.di.annotations.Creatable;
import org.eclipse.e4.ui.di.UISynchronize;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.PartInitException;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.ide.IDE;

import com.github.gradusnikov.eclipse.assistai.resources.CachedResource;
import com.github.gradusnikov.eclipse.assistai.resources.IResourceCacheListener;
import com.github.gradusnikov.eclipse.assistai.resources.ResourceCache;
import com.github.gradusnikov.eclipse.assistai.resources.ResourceCacheEvent;
import com.github.gradusnikov.eclipse.assistai.resources.ResourceDescriptor;
import com.github.gradusnikov.eclipse.assistai.resources.ResourceDescriptor.ResourceType;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;

/**
 * Presenter for the ResourcesView following the MVP pattern.
 * Observes the ResourceCache and transforms cache data into a tree structure
 * organized by ResourceType for display in the view.
 * 
 * The view is kept passive - all actions and data transformations are handled here.
 */
@Creatable
@Singleton
public class ResourcesPresenter implements IResourceCacheListener
{
    /**
     * Represents a node in the tree structure.
     * Can be either a category (ResourceType) or a resource item.
     */
    public static sealed interface TreeNode permits CategoryNode, ResourceNode {}
    
    /**
     * Category node representing a ResourceType grouping.
     */
    public static record CategoryNode(ResourceType type, String displayName, int resourceCount) implements TreeNode {}
    
    /**
     * Resource node representing an individual cached resource.
     */
    public static record ResourceNode(URI uri, String displayName, String tooltip, int tokens, int version) implements TreeNode {}

    private final ResourceCache resourceCache;
    private final ILog logger;
    private final UISynchronize uiSync;
    
    private ResourcesView view;
    
    @Inject
    public ResourcesPresenter(ResourceCache resourceCache, ILog logger, UISynchronize uiSync)
    {
        Objects.requireNonNull(resourceCache);
        Objects.requireNonNull(logger);
        Objects.requireNonNull(uiSync);
        this.resourceCache = resourceCache;
        this.logger = logger;
        this.uiSync = uiSync;
        
        logger.info("ResourcesPresenter initialized with ResourceCache instance: " + System.identityHashCode(resourceCache));
        resourceCache.addCacheListener(this);
    }

    /**
     * Registers the view with the presenter and performs initial data load.
     */
    public void registerView(ResourcesView view)
    {
        this.view = view;
        refreshView();
    }
    
    /**
     * Unregisters the view from the presenter.
     */
    public void unregisterView()
    {
        this.view = null;
    }

    @Override
    public void cacheChanged(ResourceCacheEvent event)
    {
        logger.info("ResourcesPresenter: Cache changed - " + event.getType() + 
            " (cache instance: " + System.identityHashCode(resourceCache) + 
            ", cache size: " + resourceCache.size() + ")");
        refreshView();
    }
    
    /**
     * Refreshes the view with current cache data.
     * Builds the tree model and updates the view on the UI thread.
     */
    public void refreshView()
    {
        if (view == null)
        {
            return;
        }
        
        Map<ResourceType, List<ResourceNode>> treeModel = buildTreeModel();
        String stats = resourceCache.getStats();
        
        // Debug: log the model contents
        logger.info("ResourcesPresenter.refreshView() - Model contains types: " + 
            treeModel.entrySet().stream()
                .filter(e -> !e.getValue().isEmpty())
                .map(e -> e.getKey() + "(" + e.getValue().size() + ")")
                .collect(java.util.stream.Collectors.joining(", ")));
        
        uiSync.asyncExec(() -> {
            if (view != null)
            {
                view.setTreeModel(treeModel, stats);
            }
        });
    }
    
    /**
     * Builds the tree model from the resource cache.
     * Groups resources by their ResourceType.
     */
    private Map<ResourceType, List<ResourceNode>> buildTreeModel()
    {
        Map<ResourceType, List<ResourceNode>> model = new EnumMap<>(ResourceType.class);
        
        // Initialize all types with empty lists
        for (ResourceType type : ResourceType.values())
        {
            model.put(type, new ArrayList<>());
        }
        
        // Populate with cached resources
        Map<URI, CachedResource> allResources = resourceCache.getAll();
        for (CachedResource cached : allResources.values())
        {
            ResourceType type = cached.descriptor().type();
            ResourceNode node = new ResourceNode(
                cached.descriptor().uri(),
                cached.descriptor().displayName(),
                buildTooltip(cached),
                cached.estimateTokens(),
                cached.version()
            );
            model.get(type).add(node);
        }
        
        return model;
    }
    
    /**
     * Builds a tooltip string for a cached resource.
     */
    private String buildTooltip(CachedResource cached)
    {
        return String.format("URI: %s%nTool: %s%nVersion: %d%nTokens: ~%d%nCached: %s",
            cached.descriptor().uri(),
            cached.descriptor().toolName(),
            cached.version(),
            cached.estimateTokens(),
            cached.cachedAt()
        );
    }
    
    /**
     * Called when a resource is selected in the view.
     * Could be used to show details or enable actions.
     */
    public void onResourceSelected(URI resourceUri)
    {
        if (resourceUri == null)
        {
            return;
        }
        
        resourceCache.get(resourceUri).ifPresent(cached -> {
            logger.info("ResourcesPresenter: Selected resource - " + cached.descriptor().displayName());
        });
    }
    
    /**
     * Called when a resource is double-clicked in the view.
     * Opens the resource in an editor if it's a file-based resource.
     */
    public void onResourceDoubleClicked(URI resourceUri)
    {
        if (resourceUri == null)
        {
            return;
        }
        
        resourceCache.get(resourceUri).ifPresent(cached -> {
            logger.info("ResourcesPresenter: Opening resource - " + cached.descriptor().displayName());
            openResourceInEditor(cached.descriptor());
        });
    }
    
    /**
     * Opens the resource in an Eclipse editor.
     * Supports workspace files and Java types.
     */
    private void openResourceInEditor(ResourceDescriptor descriptor)
    {
        uiSync.asyncExec(() -> {
            try
            {
                IWorkbenchPage page = PlatformUI.getWorkbench()
                                                .getActiveWorkbenchWindow()
                                                .getActivePage();
                
                if (page == null)
                {
                    logger.error("ResourcesPresenter: No active workbench page");
                    return;
                }
                
                // Try to get the file from workspace path first
                Optional<IFile> fileOpt = getFileFromDescriptor(descriptor);
                
                if (fileOpt.isPresent())
                {
                    IFile file = fileOpt.get();
                    if (file.exists())
                    {
                        // Opens the file in the default editor, or activates existing editor
                        IDE.openEditor(page, file);
                        logger.info("ResourcesPresenter: Opened file - " + file.getFullPath());
                    }
                    else
                    {
                        logger.warn("ResourcesPresenter: File does not exist - " + file.getFullPath());
                    }
                }
                else
                {
                    logger.info("ResourcesPresenter: Resource is not a file-based resource - " + descriptor.uri());
                }
            }
            catch (PartInitException e)
            {
                logger.error("ResourcesPresenter: Failed to open editor", e);
            }
        });
    }
    
    /**
     * Gets the IFile from a ResourceDescriptor if available.
     */
    private Optional<IFile> getFileFromDescriptor(ResourceDescriptor descriptor)
    {
        // First try the workspace path
        IPath workspacePath = descriptor.workspacePath();
        if (workspacePath != null)
        {
            IResource resource = ResourcesPlugin.getWorkspace().getRoot().findMember(workspacePath);
            if (resource instanceof IFile file)
            {
                return Optional.of(file);
            }
        }
        
        // For Java types, try to get the underlying file
        if (descriptor.type() == ResourceType.JAVA_TYPE)
        {
            return descriptor.toWorkspaceFile();
        }
        
        // For workspace files, use the built-in method
        if (descriptor.type() == ResourceType.WORKSPACE_FILE)
        {
            return descriptor.toWorkspaceFile();
        }
        
        return Optional.empty();
    }
    
    /**
     * Removes a specific resource from the cache.
     */
    public void onRemoveResource(URI resourceUri)
    {
        if (resourceUri == null)
        {
            return;
        }
        
        logger.info("ResourcesPresenter: Removing resource - " + resourceUri);
        resourceCache.remove(resourceUri);
        // View will be updated via cacheChanged callback
    }
    
    /**
     * Clears all resources from the cache.
     */
    public void onClearAll()
    {
        logger.info("ResourcesPresenter: Clearing all resources");
        resourceCache.clear();
        // View will be updated via cacheChanged callback
    }
    
    /**
     * Gets the content of a specific resource for preview.
     */
    public String getResourceContent(URI resourceUri)
    {
        if (resourceUri == null)
        {
            return "";
        }
        
        return resourceCache.get(resourceUri)
                           .map(CachedResource::content)
                           .orElse("");
    }
    
    /**
     * Gets the children (ResourceNodes) for a given category type.
     */
    public List<ResourceNode> getChildrenForType(ResourceType type)
    {
        Map<ResourceType, List<ResourceNode>> model = buildTreeModel();
        return model.getOrDefault(type, List.of());
    }
    
    /**
     * Checks if a category type has any children.
     */
    public boolean hasChildren(ResourceType type)
    {
        return !getChildrenForType(type).isEmpty();
    }
    
    /**
     * Gets the display name for a ResourceType category.
     */
    public static String getDisplayNameForType(ResourceType type)
    {
        return switch (type) {
            case WORKSPACE_FILE -> "Workspace Files";
            case JAVA_TYPE -> "Java Types";
            case PROJECT_LAYOUT -> "Project Layouts";
            case CONSOLE_OUTPUT -> "Console Output";
            case EXTERNAL_FILE -> "External Files";
            case QUERY_RESULT -> "Query Results";
            case TRANSIENT -> "Transient";
        };
    }
}
