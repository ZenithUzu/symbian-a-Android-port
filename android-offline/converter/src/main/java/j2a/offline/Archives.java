package j2a.offline;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.*;

public final class Archives {
    public static final int MAX_ENTRY=32*1024*1024, MAX_TOTAL=200*1024*1024, MAX_ENTRIES=20000;
    private Archives() {}
    public static String safe(String name) throws IOException {
        if(name.isEmpty() || name.startsWith("/") || name.indexOf('\\')>=0 || name.indexOf('\0')>=0 || Arrays.asList(name.split("/")).contains(".."))throw new IOException("Ruta inválida en JAR: "+name);
        return name;
    }
    public static byte[] read(InputStream in,int limit) throws IOException {
        ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] buffer=new byte[16384];int n;
        while((n=in.read(buffer))!=-1){if(n>limit-out.size())throw new IOException("El archivo excede el límite de memoria");out.write(buffer,0,n);}return out.toByteArray();
    }
    public static Map<String,byte[]> load(File file) throws IOException {
        Map<String,byte[]> entries=new LinkedHashMap<>();long total=0;
        try(ZipFile zip=new ZipFile(file)) {
            Enumeration<? extends ZipEntry> all=zip.entries();
            while(all.hasMoreElements()) {
                ZipEntry e=all.nextElement();if(e.isDirectory())continue;String name=safe(e.getName());
                if(entries.size()>=MAX_ENTRIES || entries.containsKey(name))throw new IOException("Demasiadas entradas o nombre duplicado: "+name);
                byte[] data;try(InputStream in=zip.getInputStream(e)){data=read(in,MAX_ENTRY);}
                total+=data.length;if(total>MAX_TOTAL)throw new IOException("El JAR descomprimido es demasiado grande");entries.put(name,data);
            }
        }
        return entries;
    }
    public static byte[] properties(Map<String,String> values) throws IOException {
        Properties p=new Properties();p.putAll(values);ByteArrayOutputStream out=new ByteArrayOutputStream();p.store(out,"j2a Offline");return out.toByteArray();
    }
    private static final class Counted extends FilterOutputStream {
        long count;Counted(OutputStream out){super(out);}
        @Override public void write(int b)throws IOException{out.write(b);count++;}
        @Override public void write(byte[] b,int off,int len)throws IOException{out.write(b,off,len);count+=len;}
    }
    public static void write(File target,Map<String,byte[]> entries) throws IOException {
        try(Counted raw=new Counted(new FileOutputStream(target));ZipOutputStream zip=new ZipOutputStream(raw,StandardCharsets.UTF_8)) {
            for(Map.Entry<String,byte[]> item:entries.entrySet()) {
                String name=safe(item.getKey());byte[] data=item.getValue();ZipEntry e=new ZipEntry(name);e.setTime(315619200000L);
                if(name.equals("resources.arsc")) {
                    e.setMethod(ZipEntry.STORED);e.setSize(data.length);CRC32 crc=new CRC32();crc.update(data);e.setCrc(crc.getValue());
                    int padding=(int)((4-(raw.count+30+name.getBytes(StandardCharsets.UTF_8).length+4)%4)%4);
                    byte[] extra=new byte[4+padding];extra[0]=0x35;extra[1]=(byte)0xd9;extra[2]=(byte)padding;e.setExtra(extra);
                }
                zip.putNextEntry(e);zip.write(data);zip.closeEntry();
            }
        }
    }
}
