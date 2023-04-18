package com.github.gradusnikov.eclipse.assistai.handlers;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Flow;
import java.util.concurrent.Flow.Subscription;

import javax.inject.Inject;
import javax.inject.Named;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.e4.core.di.annotations.Execute;
import org.eclipse.e4.ui.model.application.MApplication;
import org.eclipse.e4.ui.model.application.ui.basic.MPart;
import org.eclipse.e4.ui.services.IServiceConstants;
import org.eclipse.e4.ui.workbench.modeling.EModelService;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.JavaModelException;
import org.eclipse.jdt.ui.JavaUI;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.ITextSelection;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.texteditor.ITextEditor;

import com.github.gradusnikov.eclipse.assistai.Activator;
import com.github.gradusnikov.eclipse.assistai.part.ChatGPTViewPart;
import com.github.gradusnikov.eclipse.assistai.services.OpenAIStreamJavaHttpClient;


public class AssistAICodeRefactorHandler {
	
	@Inject
	private MApplication application;
	
	@Inject 
	private EModelService modelService;

	private List<ChatMessage> conversation = new ArrayList<>();
	
	public AssistAICodeRefactorHandler()
	{
	}
	
        public Flow.Subscriber<String> createSubscriber()
        {
            return new Flow.Subscriber<String>()
            {
                private Flow.Subscription subscription;
                private ChatMessage message;

                @Override
                public void onSubscribe(Subscription subscription)
                {
                    this.subscription = subscription;
                    synchronized (conversation)
                    {
                        message = new ChatMessage(conversation.size(), "assistent");
                        conversation.add(message);
                    }
                    findMessageView().ifPresent(messageView -> messageView.appendMessage(message.id));
                    subscription.request(1);
                }

                @Override
                public void onNext(String item)
                {
                    message.append(item);
                    findMessageView().ifPresent(messageView -> messageView.setMessageHtml(message.id, message.message));
                    subscription.request(1);
                }

                @Override
                public void onError(Throwable throwable)
                {
                    Activator.getDefault().getLog().error(throwable.getMessage(), throwable);
                }

                @Override
                public void onComplete()
                {
                    subscription.request(1);
                }
            };
        }

        private Flow.Subscriber<String> createPrintSubscriber()
        {
            return new Flow.Subscriber<String>()
            {
                private Flow.Subscription subscription;

                @Override
                public void onSubscribe(Subscription subscription)
                {
                    this.subscription = subscription;
                    subscription.request(1);
                }

                @Override
                public void onNext(String item)
                {
                    System.out.print(item);
                    subscription.request(1);
                }

                @Override
                public void onError(Throwable throwable)
                {
                }

                @Override
                public void onComplete()
                {
                    System.out.print("\n\n");
                    subscription.request(1);
                }
            };

        }
	
	@Execute
	public void execute(@Named(IServiceConstants.ACTIVE_SHELL) Shell s ) {	
		
		Activator.getDefault().getLog().info("Asking AI to refactor the code");
		
		// Get the active editor
		IWorkbenchPage activePage   = PlatformUI.getWorkbench().getActiveWorkbenchWindow().getActivePage();
		IEditorPart    activeEditor = activePage.getActiveEditor();
		
		// Check if it is a text editor
		if (activeEditor instanceof ITextEditor) {
			ITextEditor textEditor = (ITextEditor) activeEditor;
			
			// Retrieve the document and text selection
			ITextSelection textSelection = (ITextSelection) textEditor.getSelectionProvider().getSelection();
			
			Activator.getDefault().getLog().info("Text selection:\n" + textSelection);
			
			
			IDocument document = textEditor.getDocumentProvider().getDocument(textEditor.getEditorInput());
			String documentText = document.get();
			String selectedText = textSelection.getText();
			
			String fileName = activeEditor.getEditorInput().getName();
			
			IJavaElement elem = JavaUI.getEditorInputJavaElement(textEditor.getEditorInput());
			if (elem instanceof ICompilationUnit) {
			    ITextSelection sel = (ITextSelection) textEditor.getSelectionProvider().getSelection();
			    IJavaElement selected;
				try {
					selected = ((ICompilationUnit) elem).getElementAt(sel.getOffset());
					if (selected != null && selected.getElementType() == IJavaElement.METHOD) {
						System.out.println("Selected method: " + selected );
					}
				} catch (JavaModelException e) {
					// TODO Auto-generated catch block
					e.printStackTrace();
				}
			}
			
			Job job = new Job("Asking AI for help") {
			    @Override
			    protected IStatus run(IProgressMonitor arg0) {
			        Activator.getDefault().getLog().info("Execution thread");
			        OpenAIStreamJavaHttpClient openAIClient = new OpenAIStreamJavaHttpClient();
			        openAIClient.subscribe(createSubscriber());
                                openAIClient.subscribe(createPrintSubscriber());

                                try (InputStream in = getClass().getResourceAsStream("refactor-prompt.txt");
                                     BufferedReader reader = new BufferedReader(new InputStreamReader(in)) )
                                {
                                    StringBuilder promptBuilder = new StringBuilder();
                                    String line;
                                    while ((line = reader.readLine()) != null)
                                    {
                                        promptBuilder.append(line).append("\n");
                                    }
                                    String prompt = promptBuilder.toString();
                                    prompt = prompt.replace("${documentText}", documentText);
                                    prompt = prompt.replace("${selectedText}", selectedText);
                                    prompt = prompt.replace("${fileName}", fileName);

                                    openAIClient.run(prompt);
                                } 
                                catch (Exception e)
                                {
                                    return Status.error("Unable to run the task: " + e.getMessage(), e);
                                }
                                return Status.OK_STATUS;
			    }
			};
			job.schedule();
		}
	}
	
	public Optional<ChatGPTViewPart> findMessageView() 
	{
		// Find the MessageView by element ID in the application model
        return modelService.findElements(application, "assitai.partdescriptor.chatgptview", MPart.class)
        				   .stream()
        				   .findFirst()
        				   .map( mpart -> mpart.getObject() )
        				   .map( ChatGPTViewPart.class::cast );
	}
}
