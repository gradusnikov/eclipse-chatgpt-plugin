package com.github.gradusnikov.eclipse.assistai.mcp.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IFolder;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IProjectDescription;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.ILog;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.e4.core.contexts.ContextInjectionFactory;
import org.eclipse.e4.core.contexts.EclipseContextFactory;
import org.eclipse.e4.core.contexts.IEclipseContext;
import org.eclipse.e4.ui.di.UISynchronize;
import org.eclipse.jdt.core.IClasspathEntry;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.launching.JavaRuntime;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.github.gradusnikov.eclipse.assistai.Activator;
import com.github.gradusnikov.eclipse.assistai.mcp.results.DiagnosticCode;
import com.github.gradusnikov.eclipse.assistai.resources.EditResult;
import com.github.gradusnikov.eclipse.assistai.resources.EditResult.ChangeKind;
import com.github.gradusnikov.eclipse.assistai.resources.EditResult.EditStatus;
import com.github.gradusnikov.eclipse.assistai.resources.ResourceCache;
import com.github.gradusnikov.eclipse.assistai.tools.ResourceUtilities;

/**
 * Renaming Java elements by position (issue 152): methods, fields, locals, parameters
 * and types, each through JDT's rename refactoring so references follow.
 * <p>
 * The fixture is two small classes with fixed formatting, so every line and column
 * below is a known fact rather than something computed.
 */
public class RefactorRenameJavaElementPDETest
{
    private static final String PROJECT = "RenameElementTestProject";

    private static final String ACCOUNT = ""
            + "package app;\n"                                    // 1
            + "\n"                                                // 2
            + "public class Account {\n"                          // 3
            + "    private int balance;\n"                        // 4
            + "\n"                                                // 5
            + "    public int getBalance() {\n"                   // 6
            + "        return balance;\n"                         // 7
            + "    }\n"                                           // 8
            + "\n"                                                // 9
            + "    public void deposit(int amount) {\n"           // 10
            + "        int total = balance + amount;\n"           // 11
            + "        balance = total;\n"                        // 12
            + "    }\n"                                           // 13
            + "}\n";                                              // 14

    private static final String BANK = ""
            + "package app;\n"                                    // 1
            + "\n"                                                // 2
            + "public class Bank {\n"                             // 3
            + "    void open(Account account) {\n"                // 4
            + "        account.deposit(10);\n"                    // 5
            + "        int b = account.getBalance();\n"           // 6
            + "    }\n"                                           // 7
            + "}\n";                                              // 8

    private final NullProgressMonitor monitor = new NullProgressMonitor();

    private IProject project;
    private CodeEditingService service;
    private ResourceCache resourceCache;

    @BeforeEach
    public void beforeEach() throws Exception
    {
        project = ResourcesPlugin.getWorkspace().getRoot().getProject( PROJECT );
        if ( project.exists() )
        {
            project.delete( true, true, monitor );
        }
        IProjectDescription description = project.getWorkspace().newProjectDescription( PROJECT );
        description.setNatureIds( new String[] { JavaCore.NATURE_ID } );
        project.create( description, monitor );
        project.open( monitor );

        IFolder src = project.getFolder( "src" );
        src.create( IResource.NONE, true, monitor );
        JavaCore.create( project ).setRawClasspath( new IClasspathEntry[] {
            JavaCore.newSourceEntry( src.getFullPath() ),
            JavaCore.newContainerEntry( IPath.fromOSString( JavaRuntime.JRE_CONTAINER ) )
        }, project.getFullPath().append( "bin" ), monitor );

        IFolder app = src.getFolder( "app" );
        app.create( IResource.NONE, true, monitor );
        write( app.getFile( "Account.java" ), ACCOUNT );
        write( app.getFile( "Bank.java" ), BANK );

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
    public void afterEach() throws Exception
    {
        if ( resourceCache != null )
        {
            resourceCache.dispose();
        }
        if ( project != null && project.exists() )
        {
            project.delete( true, true, monitor );
        }
    }

    private void write( IFile file, String content ) throws Exception
    {
        file.create( new ByteArrayInputStream( content.getBytes( StandardCharsets.UTF_8 ) ), true, monitor );
    }

    private String read( String path ) throws Exception
    {
        return ResourceUtilities.readFileContent( project.getFile( path ) );
    }

    private EditResult rename( String path, int line, int column, String newName )
    {
        return service.refactorRenameJavaElement( PROJECT, path, line, column, newName, null, true, false, false );
    }

    private static boolean touched( EditResult result, String path, ChangeKind kind )
    {
        return result.affectedResources().stream()
            .anyMatch( a -> path.equals( a.filePath() ) && a.kind() == kind );
    }

    // ---- tests -------------------------------------------------------------

    @Test
    public void renamesAMethodAndEveryCaller() throws Exception
    {
        EditResult result = rename( "src/app/Account.java", 10, 17, "credit" );

        assertEquals( EditStatus.APPLIED, result.status(), () -> result.diagnostics().toString() );
        assertEquals( "src/app/Account.java", result.filePath() );
        assertTrue( touched( result, "src/app/Account.java", ChangeKind.MODIFIED ), () -> result.affectedResources().toString() );
        assertTrue( touched( result, "src/app/Bank.java", ChangeKind.MODIFIED ), "the caller in another file was rewritten" );
        assertTrue( read( "src/app/Account.java" ).contains( "public void credit(int amount)" ) );
        assertTrue( read( "src/app/Bank.java" ).contains( "account.credit(10);" ) );
    }

    @Test
    public void renamesAParameterWithinItsMethodOnly() throws Exception
    {
        EditResult result = rename( "src/app/Account.java", 10, 29, "sum" );

        assertEquals( EditStatus.APPLIED, result.status(), () -> result.diagnostics().toString() );
        String account = read( "src/app/Account.java" );
        assertTrue( account.contains( "deposit(int sum)" ), account );
        assertTrue( account.contains( "balance + sum;" ), account );
        assertFalse( touched( result, "src/app/Bank.java", ChangeKind.MODIFIED ), "a parameter has no references elsewhere" );
    }

    @Test
    public void renamesALocalVariable() throws Exception
    {
        EditResult result = rename( "src/app/Account.java", 11, 13, "newBalance" );

        assertEquals( EditStatus.APPLIED, result.status(), () -> result.diagnostics().toString() );
        String account = read( "src/app/Account.java" );
        assertTrue( account.contains( "int newBalance = balance + amount;" ), account );
        assertTrue( account.contains( "balance = newBalance;" ), account );
    }

    @Test
    public void renamesAFieldTogetherWithItsGetter() throws Exception
    {
        EditResult result = service.refactorRenameJavaElement( PROJECT, "src/app/Account.java", 4, 17, "funds", "balance",
            true, false, true );

        assertEquals( EditStatus.APPLIED, result.status(), () -> result.diagnostics().toString() );
        String account = read( "src/app/Account.java" );
        assertTrue( account.contains( "private int funds;" ), account );
        assertTrue( account.contains( "public int getFunds()" ), "the getter followed the field" );
        assertTrue( read( "src/app/Bank.java" ).contains( "account.getFunds()" ), "and so did its callers" );
    }

    @Test
    public void aReferenceSelectsTheDeclarationItPointsAt() throws Exception
    {
        EditResult result = rename( "src/app/Bank.java", 5, 17, "credit" );

        assertEquals( EditStatus.APPLIED, result.status(), () -> result.diagnostics().toString() );
        assertTrue( read( "src/app/Account.java" ).contains( "public void credit(int amount)" ),
            "the declaration in the other file was renamed" );
        assertTrue( touched( result, "src/app/Account.java", ChangeKind.MODIFIED ) );
    }

    @Test
    public void renamesATopLevelTypeAndItsFile() throws Exception
    {
        EditResult result = rename( "src/app/Account.java", 3, 14, "Ledger" );

        assertEquals( EditStatus.APPLIED, result.status(), () -> result.diagnostics().toString() );
        assertEquals( "src/app/Ledger.java", result.filePath(), "the file follows the type" );
        assertTrue( touched( result, "src/app/Ledger.java", ChangeKind.MOVED ), () -> result.affectedResources().toString() );
        assertTrue( project.getFile( "src/app/Ledger.java" ).exists() );
        assertFalse( project.getFile( "src/app/Account.java" ).exists() );
        assertTrue( read( "src/app/Bank.java" ).contains( "void open(Ledger account)" ) );
    }

    @Test
    public void refusesAPositionWhereThereIsNoElement()
    {
        EditResult result = rename( "src/app/Account.java", 2, 1, "anything" );

        assertEquals( EditStatus.REJECTED, result.status() );
        assertEquals( List.of( DiagnosticCode.VALIDATION_ERROR ), result.diagnostics().stream().map( d -> d.code() ).toList() );
    }

    @Test
    public void refusesWhenTheElementThereIsNotTheOneNamed() throws Exception
    {
        EditResult result = service.refactorRenameJavaElement( PROJECT, "src/app/Account.java", 10, 17, "credit", "withdraw",
            true, false, false );

        assertEquals( EditStatus.REJECTED, result.status() );
        assertEquals( List.of( DiagnosticCode.VALIDATION_ERROR ), result.diagnostics().stream().map( d -> d.code() ).toList() );
        assertTrue( result.diagnostics().get( 0 ).message().contains( "deposit" ), result.diagnostics().get( 0 ).message() );
        assertTrue( read( "src/app/Account.java" ).contains( "public void deposit(int amount)" ), "nothing was renamed" );
    }

    @Test
    public void aNameEclipseRefusesIsAFailedPrecondition() throws Exception
    {
        EditResult result = rename( "src/app/Account.java", 10, 17, "1credit" );

        assertEquals( EditStatus.REJECTED, result.status() );
        assertEquals( List.of( DiagnosticCode.REFACTORING_PRECONDITION_FAILED ),
            result.diagnostics().stream().map( d -> d.code() ).toList() );
    }
}
