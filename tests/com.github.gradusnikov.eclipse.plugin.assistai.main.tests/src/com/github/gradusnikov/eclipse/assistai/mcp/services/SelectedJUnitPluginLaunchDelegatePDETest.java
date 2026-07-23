package com.github.gradusnikov.eclipse.assistai.mcp.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.util.List;

import org.eclipse.core.resources.IFolder;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IProjectDescription;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.debug.core.DebugPlugin;
import org.eclipse.debug.core.ILaunchConfigurationType;
import org.eclipse.debug.core.ILaunchConfigurationWorkingCopy;
import org.eclipse.jdt.core.IClasspathEntry;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.IMember;
import org.eclipse.jdt.core.IPackageFragment;
import org.eclipse.jdt.core.IPackageFragmentRoot;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.launching.IJavaLaunchConfigurationConstants;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class SelectedJUnitPluginLaunchDelegatePDETest
{
    private static final String TEST_PROJECT = "SelectedJUnitPluginLaunchDelegate_TestProject";

    private IProject            project;

    @BeforeEach
    public void createJavaProject() throws Exception
    {
        NullProgressMonitor monitor = new NullProgressMonitor();
        project = ResourcesPlugin.getWorkspace().getRoot().getProject( TEST_PROJECT );
        if ( project.exists() )
        {
            project.delete( true, true, monitor );
        }
        project.create( monitor );
        project.open( monitor );

        IProjectDescription description = project.getDescription();
        description.setNatureIds( new String[] { JavaCore.NATURE_ID } );
        project.setDescription( description, monitor );

        IFolder sourceFolder = project.getFolder( "src" );
        sourceFolder.create( true, true, monitor );
        IFolder outputFolder = project.getFolder( "bin" );
        outputFolder.create( true, true, monitor );

        IJavaProject javaProject = JavaCore.create( project );
        javaProject.setOutputLocation( outputFolder.getFullPath(), monitor );
        IClasspathEntry sourceEntry = JavaCore.newSourceEntry( sourceFolder.getFullPath() );
        javaProject.setRawClasspath( new IClasspathEntry[] { sourceEntry }, monitor );

        IPackageFragmentRoot sourceRoot = javaProject.getPackageFragmentRoot( sourceFolder );
        IPackageFragment packageFragment = sourceRoot.createPackageFragment( "example.selected", true, monitor );
        packageFragment.createCompilationUnit( "FirstPDETest.java", "package example.selected; public class FirstPDETest {}", true, monitor );
        packageFragment.createCompilationUnit( "SecondPDETest.java", "package example.selected; public class SecondPDETest {}", true, monitor );
    }

    @AfterEach
    public void deleteJavaProject() throws Exception
    {
        if ( project != null && project.exists() )
        {
            project.delete( true, true, new NullProgressMonitor() );
        }
    }

    @Test
    public void testEvaluateTests_resolvesEverySelectedClass() throws Exception
    {
        ILaunchConfigurationType type = DebugPlugin.getDefault().getLaunchManager()
                .getLaunchConfigurationType( SelectedJUnitPluginLaunchDelegate.LAUNCH_CONFIGURATION_TYPE );
        assertNotNull( type );

        ILaunchConfigurationWorkingCopy configuration = type.newInstance( null, "SelectedJUnitPluginLaunchDelegatePDETest" );
        configuration.setAttribute( IJavaLaunchConfigurationConstants.ATTR_PROJECT_NAME, TEST_PROJECT );
        configuration.setAttribute( SelectedJUnitPluginLaunchDelegate.ATTR_TEST_CLASSES,
                List.of( "example.selected.FirstPDETest", "example.selected.SecondPDETest" ) );

        IMember[] selected = new SelectedJUnitPluginLaunchDelegate().evaluateTests( configuration, new NullProgressMonitor() );

        assertEquals( 2, selected.length );
        assertEquals( "example.selected.FirstPDETest", ( (IType) selected[0] ).getFullyQualifiedName() );
        assertEquals( "example.selected.SecondPDETest", ( (IType) selected[1] ).getFullyQualifiedName() );
    }
}
