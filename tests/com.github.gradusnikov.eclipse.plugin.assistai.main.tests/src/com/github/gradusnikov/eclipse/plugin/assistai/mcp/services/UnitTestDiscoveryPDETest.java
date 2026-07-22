package com.github.gradusnikov.eclipse.plugin.assistai.mcp.services;

import static org.junit.jupiter.api.Assertions.assertFalse;
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
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.JavaCore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.github.gradusnikov.eclipse.assistai.mcp.services.UnitTestService;

public class UnitTestDiscoveryPDETest
{
    private static final String TEST_PROJECT = "UnitTestDiscoveryTestProject";

    private final NullProgressMonitor monitor = new NullProgressMonitor();
    private final UnitTestService service = new UnitTestService();
    private IProject project;
    private IFolder packageFolder;

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
        packageFolder = sourceFolder.getFolder("sample");
        packageFolder.create(IResource.NONE, true, monitor);

        IJavaProject javaProject = JavaCore.create(project);
        javaProject.setRawClasspath(
                new org.eclipse.jdt.core.IClasspathEntry[] { JavaCore.newSourceEntry(sourceFolder.getFullPath()) },
                project.getFullPath().append("bin"), monitor);

        createSource("PlainTest.java", "package sample; public class PlainTest {}");
        createSource("WorkspacePDETest.java", "package sample; public class WorkspacePDETest {}");
        createSource("MisnamedIntegrationTest.java",
                "package sample; import org.eclipse.core.resources.ResourcesPlugin; public class MisnamedIntegrationTest { Object workspace = ResourcesPlugin.getWorkspace(); }");
        createSource("Odd.java", "package sample; public class Odd { void test() {} }");
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
    public void testClassifiesPdeTestsAndReportsNamingWarnings()
    {
        String result = service.findTestClasses(TEST_PROJECT);

        assertTrue(result.contains("Found 3 test classes"));
        assertTrue(result.contains("Plain JUnit tests (2):"));
        assertTrue(result.contains("- sample.PlainTest"));
        assertTrue(result.contains("- sample.MisnamedIntegrationTest"));
        assertTrue(result.contains("PDE harness tests (*PDETest) (1):"));
        assertTrue(result.contains("- sample.WorkspacePDETest"));
        assertTrue(result.contains("Naming warnings"));
        assertFalse(result.substring(result.indexOf("Naming warnings")).contains("sample.PlainTest"));
        assertFalse(result.contains("sample.Odd"));
    }

    private void createSource(String fileName, String source) throws Exception
    {
        IFile file = packageFolder.getFile(fileName);
        file.create(new ByteArrayInputStream(source.getBytes(StandardCharsets.UTF_8)), true, monitor);
    }
}
