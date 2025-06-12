package com.github.gradusnikov.eclipse.assistai.tools;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.HashMap;
import java.util.Map;

import org.eclipse.e4.core.di.annotations.Creatable;
import org.eclipse.jdt.ui.ISharedImages;
import org.eclipse.jface.resource.ImageDescriptor;
import org.eclipse.swt.graphics.Image;

import jakarta.inject.Singleton;

@Creatable
@Singleton
public class AssistaiSharedImages implements ISharedImages {
	
	private Map<String, Image> cache = new HashMap<String, Image>();
	
	@Override
	public Image getImage(String key) 
	{
		return cache.computeIfAbsent(key, newKey -> getImageDescriptor(newKey).createImage() );
	}
	@Override
	public ImageDescriptor getImageDescriptor(String key) {
		
		String uri = String.format( "platform:/plugin/com.github.gradusnikov.eclipse.plugin.assistai.main/icons/%s.png", key);
		try 
		{
			return ImageDescriptor.createFromURI(new URI(uri));
		} 
		catch (URISyntaxException e) 
		{
			throw new RuntimeException(e);
		}
	}

}
