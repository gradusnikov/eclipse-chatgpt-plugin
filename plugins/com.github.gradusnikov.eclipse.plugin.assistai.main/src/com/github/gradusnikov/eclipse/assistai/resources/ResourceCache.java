package com.github.gradusnikov.eclipse.assistai.resources;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IResourceChangeEvent;
import org.eclipse.core.resources.IResourceChangeListener;
import org.eclipse.core.resources.IResourceDelta;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.ILog;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.ListenerList;
import org.eclipse.e4.core.di.annotations.Creatable;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

/**
 * Cache for resources accessed during conversation.
 * 
 */
@Creatable
@Singleton
public class ResourceCache implements IResourceChangeListener {
    
    /** Maximum number of resources to cache */
    private static final int MAX_RESOURCES = 20;
    
    /** Maximum total tokens across all cached resources */
    private static final int MAX_TOTAL_TOKENS = 100_000;
    
    private final ILog logger;
    
    // LinkedHashMap with access-order for LRU behavior
    private final Map<URI, CachedResource> resources = new LinkedHashMap<>(16, 0.75f, true);
    
    // Track workspace paths for change detection
    private final Map<IPath, URI> workspacePathIndex = new LinkedHashMap<>();
    
    // Listeners for cache change events
    private final ListenerList<IResourceCacheListener> cacheListeners = new ListenerList<>();
    
    private boolean listenerRegistered;
    
    
    @Inject
    public ResourceCache(ILog logger ) 
    {
        Objects.requireNonNull( logger );
        this.logger = logger;
        logger.info("ResourceCache created with instance ID: " + System.identityHashCode(this));
    }
    
    @PostConstruct
    public void init() 
    {
        registerWorkspaceListener();
    }
    
    /**
     * Registers the workspace change listener.
     * Safe to call multiple times.
     */
    public synchronized void registerWorkspaceListener() 
    {
        if (!listenerRegistered) 
        {
            ResourcesPlugin.getWorkspace().addResourceChangeListener(
                this, 
                IResourceChangeEvent.POST_CHANGE
            );
            listenerRegistered = true;
            logger.info("ResourcesPresenter: Workspace listener registered");
        }
    }
    
    @PreDestroy
    public void dispose() 
    {
        unregisterWorkspaceListener();
    }
    
    /**
     * Unregisters the workspace change listener.
     */
    public synchronized void unregisterWorkspaceListener() {
        if (listenerRegistered) {
            ResourcesPlugin.getWorkspace().removeResourceChangeListener(this);
            listenerRegistered = false;
            if (logger != null) {
                logger.info("ResourceCache: Workspace listener unregistered");
            }
        }
    }

    
    /**
     * Adds or updates a resource in the cache.
     * If the resource already exists, it is REPLACED (not appended).
     * 
     * @param descriptor The resource descriptor
     * @param content The resource content
     * @return The cached resource, or null if not cacheable
     */
    public synchronized CachedResource put(ResourceDescriptor descriptor, String content) {
        if (descriptor == null || !descriptor.isCacheable()) {
            return null;
        }
        
        URI uri = descriptor.uri();
        
        // Check if we're updating an existing resource
        CachedResource existing = resources.get(uri);
        int newVersion = existing != null ? existing.version() + 1 : 1;
        
        // Create new cached resource
        CachedResource cached = CachedResource.create(descriptor, content, newVersion);
        
        // Evict if necessary before adding
        evictIfNecessary(cached.estimateTokens());
        
        // Store in cache
        resources.put(uri, cached);
        
        // Index by workspace path for change detection
        if (descriptor.workspacePath() != null) 
        {
            workspacePathIndex.put(descriptor.workspacePath(), uri);
        }
        
        // Fire cache event
        fireCacheEvent(new ResourceCacheEvent(this, ResourceCacheEvent.Type.ADDED, cached));
        
        logger.info("ResourceCache: Cached " + uri + " (v" + newVersion + ", ~" + cached.estimateTokens() + " tokens)");
        
        return cached;
    }
    
    /**
     * Convenience method to cache a ResourceToolResult.
     */
    public synchronized CachedResource put(ResourceToolResult result) {
        if (result == null || !result.isCacheable()) {
            return null;
        }
        return put(result.descriptor(), result.content());
    }
    
    /**
     * Gets a cached resource by URI.
     */
    public synchronized Optional<CachedResource> get(URI uri) {
        return Optional.ofNullable(resources.get(uri));
    }
    
    /**
     * Gets a cached resource by workspace path.
     */
    public synchronized Optional<CachedResource> getByWorkspacePath(IPath path) {
        URI uri = workspacePathIndex.get(path);
        return uri != null ? get(uri) : Optional.empty();
    }
    
    /**
     * Gets a cached resource by IFile.
     */
    public synchronized Optional<CachedResource> get(IFile file) {
        if (file == null) {
            return Optional.empty();
        }
        return getByWorkspacePath(file.getFullPath());
    }
    
    /**
     * Checks if a resource is cached.
     */
    public synchronized boolean contains(URI uri) {
        return resources.containsKey(uri);
    }
    
    /**
     * Removes a resource from cache by URI.
     */
    public synchronized void remove(URI uri) {
        CachedResource removed = resources.remove(uri);
        if (removed != null) {
            if (removed.descriptor().workspacePath() != null) {
                workspacePathIndex.remove(removed.descriptor().workspacePath());
            }
            fireCacheEvent(new ResourceCacheEvent(this, ResourceCacheEvent.Type.REMOVED, removed));
            logger.info("ResourceCache: Removed " + uri);
        }
    }
    
    /**
     * Invalidates (removes) a resource by workspace path.
     * Called when file changes externally.
     */
    public synchronized void invalidate(IPath workspacePath) {
        URI uri = workspacePathIndex.remove(workspacePath);
        if (uri != null) 
        {
            CachedResource removed = resources.remove(uri);
            if (removed != null) {
                fireCacheEvent(new ResourceCacheEvent(this, ResourceCacheEvent.Type.INVALIDATED, removed));
            }
            logger.info("ResourceCache: Invalidated " + workspacePath);
        }
    }
    
    /**
     * Clears all cached resources.
     */
    public synchronized void clear() {
        int count = resources.size();
        resources.clear();
        workspacePathIndex.clear();
        fireCacheEvent(new ResourceCacheEvent(this, ResourceCacheEvent.Type.CLEARED, null));
        logger.info("ResourceCache: Cleared " + count + " resources");
    }
    
    /**
     * Returns the number of cached resources.
     */
    public synchronized int size() {
        return resources.size();
    }
    
    /**
     * Checks if the cache is empty.
     */
    public synchronized boolean isEmpty() {
        return resources.isEmpty();
    }
    
    /**
     * Returns all cached resources (copy).
     */
    public synchronized Map<URI, CachedResource> getAll() {
        logger.info("ResourceCache.getAll() called on instance " + System.identityHashCode(this) + ", returning " + resources.size() + " resources");
        return new LinkedHashMap<>(resources);
    }
    
    /**
     * Estimates total token count across all cached resources.
     */
    public synchronized int estimateTotalTokens() {
        return resources.values().stream()
                       .mapToInt(CachedResource::estimateTokens)
                       .sum();
    }
    
    /**
     * Generates the &lt;resources&gt; block for LLM context injection.
     * This should be injected at the beginning of the system prompt.
     */
    public synchronized String toContextBlock() {
        if (resources.isEmpty()) {
            return "";
        }
        
        StringBuilder sb = new StringBuilder();
        sb.append("<resources>\n");
        sb.append("<!-- Currently cached resources. These are the CURRENT versions of files/data you have accessed. -->\n");
        sb.append("<!-- When you call tools that read these resources, the cache will be updated automatically. -->\n");
        sb.append("<!-- Total: ").append(resources.size()).append(" resources, ~")
          .append(estimateTotalTokens()).append(" tokens -->\n\n");
        
        for (CachedResource resource : resources.values()) {
            sb.append(resource.toXmlElement());
            sb.append("\n\n");
        }
        
        sb.append("</resources>\n");
        return sb.toString();
    }
    
    /**
     * Generates a short summary of cached resources (for UI display).
     */
    public synchronized String toSummary() {
        if (resources.isEmpty()) {
            return "No resources cached";
        }
        
        return resources.values().stream()
            .map(CachedResource::toSummary)
            .collect(Collectors.joining("\nâ¢ ", "â¢ ", ""));
    }
    
    /**
     * Gets cache statistics.
     */
    public synchronized String getStats() {
        return String.format("Resources: %d/%d, Tokens: ~%d/%d", 
            resources.size(), MAX_RESOURCES,
            estimateTotalTokens(), MAX_TOTAL_TOKENS);
    }
    
    // --- Eviction ---
    
    /**
     * Evicts resources if necessary to make room for a new resource.
     * Returns true if the resource can be added, false if it's too large.
     */
    private boolean evictIfNecessary(int newResourceTokens) {
        // If the new resource alone exceeds the limit, don't evict everything
        // Just log a warning and allow it (but don't evict other resources for it)
        if (newResourceTokens > MAX_TOTAL_TOKENS) {
            logger.warn("ResourceCache: New resource exceeds token limit (" + newResourceTokens + " > " + MAX_TOTAL_TOKENS + "), adding anyway without evicting others");
            return true;
        }
        
        // Evict by count
        while (resources.size() >= MAX_RESOURCES) {
            evictOldest();
        }
        
        // Evict by total tokens - but only if adding won't still exceed the limit
        while (estimateTotalTokens() + newResourceTokens > MAX_TOTAL_TOKENS && !resources.isEmpty()) {
            evictOldest();
        }
        
        return true;
    }
    
    private void evictOldest() {
        // LinkedHashMap with access-order: first entry is LRU
        var iterator = resources.entrySet().iterator();
        if (iterator.hasNext()) {
            var entry = iterator.next();
            iterator.remove();
            
            CachedResource evicted = entry.getValue();
            if (evicted.descriptor().workspacePath() != null) {
                workspacePathIndex.remove(evicted.descriptor().workspacePath());
            }
            
            logger.info("ResourceCache: Evicted LRU resource " + entry.getKey());
        }
    }
    
    public void resourceChanged( IPath path )
    {
        if ( workspacePathIndex.containsKey( path ) )
        {
            invalidate(path);
        }
    }

    public void resourceRemoved( IPath path )
    {
        if ( workspacePathIndex.containsKey( path ) )
        {
            invalidate(path);
        }
    }
    
    @Override
    public void resourceChanged( IResourceChangeEvent event )
    {
        if (event.getDelta() == null) 
        {
            return;
        }
        
        try 
        {
            event.getDelta().accept( delta -> {
                    IResource resource = delta.getResource();
                    
                    // Only care about file changes
                    if (!(resource instanceof IFile)) 
                    {
                        return true; // Continue visiting children
                    }
                    
                    IPath path = resource.getFullPath();

                    switch (delta.getKind()) {
                        case IResourceDelta.CHANGED:
                            // Content changed - invalidate cache
                            if ((delta.getFlags() & IResourceDelta.CONTENT) != 0) {
                                invalidate( path );
                            }
                            break;
                        case IResourceDelta.REMOVED:
                            // File deleted - remove from cache
                            invalidate( path );
                            break;
                        default:
                            // ADDED - no action needed (not in cache yet)
                            break;
                    }

                    return true;
            });
        }
        catch (CoreException e) 
        {
            logger.error("ResourceCache: Error processing resource change", e);
        }
    }
    
    /**
     * Fires a cache event to all registered listeners.
     */
    private void fireCacheEvent(ResourceCacheEvent event) {
        for (IResourceCacheListener listener : cacheListeners) {
            try {
                listener.cacheChanged(event);
            } catch (Exception e) {
                logger.error("Error notifying cache listener", e);
            }
        }
    }

    
    public void addCacheListener(IResourceCacheListener listener) {
        cacheListeners.add(listener);
    }
    
    public void removeCacheListener(IResourceCacheListener listener) {
        cacheListeners.remove(listener);
    }

}
