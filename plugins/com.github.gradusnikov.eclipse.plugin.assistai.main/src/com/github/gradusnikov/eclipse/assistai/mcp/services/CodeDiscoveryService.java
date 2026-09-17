package com.github.gradusnikov.eclipse.assistai.mcp.services;

import java.util.ArrayList;
import java.util.List;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.ILog;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.e4.core.di.annotations.Creatable;
import org.eclipse.jdt.core.Flags;
import org.eclipse.jdt.core.IField;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.IMethod;
import org.eclipse.jdt.core.IPackageFragment;
import org.eclipse.jdt.core.IPackageFragmentRoot;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.core.JavaModelException;
import org.eclipse.jdt.core.Signature;
import org.eclipse.jdt.core.search.IJavaSearchConstants;
import org.eclipse.jdt.core.search.SearchEngine;
import org.eclipse.jdt.core.search.SearchMatch;
import org.eclipse.jdt.core.search.SearchParticipant;
import org.eclipse.jdt.core.search.SearchPattern;
import org.eclipse.jdt.core.search.SearchRequestor;
import org.eclipse.jdt.core.search.TypeNameMatch;
import org.eclipse.jdt.core.search.TypeNameMatchRequestor;

import com.github.gradusnikov.eclipse.assistai.mcp.results.MethodSearchResponse;
import com.github.gradusnikov.eclipse.assistai.mcp.results.PackageSummaryResponse;
import com.github.gradusnikov.eclipse.assistai.mcp.results.TypeSearchResponse;
import com.github.gradusnikov.eclipse.assistai.mcp.results.WorkspaceOverviewResponse;
import com.github.gradusnikov.eclipse.assistai.tools.Javadocs;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;

@Creatable
@Singleton
public class CodeDiscoveryService
{
    private static final int DEFAULT_LIMIT = 100;

    @Inject
    private ILog logger;

    /**
     * @param javadoc how much of each type's documentation to carry, for the types shown;
     *            the matches past the limit are counted, not documented
     */
    public TypeSearchResponse searchTypes( String pattern, Integer maxResults, Javadocs.Detail javadoc )
    {
        int limit = maxResults != null && maxResults > 0 ? maxResults : DEFAULT_LIMIT;
        int collectLimit = limit * 2;

        try
        {
            var scope = SearchEngine.createWorkspaceScope();
            var engine = new SearchEngine();
            var found = new ArrayList<TypeNameMatch>();

            int matchRule = determineMatchRule( pattern );
            char[] packagePattern = null;
            char[] typePattern = pattern.toCharArray();

            if ( pattern.contains( "." ) )
            {
                int lastDot = pattern.lastIndexOf( '.' );
                packagePattern = pattern.substring( 0, lastDot ).toCharArray();
                typePattern = pattern.substring( lastDot + 1 ).toCharArray();
            }

            engine.searchAllTypeNames(
                    packagePattern,
                    packagePattern != null ? SearchPattern.R_PATTERN_MATCH : 0,
                    typePattern,
                    matchRule,
                    IJavaSearchConstants.TYPE,
                    scope,
                    new TypeNameMatchRequestor()
                    {
                        @Override
                        public void acceptTypeNameMatch( TypeNameMatch match )
                        {
                            if ( found.size() < collectLimit && match.getType() != null )
                            {
                                found.add( match );
                            }
                        }
                    },
                    IJavaSearchConstants.WAIT_UNTIL_READY_TO_SEARCH,
                    new NullProgressMonitor() );

            var matches = new ArrayList<TypeSearchResponse.TypeMatch>( found.size() );
            for ( int i = 0; i < found.size(); i++ )
            {
                TypeNameMatch match = found.get( i );
                IType type = match.getType();
                IJavaProject project = type.getJavaProject();
                matches.add( new TypeSearchResponse.TypeMatch(
                        match.getFullyQualifiedName(),
                        match.getSimpleTypeName(),
                        match.getPackageName(),
                        project != null ? project.getElementName() : null,
                        typeKindLabel( type ),
                        i < limit ? documentation( type, javadoc ) : null ) );
            }

            return TypeSearchResponse.of( pattern, matches, limit );
        }
        catch ( JavaModelException e )
        {
            logger.error( e.getMessage(), e );
            throw new RuntimeException( "Error searching types: " + e.getMessage(), e );
        }
    }

    /**
     * @param javadoc how much of each method's documentation to carry, for the methods
     *            shown; an undocumented override reports its supertype's text
     */
    public MethodSearchResponse searchMethods( String pattern, String declaringTypePattern, Integer maxResults,
                                               Javadocs.Detail javadoc )
    {
        int limit = maxResults != null && maxResults > 0 ? maxResults : DEFAULT_LIMIT;
        int collectLimit = limit * 2;

        try
        {
            var scope = SearchEngine.createWorkspaceScope();
            var engine = new SearchEngine();
            var found = new ArrayList<IMethod>();

            int matchRule = determineMatchRule( pattern );
            char[] methodPattern = pattern.toCharArray();
            char[] qualifyingType = declaringTypePattern != null && !declaringTypePattern.isBlank()
                    ? declaringTypePattern.toCharArray()
                    : null;

            engine.searchAllMethodNames(
                    qualifyingType,
                    qualifyingType != null ? determineMatchRule( declaringTypePattern ) : 0,
                    methodPattern,
                    matchRule,
                    scope,
                    new org.eclipse.jdt.core.search.MethodNameMatchRequestor()
                    {
                        @Override
                        public void acceptMethodNameMatch( org.eclipse.jdt.core.search.MethodNameMatch match )
                        {
                            if ( found.size() < collectLimit && match.getMethod() != null )
                            {
                                found.add( match.getMethod() );
                            }
                        }
                    },
                    IJavaSearchConstants.WAIT_UNTIL_READY_TO_SEARCH,
                    new NullProgressMonitor() );

            var matches = new ArrayList<MethodSearchResponse.MethodMatch>( found.size() );
            for ( int i = 0; i < found.size(); i++ )
            {
                IMethod method = found.get( i );
                IType declaringType = method.getDeclaringType();
                IJavaProject project = method.getJavaProject();

                List<String> paramTypes = new ArrayList<>();
                for ( String paramSig : method.getParameterTypes() )
                {
                    paramTypes.add( Signature.toString( paramSig ) );
                }

                String returnType = null;
                try
                {
                    returnType = Signature.toString( method.getReturnType() );
                }
                catch ( JavaModelException e )
                {
                    // ignore
                }

                Javadocs.Rendered rendered = i < limit ? Javadocs.render( method, javadoc, false ) : null;
                matches.add( new MethodSearchResponse.MethodMatch(
                        method.getElementName(),
                        declaringType != null ? declaringType.getFullyQualifiedName() : null,
                        declaringType != null ? declaringType.getPackageFragment().getElementName() : null,
                        project != null ? project.getElementName() : null,
                        returnType,
                        paramTypes,
                        rendered == null ? null : rendered.markdown(),
                        rendered != null && rendered.inherited() ) );
            }

            return MethodSearchResponse.of( pattern, matches, limit );
        }
        catch ( JavaModelException e )
        {
            logger.error( e.getMessage(), e );
            throw new RuntimeException( "Error searching methods: " + e.getMessage(), e );
        }
    }

    /**
     * @param javadoc how much of each type's documentation to carry; the default of the
     *            tool is the first sentence, which is what makes the listing a table of
     *            contents rather than a list of names
     */
    public PackageSummaryResponse getPackageSummary( String packageName, String projectName, Javadocs.Detail javadoc )
    {
        try
        {
            IPackageFragment pkg = findPackage( packageName, projectName );
            if ( pkg == null )
            {
                return PackageSummaryResponse.of( packageName, projectName, List.of() );
            }

            String resolvedProject = pkg.getJavaProject().getElementName();
            var types = new ArrayList<PackageSummaryResponse.TypeSummary>();

            for ( var cu : pkg.getCompilationUnits() )
            {
                for ( IType type : cu.getTypes() )
                {
                    String typeKind = typeKindLabel( type );

                    int methodCount = type.getMethods().length;
                    int fieldCount = type.getFields().length;

                    List<String> superInterfaces = new ArrayList<>();
                    for ( String iface : type.getSuperInterfaceNames() )
                    {
                        superInterfaces.add( iface );
                    }

                    types.add( new PackageSummaryResponse.TypeSummary(
                            type.getElementName(),
                            typeKind,
                            documentation( type, javadoc ),
                            methodCount,
                            fieldCount,
                            superInterfaces ) );
                }
            }

            return PackageSummaryResponse.of( packageName, resolvedProject, types );
        }
        catch ( JavaModelException e )
        {
            logger.error( e.getMessage(), e );
            throw new RuntimeException( "Error getting package summary: " + e.getMessage(), e );
        }
    }

    public WorkspaceOverviewResponse getWorkspaceOverview( String projectFilter, Integer maxPackagesPerProject )
    {
        int maxPkgs = maxPackagesPerProject != null && maxPackagesPerProject > 0 ? maxPackagesPerProject : 50;

        try
        {
            var projects = new ArrayList<WorkspaceOverviewResponse.ProjectOverview>();

            for ( IJavaProject javaProject : getAvailableJavaProjects() )
            {
                String name = javaProject.getElementName();
                if ( projectFilter != null && !projectFilter.isBlank() && !name.contains( projectFilter ) )
                {
                    continue;
                }

                var packageOverviews = new ArrayList<WorkspaceOverviewResponse.PackageOverview>();
                int totalTypes = 0;
                int totalPackageCount = 0;

                for ( IPackageFragmentRoot root : javaProject.getPackageFragmentRoots() )
                {
                    if ( root.getKind() != IPackageFragmentRoot.K_SOURCE )
                    {
                        continue;
                    }

                    for ( IJavaElement child : root.getChildren() )
                    {
                        if ( !( child instanceof IPackageFragment pkg ) || pkg.getCompilationUnits().length == 0 )
                        {
                            continue;
                        }

                        totalPackageCount++;

                        var typeNames = new ArrayList<String>();
                        for ( var cu : pkg.getCompilationUnits() )
                        {
                            for ( IType type : cu.getTypes() )
                            {
                                typeNames.add( type.getElementName() );
                            }
                        }

                        totalTypes += typeNames.size();

                        if ( packageOverviews.size() < maxPkgs )
                        {
                            packageOverviews.add( new WorkspaceOverviewResponse.PackageOverview(
                                    pkg.getElementName(),
                                    typeNames.size(),
                                    typeNames ) );
                        }
                    }
                }

                projects.add( new WorkspaceOverviewResponse.ProjectOverview(
                        name,
                        totalPackageCount,
                        totalTypes,
                        packageOverviews ) );
            }

            return WorkspaceOverviewResponse.of( projects );
        }
        catch ( JavaModelException e )
        {
            logger.error( e.getMessage(), e );
            throw new RuntimeException( "Error building workspace overview: " + e.getMessage(), e );
        }
    }

    private int determineMatchRule( String pattern )
    {
        if ( pattern.contains( "*" ) || pattern.contains( "?" ) )
        {
            return SearchPattern.R_PATTERN_MATCH;
        }
        if ( isCamelCasePattern( pattern ) )
        {
            return SearchPattern.R_CAMELCASE_MATCH;
        }
        return SearchPattern.R_PREFIX_MATCH | SearchPattern.R_CASE_SENSITIVE;
    }

    private boolean isCamelCasePattern( String pattern )
    {
        if ( pattern.isEmpty() )
        {
            return false;
        }
        if ( !Character.isLetter( pattern.charAt( 0 ) ) )
        {
            return false;
        }
        int upperCount = 0;
        for ( char c : pattern.toCharArray() )
        {
            if ( Character.isUpperCase( c ) )
            {
                upperCount++;
            }
        }
        return upperCount >= 2;
    }

    private String typeKindLabel( IType type )
    {
        try
        {
            if ( type.isInterface() )
            {
                return "interface";
            }
            if ( type.isEnum() )
            {
                return "enum";
            }
            if ( type.isRecord() )
            {
                return "record";
            }
            if ( type.isAnnotation() )
            {
                return "annotation";
            }
            if ( Flags.isAbstract( type.getFlags() ) )
            {
                return "abstract class";
            }
        }
        catch ( JavaModelException e )
        {
            // fall through
        }
        return "class";
    }

    /**
     * Source only, attached or in the workspace: a listing can reach many library types,
     * and a project's Javadoc location may be a URL.
     */
    private static String documentation( IType type, Javadocs.Detail javadoc )
    {
        Javadocs.Rendered rendered = Javadocs.render( type, javadoc, false );
        return rendered == null ? null : rendered.markdown();
    }

    private IPackageFragment findPackage( String packageName, String projectName ) throws JavaModelException
    {
        for ( IJavaProject project : getAvailableJavaProjects() )
        {
            if ( projectName != null && !projectName.isBlank() && !project.getElementName().equals( projectName ) )
            {
                continue;
            }

            for ( IPackageFragmentRoot root : project.getPackageFragmentRoots() )
            {
                if ( root.getKind() != IPackageFragmentRoot.K_SOURCE )
                {
                    continue;
                }

                IPackageFragment pkg = root.getPackageFragment( packageName );
                if ( pkg != null && pkg.exists() )
                {
                    return pkg;
                }
            }
        }
        return null;
    }

    private List<IJavaProject> getAvailableJavaProjects()
    {
        List<IJavaProject> javaProjects = new ArrayList<>();
        try
        {
            IProject[] projects = ResourcesPlugin.getWorkspace().getRoot().getProjects();
            for ( IProject project : projects )
            {
                if ( project.isOpen() && project.hasNature( JavaCore.NATURE_ID ) )
                {
                    javaProjects.add( JavaCore.create( project ) );
                }
            }
        }
        catch ( CoreException e )
        {
            throw new RuntimeException( e );
        }
        return javaProjects;
    }
}
