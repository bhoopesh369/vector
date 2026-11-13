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
    static void run(Instrumentation test){Bundle result=new Bundle();Context context=test.getTargetContext();RecorderService[] recorder=new RecorderService[1];boolean[] bound={false};CountDownLatch connected=new CountDownLatch(1);
        ServiceConnection connection=new ServiceConnection(){public void onServiceConnected(ComponentName n,IBinder b){recorder[0]=((RecorderService.LocalBinder)b).service();connected.countDown();}public void onServiceDisconnected(ComponentName n){recorder[0]=null;}};
        try {
            MainActivity activity=(MainActivity)test.startActivitySync(new Intent(context,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));test.waitForIdleSync();
            bound[0]=context.bindService(new Intent(context,RecorderService.class),connection,Context.BIND_AUTO_CREATE);check(connected.await(10,TimeUnit.SECONDS),"Recorder bound");
            click(test,activity,"nav:Record");
            test.runOnMainSync(()->{Spinner sport=activity.getWindow().getDecorView().findViewWithTag("sport-selector");sport.setSelection(3);});
            click(test,activity,"connect");until(()->recorder[0].ready,25000);Thread.sleep(500);click(test,activity,"record");until(()->recorder[0].count>100,5000);
            String file=recorder[0].recordingName;
            VisualChecks.shot(test,"record-active");
            context.startService(new Intent(context,RecorderService.class).setAction("MARKER").putExtra("label","Screen-off verification"));
            click(test,activity,"nav:Home");long before=recorder[0].count;test.runOnMainSync(activity::recreate);Thread.sleep(1500);check(recorder[0].recording,"Recording survives activity recreation");
            shell(test,"input keyevent KEYCODE_SLEEP");Thread.sleep(1500);
            String power=shell(test,"dumpsys power");check(!power.contains("mWakefulness=Awake"),"Phone display is asleep during test");
            Thread.sleep(30000);long after=recorder[0].count;check(recorder[0].recording,"Recording remains active with screen off");check(after-before>2500,"Full-rate samples continue with screen off");
            boolean stillOff=!shell(test,"dumpsys power").contains("mWakefulness=Awake");check(stillOff,"Display stayed asleep through test");
            context.startService(new Intent(context,RecorderService.class).setAction("STOP_RECORD"));until(()->!recorder[0].recording,5000);
            File csv=new File(new File(context.getFilesDir(),"recordings"),file);until(()->!new File(csv.getPath()+".active").exists(),5000);
            SessionStore store=new SessionStore(csv.getParentFile());MotionAnalytics.Stats stats=store.summary(csv);
            check(stats.durationSeconds>=30&&stats.count>3000,"Saved data covers screen-off test");check(store.metadata(csv).getJSONArray("markers").length()==1,"Live marker persisted");
            for(SessionStore.Session s:store.list())if(s.csv.equals(csv))store.notes(s,"Automated verification: battery-powered watch; phone screen off for 30 seconds; activity recreation.");
            context.startService(new Intent(context,RecorderService.class).setAction("RECORD").putExtra("sport","Other").putExtra("wrist","Right"));until(()->recorder[0].recording&&recorder[0].count>100,5000);String interrupted=recorder[0].recordingName;
            Field field=RecorderService.class.getDeclaredField("gatt");field.setAccessible(true);((BluetoothGatt)field.get(recorder[0])).disconnect();until(()->!recorder[0].recording&&!recorder[0].ready,5000);
            File cut=new File(csv.getParentFile(),interrupted);until(()->!new File(cut.getPath()+".active").exists(),5000);check(store.metadata(cut).getString("outcome").startsWith("Interrupted"),"GATT loss finalizes interrupted recording");
            for(SessionStore.Session s:store.list())if(s.csv.equals(cut))store.notes(s,"Automated verification: GATT disconnect saves interrupted recording.");
            shell(test,"input keyevent KEYCODE_WAKEUP");
            result.putString("stream",String.format(Locale.US,"PASS: battery-watch BLE, UI navigation, activity recreation, 30 seconds phone screen off, %d new samples, saved %.2f seconds / %d samples / %d gaps, marker persistence, GATT interruption\n",after-before,stats.durationSeconds,stats.count,stats.missing));test.finish(Activity.RESULT_OK,result);
        }catch(Throwable failure){try{shell(test,"input keyevent KEYCODE_WAKEUP");}catch(Exception ignored){}result.putString("stream","FAIL: "+android.util.Log.getStackTraceString(failure));test.finish(Activity.RESULT_CANCELED,result);}
        finally{if(bound[0])try{context.unbindService(connection);}catch(Exception ignored){}}
    }
}
