package com.github.gradusnikov.eclipse.assistai.prompt;

import java.util.stream.Collectors;

import com.github.gradusnikov.eclipse.assistai.model.ChatMessage;
import com.github.gradusnikov.eclipse.assistai.tools.ImageUtilities;

public class ChatMessageUtilities
{
    public static String toMarkdownContent( ChatMessage message )
    {
        String content = message.getContent();
        if ( !message.getImages().isEmpty() )
        {
            content += "\n"+ message.getImages()
            .stream()
            .map( ImageUtilities::toBase64Jpeg )
            .map( data -> "![image](data:image/jpeg;base64," + data + ")" )
            .collect( Collectors.joining( "\n" ) );
        }
        return content;
    }
}
