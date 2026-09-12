package com.github.gradusnikov.eclipse.assistai.mcp.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.UUID;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IFolder;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IProjectDescription;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IWorkspace;
import org.eclipse.core.resources.IWorkspaceRoot;
import org.eclipse.core.resources.IncrementalProjectBuilder;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.ILog;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.e4.core.contexts.ContextInjectionFactory;
import org.eclipse.e4.core.contexts.EclipseContextFactory;
import org.eclipse.e4.core.contexts.IEclipseContext;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.launching.JavaRuntime;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.osgi.framework.BundleContext;
import org.osgi.framework.FrameworkUtil;
import org.osgi.util.tracker.ServiceTracker;

import com.github.gradusnikov.eclipse.assistai.Activator;
import com.github.gradusnikov.eclipse.assistai.mcp.results.MethodSearchResponse;
import com.github.gradusnikov.eclipse.assistai.mcp.results.PackageSummaryResponse;
import com.github.gradusnikov.eclipse.assistai.mcp.results.TypeSearchResponse;
import com.github.gradusnikov.eclipse.assistai.mcp.results.WorkspaceOverviewResponse;
import com.github.gradusnikov.eclipse.assistai.tools.Javadocs;

public class CodeDiscoveryServicePDETest
{
    private static final String TEST_PROJECT_NAME_PREFIX = "CodeDiscoveryTestProject";
    private String testProjectName;
    private IProject project;
    private IJavaProject javaProject;
    private CodeDiscoveryService service;
    private NullProgressMonitor monitor = new NullProgressMonitor();

    @BeforeEach
    public void beforeEach() throws CoreException, IOException, InterruptedException
    {
        BundleContext bundleContext = FrameworkUtil.getBundle( CodeDiscoveryServicePDETest.class ).getBundleContext();
        ServiceTracker<IWorkspace, IWorkspace> workspaceTracker = new ServiceTracker<>( bundleContext, IWorkspace.class, null );

        workspaceTracker.open();
        IWorkspace workspace = workspaceTracker.getService();
        IWorkspaceRoot root = workspace.getRoot();
        testProjectName = TEST_PROJECT_NAME_PREFIX + "_" + UUID.randomUUID();

        project = root.getProject( testProjectName );
        if ( project.exists() )
        {
            project.delete( true, true, monitor );
        }

        project = root.getProject( testProjectName );
        IProjectDescription desc = project.getWorkspace().newProjectDescription( project.getName() );
        project.create( desc, monitor );
        project.open( monitor );

        IProjectDescription openDesc = project.getDescription();
        openDesc.setNatureIds( new String[] { JavaCore.NATURE_ID } );
        project.setDescription( openDesc, monitor );

        javaProject = JavaCore.create( project );

        javaProject.setOption( JavaCore.COMPILER_COMPLIANCE, "21" );
        javaProject.setOption( JavaCore.COMPILER_SOURCE, "21" );
        javaProject.setOption( JavaCore.COMPILER_CODEGEN_TARGET_PLATFORM, "21" );

        IFolder binFolder = project.getFolder( "bin" );
        if ( !binFolder.exists() )
        {
            binFolder.create( true, true, monitor );
        }
        javaProject.setOutputLocation( binFolder.getFullPath(), monitor );

        IFolder srcFolder = project.getFolder( "src" );
        if ( !srcFolder.exists() )
        {
            srcFolder.create( IResource.NONE, true, monitor );
        }

        javaProject.setRawClasspath(
                new org.eclipse.jdt.core.IClasspathEntry[] {
                        JavaCore.newSourceEntry( project.getFullPath().append( "src" ) ),
                        JavaRuntime.getDefaultJREContainerEntry()
                },
                monitor );

        createPackageStructure();
        createTestClasses();

        project.build( IncrementalProjectBuilder.FULL_BUILD, monitor );
        Job.getJobManager().join( ResourcesPlugin.FAMILY_MANUAL_BUILD, monitor );
        Job.getJobManager().join( ResourcesPlugin.FAMILY_AUTO_BUILD, monitor );
        Thread.sleep( 500 );

        IEclipseContext context = EclipseContextFactory.create();
        context.set( ILog.class, Activator.getDefault().getLog() );
        service = ContextInjectionFactory.make( CodeDiscoveryService.class, context );
    }

    @AfterEach
    public void afterEach() throws CoreException, InterruptedException
    {
        Job.getJobManager().join( ResourcesPlugin.FAMILY_MANUAL_BUILD, monitor );
        Job.getJobManager().join( ResourcesPlugin.FAMILY_AUTO_BUILD, monitor );
        Thread.sleep( 500 );

        org.eclipse.ui.PlatformUI.getWorkbench().getDisplay().syncExec( () -> {
            org.eclipse.ui.IWorkbenchWindow window =
                    org.eclipse.ui.PlatformUI.getWorkbench().getActiveWorkbenchWindow();
            if ( window != null )
            {
                org.eclipse.ui.IWorkbenchPage page = window.getActivePage();
                if ( page != null )
                {
                    page.closeAllEditors( false );
                }
            }
        } );

        if ( project != null && project.exists() )
        {
            if ( project.isOpen() )
            {
                project.close( monitor );
            }
            for ( int attempt = 0; attempt < 10; attempt++ )
            {
                try
                {
                    project.delete( true, true, monitor );
                    break;
                }
                catch ( CoreException e )
                {
                    if ( attempt == 9 )
                    {
                        project.delete( false, true, monitor );
                        break;
                    }
                    Thread.sleep( 1000 );
                }
            }
        }
    }

    // -------------------------------------------------------------------------
    // searchTypes
    // -------------------------------------------------------------------------

    @Test
    public void testSearchTypes_WildcardPattern()
    {
        TypeSearchResponse result = service.searchTypes( "*PaymentService*", null, Javadocs.Detail.NONE );

        assertNotNull( result );
        assertEquals( "*PaymentService*", result.pattern() );
        assertTrue( result.totalMatches() >= 1, "Should find PaymentService" );
        assertTrue( result.types().stream()
                .anyMatch( t -> "PaymentService".equals( t.simpleName() ) ) );
    }

    @Test
    public void testSearchTypes_CamelCasePattern()
    {
        TypeSearchResponse result = service.searchTypes( "PS", null, Javadocs.Detail.NONE );

        assertNotNull( result );
        assertTrue( result.types().stream()
                .anyMatch( t -> "PaymentService".equals( t.simpleName() ) ),
                "CamelCase 'PS' should match PaymentService" );
    }

    @Test
    public void testSearchTypes_PrefixPattern()
    {
        TypeSearchResponse result = service.searchTypes( "Payment", null, Javadocs.Detail.NONE );

        assertNotNull( result );
        assertTrue( result.totalMatches() >= 1 );
        assertTrue( result.types().stream()
                .anyMatch( t -> t.simpleName().startsWith( "Payment" ) ) );
    }

    @Test
    public void testSearchTypes_QualifiedPattern()
    {
        TypeSearchResponse result = service.searchTypes( "com.example.payment.*Service", null, Javadocs.Detail.NONE );

        assertNotNull( result );
        assertTrue( result.totalMatches() >= 1 );
        assertTrue( result.types().stream()
                .anyMatch( t -> "com.example.payment".equals( t.packageName() ) ) );
    }

    @Test
    public void testSearchTypes_NoMatch()
    {
        TypeSearchResponse result = service.searchTypes( "XyzNonExistentClass999", null, Javadocs.Detail.NONE );

        assertNotNull( result );
        assertEquals( 0, result.totalMatches() );
        assertTrue( result.types().isEmpty() );
    }

    @Test
    public void testSearchTypes_MaxResults()
    {
        TypeSearchResponse result = service.searchTypes( "*", 2, Javadocs.Detail.NONE );

        assertNotNull( result );
        assertTrue( result.types().size() <= 2 );
        if ( result.totalMatches() > 2 )
        {
            assertTrue( result.truncated() );
        }
    }

    @Test
    public void testSearchTypes_ReturnsTypeKind()
    {
        TypeSearchResponse result = service.searchTypes( "*PaymentProcessor*", null, Javadocs.Detail.NONE );

        assertNotNull( result );
        assertTrue( result.types().stream()
                .anyMatch( t -> "interface".equals( t.typeKind() ) ),
                "PaymentProcessor is an interface" );
    }

    // -------------------------------------------------------------------------
    // searchMethods
    // -------------------------------------------------------------------------

    @Test
    public void testSearchMethods_WildcardPattern()
    {
        MethodSearchResponse result = service.searchMethods( "process*", null, null, Javadocs.Detail.NONE );

        assertNotNull( result );
        assertTrue( result.totalMatches() >= 1 );
        assertTrue( result.methods().stream()
                .anyMatch( m -> m.methodName().startsWith( "process" ) ) );
    }

    @Test
    public void testSearchMethods_ExactName()
    {
        MethodSearchResponse result = service.searchMethods( "handleError", null, null, Javadocs.Detail.NONE );

        assertNotNull( result );
        assertTrue( result.totalMatches() >= 1 );
        assertTrue( result.methods().stream()
                .anyMatch( m -> "handleError".equals( m.methodName() ) ) );
    }

    @Test
    public void testSearchMethods_WithDeclaringTypeFilter()
    {
        MethodSearchResponse result = service.searchMethods( "process*", "*PaymentService*", null, Javadocs.Detail.NONE );

        assertNotNull( result );
        assertTrue( result.totalMatches() >= 1 );
        assertTrue( result.methods().stream()
                .allMatch( m -> m.declaringType() != null && m.declaringType().contains( "PaymentService" ) ) );
    }

    @Test
    public void testSearchMethods_NoMatch()
    {
        MethodSearchResponse result = service.searchMethods( "xyzNonExistentMethod999", null, null, Javadocs.Detail.NONE );

        assertNotNull( result );
        assertEquals( 0, result.totalMatches() );
        assertTrue( result.methods().isEmpty() );
    }

    @Test
    public void testSearchMethods_ReturnsParameterTypes()
    {
        MethodSearchResponse result = service.searchMethods( "processPayment", null, null, Javadocs.Detail.NONE );

        assertNotNull( result );
        assertTrue( result.totalMatches() >= 1 );
        var method = result.methods().stream()
                .filter( m -> "processPayment".equals( m.methodName() ) )
                .findFirst()
                .orElseThrow();
        assertFalse( method.parameterTypes().isEmpty(), "processPayment takes parameters" );
    }

    // -------------------------------------------------------------------------
    // getPackageSummary
    // -------------------------------------------------------------------------

    @Test
    public void testGetPackageSummary_ExistingPackage()
    {
        PackageSummaryResponse result = service.getPackageSummary( "com.example.payment", testProjectName, Javadocs.Detail.SUMMARY );

        assertNotNull( result );
        assertEquals( "com.example.payment", result.packageName() );
        assertTrue( result.totalTypes() >= 2, "Should find PaymentService and PaymentProcessor" );
        assertTrue( result.types().stream()
                .anyMatch( t -> "PaymentService".equals( t.simpleName() ) ) );
        assertTrue( result.types().stream()
                .anyMatch( t -> "PaymentProcessor".equals( t.simpleName() ) ) );
    }

    @Test
    public void testGetPackageSummary_ReportsTypeKind()
    {
        PackageSummaryResponse result = service.getPackageSummary( "com.example.payment", testProjectName, Javadocs.Detail.SUMMARY );

        var processor = result.types().stream()
                .filter( t -> "PaymentProcessor".equals( t.simpleName() ) )
                .findFirst()
                .orElseThrow();
        assertEquals( "interface", processor.typeKind() );

        var serviceType = result.types().stream()
                .filter( t -> "PaymentService".equals( t.simpleName() ) )
                .findFirst()
                .orElseThrow();
        assertEquals( "class", serviceType.typeKind() );
    }

    @Test
    public void testGetPackageSummary_ReportsJavadoc()
    {
        PackageSummaryResponse result = service.getPackageSummary( "com.example.payment", testProjectName, Javadocs.Detail.SUMMARY );

        var serviceType = result.types().stream()
                .filter( t -> "PaymentService".equals( t.simpleName() ) )
                .findFirst()
                .orElseThrow();
        assertNotNull( serviceType.javadoc() );
        assertTrue( serviceType.javadoc().contains( "payment" ),
                "Javadoc should mention payment: " + serviceType.javadoc() );
    }

    @Test
    public void testGetPackageSummary_ReportsMethodAndFieldCounts()
    {
        PackageSummaryResponse result = service.getPackageSummary( "com.example.payment", testProjectName, Javadocs.Detail.SUMMARY );

        var serviceType = result.types().stream()
                .filter( t -> "PaymentService".equals( t.simpleName() ) )
                .findFirst()
                .orElseThrow();
        assertTrue( serviceType.methodCount() >= 2, "PaymentService has processPayment and handleError" );
    }

    @Test
    public void testGetPackageSummary_ReportsSuperInterfaces()
    {
        PackageSummaryResponse result = service.getPackageSummary( "com.example.payment", testProjectName, Javadocs.Detail.SUMMARY );

        var serviceType = result.types().stream()
                .filter( t -> "PaymentService".equals( t.simpleName() ) )
                .findFirst()
                .orElseThrow();
        assertTrue( serviceType.superInterfaces().contains( "PaymentProcessor" ),
                "PaymentService implements PaymentProcessor" );
    }

    @Test
    public void testGetPackageSummary_NonExistentPackage()
    {
        PackageSummaryResponse result = service.getPackageSummary( "com.nonexistent.pkg", testProjectName, Javadocs.Detail.SUMMARY );

        assertNotNull( result );
        assertEquals( 0, result.totalTypes() );
        assertTrue( result.types().isEmpty() );
    }

    // -------------------------------------------------------------------------
    // getPackageSummary — Javadoc extraction edge cases
    // -------------------------------------------------------------------------

    @Test
    public void testGetPackageSummary_ClassWithoutJavadoc_ReturnsNull()
    {
        PackageSummaryResponse result = service.getPackageSummary( "com.example.javadoc", testProjectName, Javadocs.Detail.SUMMARY );

        var noDoc = result.types().stream()
                .filter( t -> "NoDocClass".equals( t.simpleName() ) )
                .findFirst()
                .orElseThrow();
        assertNull( noDoc.javadoc(),
                "A class without Javadoc must return null, not a method's Javadoc" );
    }

    @Test
    public void testGetPackageSummary_ClassWithMethodJavadocOnly_ReturnsNull()
    {
        PackageSummaryResponse result = service.getPackageSummary( "com.example.javadoc", testProjectName, Javadocs.Detail.SUMMARY );

        var methodDocOnly = result.types().stream()
                .filter( t -> "MethodDocOnly".equals( t.simpleName() ) )
                .findFirst()
                .orElseThrow();
        assertNull( methodDocOnly.javadoc(),
                "A class without class-level Javadoc but with method Javadoc must return null" );
    }

    @Test
    public void testGetPackageSummary_ClassWithMultiSentenceJavadoc_ReturnsFirstSentence()
    {
        PackageSummaryResponse result = service.getPackageSummary( "com.example.javadoc", testProjectName, Javadocs.Detail.SUMMARY );

        var multiSentence = result.types().stream()
                .filter( t -> "MultiSentenceDoc".equals( t.simpleName() ) )
                .findFirst()
                .orElseThrow();
        assertNotNull( multiSentence.javadoc() );
        assertTrue( multiSentence.javadoc().endsWith( "." ),
                "First sentence should end with a period: " + multiSentence.javadoc() );
        assertFalse( multiSentence.javadoc().contains( "second sentence" ),
                "Should only contain the first sentence" );
    }

    @Test
    public void testGetPackageSummary_ClassWithTagOnlyJavadoc_ReturnsNull()
    {
        PackageSummaryResponse result = service.getPackageSummary( "com.example.javadoc", testProjectName, Javadocs.Detail.SUMMARY );

        var tagOnly = result.types().stream()
                .filter( t -> "TagOnlyDoc".equals( t.simpleName() ) )
                .findFirst()
                .orElseThrow();
        assertNull( tagOnly.javadoc(),
                "A Javadoc with only @tags and no description should return null" );
    }

    @Test
    public void testGetPackageSummary_ClassWithShortJavadocNoPeriod_ReturnsFullText()
    {
        PackageSummaryResponse result = service.getPackageSummary( "com.example.javadoc", testProjectName, Javadocs.Detail.SUMMARY );

        var shortDoc = result.types().stream()
                .filter( t -> "ShortDocNoPeriod".equals( t.simpleName() ) )
                .findFirst()
                .orElseThrow();
        assertNotNull( shortDoc.javadoc() );
        assertEquals( "A utility without a period", shortDoc.javadoc() );
    }

    // -------------------------------------------------------------------------
    // Javadoc on search results
    // -------------------------------------------------------------------------

    @Test
    public void testSearchTypes_SummaryAddsTheFirstJavadocSentence()
    {
        TypeSearchResponse result = service.searchTypes( "*PaymentService*", null, Javadocs.Detail.SUMMARY );

        var match = result.types().stream()
                .filter( t -> "PaymentService".equals( t.simpleName() ) )
                .findFirst()
                .orElseThrow();
        assertEquals( "Service that handles payment processing and error recovery.", match.javadoc() );
    }

    @Test
    public void testSearchTypes_NoneLeavesJavadocNull()
    {
        TypeSearchResponse result = service.searchTypes( "*PaymentService*", null, Javadocs.Detail.NONE );

        assertTrue( result.types().stream().allMatch( t -> t.javadoc() == null ) );
    }

    @Test
    public void testSearchMethods_SummaryReportsInheritedJavadoc()
    {
        MethodSearchResponse result = service.searchMethods( "processPayment", null, null, Javadocs.Detail.SUMMARY );

        var declared = result.methods().stream()
                .filter( m -> "com.example.payment.PaymentProcessor".equals( m.declaringType() ) )
                .findFirst()
                .orElseThrow();
        var overriding = result.methods().stream()
                .filter( m -> "com.example.payment.PaymentService".equals( m.declaringType() ) )
                .findFirst()
                .orElseThrow();

        assertEquals( "Charges the order.", declared.javadoc() );
        assertFalse( declared.javadocInherited() );
        assertEquals( "Charges the order.", overriding.javadoc(), "an undocumented override reports its supertype's text" );
        assertTrue( overriding.javadocInherited() );
    }

    @Test
    public void testGetPackageSummary_FullRendersTheWholeComment()
    {
        PackageSummaryResponse result = service.getPackageSummary( "com.example.javadoc", testProjectName, Javadocs.Detail.FULL );

        var multiSentence = result.types().stream()
                .filter( t -> "MultiSentenceDoc".equals( t.simpleName() ) )
                .findFirst()
                .orElseThrow();
        assertTrue( multiSentence.javadoc().contains( "second sentence" ), multiSentence.javadoc() );
    }

    @Test
    public void testGetPackageSummary_NoneLeavesJavadocNull()
    {
        PackageSummaryResponse result = service.getPackageSummary( "com.example.payment", testProjectName, Javadocs.Detail.NONE );

        assertTrue( result.types().stream().allMatch( t -> t.javadoc() == null ) );
    }

    // -------------------------------------------------------------------------
    // getWorkspaceOverview
    // -------------------------------------------------------------------------

    @Test
    public void testGetWorkspaceOverview_FindsTestProject()
    {
        WorkspaceOverviewResponse result = service.getWorkspaceOverview( testProjectName, null );

        assertNotNull( result );
        assertTrue( result.totalProjects() >= 1 );
        assertTrue( result.projects().stream()
                .anyMatch( p -> testProjectName.equals( p.projectName() ) ) );
    }

    @Test
    public void testGetWorkspaceOverview_ReportsPackagesAndTypes()
    {
        WorkspaceOverviewResponse result = service.getWorkspaceOverview( testProjectName, null );

        var proj = result.projects().stream()
                .filter( p -> testProjectName.equals( p.projectName() ) )
                .findFirst()
                .orElseThrow();
        assertTrue( proj.packageCount() >= 2, "Should have com.example and com.example.payment" );
        assertTrue( proj.typeCount() >= 3, "Should have at least PaymentService, PaymentProcessor, ErrorHandler" );
    }

    @Test
    public void testGetWorkspaceOverview_PackageContainsTypeNames()
    {
        WorkspaceOverviewResponse result = service.getWorkspaceOverview( testProjectName, null );

        var proj = result.projects().stream()
                .filter( p -> testProjectName.equals( p.projectName() ) )
                .findFirst()
                .orElseThrow();

        var paymentPkg = proj.packages().stream()
                .filter( pkg -> "com.example.payment".equals( pkg.packageName() ) )
                .findFirst()
                .orElseThrow();
        assertTrue( paymentPkg.typeNames().contains( "PaymentService" ) );
        assertTrue( paymentPkg.typeNames().contains( "PaymentProcessor" ) );
    }

    @Test
    public void testGetWorkspaceOverview_ProjectFilter()
    {
        WorkspaceOverviewResponse result = service.getWorkspaceOverview( "XyzNonExistent999", null );

        assertNotNull( result );
        assertEquals( 0, result.totalProjects() );
    }

    @Test
    public void testGetWorkspaceOverview_MaxPackagesPerProject()
    {
        WorkspaceOverviewResponse result = service.getWorkspaceOverview( testProjectName, 1 );

        var proj = result.projects().stream()
                .filter( p -> testProjectName.equals( p.projectName() ) )
                .findFirst()
                .orElseThrow();
        assertTrue( proj.packages().size() <= 1 );
    }

    // -------------------------------------------------------------------------
    // helpers
    // -------------------------------------------------------------------------

    private void createPackageStructure() throws CoreException
    {
        createFolder( "src/com" );
        createFolder( "src/com/example" );
        createFolder( "src/com/example/payment" );
        createFolder( "src/com/example/javadoc" );
    }

    private void createTestClasses() throws CoreException
    {
        String paymentProcessorSource =
                "package com.example.payment;\n\n" +
                "/**\n" +
                " * Interface for processing payments.\n" +
                " */\n" +
                "public interface PaymentProcessor {\n" +
                "    /** Charges the order. Retries are the caller's business. */\n" +
                "    void processPayment(String orderId, double amount);\n" +
                "}\n";
        createFile( "src/com/example/payment/PaymentProcessor.java", paymentProcessorSource );

        String paymentServiceSource =
                "package com.example.payment;\n\n" +
                "/**\n" +
                " * Service that handles payment processing and error recovery.\n" +
                " */\n" +
                "public class PaymentService implements PaymentProcessor {\n" +
                "    private int retryCount = 3;\n\n" +
                "    @Override\n" +
                "    public void processPayment(String orderId, double amount) {\n" +
                "        System.out.println(\"Processing payment for order: \" + orderId);\n" +
                "    }\n\n" +
                "    public void handleError(Exception e) {\n" +
                "        System.err.println(\"Payment error: \" + e.getMessage());\n" +
                "    }\n" +
                "}\n";
        createFile( "src/com/example/payment/PaymentService.java", paymentServiceSource );

        String errorHandlerSource =
                "package com.example;\n\n" +
                "/**\n" +
                " * Generic error handler for the application.\n" +
                " */\n" +
                "public class ErrorHandler {\n" +
                "    public void handleError(String context, Exception e) {\n" +
                "        System.err.println(context + \": \" + e.getMessage());\n" +
                "    }\n" +
                "}\n";
        createFile( "src/com/example/ErrorHandler.java", errorHandlerSource );

        createJavadocTestClasses();
    }

    private void createFolder( String path ) throws CoreException
    {
        IFolder folder = project.getFolder( path );
        if ( !folder.exists() )
        {
            folder.create( IResource.NONE, true, monitor );
        }
    }

    private IFile createFile( String path, String content ) throws CoreException
    {
        IFile file = project.getFile( path );
        if ( file.exists() )
        {
            file.setContents( new ByteArrayInputStream( content.getBytes() ), true, true, monitor );
        }
        else
        {
            file.create( new ByteArrayInputStream( content.getBytes() ), true, monitor );
        }
        return file;
    }

    private void createJavadocTestClasses() throws CoreException
    {
        String noDocSource =
                "package com.example.javadoc;\n\n" +
                "public class NoDocClass {\n" +
                "    public void doSomething() {\n" +
                "    }\n" +
                "}\n";
        createFile( "src/com/example/javadoc/NoDocClass.java", noDocSource );

        String methodDocOnlySource =
                "package com.example.javadoc;\n\n" +
                "public class MethodDocOnly {\n" +
                "    /**\n" +
                "     * This Javadoc belongs to the method, not the class.\n" +
                "     */\n" +
                "    public void documented() {\n" +
                "    }\n" +
                "}\n";
        createFile( "src/com/example/javadoc/MethodDocOnly.java", methodDocOnlySource );

        String multiSentenceSource =
                "package com.example.javadoc;\n\n" +
                "/**\n" +
                " * Handles complex multi-step transactions. This is the second sentence\n" +
                " * which should not appear in the summary.\n" +
                " */\n" +
                "public class MultiSentenceDoc {\n" +
                "}\n";
        createFile( "src/com/example/javadoc/MultiSentenceDoc.java", multiSentenceSource );

        String tagOnlySource =
                "package com.example.javadoc;\n\n" +
                "/**\n" +
                " * @author someone\n" +
                " * @since 1.0\n" +
                " */\n" +
                "public class TagOnlyDoc {\n" +
                "}\n";
        createFile( "src/com/example/javadoc/TagOnlyDoc.java", tagOnlySource );

        String shortDocNoPeriodSource =
                "package com.example.javadoc;\n\n" +
                "/**\n" +
                " * A utility without a period\n" +
                " */\n" +
                "public class ShortDocNoPeriod {\n" +
                "}\n";
        createFile( "src/com/example/javadoc/ShortDocNoPeriod.java", shortDocNoPeriodSource );
    }
}
