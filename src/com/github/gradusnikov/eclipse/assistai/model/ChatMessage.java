package com.github.gradusnikov.eclipse.assistai.model;


/**
 * Represents a chat message with an ID, role, number of tokens, and the message content.
 */
public class ChatMessage {
	
	public final int id;
	public final String role;
	public int tokens;
	public String content;
	public String userInput;
	
	/**
	 * Constructs a ChatMessage with the given ID and role.
	 * @param id The unique identifier for the chat message
	 * @param role The role associated with the chat message (e.g., "user", "assistant")
	 */
	public ChatMessage( int id, String role )
	{
		this.id = id;
		this.role = role;
		this.content = "";
	}
	
	/**
	 * Appends the given message to the existing message.
	 * @param msg The message to be appended
	 */
	public void append( String msg )
	{
		this.content += msg;
	}

	/**
	 * Retrieves the message content.
	 * @return The message content
	 */
	public String getContent() {
		return content;
	}

	/**
	 * Sets the message content.
	 * @param message The new message content
	 */
	public void setMessage(String message) {
		this.content = message;
	}

	/**
	 * Retrieves the unique identifier.
	 * @return The ID of the chat message
	 */
	public int getId() {
		return id;
	}

	/**
	 * Retrieves the role associated with the chat message.
	 * @return The role of the chat message
	 */
	public String getRole() {
		return role;
	}
}
