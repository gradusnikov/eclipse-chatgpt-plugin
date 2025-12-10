package com.github.gradusnikov.eclipse.assistai;

import org.eclipse.e4.core.contexts.ContextInjectionFactory;
import org.eclipse.e4.core.contexts.EclipseContextFactory;
import org.eclipse.e4.core.contexts.IEclipseContext;
import org.eclipse.ui.IWorkbench;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.plugin.AbstractUIPlugin;
import org.osgi.framework.BundleContext;

import com.github.gradusnikov.eclipse.assistai.models.ModelApiDescriptorRepository;
import com.github.gradusnikov.eclipse.assistai.preferences.mcp.McpHttpServerPreferencePresenter;
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
        return  make ( PromptsPreferencePresenter.class );
    }
    
    public ModelListPreferencePresenter getModelsPreferencePresenter()
    {
        return make ( ModelListPreferencePresenter.class );
       
    }

    public McpServerPreferencePresenter getMCPServerPreferencePresenter()
    {
        return make( McpServerPreferencePresenter.class );
    }

    public McpHttpServerPreferencePresenter getHttpMcpServerPreferencePresenter()
    {
        return make( McpHttpServerPreferencePresenter.class );
    }

    public ModelApiDescriptorRepository getModelApiDescriptorRepository()
    {
        return make( ModelApiDescriptorRepository.class );
    }
    
    public <T> T make ( Class<T> clazz )
    {
        IEclipseContext eclipseContext;
        try
        {
            IWorkbench workbench = PlatformUI.getWorkbench();
            eclipseContext = workbench.getService( IEclipseContext.class );
        }
        catch ( Exception e )
        {
            BundleContext bundleContext = getBundle().getBundleContext();
            eclipseContext =  EclipseContextFactory.getServiceContext( bundleContext );
        }
        T instance = ContextInjectionFactory.make( clazz, eclipseContext );
        return instance;
    }
}
