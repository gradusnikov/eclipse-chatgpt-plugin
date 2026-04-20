package com.github.gradusnikov.eclipse.assistai.handlers;

import org.eclipse.core.resources.IContainer;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.runtime.ILog;
import org.eclipse.e4.core.di.annotations.Execute;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.IMethod;
import org.eclipse.jdt.core.IPackageFragment;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.JavaModelException;
import org.eclipse.jdt.ui.JavaUI;
import org.eclipse.jface.text.ITextSelection;
import org.eclipse.jface.viewers.ISelection;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.ui.IEditorInput;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.texteditor.ITextEditor;

import com.github.gradusnikov.eclipse.assistai.view.dnd.handlers.ResourceCacheHelper;

import jakarta.inject.Inject;

public class AddToAIResourcesHandler
{
    @Inject
    private ResourceCacheHelper resourceCacheHelper;

    @Inject
    private ILog logger;

    @Execute
    public void execute()
    {
        IWorkbenchPage page = PlatformUI.getWorkbench().getActiveWorkbenchWindow().getActivePage();
        if ( page == null )
        {
            return;
        }

        ISelection selection = page.getSelection();

        if ( selection instanceof IStructuredSelection structured && !structured.isEmpty() )
        {
            handleStructuredSelection( structured );
        }
        else
        {
            handleEditorContext( page );
        }
    }

    private void handleStructuredSelection( IStructuredSelection selection )
    {
        for ( var it = selection.iterator(); it.hasNext(); )
        {
            Object element = it.next();
            if ( element instanceof IFile file )
            {
                resourceCacheHelper.addWorkspaceFile( file );
            }
            else if ( element instanceof IContainer container )
            {
                resourceCacheHelper.addWorkspaceContainer( container );
            }
            else if ( element instanceof IType type )
            {
                resourceCacheHelper.addJavaType( type );
            }
            else if ( element instanceof IMethod method )
            {
                resourceCacheHelper.addJavaMethod( method );
            }
            else if ( element instanceof ICompilationUnit cu )
            {
                addCompilationUnit( cu );
            }
            else if ( element instanceof IPackageFragment pkg )
            {
                addPackageFragment( pkg );
            }
            else if ( element instanceof IJavaElement javaElement )
            {
                IResource resource = javaElement.getResource();
                if ( resource instanceof IFile file )
                {
                    resourceCacheHelper.addWorkspaceFile( file );
                }
            }
        }
    }

    private void handleEditorContext( IWorkbenchPage page )
    {
        IEditorPart editor = page.getActiveEditor();
        if ( editor == null )
        {
            return;
        }

        if ( editor instanceof ITextEditor textEditor )
        {
            ISelection sel = textEditor.getSelectionProvider().getSelection();
            if ( sel instanceof ITextSelection textSelection && textSelection.getLength() == 0 )
            {
                IJavaElement resolved = resolveJavaElementAtCursor( textEditor, textSelection );
                if ( resolved instanceof IMethod method )
                {
                    resourceCacheHelper.addJavaMethod( method );
                    return;
                }
                else if ( resolved instanceof IType type )
                {
                    resourceCacheHelper.addJavaType( type );
                    return;
                }
            }
        }

        IEditorInput input = editor.getEditorInput();
        IFile file = input.getAdapter( IFile.class );
        if ( file != null )
        {
            resourceCacheHelper.addWorkspaceFile( file );
        }
    }

    private IJavaElement resolveJavaElementAtCursor( ITextEditor editor, ITextSelection selection )
    {
        try
        {
            IJavaElement inputElement = JavaUI.getEditorInputJavaElement( editor.getEditorInput() );
            if ( inputElement instanceof ICompilationUnit cu )
            {
                IJavaElement element = cu.getElementAt( selection.getOffset() );
                if ( element instanceof IType || element instanceof IMethod )
                {
                    return element;
                }
            }
        }
        catch ( JavaModelException e )
        {
            logger.warn( "Could not resolve Java element at cursor: " + e.getMessage() );
        }
        return null;
    }

    private void addCompilationUnit( ICompilationUnit cu )
    {
        try
        {
            IResource resource = cu.getResource();
            if ( resource instanceof IFile file )
            {
                resourceCacheHelper.addWorkspaceFile( file );
            }
        }
        catch ( Exception e )
        {
            logger.error( "Error adding compilation unit to AI Resources", e );
        }
    }

    private void addPackageFragment( IPackageFragment pkg )
    {
        try
        {
            IResource resource = pkg.getResource();
            if ( resource instanceof IContainer container )
            {
                resourceCacheHelper.addWorkspaceContainer( container );
            }
        }
        catch ( Exception e )
        {
            logger.error( "Error adding package to AI Resources", e );
        }
    }
}
