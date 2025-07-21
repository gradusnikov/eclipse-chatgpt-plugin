package com.github.gradusnikov.eclipse.assistai.compare;

import org.eclipse.core.resources.IResource;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.jdt.core.IBuffer;
import org.eclipse.jdt.core.IBufferChangedListener;
import org.eclipse.jdt.core.IOpenable;
import org.eclipse.jdt.core.JavaModelException;

public class SourceMemoryBuffer implements IBuffer {
	private final StringBuilder buffer;
	private IOpenable owner;
	private boolean isReadOnly = false;
	private boolean isClosed = false;

	public SourceMemoryBuffer(String source) {
		this.buffer = new StringBuilder(source != null ? source : "");
	}

	@Override
	public char getChar(int position) {
		checkAccess();
		return buffer.charAt(position);
	}

	@Override
	public char[] getCharacters() {
		checkAccess();
		char[] result = new char[buffer.length()];
		buffer.getChars(0, buffer.length(), result, 0);
		return result;
	}

	@Override
	public String getContents() {
		checkAccess();
		return buffer.toString();
	}

	@Override
	public int getLength() {
		return buffer.length();
	}

	@Override
	public IOpenable getOwner() {
		return owner;
	}

	@Override
	public String getText(int offset, int length) {
		checkAccess();
		return buffer.substring(offset, offset + length);
	}

	@Override
	public void append(char[] text) {
		checkAccess();
		checkReadOnly();
		buffer.append(text);
	}

	@Override
	public void append(String text) {
		checkAccess();
		checkReadOnly();
		buffer.append(text);
	}

	@Override
	public void close() {
		isClosed = true;
	}

	@Override
	public boolean hasUnsavedChanges() {
		return false; // Memory buffer doesn't track changes
	}

	@Override
	public boolean isClosed() {
		return isClosed;
	}

	@Override
	public boolean isReadOnly() {
		return isReadOnly;
	}

	@Override
	public void replace(int position, int length, char[] text) {
		checkAccess();
		checkReadOnly();
		buffer.replace(position, position + length, new String(text));
	}

	@Override
	public void replace(int position, int length, String text) {
		checkAccess();
		checkReadOnly();
		buffer.replace(position, position + length, text);
	}

	@Override
	public void setContents(char[] contents) {
		checkAccess();
		checkReadOnly();
		buffer.setLength(0);
		buffer.append(contents);
	}

	@Override
	public void setContents(String contents) {
		checkAccess();
		checkReadOnly();
		buffer.setLength(0);
		buffer.append(contents);
	}

	private void checkAccess() {
		if (isClosed) {
			throw new IllegalStateException("Buffer is closed");
		}
	}

	private void checkReadOnly() {
		if (isReadOnly) {
			throw new IllegalStateException("Buffer is read-only");
		}
	}

	@Override
	public void addBufferChangedListener(IBufferChangedListener listener) {
		// TODO Auto-generated method stub

	}

	@Override
	public IResource getUnderlyingResource() {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public void removeBufferChangedListener(IBufferChangedListener listener) {
		// TODO Auto-generated method stub

	}

	@Override
	public void save(IProgressMonitor progress, boolean force) throws JavaModelException {

	}
}