package com.vector.motion;

import android.widget.*;
import org.json.*;
import java.util.*;

final class AnalysisScreen {
    final LinearLayout root;private final MainActivity host;private final List<MotionAnalytics.Sample> data;private final SessionStore.Session session;
    private final LinearLayout summary,axesStats;private final MotionChartView acceleration,rotation;private final SeekBar from,to;private final TextView rangeText;
    private double duration;private boolean adjusting;private int generation;
    AnalysisScreen(MainActivity host,SessionStore.Session session,List<MotionAnalytics.Sample> data){this.host=host;this.session=session;this.data=data;root=SportUi.column(host);
        SportUi.button(root,"← Sessions",false,()->host.navigate("Sessions"));SportUi.caption(root,SportUi.date(session));SportUi.heading(root,session.sport()+" / Analysis",28);
        SportUi.caption(root,session.wrist()+" wrist · "+session.outcome());if(!session.notes().isEmpty())SportUi.caption(root,session.notes());
        SportUi.caption(root,"All metrics use received samples at full resolution. Acceleration includes gravity.");
        summary=SportUi.column(host);root.addView(summary);
        LinearLayout ranges=SportUi.card(root);SportUi.heading(ranges,"Explore a time range",18);rangeText=SportUi.text(ranges,"Whole session",14,SportUi.MINT);rangeText.setTag("analysis-range");
        SportUi.caption(ranges,"Start");from=new SeekBar(host);from.setMax(1000);from.setTag("range-start");ranges.addView(from);
        SportUi.caption(ranges,"End");to=new SeekBar(host);to.setMax(1000);to.setTag("range-end");to.setProgress(1000);ranges.addView(to);
        duration=data.size()>1?(data.get(data.size()-1).micros-data.get(0).micros)/1e6:0;
        SportUi.button(ranges,"Reset to whole session",false,()->setRange(0,Math.max(.001,duration)));
        SportUi.caption(root,"Pinch to zoom · drag horizontally to move · select axes below");
        LinearLayout a=SportUi.card(root);SportUi.heading(a,"Acceleration · g",18);acceleration=new MotionChartView(host,0);acceleration.setTag("analysis-acceleration");a.addView(acceleration,new LinearLayout.LayoutParams(-1,SportUi.dp(host,230)));toggles(a,acceleration);
        LinearLayout r=SportUi.card(root);SportUi.heading(r,"Angular velocity · deg/s",18);rotation=new MotionChartView(host,3);rotation.setTag("analysis-rotation");r.addView(rotation,new LinearLayout.LayoutParams(-1,SportUi.dp(host,230)));toggles(r,rotation);
        axesStats=SportUi.card(root);
        acceleration.setData(data);rotation.setData(data);
        acceleration.setListener((start,end)->setRange(start,end));rotation.setListener((start,end)->setRange(start,end));
        SeekBar.OnSeekBarChangeListener listener=new SeekBar.OnSeekBarChangeListener(){public void onProgressChanged(SeekBar bar,int progress,boolean user){if(!user||adjusting)return;int lo=from.getProgress(),hi=to.getProgress();if(lo>=hi){if(bar==from)lo=Math.max(0,hi-1);else hi=Math.min(1000,lo+1);}setRange(lo*Math.max(.001,duration)/1000,hi*Math.max(.001,duration)/1000);}public void onStartTrackingTouch(SeekBar b){}public void onStopTrackingTouch(SeekBar b){}};
        from.setOnSeekBarChangeListener(listener);to.setOnSeekBarChangeListener(listener);
        markers();SportUi.button(root,"Export CSV",false,()->host.export(session,false));SportUi.button(root,"Export session ZIP",true,()->host.export(session,true));
        setRange(0,Math.max(.001,duration));
    }
    private void toggles(LinearLayout parent,MotionChartView chart){LinearLayout row=SportUi.row(host);parent.addView(row);int[] colors={SportUi.CORAL,SportUi.MINT,SportUi.BLUE};for(int i=0;i<3;i++){final int axis=i;CheckBox box=new CheckBox(host);box.setText(new String[]{"X","Y","Z"}[i]);box.setTextColor(colors[i]);box.setChecked(true);box.setTag("axis:"+(chart==acceleration?0:3)+":"+i);row.addView(box);box.setOnCheckedChangeListener((b,checked)->chart.setAxis(axis,checked));}}
    private void setRange(double start,double end){adjusting=true;double total=Math.max(.001,duration);from.setProgress((int)(start/total*1000));to.setProgress((int)(end/total*1000));adjusting=false;
        rangeText.setText(String.format(Locale.US,"%.2f s → %.2f s",start,end));acceleration.range(start,end);rotation.range(start,end);
        int task=++generation;host.io.execute(()->{try{MotionAnalytics.Stats result=MotionAnalytics.calculate(data,start,end);host.handler.post(()->{if(task==generation && host.screenObject==this)renderStats(result);});}catch(Exception e){host.handler.post(()->host.message(e.getMessage()));}});
    }
    private void renderStats(MotionAnalytics.Stats s){summary.removeAllViews();axesStats.removeAllViews();SportUi.heading(summary,"Selected motion",21);
        if(s.count==0){SportUi.caption(summary,"No samples in this range.");return;}
        SportUi.metric(summary,"PEAK ACCELERATION",SportUi.number(s.peakAcceleration),"g · vector magnitude, including gravity");
        SportUi.metric(summary,"PEAK ANGULAR SPEED",SportUi.number(s.peakRotation),"deg/s · vector magnitude");
        LinearLayout quality=SportUi.card(summary);SportUi.heading(quality,"Recording quality",19);
        SportUi.text(quality,String.format(Locale.US,"%s duration · %,d samples\n%.2f Hz received\n%,d missing sequences\n%.2f ms longest timestamp gap\n%.2f%% clipped samples",SportUi.duration(s.durationSeconds),s.count,s.receivedHz,s.missing,s.maxGapUs/1000.0,s.clippedPercent()),15,SportUi.TEXT);
        SportUi.caption(quality,"Sequence gaps reveal acquisition/transport loss. Hardware samples missed before acquisition cannot all be detected.");
        SportUi.heading(axesStats,"Per-axis measurements",19);SportUi.caption(axesStats,"Minimum / Maximum / RMS · sensor-native axes");
        for(int i=0;i<6;i++)SportUi.text(axesStats,String.format(Locale.US,"%s  %+.2f / %+.2f / %.2f %s",new String[]{"AX","AY","AZ","GX","GY","GZ"}[i],s.min[i],s.max[i],s.rms[i],i<3?"g":"deg/s"),14,i<3?SportUi.MINT:SportUi.BLUE);
    }
    private void markers(){LinearLayout card=SportUi.card(root);SportUi.heading(card,"Drill markers",20);JSONArray markers=session.metadata.optJSONArray("markers");
        if(markers==null||markers.length()==0||data.isEmpty()){SportUi.caption(card,"No drill markers. Add markers while recording your next session.");return;}
        long origin=data.get(0).micros;for(int i=0;i<markers.length();i++){JSONObject m=markers.optJSONObject(i);if(m==null)continue;
            double time=(m.optLong("watchUs",origin)-origin)/1e6;if(time<0||time>duration)continue;
            JSONObject next=markers.optJSONObject(i+1);double end=next==null?duration:Math.min(duration,(next.optLong("watchUs",origin)-origin)/1e6);
            final double start=time,finish=Math.max(time+.001,end);
            SportUi.button(card,String.format(Locale.US,"%.1f s · %s",time,m.optString("label","Marker")),false,()->setRange(start,Math.min(Math.max(.001,duration),finish)));
        }
    }
    static LinearLayout compare(MainActivity host,SessionStore.Session a,SessionStore.Session b){LinearLayout root=SportUi.column(host);SportUi.button(root,"← Sessions",false,()->host.navigate("Sessions"));SportUi.heading(root,"Session comparison",28).setTag("comparison-title");
        SportUi.caption(root,"Compare context as well as numbers. Wrist side, drill, and watch fit affect measured motion.");
        LinearLayout titles=SportUi.row(host);root.addView(titles);cell(titles,"MEASUREMENT",SportUi.MUTED);cell(titles,a.sport()+"\n"+SportUi.date(a),SportUi.MINT);cell(titles,b.sport()+"\n"+SportUi.date(b),SportUi.BLUE);
        compareRow(root,"Wrist",a.wrist(),b.wrist());compareRow(root,"Duration",SportUi.duration(a.stats.durationSeconds),SportUi.duration(b.stats.durationSeconds));
        compareRow(root,"Peak accel incl. gravity",SportUi.number(a.stats.peakAcceleration)+" g",SportUi.number(b.stats.peakAcceleration)+" g");
        compareRow(root,"Peak angular speed",SportUi.number(a.stats.peakRotation)+" deg/s",SportUi.number(b.stats.peakRotation)+" deg/s");
        compareRow(root,"Received samples",String.valueOf(a.stats.count),String.valueOf(b.stats.count));compareRow(root,"Received rate",SportUi.number(a.stats.receivedHz)+" Hz",SportUi.number(b.stats.receivedHz)+" Hz");
        compareRow(root,"Missing sequences",String.valueOf(a.stats.missing),String.valueOf(b.stats.missing));compareRow(root,"Clipped samples",SportUi.number(a.stats.clippedPercent())+"%",SportUi.number(b.stats.clippedPercent())+"%");
        compareRow(root,"Longest gap",SportUi.number(a.stats.maxGapUs/1000.0)+" ms",SportUi.number(b.stats.maxGapUs/1000.0)+" ms");
        SportUi.caption(root,"Motion values are measurements, not scores. Larger values do not by themselves indicate better technique or training.");return root;
    }
    private static void compareRow(LinearLayout parent,String label,String a,String b){LinearLayout card=SportUi.card(parent);LinearLayout row=SportUi.row(parent.getContext());card.addView(row);cell(row,label,SportUi.TEXT);cell(row,a,SportUi.MINT);cell(row,b,SportUi.BLUE);}
    private static void cell(LinearLayout row,String value,int color){TextView t=new TextView(row.getContext());t.setText(value);t.setTextColor(color);t.setTextSize(13);t.setPadding(4,10,4,10);row.addView(t,new LinearLayout.LayoutParams(0,-2,1));}
}
