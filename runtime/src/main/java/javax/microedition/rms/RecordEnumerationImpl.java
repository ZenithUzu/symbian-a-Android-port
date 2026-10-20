package javax.microedition.rms;

import java.util.Vector;

final class RecordEnumerationImpl implements RecordEnumeration {
    private final RecordStore store;
    private final RecordFilter filter;
    private final RecordComparator comparator;
    private boolean keepUpdated;
    private int[] ids = new int[0];
    /** Index of the record returned last; -1 = before the first element. */
    private int index = -1;
    private boolean destroyed;

    RecordEnumerationImpl(RecordStore store, RecordFilter filter, RecordComparator comparator, boolean keepUpdated) {
        this.store = store;
        this.filter = filter;
        this.comparator = comparator;
        this.keepUpdated = keepUpdated;
        rebuild();
    }

    public synchronized void rebuild() {
        int[] all = store.ids();
        Vector<Integer> v = new Vector<Integer>();
        for (int i = 0; i < all.length; i++) {
            if (filter == null || filter.matches(store.raw(all[i]))) v.addElement(all[i]);
        }
        if (comparator != null) {
            // insertion sort keeps it stable and avoids generics/Comparator boilerplate
            for (int i = 1; i < v.size(); i++) {
                Integer cur = v.elementAt(i);
                int j = i - 1;
                while (j >= 0 && comparator.compare(store.raw(v.elementAt(j)), store.raw(cur)) == RecordComparator.FOLLOWS) {
                    v.setElementAt(v.elementAt(j), j + 1);
                    j--;
                }
                v.setElementAt(cur, j + 1);
            }
        }
        ids = new int[v.size()];
        for (int i = 0; i < ids.length; i++) ids[i] = v.elementAt(i);
        if (index >= ids.length) index = ids.length - 1;
    }

    private void check() {
        if (destroyed) throw new IllegalStateException("RecordEnumeration destroyed");
    }

    public synchronized int numRecords() {
        check();
        return ids.length;
    }

    public synchronized int nextRecordId() throws InvalidRecordIDException {
        check();
        if (index + 1 >= ids.length) throw new InvalidRecordIDException();
        return ids[++index];
    }

    public byte[] nextRecord() throws RecordStoreException {
        return store.getRecord(nextRecordId());
    }

    public synchronized int previousRecordId() throws InvalidRecordIDException {
        check();
        if (index == -1) index = ids.length;
        if (index - 1 < 0) throw new InvalidRecordIDException();
        return ids[--index];
    }

    public byte[] previousRecord() throws RecordStoreException {
        return store.getRecord(previousRecordId());
    }

    public synchronized boolean hasNextElement() {
        check();
        return index + 1 < ids.length;
    }

    public synchronized boolean hasPreviousElement() {
        check();
        return index == -1 ? ids.length > 0 : index > 0;
    }

    public synchronized void reset() {
        check();
        index = -1;
    }

    public void keepUpdated(boolean keep) {
        check();
        keepUpdated = keep;
        if (keep) {
            store.register(this);
            rebuild();
        } else {
            store.unregister(this);
        }
    }

    public boolean isKeptUpdated() { return keepUpdated; }

    public void destroy() {
        destroyed = true;
        store.unregister(this);
    }
}
