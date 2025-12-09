package com.github.gradusnikov.eclipse.assistai.resources;

/**
 * Listener for cache events (resources added/removed).
 */
public interface IResourceCacheListener {
    
    /**
     * Called when a resource cache event occurs.
     * 
     * @param event The cache event
     */
    void cacheChanged(ResourceCacheEvent event);
}
