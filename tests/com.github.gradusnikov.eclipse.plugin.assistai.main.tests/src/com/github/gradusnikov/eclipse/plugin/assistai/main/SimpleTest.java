package com.github.gradusnikov.eclipse.plugin.assistai.main;


import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IFolder;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IProjectDescription;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IWorkspace;
import org.eclipse.core.resources.IWorkspaceRoot;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.ILog;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.Path;
import org.eclipse.core.runtime.Platform;
import org.eclipse.e4.core.contexts.ContextInjectionFactory;
import org.eclipse.e4.core.contexts.EclipseContextFactory;
import org.eclipse.e4.core.contexts.IEclipseContext;
import org.eclipse.jdt.core.IClasspathAttribute;
import org.eclipse.jdt.core.IClasspathEntry;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.IPackageFragment;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTParser;
import org.eclipse.jdt.core.dom.ASTVisitor;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.Javadoc;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.eclipse.jdt.core.dom.TypeDeclaration;
import org.eclipse.jdt.launching.JavaRuntime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.FrameworkUtil;
import org.osgi.util.tracker.ServiceTracker;

import com.github.gradusnikov.eclipse.assistai.Activator;
import com.github.gradusnikov.eclipse.assistai.commands.ReadJavaDocCommand;

/**
 * A simple test class for testing JavaDoc creation in Eclipse.
 * <p>
 * This class provides a setup for a test environment within Eclipse, including
 * creating a test project, adding a Java nature, setting the JRE path, and creating
 * a sample Java file within the project. It also includes methods for testing the JavaDoc
 * command and parsing the attached source code.
 */
public class SimpleTest 
{
    private ReadJavaDocCommand command;


    @BeforeEach
    public void beforeEach() throws CoreException, IOException, InterruptedException
    {
        BundleContext bundleContext = FrameworkUtil.getBundle(SimpleTest.class).getBundleContext();
        ServiceTracker<IWorkspace, IWorkspace> workspaceTracker = new ServiceTracker<>(bundleContext, IWorkspace.class, null);

        workspaceTracker.open();
        IWorkspace workspace = workspaceTracker.getService();
        IWorkspaceRoot root = workspace.getRoot();
        
        // Create a project
        IProject project = root.getProject("Test Project");
        if (!project.exists()) {
          IProjectDescription desc = project.getWorkspace().newProjectDescription(project.getName());
          desc.setNatureIds(new String[] {JavaCore.NATURE_ID}); // set Java nature
          project.create(desc, null);
        }
        if (!project.isOpen()) {
          project.open(null);
        }
        // add javadoc location for JDK
        IJavaProject javaProject = JavaCore.create( project );
        
        IPath jrePath = new Path(JavaRuntime.JRE_CONTAINER);
//        IClasspathEntry jreEntry = JavaCore.newContainerEntry(jrePath);

        IPath pathToDoc = new Path("https://docs.oracle.com/en/java/javase/17/docs/api/"); // replace with the actual path

        IClasspathAttribute[] extraAttributes = new IClasspathAttribute[] {
            JavaCore.newClasspathAttribute(IClasspathAttribute.JAVADOC_LOCATION_ATTRIBUTE_NAME, pathToDoc.toString())
        };

        IClasspathEntry newJreEntry = JavaCore.newContainerEntry(jrePath, null, extraAttributes, false);

        IClasspathEntry[] oldEntries = javaProject.getRawClasspath();
        List<IClasspathEntry> newEntries = new ArrayList<>(Arrays.asList(oldEntries));
        
        boolean foundJRE = false;
        for (int i = 0; i < oldEntries.length; i++) {
            if (oldEntries[i].getPath().equals(jrePath)) {
                newEntries.set(i,newJreEntry);
                foundJRE = true;
                break;
            }
        }
        if (!foundJRE) {
            newEntries.add(newJreEntry); // If the JRE entry was not found, add it.
        }
        javaProject.setRawClasspath(newEntries.toArray(new IClasspathEntry[0]), null);
        
        // Create a folder
        IFolder srcFolder = project.getFolder("src");
        if (!srcFolder.exists()) {
            srcFolder.create(IResource.NONE, true, null);
        }

        // Create a folder for the package
        IFolder packageFolder = srcFolder.getFolder("com");
        if (!packageFolder.exists()) {
            packageFolder.create(IResource.NONE, true, null);
        }
        packageFolder = packageFolder.getFolder("example");
        if (!packageFolder.exists()) {
            packageFolder.create(IResource.NONE, true, null);
        }
        // Create a file
        IFile file = packageFolder.getFile("Test.java");
        if (!file.exists()) {
            
            String classBody = """
                    package src.com.example;
                    /**
                     * Class comment
                     */
                    public class Test 
                    {
                        /**
                         * Method returns 1
                         */
                        public int testMethod()
                        {
                            return 1;
                        }
                        /**
                         * Method returns 2
                         */
                        public int testMethod2()
                        {
                            return 2;
                        }                        
                    }
                    """;
            
            try ( ByteArrayInputStream source = new ByteArrayInputStream(classBody.getBytes()) )
            {
                file.create(source, IResource.NONE, null);
            }
        }
    }
    
    @Test
    public void test() throws Exception
    {
        beforeEach();
        
        Bundle bundle = Platform.getBundle("org.eclipse.egit.ui");
        bundle.start(Bundle.START_TRANSIENT);

        IEclipseContext context = EclipseContextFactory.create(); 
        context.set( ILog.class, Activator.getDefault().getLog() );
        command = ContextInjectionFactory.make( ReadJavaDocCommand.class, context );
        
        System.err.println( command.getAvailableJavaProjects() );
        command.getAvailableJavaProjects().stream().forEach( System.out::println );
        
        IJavaProject project = command.getAvailableJavaProjects().get(0);
        project.open( null );
        
        System.out.println( "project name: " + project.getElementName() );
        System.out.println( "isOpen: " + project.isOpen() );
        
        List<IFile> javaFiles = new ArrayList<>();
        findJavaFiles(project.getProject().members(), javaFiles);
        for ( IPackageFragment packageFragmentRoot : project.getPackageFragments() )
        {
            for ( ICompilationUnit unit : packageFragmentRoot.getCompilationUnits() )
            {
                System.out.println( "compilation unit: " + unit.getElementName() );
                System.out.println( "types: " +  Arrays.stream( unit.getTypes() ).map(IType::getFullyQualifiedName).collect( Collectors.joining( "\n" ) ));
            }
        }
        
        IType type = project.findType( "src.com.example.Test" );
        for( IJavaElement e : type.getChildren() )
        {
            System.out.println( "member: " + e );
        }
        System.out.println( "type: " + type );
        System.out.println(  javaFiles );
        System.out.println( "src: " + command.getAttachedSource( "src.com.example.Test", project ) );
        System.out.println( "javadoc: " + command.getAttachedJavadoc( "src.com.example.Test", project ) );
        
        ASTParser parser = ASTParser.newParser(AST.JLS20);
        parser.setSource(command.getAttachedSource( "src.com.example.Test", project ).toCharArray());
        parser.setKind(ASTParser.K_COMPILATION_UNIT);
        parser.setResolveBindings(true);

        CompilationUnit cu = (CompilationUnit) parser.createAST(null);
        cu.accept(new ASTVisitor() {
            public boolean visit(MethodDeclaration node) {
                Javadoc javadoc = node.getJavadoc();
                if (javadoc != null) {
                    System.out.println("javadoc from source: \n" + javadoc);
                }
                return false;
            }
            public boolean visit(TypeDeclaration node) {
                Javadoc javadoc = node.getJavadoc();
                if (javadoc != null) {
                    System.out.println("javadoc from source: \n" + javadoc);
                }
                return true;
            }
        });
        System.out.println( "src: " + command.getAttachedSource( "java.lang.System", project ) );
        System.out.println( "javadoc: " + command.getAttachedJavadoc( "java.util.Map", project ) );
    }
    
    
    private void findJavaFiles( IResource[] resources, List<IFile> javaFiles ) throws CoreException
    {
        for ( IResource res : resources )
        {
            if ( res instanceof IFile && res.getName().endsWith( ".java" ) )
            {
                javaFiles.add( (IFile) res );
            }
            else if ( res instanceof IFolder )
            {
                findJavaFiles( ( (IFolder) res ).members(), javaFiles ); // Recursive call
            }
        }
    }
}
