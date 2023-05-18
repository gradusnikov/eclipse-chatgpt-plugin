package com.github.gradusnikov.eclipse.assistai.part;

import java.util.Arrays;

import javax.annotation.PostConstruct;
import javax.inject.Inject;
import javax.inject.Singleton;

import org.eclipse.core.runtime.ILog;
import org.eclipse.core.runtime.jobs.IJobManager;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.e4.core.di.annotations.Creatable;
import org.eclipse.e4.ui.di.UISynchronize;
import org.eclipse.swt.dnd.Clipboard;
import org.eclipse.swt.dnd.TextTransfer;
import org.eclipse.swt.dnd.Transfer;
import org.eclipse.ui.PlatformUI;

import com.github.gradusnikov.eclipse.assistai.model.ChatMessage;
import com.github.gradusnikov.eclipse.assistai.model.Conversation;
import com.github.gradusnikov.eclipse.assistai.prompt.JobFactory;
import com.github.gradusnikov.eclipse.assistai.subscribers.AppendMessageToViewSubscriber;

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
    private JobFactory jobFactory;        

    @Inject
    private IJobManager jobManager;
    
    @Inject
    private AppendMessageToViewSubscriber appendMessageToViewSubscriber;
    
    
    @PostConstruct
    public void init()
    {
        appendMessageToViewSubscriber.setPresenter( this );
    }
    
    public void onClear()
    {
        onStop();
        conversation.clear();
        partAccessor.findMessageView().ifPresent( ChatGPTViewPart::clearChatView );
    }

    public void onSendUserMessage( String text )
    {
        logger.info( "Send user message" );
        ChatMessage message;
        synchronized( conversation )
        {
            message = conversation.newMessage( "user" );
            message.setMessage( text );
            conversation.add( message );
        }
        partAccessor.findMessageView().ifPresent( part -> { 
            part.clearUserInput(); 
            part.appendMessage( message.getId(), message.getRole() );
            part.setMessageHtml( message.getId(), message.getContent() );
        });
        jobFactory.createSendUserMessageJob( text ).schedule();
    }


    public ChatMessage beginMessageFromAssitant()
    {   
        ChatMessage message;
        synchronized (conversation)
        {
            message = conversation.newMessage( "assistant" );
            conversation.add(message);
        }
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
              .filter( job -> job.getName().startsWith( JobFactory.JOB_PREFIX ) )
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
        ApplyPatchWizzardHelper wizzardHelper = new ApplyPatchWizzardHelper();
        wizzardHelper.showApplyPatchWizardDialog( codeBlock, null );
        
    }
}
