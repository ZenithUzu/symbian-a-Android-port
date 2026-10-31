package j2a.offline;

import java.io.*;
import java.util.*;

/** The desktop resource-call patch, ported to Java. Instruction sizes never change. */
public final class ClassPatcher {
    final byte[] data;
    final int[] tags, a, b;
    final String[] text;
    final int cpEnd;
    final List<int[]> methods=new ArrayList<>();
    private int pos;
    private int u1() throws IOException { if(pos>=data.length)throw new IOException("Clase truncada");return data[pos++]&255; }
    private int u2() throws IOException {return u1()<<8|u1();}
    private int u4() throws IOException {return u2()<<16|u2();}
    private void skip(int n)throws IOException{if(n<0 || n>data.length-pos)throw new IOException("Clase inválida");pos+=n;}
    public ClassPatcher(byte[] bytes) throws IOException {
        data=bytes;
        if(u4()!=0xcafebabe)throw new IOException("No es una clase Java");skip(4);
        int count=u2();tags=new int[count];a=new int[count];b=new int[count];text=new String[count];
        for(int i=1;i<count;i++) {
            int tag=tags[i]=u1();
            switch(tag) {
                case 1: {int length=u2();int start=pos;skip(length);text[i]=new DataInputStream(new ByteArrayInputStream(data,start-2,length+2)).readUTF();break;}
                case 3:case 4:a[i]=u4();break;
                case 5:case 6:skip(8);i++;break;
                case 7:case 8:case 16:case 19:case 20:a[i]=u2();break;
                case 9:case 10:case 11:case 12:case 17:case 18:a[i]=u2();b[i]=u2();break;
                case 15:skip(3);break;
                default:throw new IOException("Constant pool no soportado: "+tag);
            }
        }
        cpEnd=pos;skip(6);int interfaces=u2();skip(interfaces*2);members(false);members(true);
    }
    private void members(boolean code)throws IOException {
        int count=u2();
        for(int i=0;i<count;i++) {
            skip(6);int attributes=u2();
            for(int k=0;k<attributes;k++) {
                int name=u2(), length=u4(), begin=pos;
                if(code && "Code".equals(text[name])) {
                    skip(4);int size=u4();int start=pos;skip(size);methods.add(new int[]{start,size});
                    pos=begin;
                }
                skip(length);
            }
        }
    }
    String[] ref(int index) {
        if(index<=0 || index>=tags.length || (tags[index]!=10 && tags[index]!=11))return null;
        int nat=b[index];return new String[]{text[a[a[index]]],text[a[nat]],text[b[nat]]};
    }
    private int number(int at,int op) {
        if(op>=2 && op<=8)return op-3;
        if(op==0x10)return data[at+1];
        if(op==0x11)return (short)((data[at+1]&255)<<8|(data[at+2]&255));
        if(op==0x12 || op==0x13) {int index=op==0x12?data[at+1]&255:(data[at+1]&255)<<8|(data[at+2]&255);if(tags[index]==3)return a[index];}
        return Integer.MIN_VALUE;
    }
    static int intAt(byte[] bytes,int at){return (bytes[at]&255)<<24|(bytes[at+1]&255)<<16|(bytes[at+2]&255)<<8|(bytes[at+3]&255);}
    private static final int[] LENGTH=new int[256];
    static {
        Arrays.fill(LENGTH,1);
        for(int op:new int[]{0x10,0x12,0x15,0x16,0x17,0x18,0x19,0x36,0x37,0x38,0x39,0x3a,0xa9,0xbc})LENGTH[op]=2;
        for(int op=0x99;op<0xa9;op++)LENGTH[op]=3;
        for(int op:new int[]{0x11,0x13,0x14,0x84,0xb2,0xb3,0xb4,0xb5,0xb6,0xb7,0xb8,0xbb,0xbd,0xc0,0xc1,0xc6,0xc7})LENGTH[op]=3;
        LENGTH[0xc5]=4;for(int op:new int[]{0xb9,0xba,0xc8,0xc9})LENGTH[op]=5;
    }
    private int next(int start,int pc,int op,int size)throws IOException {
        int next;
        if(op==0xaa || op==0xab) {
            int p=(pc+4)&~3;
            long result=op==0xaa ? (long)p+12+((long)intAt(data,start+p+8)-intAt(data,start+p+4)+1)*4 : (long)p+8+(long)intAt(data,start+p+4)*8;
            if(result<=pc || result>size)throw new IOException("Switch inválido");next=(int)result;
        } else if(op==0xc4)next=pc+((data[start+pc+1]&255)==0x84?6:4);
        else next=pc+LENGTH[op];
        if(next<=pc || next>size)throw new IOException("Instrucción truncada");return next;
    }
    public Map<String,Integer> drawingSizes(Set<String> known)throws IOException {
        Map<String,Integer> result=new HashMap<>();Set<String> seen=new HashSet<>();
        for(int[] method:methods) {
            List<Integer> pushed=new ArrayList<>();
            for(int pc=0;pc<method[1];) {
                int at=method[0]+pc,op=data[at]&255,next=next(method[0],pc,op,method[1]);int value=number(at,op);
                if(value!=Integer.MIN_VALUE){pushed.add(value);if(pushed.size()>4)pushed.remove(0);}
                else {
                    if(op==0xb6 || op==0xb8) {
                        String[] r=ref((data[at+1]&255)<<8|(data[at+2]&255));int n=pushed.size();
                        if(r!=null && n>=2) {
                            String size=pushed.get(n-2)+"x"+pushed.get(n-1);
                            if(known.contains(size)) {
                                boolean rect=r[0].equals("javax/microedition/lcdui/Graphics") && Arrays.asList("fillRect","setClip","drawRect").contains(r[1]) && r[2].equals("(IIII)V") && n==4 && pushed.get(0)==0 && pushed.get(1)==0;
                                boolean buffer=r[0].equals("javax/microedition/lcdui/Image") && r[1].equals("createImage") && r[2].startsWith("(II)");
                                if(rect || buffer){String key=size+":"+(rect?"draw":"buffer");if(seen.add(key))result.put(key,rect?95:35);}
                            }
                        }
                    }
                    pushed.clear();
                }
                pc=next;
            }
        }
        return result;
    }
    public byte[] patch() throws IOException {
        Set<Integer> targets=new HashSet<>();
        for(int i=1;i<tags.length;i++)if(tags[i]==10 && Arrays.equals(ref(i),new String[]{"java/lang/Class","getResourceAsStream","(Ljava/lang/String;)Ljava/io/InputStream;"}))targets.add(i);
        if(targets.isEmpty())return data;
        List<Integer> sites=new ArrayList<>();
        for(int[] method:methods)for(int pc=0;pc<method[1];) {
            int at=method[0]+pc,op=data[at]&255;
            if(op==0xb6 && targets.contains((data[at+1]&255)<<8|(data[at+2]&255)))sites.add(at);
            pc=next(method[0],pc,op,method[1]);
        }
        if(sites.isEmpty())return data;
        int n=tags.length;if(n+6>65535)throw new IOException("Constant pool lleno");
        ByteArrayOutputStream out=new ByteArrayOutputStream();DataOutputStream d=new DataOutputStream(out);
        d.write(data,0,8);d.writeShort(n+6);d.write(data,10,cpEnd-10);
        d.writeByte(1);d.writeUTF("j2a/core/ResourceShim");d.writeByte(7);d.writeShort(n);
        d.writeByte(1);d.writeUTF("getResourceAsStream");d.writeByte(1);d.writeUTF("(Ljava/lang/Class;Ljava/lang/String;)Ljava/io/InputStream;");
        d.writeByte(12);d.writeShort(n+2);d.writeShort(n+3);d.writeByte(10);d.writeShort(n+1);d.writeShort(n+4);
        byte[] rest=Arrays.copyOfRange(data,cpEnd,data.length);
        for(int site:sites){int off=site-cpEnd;rest[off]=(byte)0xb8;rest[off+1]=(byte)((n+5)>>8);rest[off+2]=(byte)(n+5);}
        d.write(rest);return out.toByteArray();
    }
}
