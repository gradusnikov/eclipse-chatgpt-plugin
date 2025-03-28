package com.github.gradusnikov.eclipse.plugin.assistai.main;


import org.junit.jupiter.api.Test;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;

import com.vladsch.flexmark.html2md.converter.FlexmarkHtmlConverter;

public class HtmlToMarkdownConverterTest {
    
    @Test
    public void testHtmlToMarkdown() {
        String html = "<h3>Hello World</h3>"; // Your HTML goes here

        var options   = FlexmarkHtmlConverter.builder()
                                             .toImmutable();
        var converter = FlexmarkHtmlConverter.builder( options ).build();
        String markdown = converter.convert( html );

        System.out.println(markdown); // Prints: "### Hello World"
        
        assertThat( markdown.trim(), is("### Hello World") );
        
    }
}
