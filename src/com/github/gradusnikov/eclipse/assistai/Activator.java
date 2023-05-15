package com.github.gradusnikov.eclipse.assistai;

import org.eclipse.jface.preference.IPreferenceStore;
import org.eclipse.ui.plugin.AbstractUIPlugin;
import org.osgi.framework.BundleContext;

public class Activator extends AbstractUIPlugin {
    private static Activator plugin = null;
    
    @Override
    public void start(BundleContext context) throws Exception {
        super.start(context);
        plugin = this;
    }
    
    public static Activator getDefault() {
        return plugin;
    }
    
    public IPreferenceStore getPreferenceStore() {
        return super.getPreferenceStore();
    }
    
    // rest of the class code goes here
}
