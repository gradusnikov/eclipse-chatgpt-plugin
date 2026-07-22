package com.github.gradusnikov.eclipse.assistai.mcp.services;

import java.util.ArrayList;
import java.util.List;

import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.Status;
import org.eclipse.debug.core.ILaunchConfiguration;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.IMember;
import org.eclipse.jdt.core.IType;
import org.eclipse.pde.launching.JUnitLaunchConfigurationDelegate;

/**
 * PDE JUnit launch delegate that resolves an explicit list of test classes.
 * <p>
 * The standard PDE launch configuration supports a single class or a Java
 * container. This delegate only changes test selection; all plug-in resolution
 * and launch behavior remains provided by PDE.
 */
public class SelectedJUnitPluginLaunchDelegate extends JUnitLaunchConfigurationDelegate
{
    public static final String LAUNCH_CONFIGURATION_TYPE = "com.github.gradusnikov.eclipse.assistai.selectedJUnitPluginTests";

    public static final String ATTR_TEST_CLASSES         = "com.github.gradusnikov.eclipse.assistai.selectedJUnitPluginTests.testClasses";

    @Override
    protected IMember[] evaluateTests( ILaunchConfiguration configuration, IProgressMonitor monitor ) throws CoreException
    {
        List<String> classNames = configuration.getAttribute( ATTR_TEST_CLASSES, List.of() );
        if ( classNames.isEmpty() )
        {
            return super.evaluateTests( configuration, monitor );
        }

        IJavaProject javaProject = getJavaProject( configuration );
        if ( javaProject == null )
        {
            throw new CoreException( Status.error( "The configured Java project was not found." ) );
        }
        List<IMember> testClasses = new ArrayList<>( classNames.size() );
        for ( String className : classNames )
        {
            IType testClass = javaProject.findType( className );
            if ( testClass == null )
            {
                throw new CoreException( Status.error( "Test class '" + className + "' was not found in project '" + javaProject.getElementName() + "'." ) );
            }
            testClasses.add( testClass );
        }
        return testClasses.toArray( IMember[]::new );
    }
}
