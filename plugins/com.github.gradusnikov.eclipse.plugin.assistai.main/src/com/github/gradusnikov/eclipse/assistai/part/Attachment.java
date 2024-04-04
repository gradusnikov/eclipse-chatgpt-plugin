package com.github.gradusnikov.eclipse.assistai.part;

import org.apache.commons.lang3.StringUtils;
import org.eclipse.jface.resource.ImageDescriptor;
import org.eclipse.swt.graphics.ImageData;

/**
 * Represents an attachment to a chat message, e.g. an image or content from a
 * file.
 */
public interface Attachment
{
    String toChatMessageContent();

    String toMarkdownContent();

    ImageData getImageData();

    void accept( UiVisitor visitor );

    public interface UiVisitor
    {
        void add( ImageData preview, String caption );
    }

    abstract class BaseAttachment implements Attachment
    {
        @Override
        public String toChatMessageContent()
        {
            return null;
        }

        @Override
        public String toMarkdownContent()
        {
            return null;
        }

        @Override
        public ImageData getImageData()
        {
            return null;
        }
    }

    /**
     * Represents a context item with a file name, start and end line numbers,
     * and selected content.
     */
    public class FileContentAttachment extends BaseAttachment
    {
        private static ImageData                  icon;

        static
        {
            ImageDescriptor iconDescriptor = ImageDescriptor.createFromFile( FileContentAttachment.class, "/icons/folder.png" );
            icon = iconDescriptor.getImageData( 100 );
        }

        private final String filePath;

        private final int    lineNumberStart;

        private final int    lineNumberEnd;

        private final String selectedContent;

        public FileContentAttachment( String filePath, int lineNumberStart, int lineNumberEnd, String selectedContent )
        {
            this.filePath = filePath;
            this.lineNumberStart = lineNumberStart;
            this.lineNumberEnd = lineNumberEnd;
            this.selectedContent = selectedContent;
        }

        public String getFileName()
        {
            return filePath;
        }

        public int getLineNumberStart()
        {
            return lineNumberStart;
        }

        public int getLineNumberEnd()
        {
            return lineNumberEnd;
        }

        public String getSelectedContent()
        {
            return selectedContent;
        }

        @Override
        public String toChatMessageContent()
        {
            return String.format( """
                    === Context
                    File: %s
                    Lines: %s
                    %s
                    ===
                    """, filePath, lineNumberStart > 0 ? lineNumberStart + "-" + lineNumberEnd : "unknown", selectedContent );
        }

        @Override
        public String toMarkdownContent()
        {
            String fileName = getFileName( filePath );
            return String.format( """
                    <|ContextStart|>
                    File: %s
                    Lines: %d-%d
                    <|ContentStart|>
                    %s
                    <|ContentEnd|>
                    <|ContextEnd|>
                    """, fileName, lineNumberStart, lineNumberEnd, selectedContent );
        }

        @Override
        public void accept( UiVisitor visitor )
        {
            visitor.add( icon, String.format( "File: %s, Line: %d-%d\n\n%s", getFileName( filePath ), lineNumberStart, lineNumberEnd, trimmedContent() ) );
        }

        private String trimmedContent()
        {
            int previewMaxLength = 500;
            String previewContent = StringUtils.stripToEmpty( this.selectedContent )
                    .replaceAll( "\\s*\n\r?", "\n" );
            int maxLength = Math.min( previewMaxLength, previewContent.length() );
            int completeLength = previewContent.length();
            previewContent = previewContent.substring( 0, maxLength );

            // Special case: if the content is a single line, return it as is
            if ( previewContent.indexOf( "\n" ) == -1 )
            {
                return previewContent;
            }

            String res = "";

            int lineNo = 1;
            int offset = 0, lineLength;
            String indent = "";
            do
            {
                lineLength = previewContent.substring( offset ).indexOf( '\n' );

                if ( lineLength < 0 )
                {
                    if ( offset < maxLength )
                    {
                        res += previewContent.substring( offset, maxLength ).replaceAll( "^" + indent, "" );
                    }

                    break;
                }

                String line = previewContent.substring( offset, offset + lineLength );
                offset += line.length() + 1;

                if ( lineNo == 2 )
                {
                    indent = line.replaceAll( "[^\\s].*$", "" );
                }

                res += StringUtils.stripEnd( line.replaceAll( "^" + indent, "" ), null ) + "\n";
                lineNo++;
            }
            while ( offset < previewMaxLength );

            if ( maxLength < completeLength )
            {
                res += "\n";
                res += indent;
                res += "...";
            }

            return res;
        }

        private String getFileName( String pathStr )
        {
            String[] path = pathStr.split( "/" );
            if ( path.length == 0 )
            {
                return pathStr;
            }
            else
            {
                return path[path.length - 1];
            }
        }
    }

    public class ImageAttachment extends BaseAttachment
    {
        private final ImageData image;

        private final ImageData preview;

        public ImageAttachment( ImageData image, ImageData preview )
        {
            this.image = image;
            this.preview = preview;
        }

        public ImageData getImageData()
        {
            return image;
        }

        @Override
        public void accept( UiVisitor visitor )
        {
            visitor.add( preview, null );
        }
    }
}
