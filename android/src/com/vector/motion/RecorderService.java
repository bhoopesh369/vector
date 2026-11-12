package com.vector.motion;

import android.app.*;
import android.bluetooth.*;
import android.bluetooth.le.*;
import android.content.*;
import android.content.pm.ServiceInfo;
import android.os.*;
import java.io.*;
import java.time.Instant;
import java.util.*;

public class RecorderService extends Service {
    public static final UUID SERVICE = UUID.fromString("7b8e0001-6f27-4b8e-a9a1-3cd405ee1920");
    public static final UUID DATA = UUID.fromString("7b8e0002-6f27-4b8e-a9a1-3cd405ee1920");
    private static final UUID CCC = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");
    public volatile String status = "Connect your watch to start", recordingName = "", lastSaved = "";
    public volatile MotionPacket latest;
    public volatile boolean ready, recording;
    public volatile long count, missing, clipped;
    public volatile double recordedSeconds;
    private long recordingFirstUs;
    private HandlerThread thread;
    private Handler work;
    private BluetoothGatt gatt;
    private BluetoothLeScanner scanner;
    private BufferedWriter writer;
    private PowerManager.WakeLock wake;
    private boolean active, scanning;
    private long lastData, deadline, lastFlush;
    private MotionPacket previous;
    private final IBinder binder = new LocalBinder();
    public class LocalBinder extends Binder { public RecorderService service() { return RecorderService.this; } }
    @Override public IBinder onBind(Intent intent) { return binder; }
    @Override public void onCreate() {
        super.onCreate();
        thread = new HandlerThread("Vector BLE recorder"); thread.start(); work = new Handler(thread.getLooper());
        wake = ((PowerManager)getSystemService(POWER_SERVICE)).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Vector:recording");
        wake.setReferenceCounted(false);
        lastSaved = getSharedPreferences("session",0).getString("lastSaved", "");
        status = getSharedPreferences("session",0).getString("lastOutcome", status);
        File dir = new File(getFilesDir(),"recordings"); dir.mkdirs();
        // A crash or forced process stop leaves a durable marker rather than claiming a clean finish.
        File[] pending = dir.listFiles((d,n)->n.endsWith(".active"));
        if (pending != null) for(File marker:pending) {
            try { try(FileWriter f=new FileWriter(new File(dir,marker.getName().replace(".active",".status.txt")))) {
                f.write("Interrupted: app process ended; buffered samples may be missing.\n"); }
                marker.delete(); status="Previous recording interrupted; saved CSV is available";
                getSharedPreferences("session",0).edit().putString("lastOutcome",status).apply();
            } catch(IOException ignored) { status="Previous recording interrupted; recovery marker retained"; }
        }
        work.postDelayed(watchdog,1000);
    }
    @Override public int onStartCommand(Intent intent,int flags,int id) {
        String action = intent == null ? "DISCONNECT" : intent.getAction();
        if ("CONNECT".equals(action)) {
            try {
                NotificationManager nm=getSystemService(NotificationManager.class);
                nm.createNotificationChannel(new NotificationChannel("recorder","Watch connection",NotificationManager.IMPORTANCE_LOW));
                startForeground(1, notification("Connecting to watch"), ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
            } catch(RuntimeException e) { status="Cannot start Bluetooth service: "+e.getMessage(); stopSelf(); return START_NOT_STICKY; }
        }
        work.post(()->{
            try {
                if("CONNECT".equals(action)) connect();
                else if("RECORD".equals(action)) beginRecording();
                else if("STOP_RECORD".equals(action)) finishRecording("Stopped by user");
                else shutdown("Disconnected by user");
            } catch(Exception e) { shutdown("Error: "+e.getMessage()); }
        });
        return START_NOT_STICKY;
    }
    private Notification notification(String text) {
        PendingIntent open=PendingIntent.getActivity(this,0,new Intent(this,MainActivity.class),PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stop=PendingIntent.getService(this,1,new Intent(this,RecorderService.class).setAction("DISCONNECT"),PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this,"recorder").setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle("Vector Motion").setContentText(text).setContentIntent(open).setOngoing(true)
            .addAction(new Notification.Action.Builder(null,"Stop & disconnect",stop).build()).build();
    }
    private void update(String text) {
        status=text;
        if(active) getSystemService(NotificationManager.class).notify(1,notification(text));
    }
    private void connect() {
        if(active) return;
        BluetoothAdapter adapter=getSystemService(BluetoothManager.class).getAdapter();
        if(adapter==null || !adapter.isEnabled()) { shutdown("Turn on Bluetooth, then tap Connect"); return; }
        active=true; ready=false; latest=null; previous=null;
        scanner=adapter.getBluetoothLeScanner(); scanning=true; deadline=SystemClock.elapsedRealtime()+20000;
        update("Looking for Vector Motion…");
        scanner.startScan(Collections.singletonList(new ScanFilter.Builder().setServiceUuid(new ParcelUuid(SERVICE)).build()),
            new ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), scan);
    }
    private final ScanCallback scan=new ScanCallback() {
        @Override public void onScanResult(int type, ScanResult result) { work.post(()->{
            if(!active || !scanning) return;
            try {
                stopScan(); update("Connecting to watch…"); deadline=SystemClock.elapsedRealtime()+20000;
                gatt=result.getDevice().connectGatt(RecorderService.this,false,callback,BluetoothDevice.TRANSPORT_LE);
            } catch(RuntimeException e) { shutdown("Connection failed: "+e.getMessage()); }
        }); }
        @Override public void onScanFailed(int code) { work.post(()->shutdown("Bluetooth scan failed ("+code+")")); }
    };
    private void stopScan() {
        if(scanning && scanner!=null) scanner.stopScan(scan);
        scanning=false;
    }
    private final BluetoothGattCallback callback=new BluetoothGattCallback() {
        @Override public void onConnectionStateChange(BluetoothGatt g,int result,int state) { work.post(()->{
            if(g!=gatt || !active) return;
            if(result!=BluetoothGatt.GATT_SUCCESS || state==BluetoothProfile.STATE_DISCONNECTED) {
                shutdown("Interrupted: Bluetooth disconnected ("+result+")"); return;
            }
            if(state==BluetoothProfile.STATE_CONNECTED) {
                g.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH);
                if(!g.requestMtu(185)) shutdown("Could not request Bluetooth packet size");
            }
        }); }
        @Override public void onMtuChanged(BluetoothGatt g,int mtu,int result) { work.post(()->{
            if(g!=gatt || !active) return;
            if(result!=0 || mtu<29 || !g.discoverServices()) shutdown("Bluetooth packet-size negotiation failed");
        }); }
        @Override public void onServicesDiscovered(BluetoothGatt g,int result) { work.post(()->{
            if(g!=gatt || !active) return;
            BluetoothGattService service=g.getService(SERVICE);
            BluetoothGattCharacteristic data=service==null?null:service.getCharacteristic(DATA);
            BluetoothGattDescriptor descriptor=data==null?null:data.getDescriptor(CCC);
            if(result!=0 || descriptor==null || !g.setCharacteristicNotification(data,true)) { shutdown("Watch motion service unavailable"); return; }
            descriptor.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
            if(!g.writeDescriptor(descriptor)) shutdown("Could not subscribe to motion data");
        }); }
        @Override public void onDescriptorWrite(BluetoothGatt g,BluetoothGattDescriptor d,int result) { work.post(()->{
            if(g!=gatt || !active) return;
            if(result!=0) { shutdown("Motion subscription failed"); return; }
            lastData=SystemClock.elapsedRealtime(); update("Waiting for first motion sample…");
        }); }
        @Override public void onCharacteristicChanged(BluetoothGatt g,BluetoothGattCharacteristic c) {
            enqueueNotification(g,c,c.getValue()); // Android 12/12L callback.
        }
        @Override public void onCharacteristicChanged(BluetoothGatt g,BluetoothGattCharacteristic c,byte[] value) {
            enqueueNotification(g,c,value); // Android 13+ callback.
        }
        private void enqueueNotification(BluetoothGatt g,BluetoothGattCharacteristic c,byte[] value) {
            if(value==null) return;
            byte[] copy=value.clone(); work.post(()->{ if(g==gatt && active && DATA.equals(c.getUuid())) receive(copy); });
        }
    };
    private void receive(byte[] bytes) {
        try {
            MotionPacket sample=new MotionPacket(bytes);
            long gap=previous==null?0:MotionPacket.gap(previous.sequence,previous.micros,sample);
            previous=sample; latest=sample; lastData=SystemClock.elapsedRealtime();
            if(!ready) { ready=true; update("Connected • live motion"); }
            if(recording) {
                writer.write(sample.csv());
                if(count==0) recordingFirstUs=sample.micros;
                recordedSeconds=(sample.micros-recordingFirstUs)/1e6;
                count++; missing+=gap; clipped+=sample.clipped;
                if(lastData-lastFlush>=1000) {
                    writer.flush(); lastFlush=lastData;
                    update(String.format(Locale.US,"Recording • %.1f s • %,d samples saved",recordedSeconds,count));
                }
            }
        } catch(Exception e) { shutdown("Interrupted: "+e.getMessage()); }
    }
    private void beginRecording() throws IOException {
        if(!ready || recording) return;
        recordingName="badminton-"+Instant.now().toString().replace(':','-')+".csv";
        File f=new File(new File(getFilesDir(),"recordings"),recordingName);
        if(!f.createNewFile()) throw new IOException("Recording filename already exists");
        writer=new BufferedWriter(new FileWriter(f));
        writer.write(MotionPacket.HEADER+"\n"); writer.flush();
        new File(f.getPath()+".active").createNewFile();
        count=missing=clipped=0; recordedSeconds=0; lastFlush=SystemClock.elapsedRealtime();
        recording=true; wake.acquire(); update("Recording badminton • screen may be turned off");
    }
    private void finishRecording(String reason) {
        if(writer==null) return;
        recording=false;
        try { writer.close(); } catch(IOException e) { reason+="; file close error: "+e.getMessage(); }
        writer=null;
        if(wake.isHeld()) wake.release();
        File f=new File(new File(getFilesDir(),"recordings"),recordingName);
        boolean summarySaved=false;
        try(FileWriter summary=new FileWriter(f.getPath()+".status.txt")) {
            summary.write(reason+"\nDurationSeconds="+recordedSeconds+"\nSamples="+count+"\nMissing="+missing+"\nClipped="+clipped+"\n"); summarySaved=true;
        } catch(IOException e) { reason+="; could not save summary"; }
        if(summarySaved) new File(f.getPath()+".active").delete();
        lastSaved=recordingName;
        String outcome=reason+" • saved "+count+" samples";
        getSharedPreferences("session",0).edit().putString("lastSaved",lastSaved).putString("lastOutcome",outcome).apply();
        update(outcome);
    }
    private void shutdown(String reason) {
        boolean endedRecording=writer!=null;
        finishRecording(reason);
        active=false; ready=false;
        try { stopScan(); } catch(RuntimeException ignored) { scanning=false; }
        if(gatt!=null) { BluetoothGatt old=gatt; gatt=null; try { old.disconnect(); old.close(); } catch(RuntimeException ignored) {} }
        if(wake.isHeld()) wake.release();
        status=endedRecording ? getSharedPreferences("session",0).getString("lastOutcome",reason) : reason;
        stopForeground(STOP_FOREGROUND_REMOVE);
        if(endedRecording) {
            PendingIntent open=PendingIntent.getActivity(this,0,new Intent(this,MainActivity.class),PendingIntent.FLAG_IMMUTABLE);
            getSystemService(NotificationManager.class).notify(2,new Notification.Builder(this,"recorder")
                .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth).setContentTitle("Vector recording ended")
                .setContentText(status).setContentIntent(open).setAutoCancel(true).build());
        }
        stopSelf();
    }
    private final Runnable watchdog=new Runnable() { public void run() {
        if(active) {
            long now=SystemClock.elapsedRealtime();
            if(!ready && now>deadline) shutdown("Connection timed out • keep watch nearby and try again");
            else if(ready && now-lastData>5000) shutdown("Interrupted: no motion data for five seconds");
        }
        work.postDelayed(this,1000);
    }};
    @Override public void onDestroy() {
        work.post(()->{ shutdown("Service ended"); work.removeCallbacksAndMessages(null); thread.quitSafely(); });
        super.onDestroy();
    }
}
