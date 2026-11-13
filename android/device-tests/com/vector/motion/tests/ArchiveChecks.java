package com.vector.motion.tests;

import android.app.*;
import android.content.*;
import android.os.*;
import com.vector.motion.*;
import java.io.*;
import java.util.concurrent.*;

/** Uses a real already-saved watch session; never deletes watch files or existing phone recordings. */
final class ArchiveChecks {
 static void run(Instrumentation test){Bundle result=new Bundle();Context context=test.getTargetContext();RecorderService[] recorder=new RecorderService[1];CountDownLatch latch=new CountDownLatch(1);boolean[] bound={false};
  ServiceConnection connection=new ServiceConnection(){public void onServiceConnected(ComponentName n,IBinder b){recorder[0]=((RecorderService.LocalBinder)b).service();latch.countDown();}public void onServiceDisconnected(ComponentName n){recorder[0]=null;}};
  try{
   MainActivity activity=(MainActivity)test.startActivitySync(new Intent(context,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));test.waitForIdleSync();
   bound[0]=context.bindService(new Intent(context,RecorderService.class),connection,Context.BIND_AUTO_CREATE);LiveChecks.check(latch.await(10,TimeUnit.SECONDS),"Recorder bound");
   LiveChecks.click(test,activity,"nav:Record");LiveChecks.click(test,activity,"connect");LiveChecks.until(()->recorder[0].syncAvailable&&!recorder[0].syncBusy,30000);
   LiveChecks.check(!recorder[0].watchSessions.isEmpty(),"No watch sessions yet. Record and stop a short session on the watch first. Status: "+recorder[0].syncStatus);
   File directory=new File(context.getFilesDir(),"recordings");
   WatchSync.Item item=null;for(WatchSync.Item candidate:recorder[0].watchSessions)if(!new File(directory,"watch-"+candidate.id+".csv").exists()&&(item==null||candidate.started>item.started||(candidate.started==item.started&&candidate.bytes>item.bytes)))item=candidate;
   if(item==null)item=recorder[0].watchSessions.get(0);
   WatchSync.Item chosen=item;File target=new File(directory,"watch-"+item.id+".csv");File partial=new File(new File(directory,".watch-downloads"),item.id+".vmo.part");
   boolean fresh=!target.exists();
   if(fresh&&!partial.exists()){
    partial.getParentFile().mkdirs();try(RandomAccessFile corrupt=new RandomAccessFile(partial,"rw")){corrupt.setLength(item.bytes);}
    context.startService(new Intent(context,RecorderService.class).setAction("WATCH_GET").putExtra("id",item.id));Thread.sleep(300);
    LiveChecks.until(()->!recorder[0].syncBusy,15000);LiveChecks.check(!target.exists()&&!partial.exists(),"Corrupt completed archive discarded rather than permanently resumed");
   }
   if(fresh&&item.bytes>10000){
    context.startService(new Intent(context,RecorderService.class).setAction("WATCH_GET").putExtra("id",item.id));
    LiveChecks.until(()->partial.exists()&&partial.length()>1024&&partial.length()<chosen.bytes,15000);
    context.startService(new Intent(context,RecorderService.class).setAction("CANCEL_SYNC"));LiveChecks.until(()->!recorder[0].ready&&!recorder[0].syncBusy,8000);long retained=partial.length();LiveChecks.check(retained>0&&retained<item.bytes,"Partial archive retained after disconnection");
    context.startForegroundService(new Intent(context,RecorderService.class).setAction("CONNECT"));LiveChecks.until(()->recorder[0].syncAvailable&&!recorder[0].syncBusy,30000);
   }
   context.startService(new Intent(context,RecorderService.class).setAction("WATCH_GET").putExtra("id",item.id));
   LiveChecks.until(()->target.exists()&&!recorder[0].syncBusy,120000);
   SessionStore store=new SessionStore(directory);MotionAnalytics.Stats stats=store.summary(target);LiveChecks.check(stats.count==item.samples,"Imported sample count matches watch inventory");LiveChecks.check(store.metadata(target).getLong("watchCrc")==item.crc,"CRC persisted");
   byte[] before=java.nio.file.Files.readAllBytes(target.toPath());context.startService(new Intent(context,RecorderService.class).setAction("WATCH_GET").putExtra("id",item.id));Thread.sleep(500);
   LiveChecks.check(java.util.Arrays.equals(before,java.nio.file.Files.readAllBytes(target.toPath())),"Duplicate import never overwrites CSV");
   VisualChecks.click(test,activity,"nav:Home");VisualChecks.click(test,activity,"watch-library");VisualChecks.shot(test,"watch-library");
   result.putString("stream","PASS: real watch inventory, corrupt-complete retry, cancel/reconnect/resume, verified import "+stats.count+" samples over "+stats.durationSeconds+" seconds, "+stats.receivedHz+" Hz, "+stats.missing+" sequence gaps, longest timestamp gap "+stats.maxGapUs+" us, clipping "+stats.clippedPercent()+"%, file "+target.getName()+", duplicate-import preservation, Watch library UI\n");
   context.startService(new Intent(context,RecorderService.class).setAction("DISCONNECT"));test.finish(Activity.RESULT_OK,result);
  }catch(Throwable failure){result.putString("stream","FAIL: "+android.util.Log.getStackTraceString(failure)+"\nTransfer: "+(recorder[0]==null?"unbound":recorder[0].syncStatus+" / "+recorder[0].status));test.finish(Activity.RESULT_CANCELED,result);}finally{if(bound[0])try{context.unbindService(connection);}catch(Exception ignored){}}
 }
}
