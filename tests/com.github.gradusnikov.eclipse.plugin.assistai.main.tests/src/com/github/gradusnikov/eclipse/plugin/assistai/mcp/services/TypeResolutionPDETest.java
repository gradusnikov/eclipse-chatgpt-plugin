package com.github.gradusnikov.eclipse.plugin.assistai.mcp.services;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

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

import com.github.gradusnikov.eclipse.assistai.mcp.services.JavaDocService;

public class TypeResolutionPDETest
{
    private static final String TEST_PROJECT = "TypeResolutionTestProject";

    private final NullProgressMonitor monitor = new NullProgressMonitor();
    private final JavaDocService service = new JavaDocService();
    private IProject project;

    @BeforeEach
    public void beforeEach() throws Exception
    {
        project = ResourcesPlugin.getWorkspace().getRoot().getProject(TEST_PROJECT);
        if (project.exists())
        {
            project.delete(true, true, monitor);
        }

        IProjectDescription description = project.getWorkspace().newProjectDescription(TEST_PROJECT);
        description.setNatureIds(new String[] { JavaCore.NATURE_ID });
        project.create(description, monitor);
        project.open(monitor);

        IFolder sourceFolder = project.getFolder("src");
        sourceFolder.create(IResource.NONE, true, monitor);

        IJavaProject javaProject = JavaCore.create(project);
        IClasspathEntry[] classpath = {
                JavaCore.newSourceEntry(sourceFolder.getFullPath()),
                JavaCore.newContainerEntry(new Path(JavaRuntime.JRE_CONTAINER))
        };
        javaProject.setRawClasspath(classpath, project.getFullPath().append("bin"), monitor);

        IFile sample = project.getFile("src/example/Sample.java");
        sample.getParent().getAdapter(IFolder.class).create(IResource.NONE, true, monitor);
        sample.create(new ByteArrayInputStream(
                "package example; public class Sample {}".getBytes(StandardCharsets.UTF_8)), true, monitor);
        project.refreshLocal(IResource.DEPTH_INFINITE, monitor);
    }

    @AfterEach
    public void afterEach() throws Exception
    {
        if (project != null && project.exists())
        {
            project.delete(true, true, monitor);
        }
    }

    @Test
    public void testExplainsWorkspaceSourceResolution()
    {
        String result = service.explainTypeResolution(TEST_PROJECT, "example.Sample");

        assertTrue(result.contains("Kind: workspace source"));
        assertTrue(result.contains("Workspace resource: /" + TEST_PROJECT));
        assertTrue(result.contains("Classpath entry: source"));
        assertTrue(result.contains("Source strategy: workspace compilation unit"));
    }

    @Test
    public void testExplainsBinaryResolution()
    {
        String result = service.explainTypeResolution(TEST_PROJECT, "java.lang.String");

        assertTrue(result.contains("Kind: binary class"));
        assertTrue(result.contains("Package fragment root:"));
        assertTrue(result.contains("Classpath entry:"));
        assertTrue(result.contains("Class file:"));
        assertTrue(result.contains("Source strategy:"));
    }

    @Test
    public void testReportsUnresolvedType()
    {
        String result = service.explainTypeResolution(TEST_PROJECT, "does.not.exist.Missing");

        assertTrue(result.contains("is not resolved on the classpath"));
    }
}
