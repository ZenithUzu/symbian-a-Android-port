package demo;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.util.Random;

import javax.microedition.rms.RecordStore;

/** Named like an obfuscated class on purpose: RMS helpers. */
final class a {
    private static final Random R = new Random();

    private a() {}

    static int rnd(int max) { return (R.nextInt() & 0x7fffffff) % Math.max(1, max); }

    static int loadBest() {
        byte[] b = read(1);
        if (b == null) return 0;
        try { return new DataInputStream(new ByteArrayInputStream(b)).readInt(); } catch (Exception e) { return 0; }
    }

    static void saveBest(int v) {
        try {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            new DataOutputStream(bo).writeInt(v);
            write(1, bo.toByteArray());
        } catch (Exception ignored) {
        }
    }

    static String loadName() {
        byte[] b = read(2);
        return b == null ? "Anónimo" : new String(b);
    }

    static void saveName(String n) { write(2, n.getBytes()); }

    private static byte[] read(int id) {
        try {
            RecordStore rs = RecordStore.openRecordStore("demo", true);
            try {
                return rs.getNumRecords() >= id ? rs.getRecord(id) : null;
            } finally {
                rs.closeRecordStore();
            }
        } catch (Exception e) {
            return null;
        }
    }

    private static void write(int id, byte[] data) {
        try {
            RecordStore rs = RecordStore.openRecordStore("demo", true);
            while (rs.getNextRecordID() <= id) rs.addRecord(new byte[0], 0, 0);
            rs.setRecord(id, data, 0, data.length);
            rs.closeRecordStore();
        } catch (Exception ignored) {
        }
    }
}
