package com.vector.motion.tests;

import android.app.*;
import android.bluetooth.BluetoothGatt;
import android.content.*;
import android.os.*;
import android.view.View;
import android.widget.Spinner;
import com.vector.motion.*;
import java.io.*;
import java.lang.reflect.Field;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;

/** Opt-in real peripheral checks; does not touch radio settings or existing sessions. */
final class LiveChecks {
    static void check(boolean condition,String message){if(!condition)throw new AssertionError(message);}
    static void until(BooleanSupplier condition,long timeout)throws Exception{long end=System.currentTimeMillis()+timeout;while(!condition.getAsBoolean()&&System.currentTimeMillis()<end)Thread.sleep(100);check(condition.getAsBoolean(),"Timed out waiting for device state");}
    static String shell(Instrumentation test,String command)throws IOException{try(ParcelFileDescriptor fd=test.getUiAutomation().executeShellCommand(command);InputStream in=new ParcelFileDescriptor.AutoCloseInputStream(fd);ByteArrayOutputStream out=new ByteArrayOutputStream()){SessionStore.copy(in,out);return out.toString("UTF-8");}}
    static void click(Instrumentation test,Activity activity,String tag){test.runOnMainSync(()->{View v=activity.getWindow().getDecorView().findViewWithTag(tag);check(v!=null&&v.isEnabled(),"Control unavailable: "+tag);v.performClick();});test.waitForIdleSync();}
    static void run(Instrumentation test){run(test,true);}
    static void run(Instrumentation test,boolean screenOff){Bundle result=new Bundle();Context context=test.getTargetContext();RecorderService[] recorder=new RecorderService[1];boolean[] bound={false};CountDownLatch connected=new CountDownLatch(1);
        ServiceConnection connection=new ServiceConnection(){public void onServiceConnected(ComponentName n,IBinder b){recorder[0]=((RecorderService.LocalBinder)b).service();connected.countDown();}public void onServiceDisconnected(ComponentName n){recorder[0]=null;}};
        try {
            MainActivity activity=(MainActivity)test.startActivitySync(new Intent(context,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));test.waitForIdleSync();
            bound[0]=context.bindService(new Intent(context,RecorderService.class),connection,Context.BIND_AUTO_CREATE);check(connected.await(10,TimeUnit.SECONDS),"Recorder bound");
            click(test,activity,"nav:Record");
            test.runOnMainSync(()->{Spinner sport=activity.getWindow().getDecorView().findViewWithTag("sport-selector");sport.setSelection(3);});
            click(test,activity,"connect");until(()->recorder[0].ready,25000);Thread.sleep(500);if(!screenOff){Field probe=RecorderService.class.getDeclaredField("gatt");probe.setAccessible(true);Bundle diagnostic=new Bundle();diagnostic.putString("stream","Connected peripheral "+((BluetoothGatt)probe.get(recorder[0])).getDevice().getAddress()+" preview="+recorder[0].latest.preview+"\n");test.sendStatus(0,diagnostic);check(recorder[0].latest.preview,"Expected high-rate firmware preview packet v2");}click(test,activity,"record");until(()->recorder[0].count>(recorder[0].latest.preview?20:100),5000);
            String file=recorder[0].recordingName;
            VisualChecks.shot(test,"record-active");
            context.startService(new Intent(context,RecorderService.class).setAction("MARKER").putExtra("label","Screen-off verification"));
            click(test,activity,"nav:Home");long before=recorder[0].count;test.runOnMainSync(activity::recreate);Thread.sleep(1500);check(recorder[0].recording,"Recording survives activity recreation");
            if(screenOff)shell(test,"input keyevent KEYCODE_SLEEP");Thread.sleep(1500);
            String power=shell(test,"dumpsys power");if(screenOff)check(!power.contains("mWakefulness=Awake"),"Phone display is asleep during test");
            Thread.sleep(30000);long after=recorder[0].count;check(recorder[0].recording,"Recording remains active with screen off");check(after-before>(recorder[0].latest.preview?200:2500),"Full-rate samples continue with screen off");
            boolean stillOff=!shell(test,"dumpsys power").contains("mWakefulness=Awake");if(screenOff)check(stillOff,"Display stayed asleep through test");
            context.startService(new Intent(context,RecorderService.class).setAction("STOP_RECORD"));until(()->!recorder[0].recording,5000);
            File csv=new File(new File(context.getFilesDir(),"recordings"),file);until(()->!new File(csv.getPath()+".active").exists(),5000);
            SessionStore store=new SessionStore(csv.getParentFile());MotionAnalytics.Stats stats=store.summary(csv);
            check(stats.durationSeconds>=30&&stats.count>(recorder[0].latest.preview?200:3000),"Saved data covers screen-off test");check(store.metadata(csv).getJSONArray("markers").length()==1,"Live marker persisted");if(recorder[0].latest.preview)check("bluetooth_preview".equals(store.metadata(csv).optString("source")),"Reduced preview provenance persisted");
            for(SessionStore.Session s:store.list())if(s.csv.equals(csv))store.notes(s,"Automated verification: watch BLE; phone recording for 30 seconds; activity recreation.");
            context.startService(new Intent(context,RecorderService.class).setAction("RECORD").putExtra("sport","Other").putExtra("wrist","Right"));until(()->recorder[0].recording&&recorder[0].count>(recorder[0].latest.preview?20:100),5000);String interrupted=recorder[0].recordingName;
            Field field=RecorderService.class.getDeclaredField("gatt");field.setAccessible(true);((BluetoothGatt)field.get(recorder[0])).disconnect();until(()->!recorder[0].recording&&!recorder[0].ready,5000);
            File cut=new File(csv.getParentFile(),interrupted);until(()->!new File(cut.getPath()+".active").exists(),5000);check(store.metadata(cut).getString("outcome").startsWith("Interrupted"),"GATT loss finalizes interrupted recording");
            for(SessionStore.Session s:store.list())if(s.csv.equals(cut))store.notes(s,"Automated verification: GATT disconnect saves interrupted recording.");
            shell(test,"input keyevent KEYCODE_WAKEUP");
            result.putString("stream",String.format(Locale.US,"PASS: watch BLE, UI navigation, activity recreation, 30 seconds phone recording (screen-off requested: "+screenOff+"), %d new samples, saved %.2f seconds / %d samples / %d gaps, marker persistence, GATT interruption\n",after-before,stats.durationSeconds,stats.count,stats.missing));test.finish(Activity.RESULT_OK,result);
        }catch(Throwable failure){try{shell(test,"input keyevent KEYCODE_WAKEUP");}catch(Exception ignored){}result.putString("stream","FAIL: "+android.util.Log.getStackTraceString(failure));test.finish(Activity.RESULT_CANCELED,result);}
        finally{if(recorder[0]!=null&&recorder[0].recording)try{context.startService(new Intent(context,RecorderService.class).setAction("STOP_RECORD"));Thread.sleep(500);}catch(Exception ignored){}if(bound[0])try{context.unbindService(connection);}catch(Exception ignored){}}
    }
}
