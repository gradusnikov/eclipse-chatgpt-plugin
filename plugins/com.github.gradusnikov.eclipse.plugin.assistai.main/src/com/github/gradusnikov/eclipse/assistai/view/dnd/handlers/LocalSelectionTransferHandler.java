package com.github.gradusnikov.eclipse.assistai.view.dnd.handlers;

import org.eclipse.core.resources.IContainer;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.runtime.ILog;
import org.eclipse.e4.core.di.annotations.Creatable;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.IPackageFragment;
import org.eclipse.jdt.core.IPackageFragmentRoot;
import org.eclipse.jface.viewers.ITreeSelection;
import org.eclipse.swt.dnd.Transfer;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;

/**
 * Handles drag and drop from workspace views (Package Explorer, Project Explorer, etc.)
 * Adds dropped resources to the ResourceCache instead of rendering attachments in ChatView.
 */
@Creatable
@Singleton
public class LocalSelectionTransferHandler implements ITransferHandler
{
    private static final Transfer TRANSFER = org.eclipse.jface.util.LocalSelectionTransfer.getTransfer();

    @Inject
    private ILog logger;
    
    @Inject
    private ResourceCacheHelper resourceCacheHelper;

    @Override
    public Transfer getTransferType()
    {
        return TRANSFER;
    }

    @Override
    public void handleTransfer(Object data)
    {
        if (data instanceof ITreeSelection selection)
        {
            for (var treePath : selection.getPaths())
            {
                Object lastElement = treePath.getLastSegment();
                handleElement(lastElement);
            }
        }
    }

    private void handleElement(Object element)
    {
        if (element instanceof IFile file)
        {
            resourceCacheHelper.addWorkspaceFile(file);
        }
        else if (element instanceof IContainer container)
        {
            resourceCacheHelper.addWorkspaceContainer(container);
        }
        else if (element instanceof ICompilationUnit compilationUnit)
        {
            handleCompilationUnit(compilationUnit);
        }
        else if (element instanceof IPackageFragment packageFragment)
        {
            handlePackageFragment(packageFragment);
        }
        else if (element instanceof IPackageFragmentRoot packageRoot)
        {
            handlePackageFragmentRoot(packageRoot);
        }
        else if (element instanceof IJavaElement javaElement)
        {
            // Handle other Java elements by getting their underlying resource
            IResource resource = javaElement.getResource();
            if (resource instanceof IFile file)
            {
                resourceCacheHelper.addWorkspaceFile(file);
            }
            else if (resource instanceof IContainer container)
            {
                resourceCacheHelper.addWorkspaceContainer(container);
            }
        }
        else
        {
            logger.warn("Unsupported element type for DnD: " + (element != null ? element.getClass().getName() : "null"));
        }
    }

    private void handleCompilationUnit(ICompilationUnit compilationUnit)
    {
        try
        {
            IResource resource = compilationUnit.getResource();
            if (resource instanceof IFile file)
            {
                resourceCacheHelper.addWorkspaceFile(file);
            }
        }
        catch (Exception e)
        {
            logger.error("Error handling compilation unit: " + e.getMessage(), e);
        }
    }

    private void handlePackageFragment(IPackageFragment packageFragment)
    {
        try
        {
            IResource resource = packageFragment.getResource();
            if (resource instanceof IContainer container)
            {
                resourceCacheHelper.addWorkspaceContainer(container);
            }
        }
        catch (Exception e)
        {
            logger.error("Error handling package fragment: " + e.getMessage(), e);
        }
    }

    private void handlePackageFragmentRoot(IPackageFragmentRoot packageRoot)
    {
        try
        {
            IResource resource = packageRoot.getResource();
            if (resource instanceof IContainer container)
            {
                resourceCacheHelper.addWorkspaceContainer(container);
            }
        }
        catch (Exception e)
        {
            logger.error("Error handling package fragment root: " + e.getMessage(), e);
        }
    }
}
