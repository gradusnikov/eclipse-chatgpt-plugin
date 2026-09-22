package com.github.gradusnikov.eclipse.assistai.mcp.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IFolder;
import org.eclipse.core.resources.IMarker;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IProjectDescription;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IWorkspaceRoot;
import org.eclipse.core.resources.IncrementalProjectBuilder;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.ILog;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.e4.core.contexts.ContextInjectionFactory;
import org.eclipse.e4.core.contexts.EclipseContextFactory;
import org.eclipse.e4.core.contexts.IEclipseContext;
import org.eclipse.jdt.core.IClasspathEntry;
import org.eclipse.jdt.core.IJavaModelMarker;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.launching.JavaRuntime;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.github.gradusnikov.eclipse.assistai.Activator;
import com.github.gradusnikov.eclipse.assistai.mcp.results.ClassOutlineResponse;
import com.github.gradusnikov.eclipse.assistai.mcp.results.MethodSourceResponse;
import com.github.gradusnikov.eclipse.assistai.resources.ResourceReadResult;
import com.github.gradusnikov.eclipse.assistai.resources.SourceOrigin;
import com.github.gradusnikov.eclipse.assistai.tools.Javadocs;

/**
 * The outline, method-source and filtered-source tools on a type that is not a
 * workspace file: a library class with attached source, and one without, which is
 * decompiled.
 * <p>
 * The library is built from a throwaway project, copied out of the workspace as an
 * external class folder, and referenced from a second project - with the source folder
 * attached or not, depending on the case. Every line number asserted below is a line
 * of {@link #WIDGET}, which is why its formatting is fixed.
 */
public class BinaryTypeSourcePDETest
{
    private static final String WIDGET = ""
            + "package lib;\n"                                    // 1
            + "\n"                                                // 2
            + "import java.util.List;\n"                          // 3
            + "\n"                                                // 4
            + "/** A widget. */\n"                                // 5
            + "public class Widget {\n"                           // 6
            + "    private int size;\n"                           // 7
            + "\n"                                                // 8
            + "    /** Grows. */\n"                               // 9
            + "    public int grow(int by) {\n"                   // 10
            + "        size = size + by;\n"                       // 11
            + "        return size;\n"                            // 12
            + "    }\n"                                           // 13
            + "\n"                                                // 14
            + "    public List<String> names() {\n"               // 15
            + "        return List.of(\"a\");\n"                  // 16
            + "    }\n"                                           // 17
            + "\n"                                                // 18
            + "    public String join(String... parts) {\n"       // 19
            + "        return String.join(\",\", parts);\n"       // 20
            + "    }\n"                                           // 21
            + "}\n";                                              // 22

    private final NullProgressMonitor monitor = new NullProgressMonitor();

    private IProject libraryProject;
    private IProject project;
    private Path libraryDir;
    private CodeAnalysisService codeAnalysisService;
    private OutlineService outlineService;

    @AfterEach
    public void afterEach() throws Exception
    {
        Job.getJobManager().join( ResourcesPlugin.FAMILY_AUTO_BUILD, monitor );
        delete( project );
        delete( libraryProject );
        if ( libraryDir != null && Files.exists( libraryDir ) )
        {
            try ( Stream<Path> paths = Files.walk( libraryDir ) )
            {
                paths.sorted( Comparator.reverseOrder() ).forEach( path -> path.toFile().delete() );
            }
        }
    }

    // ---- attached source ---------------------------------------------------

    @Test
    public void attachedSourceOutlinesWithTheAttachedFilesLines() throws Exception
    {
        setUp( true );

        ClassOutlineResponse outline = codeAnalysisService.getClassOutline( "lib.Widget", true, Javadocs.Detail.NONE );

        assertEquals( ClassOutlineResponse.Status.OK, outline.status(), outline.summaryText() );
        assertEquals( SourceOrigin.ATTACHED_SOURCE, outline.sourceOrigin() );
        assertNull( outline.filePath(), "a library type has no workspace file to read it from" );
        assertEquals( 5, outline.declaration().startLine(), "the type's range starts at its Javadoc" );
        assertEquals( 22, outline.declaration().endLine() );
        assertEquals( 7, member( outline.fields(), "size" ).startLine() );
        assertEquals( 9, member( outline.methods(), "grow" ).startLine() );
        assertEquals( 13, member( outline.methods(), "grow" ).endLine() );
        assertEquals( 15, member( outline.methods(), "names" ).startLine() );
        assertEquals( 17, member( outline.methods(), "names" ).endLine() );
    }

    @Test
    public void attachedSourceMethodSourceIsTheAttachedText() throws Exception
    {
        setUp( true );

        MethodSourceResponse response = outlineService.getMethodSource( "lib.Widget", "grow", null, false );

        assertEquals( MethodSourceResponse.Status.OK, response.status(), () -> response.diagnostics().toString() );
        assertEquals( SourceOrigin.ATTACHED_SOURCE, response.sourceOrigin() );
        MethodSourceResponse.MethodSource grow = response.methods().get( 0 );
        assertEquals( 10, grow.range().startLine(), "without the Javadoc the method starts at its declaration" );
        assertEquals( 13, grow.range().endLine() );
        assertTrue( grow.source().contains( "size = size + by;" ), grow.source() );
        assertFalse( grow.source().contains( "Grows" ), grow.source() );
    }

    @Test
    public void attachedSourceFilteredSourceCollapsesWhatWasNotAskedFor() throws Exception
    {
        setUp( true );

        ResourceReadResult result = outlineService.getFilteredSource( "lib.Widget", true, "grow" );

        assertEquals( ResourceReadResult.ReadStatus.PARTIAL, result.status(), () -> result.diagnostics().toString() );
        assertEquals( SourceOrigin.ATTACHED_SOURCE, result.origin() );
        assertFalse( result.isEditable(), "attached source is real source, but not something to write back" );
        assertTrue( result.content().contains( "return size;" ), result.content() );
        assertFalse( result.content().contains( "List.of" ), "the body of names() was collapsed" );
        assertFalse( result.content().contains( "import java.util.List;" ), "the imports were collapsed" );
        assertTrue( result.omittedRanges().stream().anyMatch( r -> r.startLine() == 3 && r.endLine() == 3 ),
                () -> result.omittedRanges().toString() );
        assertTrue( result.omittedRanges().stream().anyMatch( r -> r.startLine() == 16 && r.endLine() == 16 ),
                () -> result.omittedRanges().toString() );
    }

    // ---- decompiled class --------------------------------------------------

    @Test
    public void varargsReadAsVarargsRatherThanTransient() throws Exception
    {
        setUp( true );

        ClassOutlineResponse outline = codeAnalysisService.getClassOutline( "lib.Widget", false, Javadocs.Detail.NONE );

        // ACC_VARARGS is the bit that means "transient" on a field, so Flags.toString printed the
        // method as "transient ... (String[] parts)" - wrong in both halves of the signature.
        String label = member( outline.methods(), "join" ).label();
        assertTrue( label.contains( "String... parts" ), label );
        assertFalse( label.contains( "transient" ), label );
    }

    @Test
    public void aClassWithoutSourceIsDecompiledAndOutlined() throws Exception
    {
        setUp( false );

        ClassOutlineResponse outline = codeAnalysisService.getClassOutline( "lib.Widget", true, Javadocs.Detail.NONE );

        assertEquals( ClassOutlineResponse.Status.OK, outline.status(), outline.summaryText() );
        assertEquals( SourceOrigin.DECOMPILED_CLASS, outline.sourceOrigin() );
        assertNull( outline.filePath() );
        assertEquals( "public class Widget", outline.declaration().label(),
                "a class file spells out the superclass the language gives every type anyway" );
        ClassOutlineResponse.Member grow = member( outline.methods(), "grow" );
        ClassOutlineResponse.Member names = member( outline.methods(), "names" );
        assertTrue( grow.startLine() >= 1 && grow.endLine() >= grow.startLine(), grow.toString() );
        assertTrue( names.startLine() > grow.endLine(), "members are laid out in the decompiled text in order" );
        assertTrue( outline.declaration().endLine() >= names.endLine(), outline.declaration().toString() );
    }

    @Test
    public void aClassWithoutSourceHasReadableMethodsAndFilteredSource() throws Exception
    {
        setUp( false );

        MethodSourceResponse methods = outlineService.getMethodSource( "lib.Widget", "grow", null, true );
        assertEquals( MethodSourceResponse.Status.OK, methods.status(), () -> methods.diagnostics().toString() );
        assertEquals( SourceOrigin.DECOMPILED_CLASS, methods.sourceOrigin() );
        MethodSourceResponse.MethodSource grow = methods.methods().get( 0 );
        assertTrue( grow.source().contains( "grow(" ), grow.source() );
        assertTrue( grow.source().contains( "size" ), grow.source() );
        assertTrue( grow.range().startLine() >= 1 && grow.range().endLine() >= grow.range().startLine(), grow.range().toString() );

        ResourceReadResult filtered = outlineService.getFilteredSource( "lib.Widget", true, "grow" );
        assertEquals( SourceOrigin.DECOMPILED_CLASS, filtered.origin() );
        assertFalse( filtered.isEditable() );
        assertTrue( filtered.content().contains( "Decompiled by Vineflower" ), filtered.content() );
        assertTrue( filtered.content().contains( "class Widget" ), filtered.content() );
        assertTrue( filtered.content().contains( "grow(" ), filtered.content() );
    }

    // ---- fixture -----------------------------------------------------------

    private static ClassOutlineResponse.Member member( List<ClassOutlineResponse.Member> members, String name )
    {
        return members.stream().filter( m -> m.name().equals( name ) ).findFirst()
                .orElseThrow( () -> new AssertionError( "no member " + name + " in " + members ) );
    }

    private void setUp( boolean attachSource ) throws Exception
    {
        IWorkspaceRoot root = ResourcesPlugin.getWorkspace().getRoot();
        compileLibrary( root );

        project = root.getProject( "BinaryTypeSourceApp_" + UUID.randomUUID() );
        IProjectDescription description = root.getWorkspace().newProjectDescription( project.getName() );
        description.setNatureIds( new String[] { JavaCore.NATURE_ID } );
        project.create( description, monitor );
        project.open( monitor );
        IFolder src = project.getFolder( "src" );
        src.create( IResource.NONE, true, monitor );

        IPath classes = IPath.fromOSString( libraryDir.resolve( "classes" ).toString() );
        IPath sources = attachSource ? IPath.fromOSString( libraryDir.resolve( "src" ).toString() ) : null;
        JavaCore.create( project ).setRawClasspath( new IClasspathEntry[] {
            JavaCore.newSourceEntry( src.getFullPath() ),
            JavaCore.newLibraryEntry( classes, sources, null ),
            JavaRuntime.getDefaultJREContainerEntry()
        }, project.getFullPath().append( "bin" ), monitor );

        assertNotNull( JavaCore.create( project ).findType( "lib.Widget" ), "the library class resolves on the classpath" );

        IEclipseContext context = EclipseContextFactory.create();
        context.set( ILog.class, Activator.getDefault().getLog() );
        codeAnalysisService = ContextInjectionFactory.make( CodeAnalysisService.class, context );
        outlineService = ContextInjectionFactory.make( OutlineService.class, context );
    }

    /**
     * Compiles {@link #WIDGET} in a throwaway project, copies the class file and the
     * source out to an external folder, and closes the project so the type can only be
     * found through the library.
     */
    private void compileLibrary( IWorkspaceRoot root ) throws Exception
    {
        libraryProject = root.getProject( "BinaryTypeSourceLib_" + UUID.randomUUID() );
        libraryProject.create( root.getWorkspace().newProjectDescription( libraryProject.getName() ), monitor );
        libraryProject.open( monitor );

        // The nature is added to the open project, which is what installs the Java
        // builder; a nature in the description at creation time does not build.
        IProjectDescription description = libraryProject.getDescription();
        description.setNatureIds( new String[] { JavaCore.NATURE_ID } );
        libraryProject.setDescription( description, monitor );

        IJavaProject javaProject = JavaCore.create( libraryProject );
        javaProject.setOption( JavaCore.COMPILER_COMPLIANCE, "21" );
        javaProject.setOption( JavaCore.COMPILER_SOURCE, "21" );
        javaProject.setOption( JavaCore.COMPILER_CODEGEN_TARGET_PLATFORM, "21" );

        IFolder src = libraryProject.getFolder( "src" );
        src.create( IResource.NONE, true, monitor );
        IFolder lib = src.getFolder( "lib" );
        lib.create( IResource.NONE, true, monitor );
        lib.getFile( "Widget.java" ).create( new ByteArrayInputStream( WIDGET.getBytes( StandardCharsets.UTF_8 ) ), true, monitor );
        javaProject.setRawClasspath( new IClasspathEntry[] {
            JavaCore.newSourceEntry( src.getFullPath() ),
            JavaRuntime.getDefaultJREContainerEntry()
        }, libraryProject.getFullPath().append( "bin" ), monitor );

        libraryProject.build( IncrementalProjectBuilder.FULL_BUILD, monitor );
        Job.getJobManager().join( ResourcesPlugin.FAMILY_MANUAL_BUILD, monitor );
        Job.getJobManager().join( ResourcesPlugin.FAMILY_AUTO_BUILD, monitor );
        libraryProject.refreshLocal( IResource.DEPTH_INFINITE, monitor );

        IFile classFile = libraryProject.getFile( "bin/lib/Widget.class" );
        if ( !classFile.exists() )
        {
            StringBuilder problems = new StringBuilder();
            for ( IMarker marker : libraryProject.findMarkers( IJavaModelMarker.JAVA_MODEL_PROBLEM_MARKER, true, IResource.DEPTH_INFINITE ) )
            {
                problems.append( "\n  " ).append( marker.getAttribute( IMarker.MESSAGE, "?" ) );
            }
            for ( IMarker marker : libraryProject.findMarkers( IJavaModelMarker.BUILDPATH_PROBLEM_MARKER, true, IResource.DEPTH_INFINITE ) )
            {
                problems.append( "\n  " ).append( marker.getAttribute( IMarker.MESSAGE, "?" ) );
            }
            throw new AssertionError( "the fixture did not compile to " + classFile.getFullPath()
                    + "; build spec " + java.util.Arrays.toString( libraryProject.getDescription().getBuildSpec() )
                    + "; problems:" + problems );
        }

        libraryDir = Files.createTempDirectory( "binary-type-source" );
        Path classes = libraryDir.resolve( "classes" ).resolve( "lib" );
        Files.createDirectories( classes );
        try ( InputStream contents = classFile.getContents() )
        {
            Files.copy( contents, classes.resolve( "Widget.class" ) );
        }
        Path sources = libraryDir.resolve( "src" ).resolve( "lib" );
        Files.createDirectories( sources );
        Files.writeString( sources.resolve( "Widget.java" ), WIDGET, StandardCharsets.UTF_8 );

        // Closed, so the type is no longer found as workspace source.
        libraryProject.close( monitor );
    }

    private void delete( IProject toDelete ) throws CoreException, InterruptedException, IOException
    {
        if ( toDelete == null || !toDelete.exists() )
        {
            return;
        }
        for ( int attempt = 0; attempt < 5; attempt++ )
        {
            try
            {
                toDelete.delete( true, true, monitor );
                return;
            }
            catch ( CoreException e )
            {
                if ( attempt == 4 )
                {
                    toDelete.delete( false, true, monitor );
                    return;
                }
                Thread.sleep( 300 );
            }
        }
    }
}
