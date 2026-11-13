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
    private SessionStore sessions;
    private WatchSync sync;
    public volatile boolean syncAvailable,syncBusy;
    public volatile String syncStatus="Connect the watch to see saved sessions", lastImported="";
    public volatile List<WatchSync.Item> watchSessions=Collections.emptyList();
    public volatile String currentSport="Badminton", currentWrist="Right";
    private final ArrayDeque<MotionAnalytics.Sample> live=new ArrayDeque<>();
    public List<MotionAnalytics.Sample> liveSnapshot(){synchronized(live){return new ArrayList<>(live);}}
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
        sessions=new SessionStore(new File(getFilesDir(),"recordings"));
        work.post(this::recoverSessions);
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
                else if("RECORD".equals(action)) {
                    currentSport=valid(intent.getStringExtra("sport"),new String[]{"Badminton","Tennis","Running","Other"},"Badminton");
                    currentWrist=valid(intent.getStringExtra("wrist"),new String[]{"Left","Right"},"Right");
                    beginRecording();
                }
                else if("MARKER".equals(action) && recording && count>0 && latest!=null) {
                    String label=intent.getStringExtra("label");
                    if(label!=null && !label.trim().isEmpty())sessions.marker(new File(sessions.directory,recordingName),label.trim(),latest.micros);
                }
                else if("WATCH_LIST".equals(action)) {if(sync!=null&&!recording)sync.list();}
                else if("WATCH_GET".equals(action)) {if(sync!=null&&!recording)sync.download(intent.getStringExtra("id"));}
                else if("CANCEL_SYNC".equals(action)) shutdown("Transfer cancelled; reconnect to resume");
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
        synchronized(live){live.clear();}
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
                shutdown("Interrupted: watch connection lost"); return;
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
            if(service!=null){
                sync=new WatchSync(g,service,work,sessions,(available,busy,text,items,imported)->{
                    boolean wasBusy=syncBusy;syncAvailable=available;syncBusy=busy;if(wasBusy&&!busy)lastData=SystemClock.elapsedRealtime();syncStatus=text;watchSessions=items;
                    if(!imported.isEmpty())lastImported=imported;
                    if(busy&&!wake.isHeld())wake.acquire();else if(!busy&&!recording&&wake.isHeld())wake.release();
                    if(active&&busy)getSystemService(NotificationManager.class).notify(1,notification(text));
                    else if(active&&!recording)getSystemService(NotificationManager.class).notify(1,notification(status));
                });
            }
            BluetoothGattCharacteristic data=service==null?null:service.getCharacteristic(DATA);
            BluetoothGattDescriptor descriptor=data==null?null:data.getDescriptor(CCC);
            if(result!=0 || descriptor==null || !g.setCharacteristicNotification(data,true)) { shutdown("Watch motion service unavailable"); return; }
            descriptor.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
            if(!g.writeDescriptor(descriptor)) shutdown("Could not subscribe to motion data");
        }); }
        @Override public void onDescriptorWrite(BluetoothGatt g,BluetoothGattDescriptor d,int result) { work.post(()->{
            if(g!=gatt || !active) return;
            if(WatchSync.FILE.equals(d.getCharacteristic().getUuid())){if(sync!=null)sync.subscribed(result);return;}
            if(result!=0) { shutdown("Motion subscription failed"); return; }
            lastData=SystemClock.elapsedRealtime(); update("Waiting for first motion sample…");
            if(sync!=null&&sync.supported()&&!sync.subscribe())syncStatus="Watch transfer subscription could not start";
        }); }
        @Override public void onCharacteristicChanged(BluetoothGatt g,BluetoothGattCharacteristic c) {
            enqueueNotification(g,c,c.getValue()); // Android 12/12L callback.
        }
        @Override public void onCharacteristicChanged(BluetoothGatt g,BluetoothGattCharacteristic c,byte[] value) {
            enqueueNotification(g,c,value); // Android 13+ callback.
        }
        @Override public void onCharacteristicWrite(BluetoothGatt g,BluetoothGattCharacteristic c,int result){work.post(()->{if(g==gatt&&active&&sync!=null&&WatchSync.CONTROL.equals(c.getUuid()))sync.wrote(result);});}
        @Override public void onCharacteristicRead(BluetoothGatt g,BluetoothGattCharacteristic c,int result){enqueueRead(g,c,c.getValue(),result);}
        @Override public void onCharacteristicRead(BluetoothGatt g,BluetoothGattCharacteristic c,byte[] value,int result){enqueueRead(g,c,value,result);}
        private void enqueueRead(BluetoothGatt g,BluetoothGattCharacteristic c,byte[] value,int result){byte[] copy=value==null?new byte[0]:value.clone();work.post(()->{if(g==gatt&&active&&sync!=null&&WatchSync.CONTROL.equals(c.getUuid()))sync.read(copy,result);});}
        private void enqueueNotification(BluetoothGatt g,BluetoothGattCharacteristic c,byte[] value) {
            if(value==null) return;
            byte[] copy=value.clone(); work.post(()->{if(g!=gatt||!active)return;if(DATA.equals(c.getUuid()))receive(copy);else if(WatchSync.FILE.equals(c.getUuid())&&sync!=null)sync.chunk(copy);});
        }
    };
    private void receive(byte[] bytes) {
        try {
            MotionPacket sample=new MotionPacket(bytes);
            long gap=previous==null?0:MotionPacket.gap(previous.sequence,previous.micros,sample);
            previous=sample; latest=sample; lastData=SystemClock.elapsedRealtime();
            synchronized(live){live.addLast(MotionAnalytics.Sample.from(sample));while(live.size()>1200)live.removeFirst();}
            if(!ready) { ready=true; update("Connected • live motion"); }
            if(recording) {
                writer.write(sample.csv());
                if(count==0) recordingFirstUs=sample.micros;
                recordedSeconds=(sample.micros-recordingFirstUs)/1e6;
                if(count>0) missing+=gap; count++; clipped+=sample.clipped;
                if(lastData-lastFlush>=1000) {
                    writer.flush(); lastFlush=lastData;
                    update(String.format(Locale.US,"Recording • %.1f s • %,d samples saved",recordedSeconds,count));
                }
            }
        } catch(Exception e) { shutdown("Interrupted: "+e.getMessage()); }
    }
    private void beginRecording() throws IOException {
        if(!ready || recording || syncBusy) return;
        recordingName=currentSport.toLowerCase(Locale.US)+"-"+Instant.now().toString().replace(':','-')+".csv";
        File f=new File(new File(getFilesDir(),"recordings"),recordingName);
        if(!f.createNewFile()) throw new IOException("Recording filename already exists");
        writer=new BufferedWriter(new FileWriter(f));
        writer.write(MotionPacket.HEADER+"\n"); writer.flush();
        new File(f.getPath()+".active").createNewFile();
        sessions.create(f,currentSport,currentWrist);
        count=missing=clipped=0; recordedSeconds=0; lastFlush=SystemClock.elapsedRealtime();
        recording=true; wake.acquire(); update("Recording "+currentSport+" • screen may be turned off");
    }
    private void finishRecording(String reason) {
        if(writer==null) return;
        try { writer.close(); } catch(IOException e) { reason+="; file close error: "+e.getMessage(); }
        writer=null;
        File f=new File(new File(getFilesDir(),"recordings"),recordingName);
        boolean summarySaved=false;
        try(FileWriter summary=new FileWriter(f.getPath()+".status.txt")) {
            summary.write(reason+"\nDurationSeconds="+recordedSeconds+"\nSamples="+count+"\nMissing="+missing+"\nClipped="+clipped+"\n"); summarySaved=true;
        } catch(IOException e) { reason+="; could not save summary"; }
        try { sessions.finish(f,reason,count,missing,clipped,recordedSeconds); }
        catch(IOException e) { summarySaved=false; reason+="; could not save session metadata"; }
        if(summarySaved) new File(f.getPath()+".active").delete();
        lastSaved=recordingName;
        String outcome=reason+" • saved "+count+" samples";
        getSharedPreferences("session",0).edit().putString("lastSaved",lastSaved).putString("lastOutcome",outcome).apply();
        recording=false;
        if(wake.isHeld()) wake.release();
        update(outcome);
    }
    private void shutdown(String reason) {
        boolean endedRecording=writer!=null;
        finishRecording(reason);
        active=false; ready=false;
        if(sync!=null){sync.close();sync=null;}syncAvailable=false;syncBusy=false;
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
            else if(ready && !syncBusy && now-lastData>5000) shutdown("Interrupted: no motion data for five seconds");
        }
        work.postDelayed(this,1000);
    }};
    private static String valid(String value,String[] allowed,String fallback){for(String item:allowed)if(item.equals(value))return item;return fallback;}
    private void recoverSessions() {
        try{sessions.recoverInterrupted();}catch(IOException e){status="Interrupted session recovery needs attention: "+e.getMessage();}
    }
    @Override public void onDestroy() {
        work.post(()->{ shutdown("Service ended"); work.removeCallbacksAndMessages(null); thread.quitSafely(); });
        super.onDestroy();
    }
}
