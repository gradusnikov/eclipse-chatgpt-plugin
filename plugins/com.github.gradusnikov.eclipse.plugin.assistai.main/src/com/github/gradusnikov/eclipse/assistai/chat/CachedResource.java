package com.github.gradusnikov.eclipse.assistai.chat;

import java.time.Instant;

/**
 * A cached resource with its content and metadata.
 * Immutable record - updates create new instances.
 */
public record CachedResource(
    ResourceDescriptor descriptor,
    String content,
    Instant cachedAt,
    int version,
    long contentHash      // For change detection
) {
    
    /**
     * Creates a new cached resource with version 1.
     */
    public static CachedResource create(ResourceDescriptor descriptor, String content) {
        return create(descriptor, content, 1);
    }
    
    /**
     * Creates a new cached resource with specified version.
     */
    public static CachedResource create(ResourceDescriptor descriptor, String content, int version) {
        return new CachedResource(
            descriptor,
            content,
            Instant.now(),
            version,
            content != null ? content.hashCode() : 0
        );
    }
    
    /**
     * Creates an updated version of this resource with new content.
     */
    public CachedResource withUpdatedContent(String newContent) {
        return new CachedResource(
            descriptor,
            newContent,
            Instant.now(),
            version + 1,
            newContent != null ? newContent.hashCode() : 0
        );
    }
    
    /**
     * Checks if content has changed compared to provided content.
     */
    public boolean hasContentChanged(String newContent) {
        if (newContent == null) {
            return content != null;
        }
        return newContent.hashCode() != contentHash;
    }
    
    /**
     * Estimates token count (rough: ~4 chars per token).
     */
    public int estimateTokens() {
        return content != null ? content.length() / 4 : 0;
    }
    
    /**
     * Formats this resource as an XML element for the context block.
     */
    public String toXmlElement() {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format(
            "<resource uri=\"%s\" type=\"%s\" name=\"%s\" version=\"%d\" cached=\"%s\">\n",
            escapeXml(descriptor.uri().toString()),
            descriptor.type(),
            escapeXml(descriptor.displayName()),
            version,
            cachedAt
        ));
        sb.append(content);
        sb.append("\n</resource>");
        return sb.toString();
    }
    
    /**
     * Returns a short summary for display.
     */
    public String toSummary() {
        return String.format("%s (v%d, ~%d tokens)", 
            descriptor.displayName(), 
            version, 
            estimateTokens());
    }
    
    private static String escapeXml(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("&", "&amp;")
                    .replace("<", "&lt;")
                    .replace(">", "&gt;")
                    .replace("\"", "&quot;")
                    .replace("'", "&apos;");
    }
}
