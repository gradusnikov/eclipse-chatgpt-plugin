package com.github.gradusnikov.eclipse.assistai.view;

import static com.github.gradusnikov.eclipse.assistai.tools.ImageUtilities.createPreview;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.ILog;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.jobs.IJobManager;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.e4.core.di.annotations.Creatable;
import org.eclipse.e4.ui.di.UISynchronize;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jface.preference.IPreferenceStore;
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
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.IWorkbench;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.dialogs.WizardNewFileCreationPage;

import com.github.gradusnikov.eclipse.assistai.Activator;
import com.github.gradusnikov.eclipse.assistai.chat.Attachment;
import com.github.gradusnikov.eclipse.assistai.chat.Attachment.FileContentAttachment;
import com.github.gradusnikov.eclipse.assistai.chat.ChatHistoryRepository;
import com.github.gradusnikov.eclipse.assistai.chat.ChatHistoryRepository.ChatHistorySession;
import com.github.gradusnikov.eclipse.assistai.chat.ChatHistoryRepository.PersistedMessage;
import com.github.gradusnikov.eclipse.assistai.chat.ChatMessage;
import com.github.gradusnikov.eclipse.assistai.chat.Conversation;
import com.github.gradusnikov.eclipse.assistai.jobs.AssistAIJobConstants;
import com.github.gradusnikov.eclipse.assistai.jobs.SendConversationJob;
import com.github.gradusnikov.eclipse.assistai.mcp.services.CodeEditingService;
import com.github.gradusnikov.eclipse.assistai.models.ModelApiDescriptorRepository;
import com.github.gradusnikov.eclipse.assistai.network.subscribers.AppendMessageToViewSubscriber;
import com.github.gradusnikov.eclipse.assistai.prompt.ChatMessageFactory;
import com.github.gradusnikov.eclipse.assistai.prompt.ChatMessageUtilities;
import com.github.gradusnikov.eclipse.assistai.prompt.PromptRepository;
import com.github.gradusnikov.eclipse.assistai.prompt.Prompts;
import com.github.gradusnikov.eclipse.assistai.resources.IResourceCacheListener;
import com.github.gradusnikov.eclipse.assistai.resources.ResourceCache;
import com.github.gradusnikov.eclipse.assistai.resources.ResourceCacheEvent;
import com.github.gradusnikov.eclipse.assistai.tools.ResourceUtilities;
import com.github.gradusnikov.eclipse.assistai.view.ChatView.NotificationType;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.inject.Inject;
import jakarta.inject.Provider;
import jakarta.inject.Singleton;

@Creatable
@Singleton
public class ChatViewPresenter implements IResourceCacheListener
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
    private CodeEditingService            codeEditingService;

    @Inject
    private ModelApiDescriptorRepository  modelReposotiry;

    @Inject
    private PromptRepository              promptRepository;

    @Inject
    private UISynchronize                 uiSync;

    @Inject
    private ResourceCache                 resourceCache;

    @Inject
    private ChatHistoryRepository         historyRepository;

    private IPreferenceStore              preferences;

    private static final String           LAST_SELECTED_DIR_KEY = "lastSelectedDirectory";

    private final List<Attachment>        attachments           = new ArrayList<>();

    @PostConstruct
    public void init()
    {
        preferences = Activator.getDefault().getPreferenceStore();
        appendMessageToViewSubscriber.setPresenter( this );
        resourceCache.addCacheListener( this );
        initializeAvailableModels();
    }

    /** Flush the current session to disk when the workbench shuts down. */
    @PreDestroy
    public void destroy()
    {
        historyRepository.saveCurrentSession( conversation.messages() );
    }

    @Override
    public void cacheChanged( ResourceCacheEvent event )
    {
        if ( event.getType() == ResourceCacheEvent.Type.ADDED && event.getResource() != null )
        {
            String resourceName = event.getResource().descriptor().displayName();
            applyToView( view -> {
                view.showNotification(
                    "Resource added: " + resourceName,
                    Duration.ofSeconds( 3 ),
                    NotificationType.INFO );
            } );
        }
    }

    private void initializeAvailableModels()
    {
        var selectedModel = modelReposotiry.getChatModelInUse();
        var models        = modelReposotiry.listModelApiDescriptors();
        applyToView( view -> {
            view.setAvailableModels( models, Optional.ofNullable( selectedModel.uid() ).orElse( "" ) );
        } );
    }

    public void onClear()
    {
        onStop();
        // Persist the current conversation before wiping it
        historyRepository.saveCurrentSession( conversation.messages() );
        historyRepository.startNewSession();
        conversation.clear();
        attachments.clear();
        resourceCache.clear();
        applyToView( view -> {
            view.clearChatView();
            view.clearUserInput();
            view.clearAttachments();
        } );
    }

    public void onSendUserMessage( String text )
    {
        ChatMessage message = createUserMessage( text );
        conversation.add( message );
        ChatMessage displayedMessage = createUserMessage( "" );
        displayedMessage.setContent( text );
        applyToView( part -> {
            part.clearUserInput();
            part.clearAttachments();
            part.appendMessage( message.getId(), message.getRole() );
            String content = ChatMessageUtilities.toMarkdownContent( displayedMessage );
            part.setMessageHtml( message.getId(), content );
            attachments.clear();
        } );
        sendConversationJobProvider.get().schedule();
    }

    private ChatMessage createUserMessage( String userMessage )
    {
        Pattern commandPattern = Pattern.compile( "^/(\\S+)" );
        Matcher commandMatcher = commandPattern.matcher( userMessage );
        Supplier<String> supplier = () -> userMessage;
        if ( commandMatcher.find() )
        {
            supplier = () -> promptRepository.findPromptByCommandName( commandMatcher.group( 1 ) )
                                             .map( chatMessageFactory::createUserChatMessage )
                                             .map( ChatMessage::getContent )
                                             .orElse( userMessage );
        }
        ChatMessage message = chatMessageFactory.createUserChatMessage( supplier );
        message.setAttachments( attachments );
        return message;
    }

    public ChatMessage beginFunctionCallMessage()
    {
        ChatMessage message = chatMessageFactory.createAssistantChatMessage( "" );
        // DO NOT ADD IT TO CONVERSATION
        applyToView( messageView -> {
            messageView.appendMessage( message.getId(), message.getRole() );
            messageView.setInputEnabled( false );
        } );
        return message;
    }

    public ChatMessage beginMessageFromAssistant()
    {
        ChatMessage message = chatMessageFactory.createAssistantChatMessage( "" );
        conversation.add( message );
        applyToView( messageView -> {
            messageView.appendMessage( message.getId(), message.getRole() );
            messageView.setInputEnabled( false );
        } );
        return message;
    }

    public void updateMessageFromAssistant( ChatMessage message )
    {
        applyToView( messageView -> {
            messageView.setMessageHtml( message.getId(), message.getContent() );
        } );
    }

    public void endMessageFromAssistant( ChatMessage message )
    {
        applyToView( messageView -> {
            messageView.setInputEnabled( true );
            messageView.setFocus();
            if ( message.getContent().isBlank() )
            {
                conversation.removeMessageById( message.getId() );
                messageView.removeMessage( message.getId() );
            }
            // Auto-save after every completed assistant turn
            historyRepository.saveCurrentSession( conversation.messages() );
        } );
    }

    public void hideMessage( String messageId )
    {
        applyToView( messageView -> {
            messageView.hideMessage( messageId );
        } );
    }

    /**
     * Cancels all running ChatGPT jobs.
     */
    public void onStop()
    {
        var jobs = jobManager.find( null );
        Arrays.stream( jobs )
              .filter( job -> job.getName().startsWith( AssistAIJobConstants.JOB_PREFIX ) )
              .forEach( Job::cancel );
        applyToView( messageView -> {
            messageView.setInputEnabled( true );
        } );
    }

    /**
     * Copies the given code block to the system clipboard.
     *
     * @param codeBlock the code block to copy
     */
    public void onCopyCode( String codeBlock )
    {
        var clipboard    = new Clipboard( PlatformUI.getWorkbench().getDisplay() );
        var textTransfer = TextTransfer.getInstance();
        clipboard.setContents( new Object[]{ codeBlock }, new Transfer[]{ textTransfer } );
        clipboard.dispose();
    }

    public void onApplyPatch( String codeBlock )
    {
        applyPatchWizzardHelper.showApplyPatchWizardDialog( codeBlock, null );
    }

    public void onSendPredefinedPrompt( Prompts type, ChatMessage message )
    {
        conversation.add( message );
        applyToView( messageView -> {
            messageView.appendMessage( message.getId(), message.getRole() );
            messageView.setMessageHtml( message.getId(), "/" + type.getCommandName() );
        } );
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

        uiSync.asyncExec( () -> {
            FileDialog fileDialog = new FileDialog( display.getActiveShell(), SWT.OPEN );
            fileDialog.setText( "Select an Image" );
            String lastSelectedDirectory = preferences.getString( LAST_SELECTED_DIR_KEY );
            fileDialog.setFilterPath( lastSelectedDirectory );
            fileDialog.setFilterExtensions( new String[]{ "*.png", "*.jpeg", "*.jpg" } );
            fileDialog.setFilterNames( new String[]{ "PNG files (*.png)", "JPEG files (*.jpeg, *.jpg)" } );

            String selectedFilePath = fileDialog.open();
            if ( selectedFilePath != null )
            {
                String newLastSelectedDirectory = new File( selectedFilePath ).getParent();
                preferences.putValue( LAST_SELECTED_DIR_KEY, newLastSelectedDirectory );
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

    public void applyToView( Consumer<? super ChatView> consumer )
    {
        uiSync.asyncExec( () -> {
            partAccessor.findMessageView().ifPresent( consumer );
        } );
    }

    public void showError( String message )
    {
        ChatMessage errorMessage = chatMessageFactory.createAssistantChatMessage(
                "**Error:** " + message );
        applyToView( view -> {
            view.appendMessage( errorMessage.getId(), errorMessage.getRole() );
            view.setMessageHtml( errorMessage.getId(), errorMessage.getContent() );
            view.setInputEnabled( true );
            view.setFocus();
        } );
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

    public void onInsertCode( String codeBlock )
    {
        uiSync.asyncExec( () -> {
            try
            {
                Optional.ofNullable( PlatformUI.getWorkbench() )
                        .map( workbench -> workbench.getActiveWorkbenchWindow() )
                        .map( window -> window.getActivePage() )
                        .map( page -> page.getActiveEditor() )
                        .flatMap( editor -> Optional.ofNullable( editor.getAdapter( org.eclipse.ui.texteditor.ITextEditor.class ) ) )
                        .ifPresent( textEditor -> {
                            var selectionProvider = textEditor.getSelectionProvider();
                            var document = textEditor.getDocumentProvider()
                                                     .getDocument( textEditor.getEditorInput() );
                            if ( selectionProvider != null && document != null )
                            {
                                var selection = (org.eclipse.jface.text.ITextSelection) selectionProvider.getSelection();
                                try
                                {
                                    if ( selection.getLength() > 0 )
                                    {
                                        document.replace( selection.getOffset(), selection.getLength(), codeBlock );
                                    }
                                    else
                                    {
                                        document.replace( selection.getOffset(), 0, codeBlock );
                                    }
                                }
                                catch ( org.eclipse.jface.text.BadLocationException e )
                                {
                                    logger.error( "Error inserting code at location", e );
                                }
                            }
                            else
                            {
                                logger.error( "Selection provider or document is null" );
                            }
                        } );
            }
            catch ( Exception e )
            {
                logger.error( "Error inserting code", e );
            }
        } );
    }

    public void onDiffCode( String codeBlock )
    {
        uiSync.asyncExec( () -> {
            try
            {
                Optional.ofNullable( PlatformUI.getWorkbench() )
                        .map( workbench -> workbench.getActiveWorkbenchWindow() )
                        .map( window -> window.getActivePage() )
                        .map( page -> page.getActiveEditor() )
                        .flatMap( editor -> Optional.ofNullable( editor.getAdapter( org.eclipse.ui.texteditor.ITextEditor.class ) ) )
                        .ifPresent( textEditor -> {
                            if ( textEditor.getEditorInput() instanceof org.eclipse.ui.part.FileEditorInput )
                            {
                                org.eclipse.ui.part.FileEditorInput fileInput =
                                        (org.eclipse.ui.part.FileEditorInput) textEditor.getEditorInput();
                                String projectName = fileInput.getFile().getProject().getName();
                                String filePath    = fileInput.getFile().getProjectRelativePath().toString();
                                String diff        = codeEditingService.generateCodeDiff( projectName, filePath, codeBlock, 3 );
                                if ( diff != null && !diff.isBlank() )
                                {
                                    applyPatchWizzardHelper.showApplyPatchWizardDialog( diff, projectName );
                                }
                                else
                                {
                                    logger.info( "No differences found between current code and provided code block" );
                                }
                            }
                            else
                            {
                                logger.error( "Cannot get file information from editor" );
                            }
                        } );
            }
            catch ( Exception e )
            {
                logger.error( "Error generating diff for code", e );
            }
        } );
    }

    public void onNewFile( String codeBlock, String lang )
    {
        uiSync.asyncExec( () -> {
            try
            {
                IProject project = Optional.ofNullable( PlatformUI.getWorkbench() )
                        .map( IWorkbench::getActiveWorkbenchWindow )
                        .map( IWorkbenchWindow::getActivePage )
                        .map( IWorkbenchPage::getActiveEditor )
                        .map( editor -> editor.getEditorInput() )
                        .filter( input -> input instanceof org.eclipse.ui.part.FileEditorInput )
                        .map( input -> ((org.eclipse.ui.part.FileEditorInput) input).getFile().getProject() )
                        .orElse( null );

                if ( project != null )
                {
                    String suggestedFileName = ResourceUtilities.getSuggestedFileName( lang, codeBlock );
                    IPath  suggestedPath     = ResourceUtilities.getSuggestedPath( project, lang, codeBlock );
                    WizardNewFileCreationPage newFilePage = new WizardNewFileCreationPage( "NewFilePage", new StructuredSelection( project ) );
                    newFilePage.setTitle( "New File" );
                    newFilePage.setDescription( String.format( "Create a new %s file in the project", ResourceUtilities.getFileExtensionForLang( lang ) ) );
                    if ( suggestedPath != null )
                    {
                        newFilePage.setContainerFullPath( suggestedPath );
                    }
                    if ( suggestedFileName != null && !suggestedFileName.isBlank() )
                    {
                        newFilePage.setFileName( suggestedFileName );
                    }

                    Wizard wizard = new Wizard()
                    {
                        @Override
                        public void addPages()
                        {
                            addPage( newFilePage );
                        }

                        @Override
                        public boolean performFinish()
                        {
                            IFile newFile = newFilePage.createNewFile();
                            if ( newFile != null )
                            {
                                try ( InputStream stream = new ByteArrayInputStream( codeBlock.getBytes( StandardCharsets.UTF_8 ) ) )
                                {
                                    newFile.setContents( stream, true, true, null );
                                    logger.info( "New file created at: " + newFile.getFullPath().toString() );
                                    return true;
                                }
                                catch ( CoreException | IOException e )
                                {
                                    logger.error( "Error creating new file", e );
                                }
                            }
                            return false;
                        }
                    };

                    WizardDialog dialog = new WizardDialog( PlatformUI.getWorkbench().getActiveWorkbenchWindow().getShell(), wizard );
                    dialog.open();
                }
                else
                {
                    logger.error( "No active project found" );
                }
            }
            catch ( Exception e )
            {
                logger.error( "Error opening new file wizard", e );
            }
        } );
    }

    /**
     * Handles model selection from the dropdown menu.
     *
     * @param modelId the ID of the selected model
     */
    public void onChatModelSelected( String modelId )
    {
        logger.info( "Model selected: " + modelId );
        modelReposotiry.setChatModelInUse( modelId );
        initializeAvailableModels();
    }

    /**
     * Regenerates the last AI response using the currently selected model.
     */
    public void onReplayLastMessage()
    {
        logger.info( "Replaying last message with current model" );
        if ( conversation.messages().isEmpty() )
        {
            return;
        }
        List<ChatMessage> messages = conversation.messages();
        if ( !messages.isEmpty() && "assistant".equals( messages.get( messages.size() - 1 ).getRole() ) )
        {
            ChatMessage lastMessage = messages.get( messages.size() - 1 );
            conversation.removeLastMessage();
            applyToView( view -> {
                view.removeMessage( lastMessage.getId() );
            } );
        }
        sendConversationJobProvider.get().schedule();
    }

    public void onViewVisible()
    {
        initializeAvailableModels();
        updateAutocomplete();
    }

    public void onRemoveMessage( String messageId )
    {
        this.conversation.removeMessageById( messageId );
        applyToView( view -> {
            view.removeMessage( messageId );
        } );
    }

    public void onRemoveAttachment( int index )
    {
        if ( index >= 0 && index < attachments.size() )
        {
            attachments.remove( index );
            applyToView( view -> {
                view.setAttachments( attachments );
            } );
        }
    }

    public void updateAutocomplete()
    {
        Map<String, String> mappings = promptRepository.getAllPrompts()
                                                       .stream()
                                                       .collect( Collectors.toMap( Prompts::getCommandName, Prompts::getDescription ) );
        applyToView( view -> view.setAutocompleteModel( mappings ) );
    }

    // -------------------------------------------------------------------------
    // Chat history
    // -------------------------------------------------------------------------

    /**
     * Opens the Chat History dialog.  If the user selects a session and presses
     * Load, the current conversation is replaced by the stored one.
     */
    public void onShowHistory()
    {
        uiSync.asyncExec( () -> {
            Shell shell = PlatformUI.getWorkbench().getActiveWorkbenchWindow().getShell();

            List<ChatHistorySession> sessions = new ArrayList<>( historyRepository.listSessions() );
            if ( sessions.isEmpty() )
            {
                MessageDialog.openInformation(
                        shell,
                        "Chat History",
                        "No saved sessions found yet.\n\nSessions are saved automatically after each assistant response." );
                return;
            }

            ChatHistoryDialog dialog = new ChatHistoryDialog( shell, sessions );
            int rc = dialog.open();

            // Apply deletions the user made inside the dialog
            List<String> remainingIds = dialog.getRemainingSessionIds()
                                              .stream()
                                              .map( ChatHistorySession::id )
                                              .collect( Collectors.toList() );
            historyRepository.listSessions().stream()
                             .filter( s -> !remainingIds.contains( s.id() ) )
                             .forEach( s -> historyRepository.deleteSession( s.id() ) );

            // Load the selected session when the user pressed Load
            if ( rc == org.eclipse.jface.dialogs.Dialog.OK )
            {
                ChatHistorySession loaded = dialog.getLoadedSession();
                if ( loaded != null )
                {
                    loadHistorySession( loaded );
                }
            }
        } );
    }

    /**
     * Replaces the current conversation with the messages from the given
     * persisted session and re-renders the chat view.
     *
     * @param session the session to restore
     */
    private void loadHistorySession( ChatHistorySession session )
    {
        onStop();
        conversation.clear();
        attachments.clear();
        resourceCache.clear();

        List<ChatMessage> restored = new ArrayList<>();
        if ( session.messages() != null )
        {
            for ( PersistedMessage pm : session.messages() )
            {
                ChatMessage msg = new ChatMessage( pm.id(), pm.name(), pm.role() );
                msg.setContent( pm.content() != null ? pm.content() : "" );

                // Restore file attachments (images cannot be persisted)
                if ( pm.attachments() != null )
                {
                    List<Attachment> atts = new ArrayList<>();
                    for ( var pa : pm.attachments() )
                    {
                        if ( "file".equals( pa.type() ) )
                        {
                            atts.add( new Attachment.FileContentAttachment(
                                    pa.filePath(),
                                    pa.lineNumberStart(),
                                    pa.lineNumberEnd(),
                                    pa.selectedContent() ) );
                        }
                    }
                    msg.setAttachments( atts );
                }
                restored.add( msg );
            }
        }
        restored.forEach( conversation::add );

        // Mark this session as current so subsequent auto-saves update the same file
        historyRepository.setCurrentSession( session.id() );

        // Re-render the chat view with the restored messages
        applyToView( view -> {
            view.clearChatView();
            view.clearAttachments();
            view.renderConversation( restored );
        } );
    }
}
