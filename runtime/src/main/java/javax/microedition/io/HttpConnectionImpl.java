package javax.microedition.io;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/** HttpConnection backed by java.net.HttpURLConnection (needs INTERNET permission). */
final class HttpConnectionImpl implements HttpConnection {
    private final URL url;
    private final HttpURLConnection conn;

    HttpConnectionImpl(String name) throws IOException {
        url = new URL(name);
        conn = (HttpURLConnection) url.openConnection();
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(30000);
        conn.setInstanceFollowRedirects(true);
    }

    public String getURL() { return url.toString(); }

    public String getProtocol() { return url.getProtocol(); }

    public String getHost() { return url.getHost(); }

    public String getFile() { return url.getPath(); }

    public String getRef() { return url.getRef(); }

    public String getQuery() { return url.getQuery(); }

    public int getPort() { return url.getPort() == -1 ? url.getDefaultPort() : url.getPort(); }

    public String getRequestMethod() { return conn.getRequestMethod(); }

    public void setRequestMethod(String method) throws IOException {
        conn.setRequestMethod(method);
        if (POST.equals(method)) conn.setDoOutput(true);
    }

    public String getRequestProperty(String key) { return conn.getRequestProperty(key); }

    public void setRequestProperty(String key, String value) { conn.setRequestProperty(key, value); }

    public int getResponseCode() throws IOException { return conn.getResponseCode(); }

    public String getResponseMessage() throws IOException { return conn.getResponseMessage(); }

    public long getExpiration() { return conn.getExpiration(); }

    public long getDate() { return conn.getDate(); }

    public long getLastModified() { return conn.getLastModified(); }

    public String getHeaderField(String name) { return conn.getHeaderField(name); }

    public int getHeaderFieldInt(String name, int def) { return conn.getHeaderFieldInt(name, def); }

    public long getHeaderFieldDate(String name, long def) { return conn.getHeaderFieldDate(name, def); }

    public String getHeaderField(int n) { return conn.getHeaderField(n); }

    public String getHeaderFieldKey(int n) { return conn.getHeaderFieldKey(n); }

    public String getType() { return conn.getContentType(); }

    public String getEncoding() { return conn.getContentEncoding(); }

    public long getLength() { return conn.getContentLength(); }

    public InputStream openInputStream() throws IOException { return conn.getInputStream(); }

    public DataInputStream openDataInputStream() throws IOException { return new DataInputStream(openInputStream()); }

    public OutputStream openOutputStream() throws IOException {
        conn.setDoOutput(true);
        return conn.getOutputStream();
    }

    public DataOutputStream openDataOutputStream() throws IOException { return new DataOutputStream(openOutputStream()); }

    public void close() { conn.disconnect(); }
}
