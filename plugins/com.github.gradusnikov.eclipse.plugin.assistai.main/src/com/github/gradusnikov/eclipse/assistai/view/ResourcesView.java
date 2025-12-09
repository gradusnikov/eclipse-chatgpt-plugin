package com.github.gradusnikov.eclipse.assistai.view;

import java.util.List;
import java.util.Map;

import org.eclipse.e4.core.di.annotations.Creatable;
import org.eclipse.e4.ui.di.Focus;
import org.eclipse.jface.viewers.ColumnViewerToolTipSupport;
import org.eclipse.jface.viewers.ITreeContentProvider;
import org.eclipse.jface.viewers.StyledCellLabelProvider;
import org.eclipse.jface.viewers.StyledString;
import org.eclipse.jface.viewers.TreeViewer;
import org.eclipse.jface.viewers.ViewerCell;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Menu;
import org.eclipse.swt.widgets.MenuItem;
import org.eclipse.swt.widgets.ToolBar;
import org.eclipse.swt.widgets.ToolItem;
import org.eclipse.ui.ISharedImages;
import org.eclipse.ui.PlatformUI;

import com.github.gradusnikov.eclipse.assistai.resources.ResourceDescriptor.ResourceType;
import com.github.gradusnikov.eclipse.assistai.view.ResourcesPresenter.ResourceNode;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.inject.Inject;

/**
 * View for displaying cached resources in a tree structure organized by ResourceType.
 * This view is passive - it only displays data and delegates all actions to the presenter.
 */
@Creatable
public class ResourcesView
{
    @Inject
    private ResourcesPresenter presenter;
    
    private TreeViewer treeViewer;
    private Label statsLabel;
    private Map<ResourceType, List<ResourceNode>> currentModel;
    
    @PostConstruct
    public void createControls(Composite parent)
    {
        parent.setLayout(new GridLayout(1, false));
        
        // Toolbar with actions
        ToolBar toolBar = createToolBar(parent);
        toolBar.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        
        // Tree viewer for resources
        treeViewer = new TreeViewer(parent, SWT.BORDER | SWT.SINGLE | SWT.V_SCROLL | SWT.H_SCROLL);
        treeViewer.getTree().setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
        
        // Enable tooltip support
        ColumnViewerToolTipSupport.enableFor(treeViewer);
        
        treeViewer.setContentProvider(new ResourceTreeContentProvider());
        treeViewer.setLabelProvider(new ResourceTreeLabelProvider());
        
        // Selection listener - delegate to presenter
        treeViewer.addSelectionChangedListener(event -> {
            Object selected = event.getStructuredSelection().getFirstElement();
            if (selected instanceof ResourceNode resourceNode)
            {
                presenter.onResourceSelected(resourceNode.uri());
            }
        });
        
        // Double-click to open resource in editor
        treeViewer.addDoubleClickListener(event -> {
            Object selected = treeViewer.getStructuredSelection().getFirstElement();
            if (selected instanceof ResourceType type)
            {
                // Toggle expand/collapse on category double-click
                treeViewer.setExpandedState(type, !treeViewer.getExpandedState(type));
            }
            else if (selected instanceof ResourceNode resourceNode)
            {
                // Open resource in editor
                presenter.onResourceDoubleClicked(resourceNode.uri());
            }
        });
        
        // Context menu
        createContextMenu();
        
        // Stats label at the bottom
        statsLabel = new Label(parent, SWT.NONE);
        statsLabel.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        statsLabel.setText("No resources cached");
        
        // Register with presenter
        presenter.registerView(this);
    }
    
    @PreDestroy
    public void dispose()
    {
        presenter.unregisterView();
    }
    
    @Focus
    public void setFocus()
    {
        if (treeViewer != null && !treeViewer.getTree().isDisposed())
        {
            treeViewer.getTree().setFocus();
        }
    }
    
    private ToolBar createToolBar(Composite parent)
    {
        ToolBar toolBar = new ToolBar(parent, SWT.FLAT | SWT.RIGHT);
        
        // Refresh button
        ToolItem refreshItem = new ToolItem(toolBar, SWT.PUSH);
        refreshItem.setImage(PlatformUI.getWorkbench().getSharedImages().getImage(ISharedImages.IMG_ELCL_SYNCED));
        refreshItem.setToolTipText("Refresh");
        refreshItem.addListener(SWT.Selection, e -> presenter.refreshView());
        
        // Clear all button
        ToolItem clearItem = new ToolItem(toolBar, SWT.PUSH);
        clearItem.setImage(PlatformUI.getWorkbench().getSharedImages().getImage(ISharedImages.IMG_ETOOL_CLEAR));
        clearItem.setToolTipText("Clear all cached resources");
        clearItem.addListener(SWT.Selection, e -> presenter.onClearAll());
        
        // Expand all button
        ToolItem expandItem = new ToolItem(toolBar, SWT.PUSH);
        expandItem.setImage(PlatformUI.getWorkbench().getSharedImages().getImage(ISharedImages.IMG_OBJ_ADD));
        expandItem.setToolTipText("Expand all");
        expandItem.addListener(SWT.Selection, e -> treeViewer.expandAll());
        
        // Collapse all button
        ToolItem collapseItem = new ToolItem(toolBar, SWT.PUSH);
        collapseItem.setImage(PlatformUI.getWorkbench().getSharedImages().getImage(ISharedImages.IMG_ELCL_COLLAPSEALL));
        collapseItem.setToolTipText("Collapse all");
        collapseItem.addListener(SWT.Selection, e -> treeViewer.collapseAll());
        
        return toolBar;
    }
    
    private void createContextMenu()
    {
        Menu contextMenu = new Menu(treeViewer.getTree());
        
        // Open item - only for ResourceNode
        MenuItem openItem = new MenuItem(contextMenu, SWT.PUSH);
        openItem.setText("Open in Editor");
        openItem.setImage(PlatformUI.getWorkbench().getSharedImages().getImage(ISharedImages.IMG_OBJ_FILE));
        openItem.addListener(SWT.Selection, e -> {
            Object selected = treeViewer.getStructuredSelection().getFirstElement();
            if (selected instanceof ResourceNode resourceNode)
            {
                presenter.onResourceDoubleClicked(resourceNode.uri());
            }
        });
        
        // Separator
        new MenuItem(contextMenu, SWT.SEPARATOR);
        
        // Remove item - only for ResourceNode
        MenuItem removeItem = new MenuItem(contextMenu, SWT.PUSH);
        removeItem.setText("Remove from cache");
        removeItem.setImage(PlatformUI.getWorkbench().getSharedImages().getImage(ISharedImages.IMG_ETOOL_DELETE));
        removeItem.addListener(SWT.Selection, e -> {
            Object selected = treeViewer.getStructuredSelection().getFirstElement();
            if (selected instanceof ResourceNode resourceNode)
            {
                presenter.onRemoveResource(resourceNode.uri());
            }
        });
        
        // Separator
        new MenuItem(contextMenu, SWT.SEPARATOR);
        
        // Clear all
        MenuItem clearAllItem = new MenuItem(contextMenu, SWT.PUSH);
        clearAllItem.setText("Clear all");
        clearAllItem.setImage(PlatformUI.getWorkbench().getSharedImages().getImage(ISharedImages.IMG_ETOOL_CLEAR));
        clearAllItem.addListener(SWT.Selection, e -> presenter.onClearAll());
        
        // Show/hide menu items based on selection
        contextMenu.addMenuListener(new org.eclipse.swt.events.MenuAdapter()
        {
            @Override
            public void menuShown(org.eclipse.swt.events.MenuEvent e)
            {
                Object selected = treeViewer.getStructuredSelection().getFirstElement();
                boolean isResourceNode = selected instanceof ResourceNode;
                openItem.setEnabled(isResourceNode);
                removeItem.setEnabled(isResourceNode);
            }
        });
        
        treeViewer.getTree().setMenu(contextMenu);
    }
    
    /**
     * Updates the tree model and refreshes the display.
     * Called by the presenter when cache data changes.
     */
    public void setTreeModel(Map<ResourceType, List<ResourceNode>> model, String stats)
    {
        this.currentModel = model;
        
        if (treeViewer != null && !treeViewer.getTree().isDisposed())
        {
            // Filter to only show types that have resources
            ResourceType[] nonEmptyTypes = model.entrySet().stream()
                .filter(e -> !e.getValue().isEmpty())
                .map(Map.Entry::getKey)
                .toArray(ResourceType[]::new);
            
            treeViewer.setInput(nonEmptyTypes);
            treeViewer.expandAll();
        }
        
        if (statsLabel != null && !statsLabel.isDisposed())
        {
            statsLabel.setText(stats);
        }
    }
    
    /**
     * Content provider for the tree structure.
     * Categories (ResourceType) are parents, ResourceNodes are children.
     */
    private class ResourceTreeContentProvider implements ITreeContentProvider
    {
        @Override
        public Object[] getElements(Object inputElement)
        {
            if (inputElement instanceof ResourceType[] types)
            {
                return types;
            }
            return new Object[0];
        }

        @Override
        public Object[] getChildren(Object parentElement)
        {
            if (parentElement instanceof ResourceType type && currentModel != null)
            {
                List<ResourceNode> children = currentModel.get(type);
                return children != null ? children.toArray() : new Object[0];
            }
            return new Object[0];
        }

        @Override
        public Object getParent(Object element)
        {
            // Not needed for our use case
            return null;
        }

        @Override
        public boolean hasChildren(Object element)
        {
            if (element instanceof ResourceType type && currentModel != null)
            {
                List<ResourceNode> children = currentModel.get(type);
                return children != null && !children.isEmpty();
            }
            return false;
        }
    }
    
    /**
     * Styled label provider for tree nodes with tooltip support.
     * Provides display text, icons, and tooltips for categories and resources.
     */
    private class ResourceTreeLabelProvider extends StyledCellLabelProvider
    {
        @Override
        public void update(ViewerCell cell)
        {
            Object element = cell.getElement();
            ISharedImages sharedImages = PlatformUI.getWorkbench().getSharedImages();
            
            if (element instanceof ResourceType type)
            {
                int count = currentModel != null ? currentModel.get(type).size() : 0;
                StyledString text = new StyledString(ResourcesPresenter.getDisplayNameForType(type));
                text.append(" (" + count + ")", StyledString.COUNTER_STYLER);
                cell.setText(text.getString());
                cell.setStyleRanges(text.getStyleRanges());
                cell.setImage(sharedImages.getImage(ISharedImages.IMG_OBJ_FOLDER));
            }
            else if (element instanceof ResourceNode node)
            {
                StyledString text = new StyledString(node.displayName());
                text.append(" (v" + node.version() + ", ~" + node.tokens() + " tokens)", StyledString.DECORATIONS_STYLER);
                cell.setText(text.getString());
                cell.setStyleRanges(text.getStyleRanges());
                cell.setImage(sharedImages.getImage(ISharedImages.IMG_OBJ_FILE));
            }
            else
            {
                cell.setText(element.toString());
            }
            
            super.update(cell);
        }
        
        @Override
        public String getToolTipText(Object element)
        {
            if (element instanceof ResourceNode node)
            {
                return node.tooltip();
            }
            else if (element instanceof ResourceType type)
            {
                return ResourcesPresenter.getDisplayNameForType(type);
            }
            return null;
        }
        
        @Override
        public int getToolTipDisplayDelayTime(Object object)
        {
            return 200;
        }
        
        @Override
        public int getToolTipTimeDisplayed(Object object)
        {
            return 10000;
        }
    }
}
