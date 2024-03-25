package com.github.gradusnikov.eclipse.assistai.tools;

import java.io.ByteArrayOutputStream;
import java.util.Base64;

import org.eclipse.swt.graphics.ImageData;
import org.eclipse.swt.graphics.ImageLoader;

public class ImageUtilities
{
    /**
     * Converts ImageData to a Base64 encoded JPEG string.
     *
     * @param image ImageData to be converted to Base64
     * @return Base64 encoded JPEG string
     */
    public static String toBase64Jpeg(ImageData image) {
        if (image == null) 
        {
            throw new IllegalArgumentException("ImageData argument is null");
        }

        ImageLoader loader = new ImageLoader();
        loader.data = new ImageData[]{image}; // Set the image data

        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        // Save as JPEG format
        loader.save(outputStream, org.eclipse.swt.SWT.IMAGE_JPEG);
        String base64 = Base64.getEncoder().encodeToString(outputStream.toByteArray());
        return base64;
    }
}
