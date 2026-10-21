package com.nokia.mid.ui;

/** Nokia soft notifications: accepted and ignored (ports don't post Android notifications). */
public class SoftNotificationImpl extends SoftNotification {
	private static int nextId = 1;
	private final int id;
	private SoftNotificationListener listener;

	public SoftNotificationImpl(int notificationId) {
		id = notificationId;
	}

	public SoftNotificationImpl() {
		id = nextId++;
	}

	public int getId() { return id; }

	public void post() throws SoftNotificationException {}

	public void remove() throws SoftNotificationException {}

	public void setListener(SoftNotificationListener listener) { this.listener = listener; }

	public void setText(String text, String groupText) throws SoftNotificationException {}

	public void setSoftkeyLabels(String softkey1Label, String softkey2Label) throws SoftNotificationException {}

	public void setImage(byte[] imageData) throws SoftNotificationException {}
}
