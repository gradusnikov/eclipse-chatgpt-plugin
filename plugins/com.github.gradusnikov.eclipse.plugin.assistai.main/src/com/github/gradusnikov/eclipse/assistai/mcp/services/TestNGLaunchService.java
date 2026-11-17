package com.github.gradusnikov.eclipse.assistai.mcp.services;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.ILog;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.Platform;
import org.eclipse.debug.core.DebugPlugin;
import org.eclipse.debug.core.ILaunchConfiguration;
import org.eclipse.debug.core.ILaunchConfigurationType;
import org.eclipse.debug.core.ILaunchConfigurationWorkingCopy;
import org.eclipse.debug.core.ILaunchManager;
import org.eclipse.e4.core.di.annotations.Creatable;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.IMethod;
import org.eclipse.jdt.core.IPackageFragment;
import org.eclipse.jdt.core.IPackageFragmentRoot;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.core.JavaModelException;
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.ASTParser;
import org.eclipse.jdt.core.dom.ASTRequestor;
import org.eclipse.jdt.core.dom.ASTVisitor;
import org.eclipse.jdt.core.dom.AbstractTypeDeclaration;
import org.eclipse.jdt.core.dom.ArrayInitializer;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.Expression;
import org.eclipse.jdt.core.dom.IAnnotationBinding;
import org.eclipse.jdt.core.dom.ITypeBinding;
import org.eclipse.jdt.core.dom.MemberValuePair;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.eclipse.jdt.core.dom.Modifier;
import org.eclipse.jdt.core.dom.NormalAnnotation;
import org.eclipse.jdt.core.dom.StringLiteral;
import org.eclipse.jdt.core.dom.TypeDeclaration;
import org.eclipse.jdt.launching.IJavaLaunchConfigurationConstants;

import com.github.gradusnikov.eclipse.assistai.mcp.results.Diagnostic;
import com.github.gradusnikov.eclipse.assistai.mcp.results.DiagnosticCode;
import com.github.gradusnikov.eclipse.assistai.mcp.results.TestNGLaunchConfigurationResponse;

import jakarta.inject.Inject;

/**
 * Creates the TestNG launch configuration Eclipse's "Run As &gt; TestNG Test" saves, without
 * launching it.
 * <p>
 * Deliberately has no dependency on the {@code org.testng.eclipse} bundle: the launch type
 * and its attributes are addressed by their string identifiers, mirroring
 * {@code TestNGLaunchConfigurationConstants} upstream, so AssistAI still loads where TestNG
 * for Eclipse is not installed and reports that case as a diagnostic instead. Upstream's
 * public {@code LaunchUtil} entry points are not used either: each one saves <em>and</em>
 * launches through {@code DebugUITools}.
 * <p>
 * The attributes written for each target are the ones upstream writes for it
 * ({@code LaunchUtil.launchTypeBasedConfiguration}, {@code launchMethodBasedConfiguration},
 * {@code launchPackageConfiguration}, {@code launchSuiteConfiguration}, and for groups -
 * which have no shortcut - {@code ConfigurationHelper.updateLaunchConfiguration}, the
 * dialog's save). Target attributes a target does not use are removed rather than left
 * over, so re-targeting an existing configuration cannot leave it running a stale package
 * or class list alongside the new target; on a new configuration that is exactly what
 * upstream produces, since it never writes them.
 */
@Creatable
public class TestNGLaunchService
{
    static final String TESTNG_LAUNCH_TYPE_ID = "org.testng.eclipse.launchconfig";
    static final String REMOTE_TESTNG_MAIN = "org.testng.remote.RemoteTestNG";

    private static final String P = "org.testng.eclipse.";
    static final String ATTR_TYPE = P + "TYPE";
    static final String ATTR_CLASS_TEST_LIST = P + "CLASS_TEST_LIST";
    static final String ATTR_METHOD_TEST_LIST = P + "METHOD_TEST_LIST";
    static final String ATTR_PACKAGE_TEST_LIST = P + "PACKAGE_TEST_LIST";
    static final String ATTR_SUITE_TEST_LIST = P + "SUITE_TEST_LIST";
    static final String ATTR_GROUP_LIST = P + "GROUP_LIST";
    static final String ATTR_GROUP_CLASS_LIST = P + "GROUP_LIST_CLASS";
    static final String ATTR_ALL_METHODS_LIST = P + "ALL_CLASS_METHODS";
    static final String ATTR_PRE_DEFINED_LISTENERS = P + "PRE_DEFINED_LISTENERS";

    /** Every target attribute; whichever a target does not write is removed. */
    private static final List<String> TARGET_ATTRIBUTES = List.of( ATTR_CLASS_TEST_LIST, ATTR_METHOD_TEST_LIST,
            ATTR_PACKAGE_TEST_LIST, ATTR_SUITE_TEST_LIST, ATTR_GROUP_LIST, ATTR_GROUP_CLASS_LIST,
            ATTR_ALL_METHODS_LIST );

    /** TestNG for Eclipse's preference node and the key of its pre-defined listeners. */
    private static final String TESTNG_PREFERENCE_NODE = "org.testng.eclipse";
    private static final String PREF_PRE_DEFINED_LISTENERS = "preDefinedListeners";

    private static final String TESTNG_TEST_ANNOTATION = "org.testng.annotations.Test";

    /**
     * Values of {@code org.testng.eclipse.TYPE}. Upstream writes {@code LaunchType.X.ordinal()},
     * and its enum opens with {@code UNDEFINED}, so the ordinals happen to be these numbers;
     * they are written as literals so nothing here depends on that enum's order.
     */
    enum TestNGLaunchType
    {
        CLASS( 1 ), GROUP( 2 ), SUITE( 3 ), METHOD( 4 ), PACKAGE( 5 );

        final int value;

        TestNGLaunchType( int value )
        {
            this.value = value;
        }
    }

    @Inject
    ILog logger;

    /**
     * Creates, or updates in place, a TestNG launch configuration and saves it.
     * <p>
     * Exactly one target must be given: {@code className} (optionally with
     * {@code methodName}), {@code packageName}, {@code suiteFile} (project-relative), or
     * {@code groups} (comma-separated). Arguments and the target are checked before the
     * TestNG launch type is looked up, so a malformed request is reported as such whether
     * or not TestNG for Eclipse is installed.
     */
    public TestNGLaunchConfigurationResponse createLaunchConfiguration( String projectName, String className,
            String methodName, String packageName, String suiteFile, String groups, String configurationName,
            String vmArgs, String programArgs )
    {
        try
        {
            IJavaProject javaProject = resolveJavaProject( projectName );
            Target target = resolveTarget( javaProject, className, methodName, packageName, suiteFile, groups );

            String name = notBlank( configurationName ) ? configurationName.trim() : target.defaultName();
            ILaunchManager launchManager = DebugPlugin.getDefault().getLaunchManager();
            validateName( launchManager, name );

            ILaunchConfigurationType type = launchManager.getLaunchConfigurationType( TESTNG_LAUNCH_TYPE_ID );
            ILaunchConfiguration existing = findByName( launchManager, name );
            if ( existing != null )
            {
                rejectUnlessTestNG( existing, name, type );
            }
            if ( type == null )
            {
                throw new Rejected( Diagnostic.fatal( DiagnosticCode.TESTNG_LAUNCH_TYPE_MISSING,
                        "TestNG for Eclipse is not installed: there is no '" + TESTNG_LAUNCH_TYPE_ID
                                + "' launch configuration type. Install it from the Eclipse Marketplace or the "
                                + "update site https://testng.org/testng-eclipse-update-site and restart Eclipse." ) );
            }

            boolean created = existing == null;
            ILaunchConfigurationWorkingCopy wc = created ? type.newInstance( null, name ) : existing.getWorkingCopy();
            String project = javaProject.getElementName();
            write( wc, project, target, vmArgs, programArgs );
            wc.doSave();

            return TestNGLaunchConfigurationResponse.success( name, created, project, target.type().name(),
                    target.classes(), target.methods(), target.packages(), target.suites(), target.groups(),
                    target.groupClasses() );
        }
        catch ( Rejected e )
        {
            return TestNGLaunchConfigurationResponse.failure( projectName, e.diagnostic );
        }
        catch ( CoreException e )
        {
            logger.error( "Error creating TestNG launch configuration", e );
            return TestNGLaunchConfigurationResponse.failure( projectName,
                    Diagnostic.fatal( DiagnosticCode.INTERNAL_ERROR,
                            "The TestNG launch configuration could not be saved: " + e.getMessage() ) );
        }
    }

    // --- target resolution ---

    /**
     * What is to run, already in the form it is written to the configuration.
     *
     * @param classMethods {@code ALL_CLASS_METHODS}: class to {@code ';'}-joined method names
     */
    private record Target( TestNGLaunchType type, String defaultName, List<String> classes, List<String> methods,
            List<String> packages, List<String> suites, List<String> groups, List<String> groupClasses,
            Map<String, String> classMethods )
    {
    }

    private Target resolveTarget( IJavaProject javaProject, String className, String methodName, String packageName,
            String suiteFile, String groups ) throws Rejected, CoreException
    {
        boolean hasClass = notBlank( className );
        boolean hasMethod = notBlank( methodName );
        boolean hasPackage = notBlank( packageName );
        boolean hasSuite = notBlank( suiteFile );
        boolean hasGroups = notBlank( groups );
        int targets = ( hasClass ? 1 : 0 ) + ( hasPackage ? 1 : 0 ) + ( hasSuite ? 1 : 0 ) + ( hasGroups ? 1 : 0 );
        if ( targets != 1 || ( hasMethod && !hasClass ) )
        {
            throw new Rejected( Diagnostic.fatal( DiagnosticCode.VALIDATION_ERROR,
                    "Give exactly one target: className (optionally with methodName), packageName, suiteFile, "
                            + "or groups. methodName requires className." ) );
        }
        if ( hasClass )
        {
            return hasMethod ? methodTarget( javaProject, className.trim(), methodName.trim() )
                    : classTarget( javaProject, className.trim() );
        }
        if ( hasPackage )
        {
            return packageTarget( javaProject, packageName.trim() );
        }
        if ( hasSuite )
        {
            return suiteTarget( javaProject, suiteFile.trim() );
        }
        return groupTarget( javaProject, groups );
    }

    /** Upstream {@code launchTypeConfiguration}: named after the simple class name. */
    private Target classTarget( IJavaProject javaProject, String className ) throws Rejected, JavaModelException
    {
        IType type = findTestType( javaProject, className );
        return new Target( TestNGLaunchType.CLASS, type.getElementName(), List.of( type.getFullyQualifiedName() ),
                List.of(), List.of(), List.of(), List.of(), List.of(), Map.of() );
    }

    /** Upstream {@code launchMethodBasedConfiguration}: named {@code Class.method}. */
    private Target methodTarget( IJavaProject javaProject, String className, String methodName )
            throws Rejected, JavaModelException
    {
        IType type = findTestType( javaProject, className );
        // TestNG selects methods by name alone, so a JDT-style "name(params)" is reduced to the name.
        int paren = methodName.indexOf( '(' );
        String method = ( paren >= 0 ? methodName.substring( 0, paren ) : methodName ).trim();
        if ( !declaresOrInheritsMethod( type, method ) )
        {
            throw new Rejected( Diagnostic.fatal( DiagnosticCode.RESOURCE_NOT_FOUND,
                    "Method '" + method + "' not found in class '" + type.getFullyQualifiedName()
                            + "' or its superclasses." ) );
        }
        String fqn = type.getFullyQualifiedName();
        return new Target( TestNGLaunchType.METHOD, type.getTypeQualifiedName() + "." + method, List.of( fqn ),
                List.of( method ), List.of(), List.of(), List.of(), List.of(), Map.of( fqn, method ) );
    }

    /** Upstream {@code launchPackageConfiguration}: named {@code "package " + name}. */
    private Target packageTarget( IJavaProject javaProject, String packageName ) throws Rejected, JavaModelException
    {
        if ( !sourcePackageExists( javaProject, packageName ) )
        {
            throw new Rejected( Diagnostic.fatal( DiagnosticCode.TEST_PACKAGE_NOT_FOUND,
                    "Package '" + packageName + "' not found in the source folders of '"
                            + javaProject.getElementName() + "'." ) );
        }
        return new Target( TestNGLaunchType.PACKAGE, "package " + packageName, List.of(), List.of(),
                List.of( packageName ), List.of(), List.of(), List.of(), Map.of() );
    }

    /**
     * Upstream {@code launchSuiteConfiguration}: stores the suite's absolute file system path
     * and names the configuration {@code <project>_<project-relative path, '/' as '.'>}.
     */
    private Target suiteTarget( IJavaProject javaProject, String suiteFile ) throws Rejected
    {
        IProject project = javaProject.getProject();
        IFile file = project.getFile( IPath.fromPortableString( suiteFile.replace( '\\', '/' ) ) );
        if ( !file.exists() || file.getLocation() == null )
        {
            throw new Rejected( Diagnostic.fatal( DiagnosticCode.RESOURCE_NOT_FOUND,
                    "Suite file '" + suiteFile + "' not found in project '" + project.getName()
                            + "'. Give a project-relative path, e.g. 'src/test/resources/testng.xml'." ) );
        }
        String defaultName = project.getName() + "_" + file.getProjectRelativePath().toString().replace( '/', '.' );
        return new Target( TestNGLaunchType.SUITE, defaultName, List.of(), List.of(), List.of(),
                List.of( file.getLocation().toOSString() ), List.of(), List.of(), Map.of() );
    }

    /**
     * What the launch dialog saves for a group run ({@code updateLaunchConfiguration}). The
     * group runner builds a suite whose classes are exactly {@code GROUP_LIST_CLASS}, so those
     * classes have to be found - as the dialog's group selector finds them - or the run would
     * select from no classes at all and execute nothing.
     */
    private Target groupTarget( IJavaProject javaProject, String groups ) throws Rejected, CoreException
    {
        List<String> groupNames = splitGroups( groups );
        if ( groupNames.isEmpty() )
        {
            throw new Rejected( Diagnostic.fatal( DiagnosticCode.VALIDATION_ERROR,
                    "groups names no group: give one or more comma-separated group names." ) );
        }
        Map<String, Set<String>> declaringClasses = findGroupDeclaringClasses( javaProject );
        List<String> undeclared = groupNames.stream().filter( g -> !declaringClasses.containsKey( g ) ).toList();
        if ( !undeclared.isEmpty() )
        {
            String known = declaringClasses.isEmpty() ? "none" : String.join( ", ", new TreeSet<>( declaringClasses.keySet() ) );
            throw new Rejected( Diagnostic.fatal( DiagnosticCode.RESOURCE_NOT_FOUND,
                    "No TestNG test class in project '" + javaProject.getElementName()
                            + "' or the projects it requires declares group(s) " + undeclared
                            + ". Groups declared there: " + known + "." ) );
        }
        Set<String> classes = new TreeSet<>();
        groupNames.forEach( g -> classes.addAll( declaringClasses.get( g ) ) );
        Map<String, String> classMethods = new LinkedHashMap<>();
        classes.forEach( c -> classMethods.put( c, "" ) );
        String defaultName = javaProject.getElementName() + " groups " + String.join( ",", groupNames );
        return new Target( TestNGLaunchType.GROUP, defaultName, List.of(), List.of(), List.of(), List.of(),
                groupNames, List.copyOf( classes ), classMethods );
    }

    // --- writing ---

    private void write( ILaunchConfigurationWorkingCopy wc, String projectName, Target target, String vmArgs,
            String programArgs )
    {
        wc.setAttribute( IJavaLaunchConfigurationConstants.ATTR_PROJECT_NAME, projectName );
        wc.setAttribute( IJavaLaunchConfigurationConstants.ATTR_MAIN_TYPE_NAME, REMOTE_TESTNG_MAIN );
        wc.setAttribute( ATTR_TYPE, target.type().value );

        TARGET_ATTRIBUTES.forEach( wc::removeAttribute );
        switch ( target.type() )
        {
            case CLASS, METHOD, PACKAGE ->
            {
                wc.setAttribute( ATTR_CLASS_TEST_LIST, new ArrayList<>( target.classes() ) );
                wc.setAttribute( ATTR_METHOD_TEST_LIST, new ArrayList<>( target.methods() ) );
                wc.setAttribute( ATTR_PACKAGE_TEST_LIST, new ArrayList<>( target.packages() ) );
                wc.setAttribute( ATTR_ALL_METHODS_LIST, new LinkedHashMap<>( target.classMethods() ) );
                // Only these three shortcuts write the listeners; the suite shortcut and the
                // dialog leave whatever is there.
                wc.setAttribute( ATTR_PRE_DEFINED_LISTENERS, preDefinedListeners( projectName ) );
            }
            case SUITE -> wc.setAttribute( ATTR_SUITE_TEST_LIST, new ArrayList<>( target.suites() ) );
            case GROUP ->
            {
                wc.setAttribute( ATTR_CLASS_TEST_LIST, new ArrayList<String>() );
                wc.setAttribute( ATTR_PACKAGE_TEST_LIST, new ArrayList<String>() );
                wc.setAttribute( ATTR_GROUP_LIST, new ArrayList<>( target.groups() ) );
                wc.setAttribute( ATTR_GROUP_CLASS_LIST, new ArrayList<>( target.groupClasses() ) );
                wc.setAttribute( ATTR_ALL_METHODS_LIST, new LinkedHashMap<>( target.classMethods() ) );
            }
        }

        if ( vmArgs != null )
        {
            wc.setAttribute( IJavaLaunchConfigurationConstants.ATTR_VM_ARGUMENTS, vmArgs );
        }
        if ( programArgs != null )
        {
            wc.setAttribute( IJavaLaunchConfigurationConstants.ATTR_PROGRAM_ARGUMENTS, programArgs );
        }
    }

    /**
     * The listeners TestNG's preferences pre-define for the project, falling back to the
     * workspace-wide setting, as upstream's {@code PreferenceStoreUtil.getPreDefinedListeners}
     * reads them. Read from the preference node by name, so no TestNG class is needed.
     */
    private static String preDefinedListeners( String projectName )
    {
        var preferences = Platform.getPreferencesService();
        String projectValue = preferences.getString( TESTNG_PREFERENCE_NODE,
                projectName + PREF_PRE_DEFINED_LISTENERS, "", null );
        String value = projectValue.isBlank()
                ? preferences.getString( TESTNG_PREFERENCE_NODE, PREF_PRE_DEFINED_LISTENERS, "", null )
                : projectValue;
        return value.trim();
    }

    // --- lookups ---

    private static IJavaProject resolveJavaProject( String projectName ) throws Rejected, CoreException
    {
        if ( !notBlank( projectName ) )
        {
            throw new Rejected( Diagnostic.fatal( DiagnosticCode.VALIDATION_ERROR, "projectName is required." ) );
        }
        IProject project = ResourcesPlugin.getWorkspace().getRoot().getProject( projectName.trim() );
        if ( !project.exists() )
        {
            throw new Rejected( Diagnostic.fatal( DiagnosticCode.PROJECT_NOT_FOUND,
                    "Project '" + projectName + "' does not exist. Use listProjects to find it." ) );
        }
        if ( !project.isOpen() )
        {
            throw new Rejected( Diagnostic.retryable( DiagnosticCode.PROJECT_NOT_FOUND,
                    "Project '" + projectName + "' is closed." ) );
        }
        if ( !project.hasNature( JavaCore.NATURE_ID ) )
        {
            throw new Rejected( Diagnostic.fatal( DiagnosticCode.PROJECT_NOT_FOUND,
                    "Project '" + projectName + "' is not a Java project." ) );
        }
        return JavaCore.create( project );
    }

    private static IType findTestType( IJavaProject javaProject, String className )
            throws Rejected, JavaModelException
    {
        IType type = javaProject.findType( className );
        if ( type == null || !type.exists() )
        {
            throw new Rejected( Diagnostic.fatal( DiagnosticCode.TEST_CLASS_NOT_FOUND,
                    "Class '" + className + "' not found in project '" + javaProject.getElementName() + "'." ) );
        }
        return type;
    }

    /** TestNG runs inherited test methods too, so the superclasses count. */
    private static boolean declaresOrInheritsMethod( IType type, String methodName ) throws JavaModelException
    {
        if ( hasMethodNamed( type, methodName ) )
        {
            return true;
        }
        for ( IType superclass : type.newSupertypeHierarchy( null ).getAllSuperclasses( type ) )
        {
            if ( hasMethodNamed( superclass, methodName ) )
            {
                return true;
            }
        }
        return false;
    }

    private static boolean hasMethodNamed( IType type, String methodName ) throws JavaModelException
    {
        for ( IMethod m : type.getMethods() )
        {
            if ( m.getElementName().equals( methodName ) )
            {
                return true;
            }
        }
        return false;
    }

    private static boolean sourcePackageExists( IJavaProject javaProject, String packageName )
            throws JavaModelException
    {
        for ( IPackageFragmentRoot root : javaProject.getPackageFragmentRoots() )
        {
            if ( root.getKind() == IPackageFragmentRoot.K_SOURCE && root.getPackageFragment( packageName ).exists() )
            {
                return true;
            }
        }
        return false;
    }

    private static void validateName( ILaunchManager launchManager, String name ) throws Rejected
    {
        try
        {
            launchManager.isValidLaunchConfigurationName( name );
        }
        catch ( IllegalArgumentException e )
        {
            throw new Rejected( Diagnostic.fatal( DiagnosticCode.VALIDATION_ERROR,
                    "'" + name + "' cannot be a launch configuration name: " + e.getMessage()
                            + " Pass a different configurationName." ) );
        }
    }

    /**
     * A configuration of another type is the user's and is never overwritten. One whose type
     * cannot even be resolved - a TestNG configuration left behind after TestNG was uninstalled
     * reads like that - is let through only for the missing-TestNG diagnostic to report.
     */
    private static void rejectUnlessTestNG( ILaunchConfiguration existing, String name,
            ILaunchConfigurationType testNGType ) throws Rejected
    {
        String typeId;
        String typeName;
        try
        {
            ILaunchConfigurationType existingType = existing.getType();
            typeId = existingType.getIdentifier();
            typeName = existingType.getName();
        }
        catch ( CoreException e )
        {
            if ( testNGType == null )
            {
                return;
            }
            typeId = null;
            typeName = "an unknown type";
        }
        if ( !TESTNG_LAUNCH_TYPE_ID.equals( typeId ) )
        {
            throw new Rejected( Diagnostic.fatal( DiagnosticCode.RESOURCE_ALREADY_EXISTS,
                    "A launch configuration named '" + name + "' already exists with type '" + typeName
                            + "' and is left unchanged. Pass a different configurationName." ) );
        }
    }

    private static ILaunchConfiguration findByName( ILaunchManager launchManager, String name ) throws CoreException
    {
        for ( ILaunchConfiguration config : launchManager.getLaunchConfigurations() )
        {
            if ( config.getName().equals( name ) )
            {
                return config;
            }
        }
        return null;
    }

    // --- groups ---

    /** Comma-separated, as upstream's {@code StringUtils.stringToList} splits; order kept, duplicates dropped. */
    private static List<String> splitGroups( String groups )
    {
        return List.copyOf( new LinkedHashSet<>( Arrays.stream( groups.split( "," ) ).map( String::trim )
                .filter( s -> !s.isEmpty() ).toList() ) );
    }

    /**
     * Group name to the concrete classes declaring it in a TestNG {@code @Test(groups=...)},
     * at class or method level, across the project and the projects it requires - the scope
     * and the rule of upstream's {@code GroupSelector}, which parses each test type with
     * {@code TypeParser}/{@code AnnotationVisitor}. Abstract classes are skipped, as upstream's
     * test search skips them: TestNG cannot instantiate them.
     */
    Map<String, Set<String>> findGroupDeclaringClasses( IJavaProject javaProject ) throws CoreException
    {
        Map<String, Set<String>> result = new LinkedHashMap<>();
        for ( IJavaProject project : projectAndRequired( javaProject ) )
        {
            List<ICompilationUnit> units = new ArrayList<>();
            for ( IPackageFragmentRoot root : project.getPackageFragmentRoots() )
            {
                if ( root.getKind() != IPackageFragmentRoot.K_SOURCE || !root.getJavaProject().equals( project ) )
                {
                    continue;
                }
                for ( var child : root.getChildren() )
                {
                    for ( ICompilationUnit unit : ( (IPackageFragment) child ).getCompilationUnits() )
                    {
                        // Cheap pre-filter: a unit that never says "groups" declares none.
                        String source = unit.getSource();
                        if ( source != null && source.contains( "groups" ) )
                        {
                            units.add( unit );
                        }
                    }
                }
            }
            if ( units.isEmpty() )
            {
                continue;
            }
            ASTParser parser = ASTParser.newParser( AST.getJLSLatest() );
            parser.setProject( project );
            parser.setResolveBindings( true );
            parser.createASTs( units.toArray( ICompilationUnit[]::new ), new String[0], new ASTRequestor()
            {
                @Override
                public void acceptAST( ICompilationUnit source, CompilationUnit ast )
                {
                    ast.accept( new GroupCollector( result ) );
                }
            }, null );
        }
        return result;
    }

    private static List<IJavaProject> projectAndRequired( IJavaProject javaProject ) throws JavaModelException
    {
        List<IJavaProject> projects = new ArrayList<>();
        projects.add( javaProject );
        var model = JavaCore.create( ResourcesPlugin.getWorkspace().getRoot() );
        for ( String required : javaProject.getRequiredProjectNames() )
        {
            IJavaProject p = model.getJavaProject( required );
            if ( p != null && p.exists() && p.getProject().isOpen() )
            {
                projects.add( p );
            }
        }
        return projects;
    }

    /** Collects the groups of TestNG {@code @Test} annotations into their concrete enclosing class. */
    private static final class GroupCollector extends ASTVisitor
    {
        private final Map<String, Set<String>> result;

        GroupCollector( Map<String, Set<String>> result )
        {
            this.result = result;
        }

        @Override
        public boolean visit( NormalAnnotation node )
        {
            ASTNode parent = node.getParent();
            if ( !( parent instanceof MethodDeclaration || parent instanceof TypeDeclaration )
                    || !isTestNGTest( node ) )
            {
                return false;
            }
            TypeDeclaration declaringType = enclosingType( node );
            if ( declaringType == null || declaringType.isInterface()
                    || Modifier.isAbstract( declaringType.getModifiers() ) )
            {
                return false;
            }
            String className = binaryName( declaringType );
            if ( className == null )
            {
                return false;
            }
            for ( Object o : node.values() )
            {
                MemberValuePair pair = (MemberValuePair) o;
                if ( "groups".equals( pair.getName().getIdentifier() ) )
                {
                    for ( String value : groupValues( pair.getValue() ) )
                    {
                        for ( String group : value.split( "," ) )
                        {
                            String g = group.trim();
                            if ( !g.isEmpty() )
                            {
                                result.computeIfAbsent( g, k -> new TreeSet<>() ).add( className );
                            }
                        }
                    }
                }
            }
            return false;
        }

        /**
         * The resolved annotation type when bindings resolve; otherwise upstream's own test,
         * a type name of {@code Test} or ending in {@code .Test}.
         */
        private static boolean isTestNGTest( NormalAnnotation node )
        {
            IAnnotationBinding binding = node.resolveAnnotationBinding();
            if ( binding != null && binding.getAnnotationType() != null )
            {
                return TESTNG_TEST_ANNOTATION.equals( binding.getAnnotationType().getQualifiedName() );
            }
            String name = node.getTypeName().getFullyQualifiedName();
            return "Test".equals( name ) || name.endsWith( ".Test" );
        }

        private static List<String> groupValues( Expression value )
        {
            List<String> values = new ArrayList<>();
            if ( value instanceof ArrayInitializer array )
            {
                for ( Object e : array.expressions() )
                {
                    addConstant( values, (Expression) e );
                }
            }
            else
            {
                addConstant( values, value );
            }
            return values;
        }

        private static void addConstant( List<String> values, Expression e )
        {
            if ( e instanceof StringLiteral literal )
            {
                values.add( literal.getLiteralValue() );
                return;
            }
            Object constant = e.resolveConstantExpressionValue();
            if ( constant instanceof String s )
            {
                values.add( s );
            }
        }

        private static TypeDeclaration enclosingType( ASTNode node )
        {
            for ( ASTNode n = node.getParent(); n != null; n = n.getParent() )
            {
                if ( n instanceof AbstractTypeDeclaration type )
                {
                    return type instanceof TypeDeclaration td ? td : null;
                }
            }
            return null;
        }

        /** The name TestNG loads the class by, {@code $} for nested classes as JDT's {@code IType} spells it. */
        private static String binaryName( TypeDeclaration type )
        {
            ITypeBinding binding = type.resolveBinding();
            if ( binding != null && binding.getBinaryName() != null )
            {
                return binding.getBinaryName();
            }
            StringBuilder name = new StringBuilder( type.getName().getIdentifier() );
            ASTNode n = type.getParent();
            for ( ; n instanceof AbstractTypeDeclaration outer; n = n.getParent() )
            {
                name.insert( 0, outer.getName().getIdentifier() + "$" );
            }
            if ( n instanceof CompilationUnit cu && cu.getPackage() != null )
            {
                name.insert( 0, cu.getPackage().getName().getFullyQualifiedName() + "." );
            }
            return name.toString();
        }
    }

    // --- plumbing ---

    private static boolean notBlank( String s )
    {
        return s != null && !s.isBlank();
    }

    /** A request refused before anything was saved, carrying the diagnostic to return. */
    private static final class Rejected extends Exception
    {
        private static final long serialVersionUID = 1L;

        final transient Diagnostic diagnostic;

        Rejected( Diagnostic diagnostic )
        {
            super( diagnostic.message(), null, false, false );
            this.diagnostic = diagnostic;
        }
    }
}
