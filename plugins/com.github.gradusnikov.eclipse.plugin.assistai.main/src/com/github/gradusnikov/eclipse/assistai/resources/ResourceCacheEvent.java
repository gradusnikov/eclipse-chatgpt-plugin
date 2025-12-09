package com.github.gradusnikov.eclipse.assistai.resources;

import java.util.EventObject;

/**
 * Event fired when resources are added or removed from the cache.
 */
public class ResourceCacheEvent extends EventObject {
    
    private static final long serialVersionUID = 1L;
    
    public enum Type {
        ADDED,
        REMOVED,
        INVALIDATED,
        CLEARED
    }
    
    private final Type type;
    private final CachedResource resource; // may be null for CLEARED
    
    public ResourceCacheEvent(Object source, Type type, CachedResource resource) {
        super(source);
        this.type = type;
        this.resource = resource;
    }
    
    public Type getType() {
        return type;
    }
    
    public CachedResource getResource() {
        return resource;
    }
}
