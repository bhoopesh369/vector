package com.vector.motion;

import android.app.AlertDialog;
import android.content.Intent;
import android.widget.*;
import java.util.*;

final class RecordScreen {
    private final MainActivity host;final LinearLayout root;
    private final TextView status,timer,samples,quality,configuration;
    private final java.util.List<android.view.View> setupViews=new ArrayList<>();
    private final Spinner sport,wrist;
    private final Button connect,record,stop,marker;
    private final MotionChartView acceleration,rotation;
    private long drawnMicros=-1;
    RecordScreen(MainActivity host){this.host=host;root=SportUi.column(host);
        SportUi.caption(root,"CAPTURE / TRAIN / UNDERSTAND");SportUi.heading(root,"Your next session",30);
        LinearLayout connection=SportUi.card(root);status=SportUi.heading(connection,"Watch disconnected",16);
        connect=SportUi.button(connection,"Connect watch",false,()->{
            if(host.recorder!=null && host.recorder.ready)host.command("DISCONNECT",null);else host.command("CONNECT",null);
        });connect.setTag("connect");
        setupViews.add(SportUi.text(root,"SPORT",13,SportUi.MUTED));sport=SportUi.choices(root,new String[]{"Badminton","Tennis","Running","Other"});sport.setSelection(host.sportIndex);sport.setTag("sport-selector");setupViews.add(sport);
        sport.setOnItemSelectedListener(selection(()->host.sportIndex=sport.getSelectedItemPosition()));
        setupViews.add(SportUi.text(root,"WATCH WRIST",13,SportUi.MUTED));wrist=SportUi.choices(root,new String[]{"Right","Left"});wrist.setSelection(host.wristIndex);wrist.setTag("wrist-selector");setupViews.add(wrist);
        wrist.setOnItemSelectedListener(selection(()->host.wristIndex=wrist.getSelectedItemPosition()));
        LinearLayout session=SportUi.card(root);SportUi.caption(session,"SESSION TIME");timer=SportUi.heading(session,"00:00",52);timer.setTag("timer");samples=SportUi.text(session,"0 samples received",15,SportUi.MINT);configuration=SportUi.text(session,"",13,SportUi.MUTED);
        record=SportUi.button(session,"Start recording",true,()->{
            Intent extra=new Intent().putExtra("sport",sport.getSelectedItem().toString()).putExtra("wrist",wrist.getSelectedItem().toString());host.command("RECORD",extra);
        });record.setTag("record");
        marker=SportUi.button(session,"+ Add drill marker",false,this::marker);marker.setTag("marker");
        stop=SportUi.button(session,"Stop & save session",false,()->{host.command("STOP_RECORD",null);host.handler.postDelayed(()->host.refreshSessions(),500);});stop.setTag("stop-record");
        quality=SportUi.text(session,"Waiting for sensor data",13,SportUi.MUTED);
        SportUi.caption(root,"Live preview · X coral / Y mint / Z blue");
        LinearLayout a=SportUi.card(root);SportUi.heading(a,"Acceleration",18);SportUi.caption(a,"Includes gravity · g");acceleration=new MotionChartView(host,0);acceleration.setInteractive(false);a.addView(acceleration,new LinearLayout.LayoutParams(-1,SportUi.dp(host,190)));
        LinearLayout g=SportUi.card(root);SportUi.heading(g,"Angular velocity",18);SportUi.caption(g,"Sensor axes · deg/s");rotation=new MotionChartView(host,3);rotation.setInteractive(false);g.addView(rotation,new LinearLayout.LayoutParams(-1,SportUi.dp(host,190)));
        SportUi.caption(root,"Graphs pause while hidden. Phone recording continues with the screen off. Keep the watch nearby; Bluetooth loss ends this session. No SD recording yet.");update();
    }
    private android.widget.AdapterView.OnItemSelectedListener selection(Runnable changed){return new AdapterView.OnItemSelectedListener(){public void onItemSelected(AdapterView<?> p,android.view.View v,int pos,long id){changed.run();}public void onNothingSelected(AdapterView<?> p){}};}
    void update(){RecorderService r=host.recorder;boolean ready=r!=null&&r.ready,recording=r!=null&&r.recording;
        status.setText(r==null?"Watch disconnected":r.status);timer.setText(SportUi.duration(r==null?0:r.recordedSeconds));
        samples.setText(String.format(Locale.US,"%,d samples received",r==null?0:r.count));
        connect.setText(ready?"Disconnect watch":"Connect watch");record.setEnabled(ready&&!recording&&!r.syncBusy);record.setAlpha(record.isEnabled()?1f:.4f);stop.setEnabled(recording);marker.setEnabled(recording&&r.count>0);sport.setEnabled(!recording);wrist.setEnabled(!recording);
        for(android.view.View view:setupViews)view.setVisibility(recording?android.view.View.GONE:android.view.View.VISIBLE);
        record.setVisibility(recording?android.view.View.GONE:android.view.View.VISIBLE);stop.setVisibility(recording?android.view.View.VISIBLE:android.view.View.GONE);marker.setVisibility(recording?android.view.View.VISIBLE:android.view.View.GONE);
        configuration.setText(recording?r.currentSport+" · "+r.currentWrist+" wrist · recording on phone":"Choose sport and wrist before starting");
        quality.setText(r==null?"Waiting for sensor data":String.format(Locale.US,"%,d missing sequences   ·   %,d clipped samples",r.missing,r.clipped));
        if(recording){sport.setSelection(Arrays.asList("Badminton","Tennis","Running","Other").indexOf(r.currentSport));wrist.setSelection("Left".equals(r.currentWrist)?1:0);}
        if(r!=null&&r.latest!=null&&r.latest.micros!=drawnMicros){drawnMicros=r.latest.micros;List<MotionAnalytics.Sample> data=r.liveSnapshot();acceleration.setData(data);rotation.setData(data);}
    }
    private void marker(){new AlertDialog.Builder(host).setTitle("Mark this drill").setItems(new String[]{"Rest","Forehand","Backhand","Warm-up","Custom label…"},(dialog,index)->{
        if(index<4)host.command("MARKER",new Intent().putExtra("label",new String[]{"Rest","Forehand","Backhand","Warm-up"}[index]));
        else{EditText input=new EditText(host);input.setHint("Drill name");new AlertDialog.Builder(host).setTitle("Add marker").setView(input).setPositiveButton("Save",(d,w)->host.command("MARKER",new Intent().putExtra("label",input.getText().toString()))).setNegativeButton("Cancel",null).show();}
    }).show();}
}
