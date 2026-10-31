package j2a.offline;

import android.content.Context;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import com.android.apksig.ApkSigner;
import com.android.apksig.ApkVerifier;
import com.android.tools.r8.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.security.cert.X509Certificate;
import java.math.BigInteger;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import javax.security.auth.x500.X500Principal;

/** Local JAR → DEX → APK → signature. No desktop processes and no network requests. */
public final class OfflineBuilder {
    private static final String KEY_ALIAS="j2a-offline-signing-v1";
    private final Context context;
    private final Consumer<String> log;
    public OfflineBuilder(Context context,Consumer<String> log){this.context=context.getApplicationContext();this.log=log;}
    public File build(File source,File jad,String filename,File work) throws Exception {
        if(!work.mkdirs() && !work.isDirectory())throw new IOException("No se pudo crear el área temporal");
        log.accept("1/5 · Analizando el juego…");
        GameJar game=new GameJar(source,jad,filename);
        log.accept(game.name+" · "+game.width+"x"+game.height+" · confianza "+game.confidence);
        if(game.confidence.equals("baja"))log.accept("Resolución provisional: se podrá corregir desde el menú del juego.");
        if(GameJar.platform(game.midlet.replace('.','/')+".class"))throw new IOException("El paquete del juego colisiona con el motor");
        Map<String,byte[]> classes=new LinkedHashMap<>();
        for(Map.Entry<String,byte[]> entry:game.entries.entrySet())if(entry.getKey().endsWith(".class") && !GameJar.platform(entry.getKey()))classes.put(entry.getKey(),new ClassPatcher(entry.getValue()).patch());
        if(classes.isEmpty())throw new IOException("No hay clases de juego para convertir");
        File patched=new File(work,"game.jar");Archives.write(patched,classes);classes.clear();
        log.accept("2/5 · Convirtiendo bytecode Java a DEX en el teléfono…");
        Path dexDir=new File(work,"dex").toPath();Files.createDirectories(dexDir);
        DiagnosticsHandler diagnostics=new DiagnosticsHandler(){
            @Override public void error(Diagnostic diagnostic){log.accept("D8: "+diagnostic.getDiagnosticMessage());}
            @Override public void warning(Diagnostic diagnostic){log.accept("D8: "+diagnostic.getDiagnosticMessage());}
        };
        D8Command command=D8Command.builder(diagnostics).addProgramFiles(patched.toPath())
                .setMinApiLevel(26).setDisableDesugaring(true).setMode(CompilationMode.RELEASE)
                .setOutput(dexDir,OutputMode.DexIndexed).build();
        ExecutorService compiler=Executors.newSingleThreadExecutor();
        try{D8.run(command,compiler);}finally{compiler.shutdown();}
        log.accept("3/5 · Incorporando motor, recursos y bibliotecas 3D…");
        File base=new File(work,"base.apk");
        try(InputStream in=context.getAssets().open("engine-template.apk");OutputStream out=new FileOutputStream(base)){copy(in,out);}
        Map<String,byte[]> apk=Archives.load(base);
        apk.keySet().removeIf(p->p.toUpperCase(Locale.ROOT).startsWith("META-INF/") || p.startsWith("assets/j2a/"));
        Map<String,String> substitutions=new HashMap<>();substitutions.put("com.j2a.offline.template",game.appId);substitutions.put("J2A_GAME_TITLE",game.name);substitutions.put("J2A_GAME_VERSION",game.version);
        byte[] manifest=BinaryXml.replace(apk.get("AndroidManifest.xml"),substitutions);BinaryXml.versionCode(manifest,game.versionCode);apk.put("AndroidManifest.xml",manifest);
        if(game.icon!=null) {
            apk.put("resources.arsc",BinaryXml.replace(apk.get("resources.arsc"),Collections.singletonMap("res/drawable/ic_launcher.xml","res/drawable/game_icon.png")));
            apk.remove("res/drawable/ic_launcher.xml");apk.put("res/drawable/game_icon.png",game.icon);
        }
        StringBuilder resourceIndex=new StringBuilder();
        for(Map.Entry<String,byte[]> entry:game.entries.entrySet()) {
            String path=entry.getKey();
            if(path.endsWith(".class") || path.toUpperCase(Locale.ROOT).startsWith("META-INF/"))continue;
            if(apk.containsKey(path) || path.startsWith("assets/") || path.startsWith("res/") || path.matches("classes(?:\\d+)?\\.dex") || path.startsWith("lib/")) {
                // Root resources retain their JAR path under assets/res when Android-reserved names collide.
                apk.put("assets/res/"+path,entry.getValue());
            }else apk.put(path,entry.getValue());
            resourceIndex.append(path).append('\n');
        }
        apk.put("assets/j2a/port.properties",Archives.properties(game.config()));
        apk.put("assets/j2a/app.properties",Archives.properties(game.attributes));
        apk.put("assets/j2a/resources.txt",resourceIndex.toString().getBytes(StandardCharsets.UTF_8));
        int nextDex=1;for(String path:apk.keySet())if(path.matches("classes(?:\\d+)?\\.dex")){String n=path.substring(7,path.length()-4);nextDex=Math.max(nextDex,n.isEmpty()?2:Integer.parseInt(n)+1);}
        List<Path> outputs=new ArrayList<>();try(java.util.stream.Stream<Path> files=Files.list(dexDir)){files.filter(p->p.toString().endsWith(".dex")).forEach(outputs::add);}
        outputs.sort(Comparator.comparing(p->p.getFileName().toString()));
        if(outputs.isEmpty())throw new IOException("D8 no generó clases DEX");
        for(Path path:outputs)apk.put("classes"+(nextDex++)+".dex",Files.readAllBytes(path));
        File unsigned=new File(work,"unsigned.apk");Archives.write(unsigned,apk);apk.clear();game.entries.clear();
        log.accept("4/5 · Firmando con la clave local de este dispositivo…");
        KeyStore store=KeyStore.getInstance("AndroidKeyStore");store.load(null);
        if(!store.containsAlias(KEY_ALIAS)) {
            KeyPairGenerator generator=KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_RSA,"AndroidKeyStore");
            generator.initialize(new KeyGenParameterSpec.Builder(KEY_ALIAS,KeyProperties.PURPOSE_SIGN|KeyProperties.PURPOSE_VERIFY)
                    .setKeySize(2048).setDigests(KeyProperties.DIGEST_SHA256,KeyProperties.DIGEST_SHA512)
                    .setSignaturePaddings(KeyProperties.SIGNATURE_PADDING_RSA_PKCS1)
                    .setCertificateSubject(new X500Principal("CN=J2A Offline Local"))
                    .setCertificateSerialNumber(BigInteger.ONE).setCertificateNotAfter(new Date(System.currentTimeMillis()+20L*365*86400000)).build());
            generator.generateKeyPair();
        }
        PrivateKey key=(PrivateKey)store.getKey(KEY_ALIAS,null);
        X509Certificate cert=(X509Certificate)store.getCertificate(KEY_ALIAS);
        ApkSigner.SignerConfig signer=new ApkSigner.SignerConfig.Builder("j2a",key,Collections.singletonList(cert)).build();
        File result=new File(work,safeName(game.name)+".apk");
        new ApkSigner.Builder(Collections.singletonList(signer)).setInputApk(unsigned).setOutputApk(result)
                .setMinSdkVersion(26).setV1SigningEnabled(false).setV2SigningEnabled(true).setV3SigningEnabled(false).build().sign();
        log.accept("5/5 · Verificando la firma de la APK…");
        ApkVerifier.Result checked=new ApkVerifier.Builder(result).setMinCheckedPlatformVersion(26).build().verify();
        if(!checked.isVerified()){result.delete();throw new IOException("La firma de la APK no superó la verificación: "+checked.getErrors());}
        log.accept("APK lista · "+game.name+" · "+game.appId);
        return result;
    }
    public static String safeName(String name){String n=name.replaceAll("[<>:\"/\\\\|?*\\p{Cntrl}]","").trim();return n.isEmpty()?"juego":n.substring(0,Math.min(n.length(),100));}
    public static void copy(InputStream in,OutputStream out)throws IOException{byte[] buffer=new byte[32768];int n;while((n=in.read(buffer))!=-1)out.write(buffer,0,n);}
}
