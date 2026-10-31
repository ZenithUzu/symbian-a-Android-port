import j2a.offline.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;

public class OfflineCoreTest {
    public static void main(String[] args) throws Exception {
        GameJar game=new GameJar(new File(args[0]),null,"Wrong_320x240.jar");
        if(game.width!=240 || game.height!=320 || !game.confidence.equals("alta"))throw new AssertionError("Manifest detection failed");
        GameJar again=new GameJar(new File(args[0]),null,"renamed.jar");
        if(!game.appId.equals(again.appId))throw new AssertionError("App id depends on file name");
        Map<String,byte[]> classes=new LinkedHashMap<>();int changed=0;
        for(Map.Entry<String,byte[]> entry:game.entries.entrySet())if(entry.getKey().endsWith(".class")) {
            byte[] patched=new ClassPatcher(entry.getValue()).patch();
            if(!Arrays.equals(patched,entry.getValue()))changed++;
            new ClassPatcher(patched);classes.put(entry.getKey(),patched);
        }
        if(changed==0)throw new AssertionError("Resource calls were not patched");
        Archives.write(new File(args[1]),classes);
        try{Archives.safe("../outside");throw new AssertionError("Traversal accepted");}catch(IOException expected){}
        if(args.length>2) {
            Map<String,byte[]> apk=Archives.load(new File(args[2]));Map<String,String> map=new HashMap<>();
            map.put("com.j2a.offline.template","com.test.generated.game");map.put("J2A_GAME_TITLE","Juego ampliado ñ");map.put("J2A_GAME_VERSION","2.4.1");
            byte[] manifest=BinaryXml.replace(apk.get("AndroidManifest.xml"),map);BinaryXml.versionCode(manifest,20401);apk.put("AndroidManifest.xml",manifest);
            apk.put("resources.arsc",BinaryXml.replace(apk.get("resources.arsc"),Collections.emptyMap()));Archives.write(new File(args[3]),apk);
        }
        System.out.println("Offline core OK");
    }
}
