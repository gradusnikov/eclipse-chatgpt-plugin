package com.keg.eclipseaiassistant.handlers;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.Objects;

import jakarta.inject.Inject;
import jakarta.inject.Named;

import org.eclipse.core.resources.IResource;
import org.eclipse.core.runtime.ILog;
import org.eclipse.e4.core.di.annotations.Execute;
import org.eclipse.e4.ui.services.IServiceConstants;
import org.eclipse.egit.core.project.RepositoryMapping;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.diff.DiffFormatter;
import org.eclipse.jgit.diff.RawTextComparator;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.treewalk.AbstractTreeIterator;
import org.eclipse.jgit.treewalk.CanonicalTreeParser;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.PlatformUI;

import com.keg.eclipseaiassistant.part.ChatGPTPresenter;
import com.keg.eclipseaiassistant.prompt.ChatMessageFactory;
import com.keg.eclipseaiassistant.prompt.Prompts;

@SuppressWarnings( "restriction" )
public class AssistAiGenerateGitCommentHandler
{
    @Inject
    private ILog       logger;
    @Inject
    private ChatMessageFactory chatMessageFactory;
    @Inject
    private ChatGPTPresenter viewPresenter;

    @Execute
    public void execute( @Named( IServiceConstants.ACTIVE_SHELL ) Shell s )
    {
        // Get the active editor
        var activePage = PlatformUI.getWorkbench().getActiveWorkbenchWindow().getActivePage();
        var activeEditor = activePage.getActiveEditor();

        try
        {
            // Obtain the repository from the active editor's project
            var mapping = RepositoryMapping.getMapping( activeEditor.getEditorInput().getAdapter( IResource.class ) );
            var repository = mapping.getRepository();

            // Obtain the Git object for the repository
            try ( var git = new Git( repository ) )
            {
                // Get the staged changes
                var head = repository.resolve( "HEAD" );
                if ( Objects.isNull( head ) )
                {
                    // TODO: Handle the initial commit scenario
                    logger.info( "Initial commit: No previous commits found." );
                }
                else
                {
                    var headTree  = prepareTreeParser( repository, head );
                    var indexTree = prepareIndexTreeParser( repository );
                    var stagedChanges = git.diff().setOldTree( headTree ).setNewTree( indexTree ).call();
                    
                    var patch = printChanges( git.getRepository(), stagedChanges );
                    
                    var message = chatMessageFactory.createGenerateGitCommitCommentJob( patch );
                    viewPresenter.onSendPredefinedPrompt( Prompts.GIT_COMMENT, message );
                }
                    
            }
        }
        catch ( Exception e )
        {
            logger.error( e.getMessage(), e );
        }
    }

    // Helper method to prepare the tree parser
    private static AbstractTreeIterator prepareTreeParser( Repository repository, ObjectId objectId ) throws IOException
    {
        try (RevWalk walk = new RevWalk( repository ))
        {
            var commit = walk.parseCommit( objectId );
            var treeId  = commit.getTree().getId();

            try ( var reader = repository.newObjectReader())
            {
                return new CanonicalTreeParser( null, reader, treeId );
            }
        }
    }

    // Helper method to prepare the index tree parser
    private static AbstractTreeIterator prepareIndexTreeParser( Repository repository ) throws IOException
    {
        try ( var inserter = repository.newObjectInserter(); 
              var reader = repository.newObjectReader())
        {
            var treeId = repository.readDirCache().writeTree(inserter);
            return new CanonicalTreeParser( null, reader, treeId );
        }
    }

    private String printChanges( Repository repository, List<DiffEntry> stagedChanges ) throws IOException
    {
        
        try (var out = new ByteArrayOutputStream(); 
             var formatter = new DiffFormatter( out ))
        {
            formatter.setRepository( repository );
            formatter.setDiffComparator( RawTextComparator.DEFAULT );
            formatter.setDetectRenames( true );
            
            for ( DiffEntry diff : stagedChanges )
            {
                // Print the patch to the output stream
                formatter.format( diff );
            }
            var patch = out.toString( "UTF-8" );
            return patch;
        }
    }
}
