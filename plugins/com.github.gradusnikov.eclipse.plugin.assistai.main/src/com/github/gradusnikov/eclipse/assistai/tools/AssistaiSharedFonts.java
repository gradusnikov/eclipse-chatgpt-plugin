package com.github.gradusnikov.eclipse.assistai.tools;

import java.io.ByteArrayInputStream;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Base64;
import java.util.stream.Collectors;

import org.apache.fontbox.ttf.TTFParser;
import org.eclipse.core.runtime.ILog;
import org.eclipse.e4.core.di.annotations.Creatable;

import com.google.common.io.Files;

import jakarta.inject.Inject;

@Creatable
public class AssistaiSharedFonts
{
    @Inject
    private ILog logger;
    
    @Inject
    private AssistaiSharedFiles sharedFiles;
    
    private record FontInfo(String fontFamily, String fontWeight, String fontStyle, String fontDataBase64, String format) {};
    


    private FontInfo readFontWithFontBox(String path) {
        var fontWeight = "normal";
        var fontStyle  = "normal";
        var fontFamily = Paths.get(path).getFileName().toString()
                                .replaceFirst("\\.[^.]+$", "")
                                .replaceAll("[-_](Regular|Bold|Italic|BoldItalic|Solid|Light|Thin|Duotone|Brands)$", "");
        var format = Files.getFileExtension(Paths.get(path).getFileName().toString());
        if ("ttf".equalsIgnoreCase(format)) {
            format = "truetype";
        }
        
        // Infer style from filename
        String filename = Paths.get(path).getFileName().toString();
        if (filename.contains("Italic")) 
        {
            fontStyle = "italic";
        }
        if (filename.contains("Bold")) 
        {
            fontWeight = "bold";
        }
        if ( filename.contains( "solid" ) )
        {
            fontWeight = "900";
        }
        if ( filename.contains( "regular" ) )
        {
            fontWeight = "400";
        }

        try 
        {
            var fontData       = sharedFiles.readResourceBytes(path);
            var fontDataBase64 = Base64.getEncoder().encodeToString(fontData);
            var  parser = new TTFParser();
            try (var is = new ByteArrayInputStream(fontData)) 
            {
                var ttf = parser.parse(is);
                
                // Extract font name if available
                var name = ttf.getNaming().getFontFamily();
                if (name != null && !name.isEmpty()) 
                {
                    fontFamily = name;
                    // normalize family name
                    fontFamily = fontFamily.replaceAll("\\s+(Regular|Solid|Light|Thin|Duotone|Brands)$", "");
                }
                // Additional metadata could be extracted here
                ttf.close();
            }
            return new FontInfo(fontFamily, fontWeight, fontStyle, fontDataBase64, format);
        } 
        catch (Exception e) 
        {
            throw new RuntimeException("Error parsing font " + path + ": " + e.getMessage(), e);
        }
        
    }

    
    private String toFontFaceCss(FontInfo fontInfo) 
    {
        String fontFaceTemplate = """
                @font-face {
                      font-family: '${fontFamily}';
                      src: url('data:font/${format};base64,${fontDataBase64}') format('${format}');
                      font-weight: ${fontWeight};
                      font-style: ${fontStyle};
                }
            """;
        
        fontFaceTemplate = fontFaceTemplate.replace("${fontFamily}", fontInfo.fontFamily)
                                           .replace("${fontWeight}", fontInfo.fontWeight)
                                           .replace("${fontStyle}", fontInfo.fontStyle)
                                           .replace("${fontDataBase64}", fontInfo.fontDataBase64)
                                           .replace("${format}", fontInfo.format);
        
        return fontFaceTemplate;
    }
    /**
     * Loads all the required fonts and generates CSS with embedded font data
     * @return CSS containing all font definitions
     */
    public String loadFontsCss() {
        String[] fontFiles = {
                "fonts/fa-regular-400.ttf",
                "fonts/fa-solid-900.ttf",
                "fonts/KaTeX_AMS-Regular.ttf",
                "fonts/KaTeX_Caligraphic-Bold.ttf",
                "fonts/KaTeX_Caligraphic-Regular.ttf",
                "fonts/KaTeX_Fraktur-Bold.ttf",
                "fonts/KaTeX_Fraktur-Regular.ttf",
                "fonts/KaTeX_Main-Bold.ttf",
                "fonts/KaTeX_Main-BoldItalic.ttf",
                "fonts/KaTeX_Main-Italic.ttf",
                "fonts/KaTeX_Main-Regular.ttf",
                "fonts/KaTeX_Math-BoldItalic.ttf",
                "fonts/KaTeX_Math-Italic.ttf",
                "fonts/KaTeX_SansSerif-Bold.ttf",
                "fonts/KaTeX_SansSerif-Italic.ttf",
                "fonts/KaTeX_SansSerif-Regular.ttf",
                "fonts/KaTeX_Script-Regular.ttf",
                "fonts/KaTeX_Size1-Regular.ttf",
                "fonts/KaTeX_Size2-Regular.ttf",
                "fonts/KaTeX_Size3-Regular.ttf",
                "fonts/KaTeX_Size4-Regular.ttf",
                "fonts/KaTeX_Typewriter-Regular.ttf"
        };
        try
        {
            var css = Arrays.stream( fontFiles )
                    .map( this::readFontWithFontBox )
                    .map( this::toFontFaceCss )
                    .collect( Collectors.joining( "\n\n" ) );
            return css;
        }
        catch ( Exception e )
        {
            throw new RuntimeException( e );
        }
    }

    
}
