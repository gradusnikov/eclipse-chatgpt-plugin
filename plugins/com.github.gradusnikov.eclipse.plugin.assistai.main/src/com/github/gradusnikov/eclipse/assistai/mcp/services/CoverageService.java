package com.github.gradusnikov.eclipse.assistai.mcp.services;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.ILog;
import org.eclipse.core.runtime.Platform;
import org.eclipse.e4.core.di.annotations.Creatable;
import org.eclipse.jdt.core.IClasspathEntry;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.JavaCore;
import org.jacoco.core.analysis.Analyzer;
import org.jacoco.core.analysis.CoverageBuilder;
import org.jacoco.core.analysis.IClassCoverage;
import org.jacoco.core.analysis.ICounter;
import org.jacoco.core.analysis.IPackageCoverage;
import org.jacoco.core.data.ExecutionDataReader;
import org.jacoco.core.data.ExecutionDataStore;
import org.jacoco.core.data.SessionInfoStore;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;

@Creatable
@Singleton
public class CoverageService
{
    private static final String ECLEMMA_CORE_BUNDLE = "org.eclipse.eclemma.core";
    private static final String COVERAGE_LAUNCH_MODE = "coverage";

    @Inject
    private ILog logger;

    public boolean isCoverageAvailable()
    {
        return Platform.getBundle( ECLEMMA_CORE_BUNDLE ) != null;
    }

    public String getCoverageLaunchMode()
    {
        return COVERAGE_LAUNCH_MODE;
    }

    public String waitForLatestCoverageFile( long launchStartTime, int maxWaitMs )
    {
        Path basePath = ResourcesPlugin.getWorkspace().getRoot().getLocation().toFile().toPath()
            .resolve( ".metadata" ).resolve( ".plugins" ).resolve( ECLEMMA_CORE_BUNDLE );

        if ( !Files.exists( basePath ) )
        {
            return null;
        }

        int elapsed = 0;
        int pollInterval = 500;
        while ( elapsed < maxWaitMs )
        {
            try
            {
                var execFile = Files.walk( basePath, 2 )
                    .filter( p -> p.toString().endsWith( ".exec" ) )
                    .filter( p -> {
                        try
                        {
                            return Files.getLastModifiedTime( p ).toMillis() >= launchStartTime;
                        }
                        catch ( IOException e )
                        {
                            return false;
                        }
                    } )
                    .findFirst();
                if ( execFile.isPresent() )
                {
                    return execFile.get().toString();
                }
            }
            catch ( IOException e )
            {
                logger.error( "Error searching for coverage files", e );
                return null;
            }
            try
            {
                Thread.sleep( pollInterval );
            }
            catch ( InterruptedException e )
            {
                Thread.currentThread().interrupt();
                return null;
            }
            elapsed += pollInterval;
        }
        logger.warn( "Coverage exec file not found within " + maxWaitMs + "ms" );
        return null;
    }

    public String findLatestCoverageFile()
    {
        Path basePath = ResourcesPlugin.getWorkspace().getRoot().getLocation().toFile().toPath()
            .resolve( ".metadata" ).resolve( ".plugins" ).resolve( ECLEMMA_CORE_BUNDLE );

        if ( !Files.exists( basePath ) )
        {
            return null;
        }

        try
        {
            var execFiles = Files.walk( basePath, 2 )
                .filter( p -> p.toString().endsWith( ".exec" ) )
                .sorted( ( a, b ) -> {
                    try
                    {
                        return Long.compare( Files.getLastModifiedTime( b ).toMillis(),
                            Files.getLastModifiedTime( a ).toMillis() );
                    }
                    catch ( IOException e )
                    {
                        return 0;
                    }
                } )
                .toList();

            if ( !execFiles.isEmpty() )
            {
                return execFiles.get( 0 ).toString();
            }
        }
        catch ( IOException e )
        {
            logger.error( "Error searching for coverage files", e );
        }
        return null;
    }

    public String formatCoverageInfo( String execFilePath )
    {
        return formatCoverageInfo( execFilePath, null );
    }

    public String formatCoverageInfo( String execFilePath, String projectName )
    {
        if ( execFilePath == null )
        {
            return "";
        }

        if ( projectName != null )
        {
            try
            {
                return analyzeCoverage( execFilePath, projectName );
            }
            catch ( Exception e )
            {
                if ( logger != null )
                {
                    try { logger.error( "Error analyzing coverage data, falling back to basic info", e ); }
                    catch ( Exception logEx ) { /* logger not fully initialized */ }
                }
            }
        }

        StringBuilder sb = new StringBuilder();
        sb.append( "\n--- Coverage ---\n" );
        sb.append( "Coverage data collected. View in Eclipse via Coverage view.\n" );
        sb.append( "Coverage data file: " ).append( execFilePath ).append( "\n" );
        return sb.toString();
    }

    private String analyzeCoverage( String execFilePath, String projectName ) throws Exception
    {
        ExecutionDataStore executionDataStore = new ExecutionDataStore();
        SessionInfoStore sessionInfoStore = new SessionInfoStore();

        try ( FileInputStream fis = new FileInputStream( execFilePath ) )
        {
            ExecutionDataReader reader = new ExecutionDataReader( fis );
            reader.setExecutionDataVisitor( executionDataStore );
            reader.setSessionInfoVisitor( sessionInfoStore );
            reader.read();
        }

        Set<String> executedClassNames = executionDataStore.getContents().stream()
            .map( data -> data.getName() )
            .collect( Collectors.toSet() );

        if ( executedClassNames.isEmpty() )
        {
            return "\n--- Coverage ---\nNo execution data found in coverage file.\n";
        }

        CoverageBuilder coverageBuilder = new CoverageBuilder();
        Analyzer analyzer = new Analyzer( executionDataStore, coverageBuilder );

        List<File> outputLocations = new ArrayList<>();
        IProject[] allProjects = ResourcesPlugin.getWorkspace().getRoot().getProjects();
        for ( IProject project : allProjects )
        {
            try
            {
                if ( !project.isOpen() || !project.hasNature( JavaCore.NATURE_ID ) )
                {
                    continue;
                }
                IJavaProject javaProject = JavaCore.create( project );
                Set<File> projectOutputs = new HashSet<>();
                File defaultOutput = project.getLocation().append(
                    javaProject.getOutputLocation().removeFirstSegments( 1 ) ).toFile();
                projectOutputs.add( defaultOutput );
                for ( IClasspathEntry entry : javaProject.getResolvedClasspath( true ) )
                {
                    if ( entry.getEntryKind() == IClasspathEntry.CPE_SOURCE && entry.getOutputLocation() != null )
                    {
                        File entryOutput = project.getLocation().append(
                            entry.getOutputLocation().removeFirstSegments( 1 ) ).toFile();
                        projectOutputs.add( entryOutput );
                    }
                }
                for ( File output : projectOutputs )
                {
                    if ( output.exists() )
                    {
                        outputLocations.add( output );
                    }
                }
            }
            catch ( Exception e )
            {
                // skip projects that can't be resolved
            }
        }

        for ( String className : executedClassNames )
        {
            String classFilePath = className.replace( '/', File.separatorChar ) + ".class";
            for ( File outputLocation : outputLocations )
            {
                File classFile = new File( outputLocation, classFilePath );
                if ( classFile.exists() )
                {
                    try ( FileInputStream fis = new FileInputStream( classFile ) )
                    {
                        analyzer.analyzeAll( fis, className );
                    }
                    catch ( Exception e )
                    {
                        if ( logger != null )
                        {
                            logger.warn( "Skipping class '" + className + "': " + e.getMessage() );
                        }
                    }
                    break;
                }
            }
        }

        return formatReport( coverageBuilder, projectName );
    }

    private String formatReport( CoverageBuilder coverageBuilder, String projectName )
    {
        StringBuilder sb = new StringBuilder();
        sb.append( "\n--- Coverage Report ---\n" );

        int totalLines = 0;
        int coveredLines = 0;
        int totalBranches = 0;
        int coveredBranches = 0;

        StringBuilder jsonArray = new StringBuilder();
        jsonArray.append( "[\n" );
        boolean first = true;

        for ( IPackageCoverage pkg : coverageBuilder.getBundle( projectName ).getPackages() )
        {
            String packageName = pkg.getName().replace( '/', '.' );
            totalLines += pkg.getLineCounter().getTotalCount();
            coveredLines += pkg.getLineCounter().getCoveredCount();
            totalBranches += pkg.getBranchCounter().getTotalCount();
            coveredBranches += pkg.getBranchCounter().getCoveredCount();

            for ( IClassCoverage cls : pkg.getClasses() )
            {
                int clsLines = cls.getLineCounter().getTotalCount();
                if ( clsLines == 0 ) continue;

                List<Integer> uncoveredLines = new ArrayList<>();
                List<Integer> partiallyCoveredLines = new ArrayList<>();
                List<Integer> uncoveredBranchLines = new ArrayList<>();
                List<Integer> partiallyCoveredBranchLines = new ArrayList<>();

                for ( int i = cls.getFirstLine(); i <= cls.getLastLine(); i++ )
                {
                    int lineStatus = cls.getLine( i ).getStatus();
                    if ( lineStatus == ICounter.NOT_COVERED )
                    {
                        uncoveredLines.add( i );
                    }
                    else if ( lineStatus == ICounter.PARTLY_COVERED )
                    {
                        partiallyCoveredLines.add( i );
                    }

                    int branchTotal = cls.getLine( i ).getBranchCounter().getTotalCount();
                    if ( branchTotal > 0 )
                    {
                        int branchStatus = cls.getLine( i ).getBranchCounter().getStatus();
                        if ( branchStatus == ICounter.NOT_COVERED )
                        {
                            uncoveredBranchLines.add( i );
                        }
                        else if ( branchStatus == ICounter.PARTLY_COVERED )
                        {
                            partiallyCoveredBranchLines.add( i );
                        }
                    }
                }

                if ( uncoveredLines.isEmpty() && partiallyCoveredLines.isEmpty()
                    && uncoveredBranchLines.isEmpty() && partiallyCoveredBranchLines.isEmpty() )
                {
                    continue;
                }

                if ( !first ) jsonArray.append( ",\n" );
                first = false;

                String sourceFile = cls.getSourceFileName() != null ? cls.getSourceFileName()
                    : cls.getName().substring( cls.getName().lastIndexOf( '/' ) + 1 ) + ".java";

                jsonArray.append( "  {\n" );
                jsonArray.append( "    \"sourcefile\": \"" ).append( sourceFile ).append( "\",\n" );
                jsonArray.append( "    \"package\": \"" ).append( packageName ).append( "\",\n" );
                jsonArray.append( "    \"lines\": {\n" );
                jsonArray.append( "      \"nocovered\": " ).append( toJsonArray( uncoveredLines ) ).append( ",\n" );
                jsonArray.append( "      \"partiallycovered\": " ).append( toJsonArray( partiallyCoveredLines ) ).append( "\n" );
                jsonArray.append( "    },\n" );
                jsonArray.append( "    \"branch\": {\n" );
                jsonArray.append( "      \"nocovered\": " ).append( toJsonArray( uncoveredBranchLines ) ).append( ",\n" );
                jsonArray.append( "      \"partiallycovered\": " ).append( toJsonArray( partiallyCoveredBranchLines ) ).append( "\n" );
                jsonArray.append( "    }\n" );
                jsonArray.append( "  }" );
            }
        }

        jsonArray.append( "\n]" );

        if ( totalLines > 0 )
        {
            int overallLinePct = (int) ( 100.0 * coveredLines / totalLines );
            sb.append( "Overall: " ).append( overallLinePct ).append( "% line coverage (" )
                .append( coveredLines ).append( "/" ).append( totalLines ).append( " lines)" );
            if ( totalBranches > 0 )
            {
                int overallBranchPct = (int) ( 100.0 * coveredBranches / totalBranches );
                sb.append( ", " ).append( overallBranchPct ).append( "% branch coverage (" )
                    .append( coveredBranches ).append( "/" ).append( totalBranches ).append( " branches)" );
            }
            sb.append( "\n\n" );
        }

        if ( first )
        {
            sb.append( "All classes fully covered.\n" );
        }
        else
        {
            sb.append( "Classes with incomplete coverage:\n" );
            sb.append( jsonArray );
            sb.append( "\n" );
        }

        return sb.toString();
    }

    private String toJsonArray( List<Integer> values )
    {
        if ( values.isEmpty() ) return "[]";
        StringBuilder sb = new StringBuilder();
        sb.append( "[" );
        for ( int i = 0; i < values.size(); i++ )
        {
            if ( i > 0 ) sb.append( ", " );
            sb.append( values.get( i ) );
        }
        sb.append( "]" );
        return sb.toString();
    }
}
