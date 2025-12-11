package com.github.gradusnikov.eclipse.assistai.chat;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;

/**
 * Unit tests for {@link ConversationContext}.
 */
public class ConversationContextTest
{
    private Conversation conversation;
    
    @BeforeEach
    public void setUp()
    {
        conversation = new Conversation();
    }
    
    @Test
    public void testBuilderCreatesContextWithRequiredFields()
    {
        ConversationContext context = ConversationContext.builder()
                .conversation( conversation )
                .build();
        
        assertNotNull( context );
        assertNotNull( context.getContextId() );
        assertSame( conversation, context.getConversation() );
    }
    
    @Test
    public void testBuilderWithCustomContextId()
    {
        String customId = "test-context-123";
        ConversationContext context = ConversationContext.builder()
                .contextId( customId )
                .conversation( conversation )
                .build();
        
        assertEquals( customId, context.getContextId() );
    }
    
    @Test
    public void testBuilderThrowsWhenConversationIsNull()
    {
        assertThrows( NullPointerException.class, () -> {
            ConversationContext.builder().build();
        });
    }
    
    @Test
    public void testAddMessageDelegatesToConversation()
    {
        ConversationContext context = ConversationContext.builder()
                .conversation( conversation )
                .build();
        
        ChatMessage message = new ChatMessage( "msg-1", "user" );
        context.addMessage( message );
        
        assertEquals( 1, conversation.size() );
        assertSame( message, conversation.messages().get( 0 ) );
    }
    
    @Test
    public void testIsToolAllowedWithNoRestrictions()
    {
        ConversationContext context = ConversationContext.builder()
                .conversation( conversation )
                .allowedTools( null )
                .build();
        
        assertTrue( context.isToolAllowed( "any-tool" ) );
        assertTrue( context.isToolAllowed( "eclipse-ide__getSource" ) );
        assertTrue( context.isToolAllowed( "eclipse-coder__createFile" ) );
    }
    
    @Test
    public void testIsToolAllowedWithRestrictions()
    {
        Set<String> allowedTools = Set.of( 
            "eclipse-ide__getSource", 
            "eclipse-ide__readProjectResource" 
        );
        
        ConversationContext context = ConversationContext.builder()
                .conversation( conversation )
                .allowedTools( allowedTools )
                .build();
        
        assertTrue( context.isToolAllowed( "eclipse-ide__getSource" ) );
        assertTrue( context.isToolAllowed( "eclipse-ide__readProjectResource" ) );
        assertFalse( context.isToolAllowed( "eclipse-coder__createFile" ) );
        assertFalse( context.isToolAllowed( "unknown-tool" ) );
    }
    
    @Test
    public void testGetAllowedToolsReturnsImmutableSet()
    {
        Set<String> allowedTools = Set.of( "tool1", "tool2" );
        
        ConversationContext context = ConversationContext.builder()
                .conversation( conversation )
                .allowedTools( allowedTools )
                .build();
        
        Set<String> returned = context.getAllowedTools();
        assertNotNull( returned );
        assertEquals( 2, returned.size() );
        
        // Should throw UnsupportedOperationException
        assertThrows( UnsupportedOperationException.class, () -> {
            returned.add( "tool3" );
        });
    }
    
    @Test
    public void testHandleFunctionResultInvokesCallback()
    {
        AtomicBoolean callbackInvoked = new AtomicBoolean( false );
        AtomicReference<FunctionCall> receivedCall = new AtomicReference<>();
        AtomicReference<CallToolResult> receivedResult = new AtomicReference<>();
        
        ConversationContext context = ConversationContext.builder()
                .conversation( conversation )
                .onFunctionResult( (call, result) -> {
                    callbackInvoked.set( true );
                    receivedCall.set( call );
                    receivedResult.set( result );
                })
                .build();
        
        FunctionCall functionCall = new FunctionCall( "call-1", "test-tool", null, null );
        CallToolResult result = new CallToolResult( 
            List.of( new McpSchema.TextContent( "result" ) ), 
            false 
        );
        
        context.handleFunctionResult( functionCall, result );
        
        assertTrue( callbackInvoked.get() );
        assertSame( functionCall, receivedCall.get() );
        assertSame( result, receivedResult.get() );
    }
    
    @Test
    public void testHandleFunctionResultWithNoCallback()
    {
        ConversationContext context = ConversationContext.builder()
                .conversation( conversation )
                .build();
        
        FunctionCall functionCall = new FunctionCall( "call-1", "test-tool", null, null );
        CallToolResult result = new CallToolResult( 
            List.of( new McpSchema.TextContent( "result" ) ), 
            false 
        );
        
        // Should not throw
        assertDoesNotThrow( () -> {
            context.handleFunctionResult( functionCall, result );
        });
    }
    
    @Test
    public void testContinueConversationInvokesCallback()
    {
        AtomicBoolean callbackInvoked = new AtomicBoolean( false );
        
        ConversationContext context = ConversationContext.builder()
                .conversation( conversation )
                .onConversationContinue( () -> callbackInvoked.set( true ) )
                .build();
        
        assertTrue( context.shouldContinueConversation() );
        
        context.continueConversation();
        
        assertTrue( callbackInvoked.get() );
    }
    
    @Test
    public void testContinueConversationWithNoCallback()
    {
        ConversationContext context = ConversationContext.builder()
                .conversation( conversation )
                .build();
        
        assertFalse( context.shouldContinueConversation() );
        
        // Should not throw
        assertDoesNotThrow( () -> {
            context.continueConversation();
        });
    }
    
    @Test
    public void testToStringContainsRelevantInfo()
    {
        ConversationContext context = ConversationContext.builder()
                .contextId( "test-id" )
                .conversation( conversation )
                .allowedTools( Set.of( "tool1" ) )
                .build();
        
        String str = context.toString();
        assertTrue( str.contains( "test-id" ) );
        assertTrue( str.contains( "hasToolRestrictions=true" ) );
    }
}
