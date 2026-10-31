package j2a.offline;

import android.app.Instrumentation;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.os.Bundle;
import java.io.*;

/** Device-level smoke test: D8 and AndroidKeyStore must both work on Android, not just a desktop JVM. */
public final class OfflineSmoke extends Instrumentation {
    @Override public void onCreate(Bundle arguments){super.onCreate(arguments);start();}
    @Override public void onStart() {
        Bundle result=new Bundle();
        try {
            Context target=getTargetContext();File jar=new File(target.getFilesDir(),"smoke.jar");
            try(InputStream in=getContext().getAssets().open("demo.jar");OutputStream out=new FileOutputStream(jar)){OfflineBuilder.copy(in,out);}
            File apk=new OfflineBuilder(target,text->android.util.Log.i("J2A_SMOKE",text)).build(jar,null,"demo.jar",new File(target.getFilesDir(),"smoke-build"));
            PackageInfo info=target.getPackageManager().getPackageArchiveInfo(apk.getAbsolutePath(),0);
            if(info==null || !info.packageName.startsWith("com.j2a.game.g"))throw new AssertionError("APK manifest/package not readable");
            try(InputStream in=new FileInputStream(apk);OutputStream out=new FileOutputStream(new File(target.getExternalFilesDir(null),"demo-output.apk"))){OfflineBuilder.copy(in,out);}
            result.putString("stream","J2A_OFFLINE_SUCCESS\n"+info.packageName+"\n");finish(-1,result);
        }catch(Throwable error){android.util.Log.e("J2A_SMOKE","Offline conversion failed",error);result.putString("stream","J2A_OFFLINE_FAILURE\n"+error+"\n");finish(0,result);}
    }
}
