package javax.microedition.rms;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.util.Enumeration;
import java.util.Hashtable;
import java.util.TreeMap;
import java.util.Vector;

import j2a.core.Host;

/**
 * Record stores persisted as one small file each in the app's private
 * storage (files/rms/). Every change is written atomically.
 */
public class RecordStore {
    public static final int AUTHMODE_PRIVATE = 0;
    public static final int AUTHMODE_ANY = 1;

    private static final Hashtable<String, RecordStore> open = new Hashtable<String, RecordStore>();
    private static final int MAGIC = 0x4A324152; // "J2AR"

    private final String name;
    private final File file;
    final TreeMap<Integer, byte[]> records = new TreeMap<Integer, byte[]>();
    private int nextId = 1;
    private int version;
    private long lastModified;
    private int openCount;
    private final Vector<RecordListener> listeners = new Vector<RecordListener>();
    private final Vector<RecordEnumerationImpl> liveEnums = new Vector<RecordEnumerationImpl>();

    private RecordStore(String name, File file) {
        this.name = name;
        this.file = file;
    }

    private static File dir() {
        File d = new File(Host.filesDir(), "rms");
        if (!d.exists()) d.mkdirs();
        return d;
    }

    private static File fileFor(String name) {
        try {
            return new File(dir(), URLEncoder.encode(name, "UTF-8") + ".rms");
        } catch (Exception e) {
            return new File(dir(), Integer.toHexString(name.hashCode()) + ".rms");
        }
    }

    public static RecordStore openRecordStore(String recordStoreName, boolean createIfNecessary)
            throws RecordStoreException {
        if (recordStoreName == null || recordStoreName.length() == 0 || recordStoreName.length() > 32)
            throw new IllegalArgumentException("Invalid record store name");
        synchronized (open) {
            RecordStore rs = open.get(recordStoreName);
            if (rs == null) {
                File f = fileFor(recordStoreName);
                rs = new RecordStore(recordStoreName, f);
                if (f.exists()) {
                    rs.load();
                } else if (createIfNecessary) {
                    rs.lastModified = System.currentTimeMillis();
                    rs.save();
                } else {
                    throw new RecordStoreNotFoundException(recordStoreName);
                }
                open.put(recordStoreName, rs);
            }
            rs.openCount++;
            return rs;
        }
    }

    public static RecordStore openRecordStore(String recordStoreName, boolean createIfNecessary,
                                              int authmode, boolean writable) throws RecordStoreException {
        return openRecordStore(recordStoreName, createIfNecessary);
    }

    public static RecordStore openRecordStore(String recordStoreName, String vendorName, String suiteName)
            throws RecordStoreException {
        return openRecordStore(recordStoreName, false);
    }

    public static void deleteRecordStore(String recordStoreName) throws RecordStoreException {
        synchronized (open) {
            RecordStore rs = open.get(recordStoreName);
            if (rs != null && rs.openCount > 0) throw new RecordStoreException("Record store is open");
            open.remove(recordStoreName);
            File f = fileFor(recordStoreName);
            if (!f.exists()) throw new RecordStoreNotFoundException(recordStoreName);
            f.delete();
        }
    }

    public static String[] listRecordStores() {
        String[] files = dir().list();
        if (files == null) return null;
        Vector<String> v = new Vector<String>();
        for (int i = 0; i < files.length; i++) {
            if (!files[i].endsWith(".rms")) continue;
            try {
                v.addElement(URLDecoder.decode(files[i].substring(0, files[i].length() - 4), "UTF-8"));
            } catch (Exception ignored) {}
        }
        if (v.isEmpty()) return null;
        String[] r = new String[v.size()];
        v.copyInto(r);
        return r;
    }

    public void setMode(int authmode, boolean writable) {}

    public void closeRecordStore() throws RecordStoreException {
        synchronized (open) {
            checkOpen();
            openCount--;
            if (openCount == 0) {
                open.remove(name);
                listeners.removeAllElements();
                liveEnums.removeAllElements();
            }
        }
    }

    private void checkOpen() throws RecordStoreNotOpenException {
        if (openCount <= 0) throw new RecordStoreNotOpenException();
    }

    public String getName() throws RecordStoreNotOpenException {
        checkOpen();
        return name;
    }

    public int getVersion() throws RecordStoreNotOpenException {
        checkOpen();
        return version;
    }

    public synchronized int getNumRecords() throws RecordStoreNotOpenException {
        checkOpen();
        return records.size();
    }

    public synchronized int getSize() throws RecordStoreNotOpenException {
        checkOpen();
        int s = 64;
        for (byte[] b : records.values()) s += b.length + 8;
        return s;
    }

    public int getSizeAvailable() throws RecordStoreNotOpenException {
        checkOpen();
        return 4 * 1024 * 1024;
    }

    public long getLastModified() throws RecordStoreNotOpenException {
        checkOpen();
        return lastModified;
    }

    public synchronized int getNextRecordID() throws RecordStoreException {
        checkOpen();
        return nextId;
    }

    public void addRecordListener(RecordListener l) {
        if (!listeners.contains(l)) listeners.addElement(l);
    }

    public void removeRecordListener(RecordListener l) {
        listeners.removeElement(l);
    }

    public int addRecord(byte[] data, int offset, int numBytes) throws RecordStoreException {
        int id;
        synchronized (this) {
            checkOpen();
            byte[] copy = new byte[numBytes];
            if (numBytes > 0) System.arraycopy(data, offset, copy, 0, numBytes);
            id = nextId++;
            records.put(id, copy);
            touch();
        }
        for (int i = 0; i < listeners.size(); i++) listeners.elementAt(i).recordAdded(this, id);
        return id;
    }

    public void deleteRecord(int recordId) throws RecordStoreException {
        synchronized (this) {
            checkOpen();
            if (records.remove(recordId) == null) throw new InvalidRecordIDException();
            touch();
        }
        for (int i = 0; i < listeners.size(); i++) listeners.elementAt(i).recordDeleted(this, recordId);
    }

    public synchronized int getRecordSize(int recordId) throws RecordStoreException {
        checkOpen();
        byte[] b = records.get(recordId);
        if (b == null) throw new InvalidRecordIDException();
        return b.length;
    }

    public synchronized int getRecord(int recordId, byte[] buffer, int offset) throws RecordStoreException {
        checkOpen();
        byte[] b = records.get(recordId);
        if (b == null) throw new InvalidRecordIDException();
        if (offset + b.length > buffer.length) throw new ArrayIndexOutOfBoundsException();
        System.arraycopy(b, 0, buffer, offset, b.length);
        return b.length;
    }

    public synchronized byte[] getRecord(int recordId) throws RecordStoreException {
        checkOpen();
        byte[] b = records.get(recordId);
        if (b == null) throw new InvalidRecordIDException();
        return b.length == 0 ? null : b.clone();
    }

    public void setRecord(int recordId, byte[] newData, int offset, int numBytes) throws RecordStoreException {
        synchronized (this) {
            checkOpen();
            if (!records.containsKey(recordId)) throw new InvalidRecordIDException();
            byte[] copy = new byte[numBytes];
            if (numBytes > 0) System.arraycopy(newData, offset, copy, 0, numBytes);
            records.put(recordId, copy);
            touch();
        }
        for (int i = 0; i < listeners.size(); i++) listeners.elementAt(i).recordChanged(this, recordId);
    }

    public RecordEnumeration enumerateRecords(RecordFilter filter, RecordComparator comparator,
                                              boolean keepUpdated) throws RecordStoreNotOpenException {
        checkOpen();
        RecordEnumerationImpl e = new RecordEnumerationImpl(this, filter, comparator, keepUpdated);
        if (keepUpdated) liveEnums.addElement(e);
        return e;
    }

    void unregister(RecordEnumerationImpl e) {
        liveEnums.removeElement(e);
    }

    synchronized int[] ids() {
        int[] r = new int[records.size()];
        int i = 0;
        for (Integer k : records.keySet()) r[i++] = k;
        return r;
    }

    synchronized byte[] raw(int id) {
        return records.get(id);
    }

    private void touch() throws RecordStoreException {
        version++;
        lastModified = System.currentTimeMillis();
        save();
        for (Enumeration<RecordEnumerationImpl> en = liveEnums.elements(); en.hasMoreElements(); ) {
            en.nextElement().rebuild();
        }
    }

    private void load() throws RecordStoreException {
        try {
            DataInputStream in = new DataInputStream(new FileInputStream(file));
            try {
                if (in.readInt() != MAGIC) throw new RecordStoreException("Corrupt record store " + name);
                version = in.readInt();
                nextId = in.readInt();
                lastModified = in.readLong();
                int n = in.readInt();
                for (int i = 0; i < n; i++) {
                    int id = in.readInt();
                    byte[] b = new byte[in.readInt()];
                    in.readFully(b);
                    records.put(id, b);
                }
            } finally {
                in.close();
            }
        } catch (IOException e) {
            throw new RecordStoreException(e.toString());
        }
    }

    private void save() throws RecordStoreException {
        File tmp = new File(file.getPath() + ".tmp");
        try {
            DataOutputStream out = new DataOutputStream(new FileOutputStream(tmp));
            try {
                out.writeInt(MAGIC);
                out.writeInt(version);
                out.writeInt(nextId);
                out.writeLong(lastModified);
                out.writeInt(records.size());
                for (Integer id : records.keySet()) {
                    byte[] b = records.get(id);
                    out.writeInt(id);
                    out.writeInt(b.length);
                    out.write(b);
                }
            } finally {
                out.close();
            }
            if (!tmp.renameTo(file)) {
                file.delete();
                if (!tmp.renameTo(file)) throw new IOException("rename failed");
            }
        } catch (IOException e) {
            throw new RecordStoreFullException(e.toString());
        }
    }
}
