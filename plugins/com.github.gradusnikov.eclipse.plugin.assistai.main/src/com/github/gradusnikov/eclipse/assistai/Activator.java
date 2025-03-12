package com.github.gradusnikov.eclipse.assistai;

import org.eclipse.ui.plugin.AbstractUIPlugin;
import org.osgi.framework.BundleContext;

import com.github.gradusnikov.eclipse.assistai.preferences.ModelListPreferencePresenter;
import com.github.gradusnikov.eclipse.assistai.preferences.PromptsPreferencePresenter;
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
        McpServerPreferencePresenter presneter = new McpServerPreferencePresenter(getPreferenceStore());
        return presneter;
    }    
}
