package com.github.gradusnikov.eclipse.assistai.chat;

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
