package com.github.gradusnikov.eclipse.assistai.mcp.services;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.Objects;

import org.eclipse.core.resources.IResource;
import org.eclipse.core.runtime.ILog;
import org.eclipse.e4.core.di.annotations.Creatable;
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
import org.eclipse.ui.IEditorPart;

import com.github.gradusnikov.eclipse.assistai.tools.UISynchronizeCallable;

import jakarta.inject.Inject;

@Creatable
@SuppressWarnings( "restriction" )
public class GitService
{
    @Inject
    private ILog       logger;
    
    @Inject
    public UISynchronizeCallable uiSync;
    
    @Inject
    public EditorService editorService;
    
    public String getCurrentDiff()
    {
        return uiSync.syncCall( () -> {
            // Get the active editor
            var activeResource = editorService.getActiveEditor()
                    .map( IEditorPart::getEditorInput )
                    .map( editorInput -> editorInput.getAdapter( IResource.class ) )
                    .orElseThrow( () -> new RuntimeException( "No active resource available." ) );
            // Obtain the repository from the active editor's project
            var mapping = RepositoryMapping.getMapping( activeResource );
            var repository = mapping.getRepository();
            // Obtain the Git object for the repository
            try ( var git = new Git( repository ) )
            {
                // Get the staged changes
                var head = repository.resolve( "HEAD" );
                if ( Objects.isNull( head ) )
                {
                    logger.info( "Initial commit: No previous commits found." );
                    return "Initial commit: No previous commits found.";
                }
                else
                {
                    var headTree  = prepareTreeParser( repository, head );
                    var indexTree = prepareIndexTreeParser( repository );
                    var stagedChanges = git.diff().setOldTree( headTree ).setNewTree( indexTree ).call();
                    
                    var patch = printChanges( git.getRepository(), stagedChanges );
                    
                    return patch;
                }
            }
            catch ( Exception e )
            {
                throw new RuntimeException( e );
            }
        } );
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
