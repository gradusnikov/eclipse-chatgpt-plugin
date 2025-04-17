package com.github.gradusnikov.eclipse.assistai;

import org.eclipse.e4.core.contexts.ContextInjectionFactory;
import org.eclipse.e4.core.contexts.IEclipseContext;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.plugin.AbstractUIPlugin;
import org.osgi.framework.BundleContext;

import com.github.gradusnikov.eclipse.assistai.preferences.mcp.McpServerPreferencePresenter;
import com.github.gradusnikov.eclipse.assistai.preferences.models.ModelListPreferencePresenter;
import com.github.gradusnikov.eclipse.assistai.preferences.prompts.PromptsPreferencePresenter;

public class Activator extends AbstractUIPlugin 
{
    private static Activator plugin = null;
    
    @Override
    public void start(BundleContext context) throws Exception 
    {
        super.start(context);
        plugin = this;


    }
    
    public static Activator getDefault() 
    {
        return plugin;
    }
    
    public PromptsPreferencePresenter getPromptsPreferencePresenter()
    {
        IEclipseContext eclipseContext = PlatformUI.getWorkbench().getService( IEclipseContext.class );
        var presenter = ContextInjectionFactory.make( PromptsPreferencePresenter.class, eclipseContext );
        return presenter;
    }
    
    public ModelListPreferencePresenter getModelsPreferencePresenter()
    {
        IEclipseContext eclipseContext = PlatformUI.getWorkbench().getService( IEclipseContext.class );
        var presenter = ContextInjectionFactory.make( ModelListPreferencePresenter.class, eclipseContext );
        return presenter;
    }
    

    public McpServerPreferencePresenter getMCPServerPreferencePresenter() 
    {
        IEclipseContext eclipseContext = PlatformUI.getWorkbench().getService( IEclipseContext.class );
        var presenter = ContextInjectionFactory.make( McpServerPreferencePresenter.class, eclipseContext );
        return presenter;
    }    
}
