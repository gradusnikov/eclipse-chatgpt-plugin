package com.github.gradusnikov.eclipse.plugin.assistai.resources;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IFolder;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.e4.core.contexts.ContextInjectionFactory;
import org.eclipse.e4.core.contexts.EclipseContextFactory;
import org.eclipse.e4.core.contexts.IEclipseContext;
import org.eclipse.core.runtime.ILog;
import org.eclipse.core.runtime.Platform;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.osgi.framework.FrameworkUtil;

import com.github.gradusnikov.eclipse.assistai.mcp.services.ResourceService;
import com.github.gradusnikov.eclipse.assistai.resources.ResourceDescriptor;

/**
 * A resource whose name contains a space used to be unreadable: the URI was
 * built from the raw path, so URI.create threw "Illegal character in path"
 * before the content was ever returned.
 */
public class ResourceUriSpacesPDETest
{
    private static final String PROJECT_NAME      = "UriSpacesTestProject";

    private static final String FOLDER_WITH_SPACE = "my docs";

    private static final String FILE_WITH_SPACE   = "Summary Agent Framework.md";

    private static final String CONTENT           = "# Heading\nbody line\n";

    private static final String IMAGE_FILE        = "pixel.png";

    private static final byte[] IMAGE_BYTES       = Base64.getDecoder()
            .decode( "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAusB9WlYhZ0AAAAASUVORK5CYII=" );

    private IProject            project;

    private ResourceService     service;

    @BeforeEach
    public void setUp() throws Exception
    {
        var bundleContext = FrameworkUtil.getBundle( ResourceUriSpacesPDETest.class ).getBundleContext();
        IEclipseContext context = EclipseContextFactory.getServiceContext( bundleContext );
        context.set( ILog.class, Platform.getLog( FrameworkUtil.getBundle( ResourceService.class ) ) );
        service = ContextInjectionFactory.make( ResourceService.class, context );

        project = ResourcesPlugin.getWorkspace().getRoot().getProject( PROJECT_NAME );
        if ( !project.exists() )
        {
            project.create( new NullProgressMonitor() );
        }
        project.open( new NullProgressMonitor() );

        IFolder folder = project.getFolder( FOLDER_WITH_SPACE );
        if ( !folder.exists() )
        {
            folder.create( true, true, new NullProgressMonitor() );
        }
        IFile file = folder.getFile( FILE_WITH_SPACE );
        if ( !file.exists() )
        {
            file.create( new ByteArrayInputStream( CONTENT.getBytes( StandardCharsets.UTF_8 ) ), true, new NullProgressMonitor() );
        }

        IFile image = folder.getFile( IMAGE_FILE );
        if ( !image.exists() )
        {
            image.create( new ByteArrayInputStream( IMAGE_BYTES ), true, new NullProgressMonitor() );
        }
    }

    @AfterEach
    public void tearDown() throws Exception
    {
        if ( project != null && project.exists() )
        {
            project.delete( true, true, new NullProgressMonitor() );
        }
    }

    @Test
    public void readsAFileWhoseNameContainsSpaces()
    {
        String result = service.readProjectResource( PROJECT_NAME, FOLDER_WITH_SPACE + "/" + FILE_WITH_SPACE );

        // The bug surfaced as an exception message rather than content.
        assertFalse( result.contains( "Illegal character in path" ), result );
        assertFalse( result.contains( "URISyntaxException" ), result );
        assertTrue( result.contains( "body line" ), result );
    }

    @Test
    public void readsAnImageResourceAsBinaryData()
    {
        ResourceService.ImageResource result = service.readImageResource( PROJECT_NAME, FOLDER_WITH_SPACE + "/" + IMAGE_FILE );

        assertEquals( "image/png", result.mimeType() );
        assertArrayEquals( IMAGE_BYTES, result.data() );
    }

    @Test
    public void buildsAnEncodedUriForAPathWithSpaces()
    {
        IFile file = project.getFolder( FOLDER_WITH_SPACE ).getFile( FILE_WITH_SPACE );
        ResourceDescriptor descriptor = ResourceDescriptor.fromWorkspaceFile( file, "test" );

        assertEquals( URI.create( "workspace:///" + PROJECT_NAME + "/my%20docs/Summary%20Agent%20Framework.md" ), descriptor.uri() );

        // The path is kept separately, so nothing has to decode the URI back.
        assertTrue( descriptor.existsInWorkspace() );
        assertTrue( descriptor.toWorkspaceFile().isPresent() );
    }

    @Test
    public void parsesAUriThatWasHandedBackUnencoded()
    {
        // A caller echoing a path it saw elsewhere may well include a raw
        // space.
        URI parsed = ResourceDescriptor.parseUri( "workspace:///" + PROJECT_NAME + "/my docs/" + FILE_WITH_SPACE );

        assertEquals( URI.create( "workspace:///" + PROJECT_NAME + "/my%20docs/Summary%20Agent%20Framework.md" ), parsed );
    }
}
