package j2a.offline;

import android.app.*;
import android.content.*;
import android.os.IBinder;
import java.io.File;
import java.util.concurrent.*;

/** Keep local compilation alive when the user switches apps or locks the screen. */
public final class BuildService extends Service {
    static final String STATE="offline_build";
    private static volatile boolean running;
    static boolean isRunning(){return running;}
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private StringBuilder lines=new StringBuilder();
    private Notification notification(String text) {
        PendingIntent open=PendingIntent.getActivity(this,0,new Intent(this,MainActivity.class),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this,"j2a-build").setSmallIcon(android.R.drawable.stat_sys_upload)
                .setContentTitle("j2a Offline · creando APK").setContentText(text).setContentIntent(open).setOngoing(true).build();
    }
    @Override public void onCreate(){super.onCreate();getSystemService(NotificationManager.class).createNotificationChannel(new NotificationChannel("j2a-build","Conversión local de juegos",NotificationManager.IMPORTANCE_LOW));}
    @Override public int onStartCommand(Intent intent,int flags,int startId) {
        startForeground(1,notification("Preparando conversión local…"));
        if(running)return START_NOT_STICKY;
        if(intent==null || intent.getStringExtra("jar")==null){stopForeground(STOP_FOREGROUND_REMOVE);stopSelf();return START_NOT_STICKY;}
        running=true;lines=new StringBuilder();getSharedPreferences(STATE,0).edit().putBoolean("busy",true).putString("result","").putString("log","").apply();
        String jar=intent.getStringExtra("jar"),jad=intent.getStringExtra("jad"),name=intent.getStringExtra("name");
        worker.execute(()->{
            try {
                File work=new File(getFilesDir(),"builds/"+System.currentTimeMillis());
                File result=new OfflineBuilder(this,this::log).build(new File(jar),jad==null?null:new File(jad),name,work);
                getSharedPreferences(STATE,0).edit().putString("result",result.getAbsolutePath()).apply();
            }catch(Throwable error){log("Error: "+error.toString());}
            finally {
                running=false;getSharedPreferences(STATE,0).edit().putBoolean("busy",false).apply();
                stopForeground(STOP_FOREGROUND_REMOVE);stopSelf(startId);
            }
        });
        return START_NOT_STICKY;
    }
    private synchronized void log(String text) {
        lines.append(text).append('\n');if(lines.length()>64000)lines.delete(0,lines.length()-64000);
        getSharedPreferences(STATE,0).edit().putString("log",lines.toString()).apply();
        getSystemService(NotificationManager.class).notify(1,notification(text));
    }
    @Override public IBinder onBind(Intent intent){return null;}
    @Override public void onDestroy(){worker.shutdown();super.onDestroy();}
}
