package com.github.gradusnikov.eclipse.assistai.mcp.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IFolder;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IProjectDescription;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.core.runtime.Path;
import org.eclipse.jdt.core.IClasspathEntry;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.launching.JavaRuntime;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.github.gradusnikov.eclipse.assistai.mcp.results.DiagnosticCode;
import com.github.gradusnikov.eclipse.assistai.mcp.results.JavaDocResponse;
import com.github.gradusnikov.eclipse.assistai.tools.Javadocs;

/**
 * {@code getJavaDoc} against a real Java project.
 * <p>
 * Two things are pinned. That the documentation is rendered rather than pasted: the old
 * body carried the raw comment markers of workspace source, and no inherited text. And
 * that the miss is a status: {@code "JavaDoc is not available for X"} used to occupy the
 * answer slot, so a type with no documentation and a misspelled name produced the same
 * result - and each needs a different next move: read the source, or fix the name.
 */
public class JavaDocServicePDETest
{
    private static final String TEST_PROJECT = "JavaDocServiceTestProject";

    private final NullProgressMonitor monitor = new NullProgressMonitor();
    private final JavaDocService      service = new JavaDocService();
    private IProject                  project;

    @BeforeEach
    public void beforeEach() throws Exception
    {
        project = ResourcesPlugin.getWorkspace().getRoot().getProject( TEST_PROJECT );
        if ( project.exists() )
        {
            project.delete( true, true, monitor );
        }

        IProjectDescription description = project.getWorkspace().newProjectDescription( TEST_PROJECT );
        description.setNatureIds( new String[] { JavaCore.NATURE_ID } );
        project.create( description, monitor );
        project.open( monitor );

        IFolder sourceFolder = project.getFolder( "src" );
        sourceFolder.create( IResource.NONE, true, monitor );

        IJavaProject javaProject = JavaCore.create( project );
        javaProject.setRawClasspath( new IClasspathEntry[] {
                JavaCore.newSourceEntry( sourceFolder.getFullPath() ),
                JavaCore.newContainerEntry( new Path( JavaRuntime.JRE_CONTAINER ) ) },
                project.getFullPath().append( "bin" ), monitor );

        IFolder packageFolder = project.getFolder( "src/example" );
        packageFolder.create( IResource.NONE, true, monitor );

        createFile( "src/example/Speaker.java",
                "package example;\n"
                + "\n"
                + "public interface Speaker\n"
                + "{\n"
                + "    /** Talks. Loudly. */\n"
                + "    void speak();\n"
                + "}\n" );
        createFile( "src/example/Documented.java",
                "package example;\n"
                + "\n"
                + "/** Explains what this type is for. In two sentences. */\n"
                + "public class Documented implements Speaker\n"
                + "{\n"
                + "    /** The count. */\n"
                + "    public int count;\n"
                + "\n"
                + "    @Override\n"
                + "    public void speak()\n"
                + "    {\n"
                + "    }\n"
                + "\n"
                + "    /**\n"
                + "     * Runs once.\n"
                + "     *\n"
                + "     * @param times ignored\n"
                + "     */\n"
                + "    public void run( int times )\n"
                + "    {\n"
                + "    }\n"
                + "\n"
                + "    public void run()\n"
                + "    {\n"
                + "    }\n"
                + "}\n" );
        createFile( "src/example/Bare.java",
                "package example;\n"
                + "\n"
                + "public class Bare\n"
                + "{\n"
                + "    public void run()\n"
                + "    {\n"
                + "    }\n"
                + "}\n" );

        project.refreshLocal( IResource.DEPTH_INFINITE, monitor );
    }

    @AfterEach
    public void afterEach() throws Exception
    {
        if ( project != null && project.exists() )
        {
            project.delete( true, true, monitor );
        }
    }

    @Test
    public void rendersTheTypesCommentAndEachMembersSeparately()
    {
        JavaDocResponse response = service.getJavaDoc( "example.Documented", null, Javadocs.Detail.FULL );

        assertEquals( JavaDocResponse.Status.OK, response.status() );
        assertEquals( TEST_PROJECT, response.projectName(), "which project answered is a field" );
        assertEquals( "Explains what this type is for. In two sentences.", response.javadoc() );
        assertTrue( response.diagnostics().isEmpty() );

        JavaDocResponse.MemberJavadoc count = member( response, "count" );
        assertEquals( "public int count", count.label() );
        assertEquals( "The count.", count.javadoc() );

        JavaDocResponse.MemberJavadoc run = member( response, "run", "public void run(int times)" );
        assertTrue( run.javadoc().startsWith( "Runs once." ), run.javadoc() );
        assertTrue( run.javadoc().contains( "ignored" ), "block tags are part of the whole comment: " + run.javadoc() );
        assertFalse( run.javadoc().contains( "/**" ), "rendered, not pasted: " + run.javadoc() );
    }

    @Test
    public void anUndocumentedOverrideReportsItsSupertypesTextAndSaysSo()
    {
        JavaDocResponse response = service.getJavaDoc( "example.Documented", null, Javadocs.Detail.SUMMARY );

        JavaDocResponse.MemberJavadoc speak = member( response, "speak" );
        assertEquals( "Talks.", speak.javadoc() );
        assertTrue( speak.javadocInherited() );

        JavaDocResponse.MemberJavadoc overload = member( response, "run", "public void run()" );
        assertNull( overload.javadoc(), "nothing to inherit from" );
        assertFalse( overload.javadocInherited() );
    }

    @Test
    public void summaryIsOneSentence()
    {
        JavaDocResponse response = service.getJavaDoc( "example.Documented", null, Javadocs.Detail.SUMMARY );

        assertEquals( "Explains what this type is for.", response.javadoc() );
        assertEquals( "Runs once.", member( response, "run", "public void run(int times)" ).javadoc() );
    }

    @Test
    public void memberNameKeepsEveryOverloadOfThatNameAndNothingElse()
    {
        JavaDocResponse response = service.getJavaDoc( "example.Documented", "run", Javadocs.Detail.FULL );

        assertEquals( JavaDocResponse.Status.OK, response.status() );
        assertEquals( 2, response.members().size(), response.members().toString() );
        assertTrue( response.members().stream().allMatch( member -> "run".equals( member.name() ) ) );
        assertEquals( "Explains what this type is for. In two sentences.", response.javadoc(),
                "the type's own comment is still reported" );
    }

    @Test
    public void anUnknownMemberIsAStatusRatherThanAnEmptyList()
    {
        JavaDocResponse response = service.getJavaDoc( "example.Documented", "fly", Javadocs.Detail.FULL );

        assertEquals( JavaDocResponse.Status.MEMBER_NOT_FOUND, response.status() );
        assertEquals( TEST_PROJECT, response.projectName(), "the type was found" );
        assertEquals( DiagnosticCode.RESOURCE_NOT_FOUND, response.diagnostics().get( 0 ).code() );
    }

    @Test
    public void separatesAnUndocumentedTypeFromAMisspelledName()
    {
        JavaDocResponse undocumented = service.getJavaDoc( "example.Bare", null, Javadocs.Detail.FULL );
        JavaDocResponse misspelled = service.getJavaDoc( "example.Bear", null, Javadocs.Detail.FULL );

        // Both used to be the same sentence in the same field.
        assertEquals( JavaDocResponse.Status.NO_JAVADOC, undocumented.status() );
        assertEquals( JavaDocResponse.Status.TYPE_NOT_FOUND, misspelled.status() );
    }

    @Test
    public void anUndocumentedTypeIsNotAFault()
    {
        JavaDocResponse response = service.getJavaDoc( "example.Bare", null, Javadocs.Detail.FULL );

        assertEquals( TEST_PROJECT, response.projectName(), "the type was found; it simply has no comment" );
        assertTrue( response.diagnostics().isEmpty() );
        assertNull( response.javadoc() );
        assertEquals( List.of( "run" ), response.members().stream().map( JavaDocResponse.MemberJavadoc::name ).toList(),
                "the members are still listed, with their declarations" );
    }

    @Test
    public void aNameNoProjectResolvesCarriesACodeRatherThanASentence()
    {
        JavaDocResponse response = service.getJavaDoc( "example.Bear", null, Javadocs.Detail.FULL );

        assertNull( response.projectName() );
        assertNull( response.javadoc(), "nothing was found, so the answer slot is empty" );
        assertTrue( response.members().isEmpty() );
        assertEquals( DiagnosticCode.RESOURCE_NOT_FOUND, response.diagnostics().get( 0 ).code() );
        assertEquals( "example.Bear", response.typeName() );
    }

    @Test
    public void askingForDocumentationWithoutAnyIsRejected()
    {
        assertThrows( IllegalArgumentException.class,
                () -> service.getJavaDoc( "example.Documented", null, Javadocs.Detail.NONE ) );
    }

    private static JavaDocResponse.MemberJavadoc member( JavaDocResponse response, String name )
    {
        return response.members().stream()
                .filter( member -> name.equals( member.name() ) )
                .findFirst()
                .orElseThrow( () -> new AssertionError( name + " not in " + response.members() ) );
    }

    private static JavaDocResponse.MemberJavadoc member( JavaDocResponse response, String name, String label )
    {
        return response.members().stream()
                .filter( member -> name.equals( member.name() ) && label.equals( member.label() ) )
                .findFirst()
                .orElseThrow( () -> new AssertionError( label + " not in " + response.members() ) );
    }

    private void createFile( String path, String content ) throws Exception
    {
        IFile file = project.getFile( new Path( path ) );
        file.create( new ByteArrayInputStream( content.getBytes( StandardCharsets.UTF_8 ) ), true, monitor );
    }
}
