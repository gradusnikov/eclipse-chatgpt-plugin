package com.github.gradusnikov.eclipse.assistai;

import java.util.Objects;

import org.eclipse.core.runtime.ILog;
import org.eclipse.e4.core.contexts.ContextInjectionFactory;
import org.eclipse.e4.core.contexts.IEclipseContext;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.plugin.AbstractUIPlugin;
import org.osgi.framework.BundleContext;

import com.github.gradusnikov.eclipse.assistai.preferences.ModelListPreferencePresenter;
import com.github.gradusnikov.eclipse.assistai.preferences.PreferenceConstants;
import com.github.gradusnikov.eclipse.assistai.preferences.PromptsPreferencePresenter;
import com.github.gradusnikov.eclipse.assistai.mcp.McpClientRetistry;
import com.github.gradusnikov.eclipse.assistai.preferences.McpServerPreferencePresenter;

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
    
    public PromptsPreferencePresenter getPromptsPreferncePresenter()
    {
        PromptsPreferencePresenter presenter = new PromptsPreferencePresenter( getDefault().getPreferenceStore() );
        return presenter;
    }
    
    public ModelListPreferencePresenter getModelsPreferencePresenter()
    {
        ModelListPreferencePresenter presneter = new ModelListPreferencePresenter( getDefault().getPreferenceStore() );
        return presneter;
    }
    

    public McpServerPreferencePresenter getMCPServerPreferencePresenter() 
    {
        IEclipseContext eclipseContext = PlatformUI.getWorkbench().getService( IEclipseContext.class );
        var registry = ContextInjectionFactory.make( McpClientRetistry.class, eclipseContext );
        Objects.requireNonNull( registry, "No actual object of class " + McpClientRetistry.class + " found!" );
        
        McpServerPreferencePresenter presneter = new McpServerPreferencePresenter( 
                getDefault().getPreferenceStore(), 
                registry, 
                getLog() );
        return presneter;
    }    
}
