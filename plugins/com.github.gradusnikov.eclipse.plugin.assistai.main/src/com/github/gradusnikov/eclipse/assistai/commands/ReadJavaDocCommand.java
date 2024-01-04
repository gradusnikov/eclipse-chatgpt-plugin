package com.github.gradusnikov.eclipse.assistai.commands;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Predicate;

import javax.inject.Inject;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.ILog;
import org.eclipse.e4.core.di.annotations.Creatable;
import org.eclipse.jdt.core.IBuffer;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.IMember;
import org.eclipse.jdt.core.ISourceRange;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.core.JavaModelException;
import org.eclipse.jface.text.Document;

import org.eclipse.ui.editors.text.TextFileDocumentProvider;

@Creatable
public class ReadJavaDocCommand
{
    @Inject
    private ILog logger;
    
    
    public String getClassAttachedJavadoc( String fullyQualifiedClassName )
    {
        return getAvailableJavaProjects().stream()
                                          .map( project -> getAttachedJavadoc( fullyQualifiedClassName, project ) )
                                          .filter( Objects::nonNull )
                                          .filter( Predicate.not( String::isBlank ) )
                                          .findAny()
                                          .orElse( "JavaDoc is not available for " + fullyQualifiedClassName );
    }
    public String getClassAttachedSource( String fullyQualifiedClassName )
    {
        return getAvailableJavaProjects().stream()
                                          .map( project -> getAttachedSource( fullyQualifiedClassName, project ) )
                                          .filter( Objects::nonNull )
                                          .filter( Predicate.not( String::isBlank ) )
                                          .findAny()
                                          .orElse( "Source is not available for " + fullyQualifiedClassName );
    }
    
    public List<IJavaProject> getAvailableJavaProjects()
    {
        List<IJavaProject> javaProjects = new ArrayList<>();

        try
        {
            // Get all projects in the workspace
            IProject[] projects = ResourcesPlugin.getWorkspace().getRoot().getProjects();

            // Filter out the Java projects
            for ( IProject project : projects )
            {
                if ( project.isOpen() && project.hasNature( JavaCore.NATURE_ID ) )
                {
                    IJavaProject javaProject = JavaCore.create( project );
                    javaProjects.add( javaProject );
                }
            }
        }
        catch ( CoreException e )
        {
            throw new RuntimeException( e );
        }

        return javaProjects;
    }

    public String getAttachedJavadoc( String fullyQualifiedClassName, IJavaProject javaProject )
    {
        String javaDoc = "";
        try
        {
            IType type = javaProject.findType(fullyQualifiedClassName);
            if ( Objects.nonNull( type ) )
            {
                javaDoc += getMemberJavaDoc( (IMember) type );
                
                for ( IJavaElement child : type.getChildren() )
                {
                    javaDoc += getMemberJavaDoc( (IMember) child );
                }
            }
        }
        catch ( JavaModelException e )
        {
          logger.error( e.getMessage(), e );
        }
        return javaDoc;
    }
    
    
    
    private String getMemberJavaDoc(  IMember member ) throws JavaModelException
    {
        String javaDoc = "";
        String attachedJavaDoc = member.getAttachedJavadoc( null );
        if ( attachedJavaDoc != null )
        {
            javaDoc += attachedJavaDoc;
        }
        else
        {
            ISourceRange range = member.getJavadocRange();
            if ( range != null )
            {
                ICompilationUnit unit = member.getCompilationUnit();
                if ( unit != null )
                {
                    IBuffer buffer = unit.getBuffer();
                    javaDoc += buffer.getText( range.getOffset(), range.getLength() ) + "\n";
                }
            }
        }
        javaDoc += member.toString() + "\n";
        return javaDoc;
    }
       
    
    public String getAttachedSource( String fullyQualifiedClassName, IJavaProject javaProject )
    {
        try
        {
            // Find the type for the fully qualified class name
            IType type = javaProject.findType( fullyQualifiedClassName );
            if ( type == null )
            {
                return null;      
            }
            // Get the attached Javadoc
            IResource resource = type.getCorrespondingResource();
            if ( resource == null )
            {
                resource = type.getResource();
            }
            if ( resource == null )
            {
                resource = type.getUnderlyingResource();
            }
            // Check if the resource is a file
            if (resource instanceof IFile) 
            {
                IFile file = (IFile) resource;
                TextFileDocumentProvider provider = new TextFileDocumentProvider();
                provider.connect(file);
                Document document = (Document) provider.getDocument(file);
                String content = document.get();
                provider.disconnect(file);

                return content;
            }
        }
        catch ( Exception e )
        {
            logger.error( e.getMessage(), e );
            return null;
        }
        return null;
    }
}
