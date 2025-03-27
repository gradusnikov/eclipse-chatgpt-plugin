package com.github.gradusnikov.eclipse.assistai.part;

import static com.github.gradusnikov.eclipse.assistai.tools.ImageUtilities.createPreview;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.ILog;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.jobs.IJobManager;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.core.runtime.preferences.InstanceScope;
import org.eclipse.e4.core.di.annotations.Creatable;
import org.eclipse.jface.viewers.StructuredSelection;
import org.eclipse.jface.wizard.Wizard;
import org.eclipse.jface.wizard.WizardDialog;
import org.eclipse.swt.SWT;
import org.eclipse.swt.dnd.Clipboard;
import org.eclipse.swt.dnd.TextTransfer;
import org.eclipse.swt.dnd.Transfer;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.ImageData;
import org.eclipse.swt.graphics.ImageLoader;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.FileDialog;
import org.eclipse.ui.IWorkbench;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.dialogs.WizardNewFileCreationPage;
import org.osgi.service.prefs.BackingStoreException;
import org.osgi.service.prefs.Preferences;

import com.github.gradusnikov.eclipse.assistai.jobs.AssistAIJobConstants;
import com.github.gradusnikov.eclipse.assistai.jobs.SendConversationJob;
import com.github.gradusnikov.eclipse.assistai.mcp.services.CodeEditingService;
import com.github.gradusnikov.eclipse.assistai.model.ChatMessage;
import com.github.gradusnikov.eclipse.assistai.model.Conversation;
import com.github.gradusnikov.eclipse.assistai.part.Attachment.FileContentAttachment;
import com.github.gradusnikov.eclipse.assistai.prompt.ChatMessageFactory;
import com.github.gradusnikov.eclipse.assistai.prompt.ChatMessageUtilities;
import com.github.gradusnikov.eclipse.assistai.prompt.Prompts;
import com.github.gradusnikov.eclipse.assistai.subscribers.AppendMessageToViewSubscriber;
import com.github.gradusnikov.eclipse.assistai.tools.ResourceUtilities;

import jakarta.annotation.PostConstruct;
import jakarta.inject.Inject;
import jakarta.inject.Provider;
import jakarta.inject.Singleton;

@Creatable
@Singleton
public class ChatGPTPresenter
{
    @Inject
    private ILog                          logger;

    @Inject
    private PartAccessor                  partAccessor;

    @Inject
    private Conversation                  conversation;

    @Inject
    private ChatMessageFactory            chatMessageFactory;

    @Inject
    private IJobManager                   jobManager;

    @Inject
    private Provider<SendConversationJob> sendConversationJobProvider;

    @Inject
    private AppendMessageToViewSubscriber appendMessageToViewSubscriber;
    
    @Inject
    private ApplyPatchWizardHelper        applyPatchWizzardHelper;
    
    @Inject
    private CodeEditingService codeEditingService;
    

    private static final String           LAST_SELECTED_DIR_KEY = "lastSelectedDirectory";

    // Preference node for your plugin
    private Preferences                   preferences           = InstanceScope.INSTANCE.getNode( "com.github.gradusnikov.eclipse.assistai" );

    private final List<Attachment>        attachments           = new ArrayList<>();

    @PostConstruct
    public void init()
    {
        appendMessageToViewSubscriber.setPresenter( this );
    }

    public void onClear()
    {
        onStop();
        conversation.clear();
        attachments.clear();
        partAccessor.findMessageView().ifPresent( view -> {
            view.clearChatView();
            view.clearUserInput();
            view.clearAttachments();
        } );
    }

    public void onSendUserMessage( String text )
    {
        logger.info( "Send user message" );
        ChatMessage message = createUserMessage( text );
        conversation.add( message );
        partAccessor.findMessageView().ifPresent( part -> {
            part.clearUserInput();
            part.clearAttachments();
            part.appendMessage( message.getId(), message.getRole() );
            String content = ChatMessageUtilities.toMarkdownContent( message );
            part.setMessageHtml( message.getId(), content );
            attachments.clear();
        } );
        sendConversationJobProvider.get().schedule();
    }

    private ChatMessage createUserMessage( String userMessage )
    {
        ChatMessage message = chatMessageFactory.createUserChatMessage( () -> userMessage );
        message.setAttachments( attachments );
        return message;
    }


	public ChatMessage beginMessageFromAssistant()
    {
        ChatMessage message = chatMessageFactory.createAssistantChatMessage( "" );
        conversation.add( message );
        partAccessor.findMessageView().ifPresent( messageView -> {
            messageView.appendMessage( message.getId(), message.getRole() );
            messageView.setInputEnabled( false );
        } );
        return message;
    }

    public void updateMessageFromAssistant( ChatMessage message )
    {
        partAccessor.findMessageView().ifPresent( messageView -> {
            messageView.setMessageHtml( message.getId(), message.getContent() );
        } );
    }

    public void endMessageFromAssistant( ChatMessage message )
    {
        partAccessor.findMessageView().ifPresent( messageView -> {
            messageView.setInputEnabled( true );
        } );
    }
    
    public void hideMessage( String messageId )
    {
        partAccessor.findMessageView().ifPresent( messageView -> {
            messageView.hideMessage(messageId);
        } );
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

        partAccessor.findMessageView().ifPresent( messageView -> {
            messageView.setInputEnabled( true );
        } );
    }

    /**
     * Copies the given code block to the system clipboard.
     *
     * @param codeBlock
     *            The code block to be copied to the clipboard.
     */
    public void onCopyCode( String codeBlock )
    {
        var clipboard = new Clipboard( PlatformUI.getWorkbench().getDisplay() );
        var textTransfer = TextTransfer.getInstance();
        clipboard.setContents( new Object[] { codeBlock }, new Transfer[] { textTransfer } );
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
        partAccessor.findMessageView().ifPresent( messageView -> {
            messageView.appendMessage( message.getId(), message.getRole() );
            messageView.setMessageHtml( message.getId(), type.getDescription() );
        } );

        // schedule message
        sendConversationJobProvider.get().schedule();
    }

    public void onAddAttachment()
    {
        Display display = PlatformUI.getWorkbench().getDisplay();
        if ( Objects.isNull( display ) )
        {
            logger.error( "No active display" );
            return;
        }
        
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
                    attachments.add( new Attachment.ImageAttachment( imageDataArray[0], createPreview( imageDataArray[0] ) ) );
                    applyToView( messageView -> {
                        messageView.setAttachments( attachments );
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
        attachments.add( new Attachment.ImageAttachment( imageData, createPreview( imageData ) ) );
        applyToView( messageView -> {
            messageView.setAttachments( attachments );
        } );
    }

    public void onAttachmentAdded( FileContentAttachment attachment )
    {
        attachments.add( attachment );
        applyToView( messageView -> {
            messageView.setAttachments( attachments );
        } );
    }


    public void onInsertCode(String codeBlock) {
        Display.getDefault().asyncExec(() -> {
            try 
            {
                Optional.ofNullable(PlatformUI.getWorkbench())
                    .map(workbench -> workbench.getActiveWorkbenchWindow())
                    .map(window -> window.getActivePage())
                    .map(page -> page.getActiveEditor())
                    .flatMap(editor -> Optional.ofNullable(editor.getAdapter(org.eclipse.ui.texteditor.ITextEditor.class)))
                    .ifPresent(textEditor -> {
                        var selectionProvider = textEditor.getSelectionProvider();
                        var document = textEditor.getDocumentProvider()
                                                 .getDocument(textEditor.getEditorInput());
                        
                        if (selectionProvider != null && document != null) 
                        {
                            var selection = (org.eclipse.jface.text.ITextSelection) selectionProvider.getSelection();
                            try 
                            {
                                // Replace selection or insert at cursor position
                                if (selection.getLength() > 0) 
                                {
                                    // Replace selected text
                                    document.replace(selection.getOffset(), selection.getLength(), codeBlock);
                                }
                                else 
                                {
                                    // Insert at cursor position
                                    document.replace(selection.getOffset(), 0, codeBlock);
                                }
                            } 
                            catch (org.eclipse.jface.text.BadLocationException e) 
                            {
                                logger.error("Error inserting code at location", e);
                            }
                        } 
                        else 
                        {
                            logger.error("Selection provider or document is null");
                        }
                    });
            } 
            catch (Exception e) 
            {
                logger.error("Error inserting code", e);
            }
        });
    }

    public void onDiffCode(String codeBlock) {
        Display.getDefault().asyncExec(() -> {
            try {
                Optional.ofNullable(PlatformUI.getWorkbench())
                    .map(workbench -> workbench.getActiveWorkbenchWindow())
                    .map(window -> window.getActivePage())
                    .map(page -> page.getActiveEditor())
                    .flatMap(editor -> Optional.ofNullable(editor.getAdapter(org.eclipse.ui.texteditor.ITextEditor.class)))
                    .ifPresent(textEditor -> {
                        // Get the file information
                        if (textEditor.getEditorInput() instanceof org.eclipse.ui.part.FileEditorInput) {
                            org.eclipse.ui.part.FileEditorInput fileInput = 
                                (org.eclipse.ui.part.FileEditorInput) textEditor.getEditorInput();
                            
                            // Get project name and file path
                            String projectName = fileInput.getFile().getProject().getName();
                            String filePath = fileInput.getFile().getProjectRelativePath().toString();
                            
                            // Generate diff using the CodeEditingService
                            String diff = codeEditingService.generateCodeDiff(
                                projectName, 
                                filePath, 
                                codeBlock, 
                                3 // Default context lines
                            );
                            
                            if (diff != null && !diff.isBlank() ) 
                            {
                                // Show the apply patch wizard with the generated diff and preselected project
                                applyPatchWizzardHelper.showApplyPatchWizardDialog(diff, projectName);
                            } else {
                                logger.info("No differences found between current code and provided code block");
                            }
                        } else {
                            logger.error("Cannot get file information from editor");
                        }
                    });
            } catch (Exception e) {
                logger.error("Error generating diff for code", e);
            }
        });
    }



    public void onNewFile(String codeBlock, String lang) 
    {
        Display.getDefault().asyncExec(() -> {
            try 
            {
                IProject project = Optional.ofNullable( PlatformUI.getWorkbench() )
                        .map(IWorkbench::getActiveWorkbenchWindow)
                        .map( IWorkbenchWindow::getActivePage )
                        .map( IWorkbenchPage::getActiveEditor )
                        .map(editor -> editor.getEditorInput())
                        .filter(input -> input instanceof org.eclipse.ui.part.FileEditorInput)
                        .map(input -> ((org.eclipse.ui.part.FileEditorInput) input).getFile().getProject())
                        .orElse(null);

                if (project != null) 
                {
                    // Create suggested file name and path based on language
                	String suggestedFileName = ResourceUtilities.getSuggestedFileName(lang, codeBlock);
                    IPath suggestedPath      = ResourceUtilities.getSuggestedPath(project, lang, codeBlock);
                    WizardNewFileCreationPage newFilePage = new WizardNewFileCreationPage("NewFilePage", new StructuredSelection(project));
                    newFilePage.setTitle("New File");
                    newFilePage.setDescription(String.format("Create a new %s file in the project", ResourceUtilities.getFileExtensionForLang( lang )) );
                    
                    // Set suggested file name and path
                    if (suggestedPath != null) 
                    {
                        newFilePage.setContainerFullPath(suggestedPath);
                    }
                    if (suggestedFileName != null && !suggestedFileName.isBlank()) 
                    {
                        newFilePage.setFileName(suggestedFileName);
                    }
                    
                    
                    Wizard wizard = new Wizard() {
                        @Override
                        public void addPages() 
                        {
                            addPage(newFilePage);
                        }

                        @Override
                        public boolean performFinish() {
                            IFile newFile = newFilePage.createNewFile();
                            if (newFile != null) 
                            {
                                try (InputStream stream = new ByteArrayInputStream(codeBlock.getBytes(StandardCharsets.UTF_8))) 
                                {
                                    newFile.setContents(stream, true, true, null);
                                    logger.info("New file created at: " + newFile.getFullPath().toString());
                                    return true;
                                } 
                                catch (CoreException | IOException e) 
                                {
                                    logger.error("Error creating new file", e);
                                }
                            }
                            return false;
                        }
                    };

                    WizardDialog dialog = new WizardDialog(PlatformUI.getWorkbench().getActiveWorkbenchWindow().getShell(), wizard);
                    dialog.open();
                } 
                else 
                {
                    logger.error("No active project found");
                }
            } 
            catch (Exception e) 
            {
                logger.error("Error opening new file wizard", e);
            }
        });
    }
}

