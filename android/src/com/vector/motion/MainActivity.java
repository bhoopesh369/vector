package com.vector.motion;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.graphics.*;
import android.os.*;
import android.view.*;
import android.widget.*;
import java.io.*;
import java.util.*;

public class MainActivity extends Activity {
    private RecorderService recorder;
    private boolean bound;
    private TextView status, values, stats;
    private Button record, stop;
    private MotionChart chart;
    private File exportFile;
    private long plotted=-1;
    private final Handler ui=new Handler(Looper.getMainLooper());
    private final ServiceConnection connection=new ServiceConnection() {
        public void onServiceConnected(ComponentName name,IBinder binder) { recorder=((RecorderService.LocalBinder)binder).service(); }
        public void onServiceDisconnected(ComponentName name) { recorder=null; }
    };
    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        if(saved!=null && saved.getString("export")!=null) exportFile=new File(saved.getString("export"));
        LinearLayout page=new LinearLayout(this); page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(24,24,24,24); page.setBackgroundColor(Color.rgb(12,20,32));
        page.setOnApplyWindowInsetsListener((v,insets)->{
            android.graphics.Insets bars=insets.getInsets(WindowInsets.Type.systemBars());
            v.setPadding(24+bars.left,24+bars.top,24+bars.right,24+bars.bottom); return insets;
        });
        setContentView(page);
        TextView title=text("VECTOR / MOTION",26); page.addView(title);
        page.addView(text("Badminton • raw wrist motion",15));
        status=text("Connect your watch to start",16); page.addView(status);
        LinearLayout buttons=new LinearLayout(this); page.addView(buttons);
        button(buttons,"Connect",()->command("CONNECT"));
        button(buttons,"Disconnect",()->command("DISCONNECT"));
        LinearLayout capture=new LinearLayout(this); page.addView(capture);
        record=button(capture,"Record",()->command("RECORD"));
        stop=button(capture,"Stop & save",()->command("STOP_RECORD"));
        button(page,"Export saved CSV",this::chooseExport);
        stats=text("No recording yet",14); page.addView(stats);
        values=text("Acceleration (g)\nRotation (deg/s)",15); page.addView(values);
        chart=new MotionChart(this); page.addView(chart,new LinearLayout.LayoutParams(-1,0,1));
        page.addView(text("X coral   Y mint   Z blue\nLive graphs pause while hidden. CSV recording continues with the phone screen off.\nWatch has no SD recording yet; Bluetooth loss ends this session.",13));
        permissions();
    }
    private TextView text(String content,int size) {
        TextView t=new TextView(this); t.setText(content); t.setTextColor(Color.rgb(226,235,246));
        t.setTextSize(size); t.setPadding(0,8,0,8); return t;
    }
    private Button button(LinearLayout parent,String label,Runnable action) {
        Button b=new Button(this); b.setText(label); parent.addView(b);
        b.setOnClickListener(v->action.run()); return b;
    }
    private boolean permissions() {
        ArrayList<String> needed=new ArrayList<>();
        for(String p:new String[]{Manifest.permission.BLUETOOTH_SCAN,Manifest.permission.BLUETOOTH_CONNECT})
            if(checkSelfPermission(p)!=PackageManager.PERMISSION_GRANTED) needed.add(p);
        if(Build.VERSION.SDK_INT>=33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)
            needed.add(Manifest.permission.POST_NOTIFICATIONS);
        if(!needed.isEmpty()) { requestPermissions(needed.toArray(new String[0]),1); return false; }
        return true;
    }
    private void command(String action) {
        if("CONNECT".equals(action) && !permissions()) { status.setText("Allow Nearby devices and notifications, then tap Connect"); return; }
        Intent i=new Intent(this,RecorderService.class).setAction(action);
        try { if("CONNECT".equals(action)) startForegroundService(i); else startService(i); }
        catch(RuntimeException e) { status.setText("Could not start: "+e.getMessage()); }
    }
    @Override protected void onStart() {
        super.onStart(); bound=bindService(new Intent(this,RecorderService.class),connection,BIND_AUTO_CREATE); ui.post(refresh);
    }
    @Override protected void onStop() {
        ui.removeCallbacks(refresh); if(bound) { unbindService(connection); bound=false; recorder=null; } super.onStop();
    }
    @Override protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out); if(exportFile!=null) out.putString("export",exportFile.getAbsolutePath());
    }
    private final Runnable refresh=new Runnable() { public void run() {
        if(recorder!=null) {
            status.setText(recorder.status);
            record.setEnabled(recorder.ready && !recorder.recording); stop.setEnabled(recorder.recording);
            stats.setText((recorder.recording?"RECORDING • ":"Received in last recording • ")+String.format(Locale.US,"%.1f s • ",recorder.recordedSeconds)+recorder.count+" samples\n"+recorder.missing+" missing • "+recorder.clipped+" clipped");
            MotionPacket sample=recorder.latest;
            if(sample!=null && sample.micros!=plotted) {
                if(sample.micros<plotted) chart.clear(); plotted=sample.micros; chart.add(sample.axes);
                values.setText(String.format(Locale.US,"Acceleration (g)   %+.3f   %+.3f   %+.3f\nRotation (deg/s)   %+.1f   %+.1f   %+.1f", sample.axes[0],sample.axes[1],sample.axes[2],sample.axes[3],sample.axes[4],sample.axes[5]));
            }
        }
        ui.postDelayed(this,200);
    }};
    private void chooseExport() {
        File dir=new File(getFilesDir(),"recordings");
        File[] files=dir.listFiles((d,name)->name.endsWith(".csv") && !new File(d,name+".active").exists());
        if(files==null || files.length==0) { status.setText("Stop and save a recording before exporting"); return; }
        Arrays.sort(files,(a,b)->b.getName().compareTo(a.getName()));
        String[] names=new String[files.length]; for(int i=0;i<files.length;i++) names[i]=files[i].getName();
        new AlertDialog.Builder(this).setTitle("Choose a recording").setItems(names,(dialog,index)->{
            exportFile=files[index];
            Intent i=new Intent(Intent.ACTION_CREATE_DOCUMENT).setType("text/csv").addCategory(Intent.CATEGORY_OPENABLE)
                .putExtra(Intent.EXTRA_TITLE,exportFile.getName()); startActivityForResult(i,2);
        }).show();
    }
    @Override protected void onActivityResult(int request,int result,Intent data) {
        super.onActivityResult(request,result,data);
        if(request==2 && result==RESULT_OK && data!=null && data.getData()!=null && exportFile!=null) {
            File source=exportFile; android.net.Uri destination=data.getData();
            new Thread(()->{
                try(InputStream in=new FileInputStream(source); OutputStream out=getContentResolver().openOutputStream(destination)) {
                    if(out==null) throw new IOException("Could not open destination");
                    byte[] buffer=new byte[8192]; int n; while((n=in.read(buffer))!=-1) out.write(buffer,0,n);
                    ui.post(()->Toast.makeText(this,"CSV exported",Toast.LENGTH_LONG).show());
                } catch(IOException e) { ui.post(()->Toast.makeText(this,"Export failed: "+e.getMessage(),Toast.LENGTH_LONG).show()); }
            }).start();
        }
    }
    private static class MotionChart extends View {
        final float[][] points=new float[160][6]; int size=0, head=0;
        final Paint p=new Paint(Paint.ANTI_ALIAS_FLAG);
        final int[] colors={Color.rgb(255,130,115),Color.rgb(80,225,180),Color.rgb(90,165,255)};
        MotionChart(Context c) { super(c); }
        void clear(){size=0;head=0;}
        void add(float[] values){System.arraycopy(values,0,points[head],0,6);head=(head+1)%points.length;size=Math.min(size+1,points.length);invalidate();}
        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas); float w=getWidth(),h=getHeight()/2f;
            if(h<30) return;
            for(int panel=0;panel<2;panel++) {
                p.setColor(Color.LTGRAY);p.setTextSize(28);p.setStrokeWidth(1);
                canvas.drawText(panel==0?"Acceleration (g)":"Rotation (deg/s)",8,panel*h+30,p);
                float scale=panel==0?2:150;
                for(int i=0;i<size;i++) for(int axis=0;axis<3;axis++) scale=Math.max(scale,Math.abs(points[i][panel*3+axis])*1.15f);
                p.setTextSize(22);canvas.drawText(String.format(Locale.US,"±%.1f",scale),w-110,panel*h+30,p);
                float center=panel*h+h*.55f, span=h*.35f;
                p.setColor(Color.DKGRAY);canvas.drawLine(0,center,w,center,p);
                for(int axis=0;axis<3;axis++) {
                    p.setColor(colors[axis]);p.setStrokeWidth(3);
                    for(int i=1;i<size;i++) {
                        int first=(head-size+i-1+points.length)%points.length, second=(first+1)%points.length;
                        canvas.drawLine((i-1)*w/159,center-points[first][panel*3+axis]/scale*span,
                            i*w/159,center-points[second][panel*3+axis]/scale*span,p);
                    }
                }
            }
        }
    }
}
