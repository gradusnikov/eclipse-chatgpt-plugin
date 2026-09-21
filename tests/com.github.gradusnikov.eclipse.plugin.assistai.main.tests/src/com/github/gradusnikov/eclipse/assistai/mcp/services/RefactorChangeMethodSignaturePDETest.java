package com.github.gradusnikov.eclipse.assistai.mcp.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IFolder;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IProjectDescription;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.ILog;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.e4.core.contexts.ContextInjectionFactory;
import org.eclipse.e4.core.contexts.EclipseContextFactory;
import org.eclipse.e4.core.contexts.IEclipseContext;
import org.eclipse.e4.ui.di.UISynchronize;
import org.eclipse.jdt.core.IClasspathEntry;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.launching.JavaRuntime;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.github.gradusnikov.eclipse.assistai.Activator;
import com.github.gradusnikov.eclipse.assistai.mcp.results.DiagnosticCode;
import com.github.gradusnikov.eclipse.assistai.resources.EditResult;
import com.github.gradusnikov.eclipse.assistai.resources.EditResult.ChangeKind;
import com.github.gradusnikov.eclipse.assistai.resources.EditResult.EditStatus;
import com.github.gradusnikov.eclipse.assistai.resources.MethodSignatureChange;
import com.github.gradusnikov.eclipse.assistai.resources.MethodSignatureChange.ParameterSpec;
import com.github.gradusnikov.eclipse.assistai.resources.ResourceCache;
import com.github.gradusnikov.eclipse.assistai.tools.ResourceUtilities;

/**
 * Changing a method's signature (issue 165) through JDT's Change Method Signature
 * refactoring, so the callers in another file follow.
 * <p>
 * The fixture is two small classes with fixed formatting: every assertion below is a
 * substring of the text the refactoring should have produced.
 * <p>
 * One project serves the whole class and the two files are reset before each test,
 * rather than a project per test. The refactoring builds a type hierarchy, and JDT
 * resolves the JRE's {@code Object} through whichever project it first finds the JRE
 * on - which, with a project deleted and another created between tests, is briefly the
 * one that no longer exists.
 */
public class RefactorChangeMethodSignaturePDETest
{
    private static final String ACCOUNT_PATH = "src/app/Account.java";
    private static final String BANK_PATH = "src/app/Bank.java";

    private static final String ACCOUNT = ""
            + "package app;\n"
            + "\n"
            + "public class Account {\n"
            + "    private int balance;\n"
            + "\n"
            + "    public int getBalance() {\n"
            + "        return balance;\n"
            + "    }\n"
            + "\n"
            + "    public void deposit(int amount) {\n"
            + "        balance = balance + amount;\n"
            + "    }\n"
            + "\n"
            + "    public void log(String message, int level) {\n"
            + "        System.out.println(message);\n"
            + "    }\n"
            + "}\n";

    private static final String BANK = ""
            + "package app;\n"
            + "\n"
            + "public class Bank {\n"
            + "    void open(Account account) {\n"
            + "        account.deposit(10);\n"
            + "        account.log(\"hi\", 1);\n"
            + "        int b = account.getBalance();\n"
            + "    }\n"
            + "}\n";

    private static final NullProgressMonitor MONITOR = new NullProgressMonitor();

    private static IProject project;

    private CodeEditingService service;
    private ResourceCache resourceCache;

    @BeforeAll
    public static void createProject() throws Exception
    {
        String projectName = "ChangeSignatureTestProject_" + UUID.randomUUID();
        project = ResourcesPlugin.getWorkspace().getRoot().getProject( projectName );
        IProjectDescription description = project.getWorkspace().newProjectDescription( projectName );
        description.setNatureIds( new String[] { JavaCore.NATURE_ID } );
        project.create( description, MONITOR );
        project.open( MONITOR );

        IFolder src = project.getFolder( "src" );
        src.create( IResource.NONE, true, MONITOR );
        JavaCore.create( project ).setRawClasspath( new IClasspathEntry[] {
            JavaCore.newSourceEntry( src.getFullPath() ),
            JavaCore.newContainerEntry( IPath.fromOSString( JavaRuntime.JRE_CONTAINER ) )
        }, project.getFullPath().append( "bin" ), MONITOR );
        src.getFolder( "app" ).create( IResource.NONE, true, MONITOR );
    }

    @AfterAll
    public static void deleteProject() throws Exception
    {
        Job.getJobManager().join( ResourcesPlugin.FAMILY_AUTO_BUILD, MONITOR );
        if ( project != null && project.exists() )
        {
            project.delete( true, true, MONITOR );
        }
    }

    @BeforeEach
    public void beforeEach() throws Exception
    {
        write( project.getFile( ACCOUNT_PATH ), ACCOUNT );
        write( project.getFile( BANK_PATH ), BANK );

        IEclipseContext context = EclipseContextFactory.create();
        context.set( ILog.class, Activator.getDefault().getLog() );
        context.set( UISynchronize.class, new UISynchronize()
        {
            @Override
            public void syncExec( Runnable runnable )
            {
                runnable.run();
            }

            @Override
            public void asyncExec( Runnable runnable )
            {
                runnable.run();
            }

            @Override
            protected boolean isUIThread( Thread thread )
            {
                return false;
            }

            @Override
            protected void showBusyWhile( Runnable runnable )
            {
                runnable.run();
            }

            @Override
            protected boolean dispatchEvents()
            {
                return false;
            }
        } );
        resourceCache = ContextInjectionFactory.make( ResourceCache.class, context );
        context.set( ResourceCache.class, resourceCache );
        service = ContextInjectionFactory.make( CodeEditingService.class, context );
    }

    @AfterEach
    public void afterEach()
    {
        if ( resourceCache != null )
        {
            resourceCache.dispose();
        }
    }

    // ---- helpers -----------------------------------------------------------

    private static void write( IFile file, String content ) throws Exception
    {
        ByteArrayInputStream bytes = new ByteArrayInputStream( content.getBytes( StandardCharsets.UTF_8 ) );
        if ( file.exists() )
        {
            file.setContents( bytes, IResource.FORCE, MONITOR );
        }
        else
        {
            file.create( bytes, true, MONITOR );
        }
    }

    private String read( String path ) throws Exception
    {
        return ResourceUtilities.readFileContent( project.getFile( path ) );
    }

    private static ParameterSpec keep( String name )
    {
        return new ParameterSpec( name, null, null, null );
    }

    private static ParameterSpec renamed( String oldName, String newName )
    {
        return new ParameterSpec( newName, null, oldName, null );
    }

    private static ParameterSpec added( String name, String type, String defaultValue )
    {
        return new ParameterSpec( name, type, null, defaultValue );
    }

    private static MethodSignatureChange parameters( ParameterSpec... specs )
    {
        return new MethodSignatureChange( List.of( specs ), null, null, null, null, null, false );
    }

    private EditResult change( String method, MethodSignatureChange change )
    {
        return service.refactorChangeMethodSignature( "app.Account", method, null, change );
    }

    private static boolean touched( EditResult result, String path, ChangeKind kind )
    {
        return result.affectedResources().stream()
            .anyMatch( a -> path.equals( a.filePath() ) && a.kind() == kind );
    }

    private static List<DiagnosticCode> codes( EditResult result )
    {
        return result.diagnostics().stream().map( d -> d.code() ).toList();
    }

    // ---- tests -------------------------------------------------------------

    @Test
    public void addsAParameterAndPassesTheDefaultAtEveryCallSite() throws Exception
    {
        EditResult result = change( "deposit", parameters( keep( "amount" ), added( "note", "String", "\"cash\"" ) ) );

        assertEquals( EditStatus.APPLIED, result.status(), () -> result.diagnostics().toString() );
        assertEquals( ACCOUNT_PATH, result.filePath(), "the result is addressed to the declaring file" );
        assertTrue( touched( result, ACCOUNT_PATH, ChangeKind.MODIFIED ), () -> result.affectedResources().toString() );
        assertTrue( touched( result, BANK_PATH, ChangeKind.MODIFIED ), "the caller in another file was rewritten" );
        assertTrue( read( ACCOUNT_PATH ).contains( "public void deposit(int amount, String note)" ), read( ACCOUNT_PATH ) );
        assertTrue( read( BANK_PATH ).contains( "account.deposit(10, \"cash\");" ), read( BANK_PATH ) );
    }

    @Test
    public void removesAParameterFromTheDeclarationAndItsCallers() throws Exception
    {
        EditResult result = change( "log", parameters( keep( "message" ) ) );

        assertEquals( EditStatus.APPLIED, result.status(), () -> result.diagnostics().toString() );
        assertTrue( read( ACCOUNT_PATH ).contains( "public void log(String message)" ), read( ACCOUNT_PATH ) );
        assertTrue( read( BANK_PATH ).contains( "account.log(\"hi\");" ), read( BANK_PATH ) );
    }

    @Test
    public void reordersAndRenamesParametersEverywhere() throws Exception
    {
        EditResult result = change( "log", parameters( renamed( "level", "severity" ), keep( "message" ) ) );

        assertEquals( EditStatus.APPLIED, result.status(), () -> result.diagnostics().toString() );
        assertTrue( read( ACCOUNT_PATH ).contains( "public void log(int severity, String message)" ), read( ACCOUNT_PATH ) );
        assertTrue( read( BANK_PATH ).contains( "account.log(1, \"hi\");" ), read( BANK_PATH ) );
    }

    @Test
    public void changesReturnTypeAndVisibility() throws Exception
    {
        EditResult result = change( "getBalance",
                new MethodSignatureChange( null, "long", "protected", null, null, null, false ) );

        assertEquals( EditStatus.APPLIED, result.status(), () -> result.diagnostics().toString() );
        assertTrue( read( ACCOUNT_PATH ).contains( "protected long getBalance()" ), read( ACCOUNT_PATH ) );
    }

    @Test
    public void addsAThrownException() throws Exception
    {
        EditResult result = change( "deposit",
                new MethodSignatureChange( null, null, null, null, List.of( "java.io.IOException" ), null, false ) );

        assertEquals( EditStatus.APPLIED, result.status(), () -> result.diagnostics().toString() );
        String account = read( ACCOUNT_PATH );
        assertTrue( account.contains( "throws IOException" ) || account.contains( "throws java.io.IOException" ), account );
    }

    @Test
    public void refusesANewParameterWithoutADefaultValue() throws Exception
    {
        EditResult result = change( "deposit", parameters( keep( "amount" ), added( "note", "String", null ) ) );

        assertEquals( EditStatus.REJECTED, result.status() );
        assertEquals( List.of( DiagnosticCode.VALIDATION_ERROR ), codes( result ) );
        assertTrue( result.diagnostics().get( 0 ).message().contains( "defaultValue" ), result.diagnostics().get( 0 ).message() );
        assertEquals( ACCOUNT, read( ACCOUNT_PATH ), "nothing was changed" );
    }

    @Test
    public void refusesAnOldNameNoParameterHas() throws Exception
    {
        EditResult result = change( "log", parameters( renamed( "severity", "level" ), keep( "message" ) ) );

        assertEquals( EditStatus.REJECTED, result.status() );
        assertEquals( List.of( DiagnosticCode.VALIDATION_ERROR ), codes( result ) );
        assertTrue( result.diagnostics().get( 0 ).message().contains( "String message, int level" ),
                result.diagnostics().get( 0 ).message() );
    }

    @Test
    public void refusesAMethodTheTypeDoesNotDeclare() throws Exception
    {
        EditResult result = change( "withdraw", parameters() );

        assertEquals( EditStatus.REJECTED, result.status() );
        assertEquals( List.of( DiagnosticCode.VALIDATION_ERROR ), codes( result ) );
        assertEquals( ACCOUNT_PATH, result.filePath() );
    }

    @Test
    public void theSignatureTheMethodAlreadyHasIsNothingToChange() throws Exception
    {
        EditResult result = change( "deposit", parameters( keep( "amount" ) ) );

        assertEquals( EditStatus.REJECTED, result.status() );
        assertEquals( List.of( DiagnosticCode.VALIDATION_ERROR ), codes( result ) );
        assertEquals( ACCOUNT, read( ACCOUNT_PATH ) );
    }

    @Test
    public void aTypeEclipseRefusesIsAFailedPrecondition() throws Exception
    {
        EditResult result = change( "getBalance", new MethodSignatureChange( null, "1Bad", null, null, null, null, false ) );

        assertEquals( EditStatus.REJECTED, result.status() );
        assertEquals( List.of( DiagnosticCode.REFACTORING_PRECONDITION_FAILED ), codes( result ) );
        assertEquals( ACCOUNT, read( ACCOUNT_PATH ), "nothing was changed" );
    }
}
