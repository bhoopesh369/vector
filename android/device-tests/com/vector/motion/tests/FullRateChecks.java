package com.vector.motion.tests;
import android.app.*;
import android.content.*;
import android.os.*;
import com.vector.motion.*;
import java.io.*;
import java.util.concurrent.*;
/** Service-level full-stream check; works with the phone locked and preserves existing files. */
final class FullRateChecks {
 static void run(Instrumentation test){Context context=test.getTargetContext();Bundle result=new Bundle();RecorderService[] recorder=new RecorderService[1];CountDownLatch latch=new CountDownLatch(1);boolean[] bound={false};PowerManager power=context.getSystemService(PowerManager.class);boolean initiallyAwake=power.isInteractive();
  ServiceConnection connection=new ServiceConnection(){public void onServiceConnected(ComponentName n,IBinder binder){recorder[0]=((RecorderService.LocalBinder)binder).service();latch.countDown();}public void onServiceDisconnected(ComponentName n){recorder[0]=null;}};
  try{
   bound[0]=context.bindService(new Intent(context,RecorderService.class),connection,Context.BIND_AUTO_CREATE);LiveChecks.check(latch.await(10,TimeUnit.SECONDS),"Recorder bound");
   context.startForegroundService(new Intent(context,RecorderService.class).setAction("CONNECT"));LiveChecks.until(()->recorder[0].ready&&!recorder[0].syncBusy,30000);LiveChecks.check(recorder[0].fullRate,"Full-rate characteristic unavailable; verify updated firmware and GATT discovery");
   context.startService(new Intent(context,RecorderService.class).setAction("RECORD").putExtra("sport","Other").putExtra("wrist","Right"));LiveChecks.until(()->recorder[0].recording&&recorder[0].count>100,5000);String file=recorder[0].recordingName;
   LiveChecks.shell(test,"input keyevent KEYCODE_SLEEP");Thread.sleep(1000);boolean offAtStart=!power.isInteractive();long before=recorder[0].count;Thread.sleep(30000);long added=recorder[0].count-before;boolean offAtEnd=!power.isInteractive();
   LiveChecks.check(recorder[0].recording,"Full-rate recording interrupted: "+recorder[0].status);LiveChecks.check(added>28000,"Full-rate throughput below expected ~1000Hz: "+added+" samples/30s");
   context.startService(new Intent(context,RecorderService.class).setAction("STOP_RECORD"));LiveChecks.until(()->!recorder[0].recording,5000);
   File csv=new File(new File(context.getFilesDir(),"recordings"),file);LiveChecks.until(()->!new File(csv.getPath()+".active").exists(),5000);SessionStore store=new SessionStore(csv.getParentFile());MotionAnalytics.Stats stats=store.summary(csv);
   LiveChecks.check(stats.receivedHz>=950&&stats.missing==0,"Saved CSV rate or sequence gaps: "+stats.receivedHz+" Hz, missing="+stats.missing);LiveChecks.check("bluetooth_fullrate".equals(store.metadata(csv).optString("source")),"Full-rate provenance persisted");LiveChecks.check(recorder[0].liveSnapshot().size()<stats.count/2,"Chart decimation does not reduce recorded data");
   for(SessionStore.Session session:store.list())if(session.csv.equals(csv))store.notes(session,"Automated full-rate BLE check. Phone screen off observed at interval boundaries: "+(offAtStart&&offAtEnd)+". Raw acquired samples; FIFO timing gaps remain.");
   result.putString("stream","PASS: full-rate BLE CSV "+file+"; "+stats.count+" samples / "+stats.durationSeconds+"s = "+stats.receivedHz+"Hz; missing="+stats.missing+"; maxGapUs="+stats.maxGapUs+"; phoneScreenOffObserved="+(offAtStart&&offAtEnd)+"; 30s added="+added+"; full-rate metadata and reduced display preview verified\n");test.finish(Activity.RESULT_OK,result);
  }catch(Throwable failure){result.putString("stream","FAIL: "+android.util.Log.getStackTraceString(failure)+"\nStatus: "+(recorder[0]==null?"unbound":recorder[0].status+" / "+recorder[0].syncStatus));test.finish(Activity.RESULT_CANCELED,result);}
  finally{try{context.startService(new Intent(context,RecorderService.class).setAction("STOP_RECORD"));Thread.sleep(500);context.startService(new Intent(context,RecorderService.class).setAction("DISCONNECT"));if(initiallyAwake)LiveChecks.shell(test,"input keyevent KEYCODE_WAKEUP");}catch(Exception ignored){}if(bound[0])try{context.unbindService(connection);}catch(Exception ignored){}}
 }
}
