package com.github.gradusnikov.eclipse.assistai.handlers;


public class ChatMessage {
	
	public final int id;
	public final String role;
	public int tokens;
	public String message;
	
	public ChatMessage( int id, String role )
	{
		this.id = id;
		this.role = role;
		this.message = "";
	}
	
	public void append( String msg )
	{
		this.message += msg;
	}

	public String getMessage() {
		return message;
	}

	public void setMessage(String message) {
		this.message = message;
	}

	public int getId() {
		return id;
	}

	public String getRole() {
		return role;
	}
	
	
	
	
}
