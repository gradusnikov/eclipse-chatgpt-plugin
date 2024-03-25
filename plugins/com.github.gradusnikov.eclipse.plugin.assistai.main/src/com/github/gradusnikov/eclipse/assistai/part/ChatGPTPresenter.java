package com.github.gradusnikov.eclipse.assistai.part;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import jakarta.annotation.PostConstruct;
import jakarta.inject.Inject;
import jakarta.inject.Provider;
import jakarta.inject.Singleton;

import org.eclipse.core.runtime.ILog;
import org.eclipse.core.runtime.jobs.IJobManager;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.core.runtime.preferences.InstanceScope;
import org.eclipse.e4.core.di.annotations.Creatable;
import org.eclipse.swt.SWT;
import org.eclipse.swt.dnd.Clipboard;
import org.eclipse.swt.dnd.TextTransfer;
import org.eclipse.swt.dnd.Transfer;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.ImageData;
import org.eclipse.swt.graphics.ImageLoader;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.FileDialog;
import org.eclipse.ui.PlatformUI;
import org.osgi.service.prefs.BackingStoreException;
import org.osgi.service.prefs.Preferences;

import com.github.gradusnikov.eclipse.assistai.jobs.AssistAIJobConstants;
import com.github.gradusnikov.eclipse.assistai.jobs.SendConversationJob;
import com.github.gradusnikov.eclipse.assistai.model.ChatMessage;
import com.github.gradusnikov.eclipse.assistai.model.Conversation;
import com.github.gradusnikov.eclipse.assistai.prompt.ChatMessageFactory;
import com.github.gradusnikov.eclipse.assistai.prompt.ChatMessageUtilities;
import com.github.gradusnikov.eclipse.assistai.prompt.Prompts;
import com.github.gradusnikov.eclipse.assistai.subscribers.AppendMessageToViewSubscriber;
import com.github.gradusnikov.eclipse.assistai.tools.ImageUtilities;

@Creatable
@Singleton
public class ChatGPTPresenter
{
    @Inject
    private ILog logger;
    
    @Inject
    private PartAccessor partAccessor;
    
    @Inject
    private Conversation conversation;
    
    @Inject
    private ChatMessageFactory chatMessageFactory;        

    @Inject
    private IJobManager jobManager;
    
    @Inject
    private Provider<SendConversationJob> sendConversationJobProvider;
    
    @Inject
    private AppendMessageToViewSubscriber appendMessageToViewSubscriber;
    
    @Inject
    private ApplyPatchWizardHelper applyPatchWizzardHelper;
    
    private static final String LAST_SELECTED_DIR_KEY = "lastSelectedDirectory";

    // Preference node for your plugin
    private Preferences preferences = InstanceScope.INSTANCE.getNode("com.github.gradusnikov.eclipse.assistai");
    
    private ArrayList<ImageData> images;
    
    
    @PostConstruct
    public void init()
    {
        images = new ArrayList<>();
        appendMessageToViewSubscriber.setPresenter( this );
    }
    
    public void onClear()
    {
        onStop();
        conversation.clear();
        images.clear();
        partAccessor.findMessageView().ifPresent( view -> {
            view.clearChatView();
            view.clearUserInput();
            view.clearAttachments();
        } );
    }

    public void onSendUserMessage( String text )
    {
        logger.info( "Send user message" );
        ChatMessage message = chatMessageFactory.createUserChatMessage( () -> text );
        message.setImages( images );
        conversation.add( message );
        partAccessor.findMessageView().ifPresent( part -> { 
            part.clearUserInput(); 
            part.clearAttachments();
            part.appendMessage( message.getId(), message.getRole() );
            String content = ChatMessageUtilities.toMarkdownContent( message );
            part.setMessageHtml( message.getId(), content  );
            images.clear();
        });
        sendConversationJobProvider.get().schedule();
    }


    public ChatMessage beginMessageFromAssistant()
    {   
        ChatMessage message = chatMessageFactory.createAssistantChatMessage("");
        conversation.add(message);
        partAccessor.findMessageView().ifPresent(messageView -> {
                messageView.appendMessage(message.getId(), message.getRole());
                messageView.setInputEnabled( false );
            });
        return message;
    }


    public void updateMessageFromAssistant( ChatMessage message )
    {
        partAccessor.findMessageView().ifPresent(messageView -> {
            messageView.setMessageHtml( message.getId(), message.getContent() );   
        });
    }


    public void endMessageFromAssistant()
    {
        partAccessor.findMessageView().ifPresent(messageView -> {
            messageView.setInputEnabled( true );
        });
    }
    /**
     * Cancels all running ChatGPT jobs
     */
    public void onStop()
    {
        var jobs = jobManager.find( null );
        Arrays.stream( jobs )
              .filter( job -> job.getName().startsWith( AssistAIJobConstants.JOB_PREFIX ) )
              .forEach( Job::cancel );
        
        partAccessor.findMessageView().ifPresent(messageView -> {
            messageView.setInputEnabled( true );
        });
    }
    /**
     * Copies the given code block to the system clipboard.
     *
     * @param codeBlock The code block to be copied to the clipboard.
     */
    public void onCopyCode( String codeBlock )
    {
        var clipboard    = new Clipboard(PlatformUI.getWorkbench().getDisplay());
        var textTransfer = TextTransfer.getInstance();
        clipboard.setContents(new Object[] { codeBlock }, new Transfer[] { textTransfer });
        clipboard.dispose();
    }

    public void onApplyPatch( String codeBlock )
    {
        applyPatchWizzardHelper.showApplyPatchWizardDialog( codeBlock, null );
        
    }

    public void onSendPredefinedPrompt( Prompts type, ChatMessage message )
    {
        conversation.add( message );
        
        // update view
        partAccessor.findMessageView().ifPresent(messageView -> {
            messageView.appendMessage( message.getId(), message.getRole() );
            messageView.setMessageHtml( message.getId(), type.getDescription() );
        });        
        
        // schedule message
        sendConversationJobProvider.get().schedule();
    }

    public void onAddAttachment()
    {
        Display display = PlatformUI.getWorkbench().getDisplay();
        display.asyncExec( () -> {
            FileDialog fileDialog = new FileDialog( display.getActiveShell(), SWT.OPEN );
            fileDialog.setText( "Select an Image" );

            // Retrieve the last selected directory from the preferences
            String lastSelectedDirectory = preferences.get( LAST_SELECTED_DIR_KEY, System.getProperty( "user.home" ) );
            fileDialog.setFilterPath( lastSelectedDirectory );

            fileDialog.setFilterExtensions( new String[] { "*.png", "*.jpeg", "*.jpg" } );
            fileDialog.setFilterNames( new String[] { "PNG files (*.png)", "JPEG files (*.jpeg, *.jpg)" } );

            String selectedFilePath = fileDialog.open();

            if ( selectedFilePath != null )
            {
                // Save the last selected directory back to the preferences
                String newLastSelectedDirectory = new File( selectedFilePath ).getParent();
                preferences.put( LAST_SELECTED_DIR_KEY, newLastSelectedDirectory );

                try
                {
                    // Ensure that the preference changes are persisted
                    preferences.flush();
                }
                catch ( BackingStoreException e )
                {
                    logger.error( "Error saving last selected directory preference", e );
                }

                ImageData[] imageDataArray = new ImageLoader().load( selectedFilePath );
                if ( imageDataArray.length > 0 )
                {
                    images.add( imageDataArray[0] );
                    applyToView( messageView -> {
                        messageView.setAttachments( images );
                    } );
                }
            }
        } );
    }
    
    public void applyToView( Consumer<? super ChatGPTViewPart> consumer )
    {
        partAccessor.findMessageView().ifPresent( consumer );
    }

    public void onImageSelected( Image image )
    {
        System.out.println( "selected" );
    }

    public void onAttachmentAdded( ImageData imageData )
    {
        images.add( imageData );
        applyToView( messageView -> {
            messageView.setAttachments( images );
        } );

    }
    
}
