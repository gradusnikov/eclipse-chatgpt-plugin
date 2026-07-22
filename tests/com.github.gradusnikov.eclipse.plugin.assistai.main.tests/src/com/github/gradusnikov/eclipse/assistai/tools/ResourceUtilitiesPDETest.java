package com.github.gradusnikov.eclipse.assistai.tools;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IProjectDescription;
import org.eclipse.core.resources.IWorkspace;
import org.eclipse.core.resources.IWorkspaceRoot;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.core.runtime.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.osgi.framework.BundleContext;
import org.osgi.framework.FrameworkUtil;
import org.osgi.util.tracker.ServiceTracker;

/**
 * Plugin test for {@link ResourceUtilities#getResourceFileType(IFile)}, exercising
 * the Eclipse content-type based text/binary gate that replaced Apache Tika.
 * Requires the Eclipse plugin runtime (workspace + content-type registry), so it
 * runs as a JUnit Plug-in Test rather than plain JUnit.
 *
 * <p>Regression: the previous Tika-based implementation rejected {@code .tex}
 * (LaTeX) files, because Tika reported their MIME type as {@code application/x-tex}
 * which was absent from a hard-coded text-MIME whitelist.
 */
public class ResourceUtilitiesPDETest
{
    private static final String TEST_PROJECT_NAME = "ResourceUtilitiesTestProject";
    private final NullProgressMonitor monitor = new NullProgressMonitor();
    private IProject project;

    @BeforeEach
    public void beforeEach() throws CoreException
    {
        BundleContext bundleContext = FrameworkUtil.getBundle( ResourceUtilitiesPDETest.class ).getBundleContext();
        ServiceTracker<IWorkspace, IWorkspace> workspaceTracker = new ServiceTracker<>( bundleContext, IWorkspace.class, null );
        workspaceTracker.open();
        IWorkspace workspace = workspaceTracker.getService();
        IWorkspaceRoot root = workspace.getRoot();

        project = root.getProject( TEST_PROJECT_NAME );
        if ( project.exists() )
        {
            project.delete( true, true, monitor );
        }
        IProjectDescription desc = workspace.newProjectDescription( TEST_PROJECT_NAME );
        project.create( desc, monitor );
        project.open( monitor );
    }

    @AfterEach
    public void afterEach() throws CoreException
    {
        if ( project != null && project.exists() )
        {
            project.delete( true, true, monitor );
        }
    }

    @Test
    public void latexFileIsAccessible() throws CoreException
    {
        // Regression: Tika reported .tex as application/x-tex and the file was
        // rejected as "binary"; it must now be readable as a (text) resource.
        IFile tex = createTextFile( "paper.tex",
                "\\documentclass{article}\n\\begin{document}\nHello, \\LaTeX!\n\\end{document}\n" );
        assertNotNull( assertDoesNotThrow( () -> ResourceUtilities.getResourceFileType( tex ) ) );
    }

    @Test
    public void javaFileReturnsJavaLanguage() throws CoreException, IOException
    {
        IFile java = createTextFile( "Sample.java", "public class Sample {}\n" );
        assertEquals( "java", ResourceUtilities.getResourceFileType( java ) );
    }

    @Test
    public void plainTextFileReturnsTextLanguage() throws CoreException, IOException
    {
        IFile txt = createTextFile( "readme.txt", "just some notes\n" );
        assertEquals( "text", ResourceUtilities.getResourceFileType( txt ) );
    }

    @Test
    public void binaryFileIsRejected() throws CoreException
    {
        // A NUL byte in otherwise-unknown content is a strong binary signal.
        IFile bin = createBinaryFile( "data.bin", new byte[] { 'h', 'e', 'l', 0x00, 'l', 'o' } );
        assertThrows( IOException.class, () -> ResourceUtilities.getResourceFileType( bin ) );
    }

    private IFile createTextFile( String name, String content ) throws CoreException
    {
        return createBinaryFile( name, content.getBytes( StandardCharsets.UTF_8 ) );
    }

    private IFile createBinaryFile( String name, byte[] bytes ) throws CoreException
    {
        IFile file = project.getFile( new Path( name ) );
        if ( file.exists() )
        {
            file.setContents( new ByteArrayInputStream( bytes ), true, true, monitor );
        }
        else
        {
            file.create( new ByteArrayInputStream( bytes ), true, monitor );
        }
        return file;
    }
}
