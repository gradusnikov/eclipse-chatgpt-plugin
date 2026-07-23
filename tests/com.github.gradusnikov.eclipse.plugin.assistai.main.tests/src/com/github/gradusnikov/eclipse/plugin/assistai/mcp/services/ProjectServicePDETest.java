package com.github.gradusnikov.eclipse.plugin.assistai.mcp.services;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IWorkspace;
import org.eclipse.core.resources.IWorkspaceRoot;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.ILog;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.e4.core.contexts.ContextInjectionFactory;
import org.eclipse.e4.core.contexts.EclipseContextFactory;
import org.eclipse.e4.core.contexts.IEclipseContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.osgi.framework.BundleContext;
import org.osgi.framework.FrameworkUtil;
import org.osgi.util.tracker.ServiceTracker;

import com.github.gradusnikov.eclipse.assistai.Activator;
import com.github.gradusnikov.eclipse.assistai.mcp.services.ProjectService;
import com.github.gradusnikov.eclipse.assistai.services.AiIgnoreService;

public class ProjectServicePDETest {

    private ProjectService service;
    private IWorkspaceRoot root;
    private NullProgressMonitor monitor = new NullProgressMonitor();
    private Path tempDir;

    @BeforeEach
    public void beforeEach() throws CoreException, IOException {
        BundleContext bundleContext = FrameworkUtil.getBundle(ProjectServicePDETest.class).getBundleContext();
        ServiceTracker<IWorkspace, IWorkspace> workspaceTracker = new ServiceTracker<>(bundleContext, IWorkspace.class, null);
        workspaceTracker.open();
        IWorkspace workspace = workspaceTracker.getService();
        root = workspace.getRoot();

        IEclipseContext context = EclipseContextFactory.create();
        context.set(ILog.class, Activator.getDefault().getLog());
        context.set(AiIgnoreService.class, ContextInjectionFactory.make(AiIgnoreService.class, context));
        service = ContextInjectionFactory.make(ProjectService.class, context);

        tempDir = Files.createTempDirectory("projectServiceTest");
    }

    @AfterEach
    public void afterEach() throws CoreException, IOException {
        for (String name : new String[]{"ExistingProjectTest", "NoProjectFileTest"}) {
            IProject project = root.getProject(name);
            if (project.exists()) {
                project.delete(false, true, monitor);
            }
        }
        deleteRecursively(tempDir.toFile());
    }

    @Test
    public void testOpenProjectWithExistingProjectFile() throws IOException {
        Path projectDir = tempDir.resolve("ExistingProjectTest");
        Files.createDirectories(projectDir);

        String dotProject = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<projectDescription>\n"
                + "    <name>ExistingProjectTest</name>\n"
                + "    <comment></comment>\n"
                + "    <projects></projects>\n"
                + "    <buildSpec></buildSpec>\n"
                + "    <natures></natures>\n"
                + "</projectDescription>\n";
        Files.writeString(projectDir.resolve(".project"), dotProject);

        String result = service.openProject(projectDir.toAbsolutePath().toString());

        assertTrue(result.contains("imported and opened successfully"), "Expected success message, got: " + result);

        String projects = service.listProjects();
        assertTrue(projects.contains("ExistingProjectTest"), "Project should appear in listProjects");
    }

    @Test
    public void testOpenProjectWithoutProjectFile() throws IOException {
        Path projectDir = tempDir.resolve("NoProjectFileTest");
        Files.createDirectories(projectDir);
        Files.writeString(projectDir.resolve("hello.txt"), "hello world");

        assertFalse(Files.exists(projectDir.resolve(".project")), ".project should not exist before openProject");

        String result = service.openProject(projectDir.toAbsolutePath().toString());

        assertTrue(result.contains("imported and opened successfully"), "Expected success message, got: " + result);

        String projects = service.listProjects();
        assertTrue(projects.contains("NoProjectFileTest"), "Project should appear in listProjects");
    }

    @Test
    public void testOpenProjectNonExistentDirectory() {
        String result = service.openProject("/non/existent/path/xyz123");
        assertTrue(result.contains("Directory does not exist"), "Expected error about non-existent directory, got: " + result);
    }

    @Test
    public void testOpenProjectAlreadyOpen() throws IOException {
        Path projectDir = tempDir.resolve("NoProjectFileTest");
        Files.createDirectories(projectDir);

        service.openProject(projectDir.toAbsolutePath().toString());
        String result = service.openProject(projectDir.toAbsolutePath().toString());

        assertTrue(result.contains("already open"), "Expected already open message, got: " + result);
    }

    private void deleteRecursively(File file) {
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) {
                    deleteRecursively(child);
                }
            }
        }
        file.delete();
    }
}
