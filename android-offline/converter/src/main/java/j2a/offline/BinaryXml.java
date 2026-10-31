package j2a.offline;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Rebuild Android binary string pools without changing string indexes or resource IDs. */
public final class BinaryXml {
    private BinaryXml() {}
    static int u16(byte[] b, int p) { return (b[p]&255) | (b[p+1]&255)<<8; }
    static int i32(byte[] b, int p) { return u16(b,p) | u16(b,p+2)<<16; }
    static void put32(byte[] b, int p, int v) { for (int i=0;i<4;i++) b[p+i]=(byte)(v >>> (i*8)); }
    static void le32(ByteArrayOutputStream out, int v) { for(int i=0;i<4;i++) out.write(v >>> (i*8)); }
    static int[] length8(byte[] b, int p) { int n=b[p++]&255; return n<128 ? new int[]{n,p} : new int[]{(n&127)<<8|(b[p++]&255),p}; }
    static int[] length16(byte[] b, int p) { int n=u16(b,p); p+=2; return n<32768 ? new int[]{n,p} : new int[]{(n&32767)<<16|u16(b,p),p+2}; }
    static void len8(ByteArrayOutputStream out,int n) { if(n>32767)throw new IllegalArgumentException("String too long"); if(n>127)out.write((n>>8)|128);out.write(n&255); }
    static void len16(ByteArrayOutputStream out,int n) { if(n>32767){out.write((n>>16)&255);out.write(((n>>24)&127)|128);}out.write(n&255);out.write((n>>8)&255); }

    private static byte[] pool(byte[] b, Map<String,String> replacements) throws IOException {
        int header=u16(b,2), count=i32(b,8), styles=i32(b,12), flags=i32(b,16), start=i32(b,20), styleStart=i32(b,24);
        if(header<28 || count<0 || count>100000 || styles<0 || start<header+(count+styles)*4)throw new IOException("Invalid string pool");
        boolean utf8=(flags&256)!=0;
        ByteArrayOutputStream strings=new ByteArrayOutputStream();
        List<Integer> offsets=new ArrayList<>();
        for(int i=0;i<count;i++) {
            int pos=start+i32(b,header+i*4);
            int[] length=utf8 ? length8(b,pos) : length16(b,pos);
            if(utf8) length=length8(b,length[1]);
            int bytes=utf8 ? length[0] : Math.multiplyExact(length[0],2);
            if(length[1]<0 || bytes<0 || length[1]+bytes>b.length)throw new IOException("Invalid string bounds");
            String old=new String(b,length[1],bytes,utf8?StandardCharsets.UTF_8:StandardCharsets.UTF_16LE);
            String value=replacements.getOrDefault(old,old);
            offsets.add(strings.size());
            byte[] encoded=value.getBytes(utf8?StandardCharsets.UTF_8:StandardCharsets.UTF_16LE);
            if(utf8){len8(strings,value.length());len8(strings,encoded.length);}else len16(strings,value.length());
            strings.write(encoded);strings.write(0);if(!utf8)strings.write(0);
        }
        while(strings.size()%4!=0)strings.write(0);
        byte[] head=java.util.Arrays.copyOf(b,header);
        int newStart=header+(count+styles)*4;
        put32(head,16,flags&~1); // replacement strings need not remain lexicographically sorted
        put32(head,20,newStart);
        put32(head,24,styleStart==0?0:newStart+strings.size());
        int styleBytes=styleStart==0?0:b.length-styleStart;
        put32(head,4,newStart+strings.size()+styleBytes);
        ByteArrayOutputStream out=new ByteArrayOutputStream();out.write(head);
        for(int offset:offsets)le32(out,offset);
        out.write(b,header+count*4,styles*4);
        out.write(strings.toByteArray());
        if(styleBytes>0)out.write(b,styleStart,styleBytes);
        return out.toByteArray();
    }

    public static byte[] replace(byte[] input, Map<String,String> replacements) throws IOException {
        if(input.length<8 || i32(input,4)!=input.length)throw new IOException("Invalid binary XML/table");
        ByteArrayOutputStream out=new ByteArrayOutputStream();
        int header=u16(input,2);out.write(input,0,header);
        for(int p=header;p<input.length;) {
            if(p+8>input.length)throw new IOException("Truncated chunk");
            int size=i32(input,p+4);
            if(size<8 || size>input.length-p)throw new IOException("Invalid chunk size");
            byte[] chunk=java.util.Arrays.copyOfRange(input,p,p+size);
            out.write(u16(input,p)==1?pool(chunk,replacements):chunk);
            p+=size;
        }
        byte[] result=out.toByteArray();put32(result,4,result.length);return result;
    }

    public static void versionCode(byte[] xml,int version) throws IOException {
        for(int p=u16(xml,2);p<xml.length;p+=i32(xml,p+4)) {
            if(u16(xml,p)!=0x0102)continue;
            int ext=p+u16(xml,p+2), start=u16(xml,ext+8), stride=u16(xml,ext+10), count=u16(xml,ext+12);
            if(stride<20)throw new IOException("Invalid attributes");
            // versionCode's resource ID is resolved using the resource-map chunk.
            for(int map=u16(xml,2);map<xml.length;map+=i32(xml,map+4)) {
                if(u16(xml,map)!=0x0180)continue;
                int mapStart=map+u16(xml,map+2), mapEnd=map+i32(xml,map+4);
                for(int i=0;i<count;i++) {
                    int a=ext+start+i*stride, name=i32(xml,a+4), resource=mapStart+name*4;
                    if(resource>=mapStart && resource+4<=mapEnd && i32(xml,resource)==0x0101021b)put32(xml,a+16,version);
                }
            }
        }
    }
}
