package com.vector.motion.tests;

import android.app.*;
import android.content.*;
import android.graphics.Bitmap;
import android.os.*;
import android.view.*;
import android.widget.*;
import com.vector.motion.*;
import java.io.*;
import java.util.*;

final class VisualChecks {
    static void check(boolean ok,String message){if(!ok)throw new AssertionError(message);}
    static View find(Instrumentation test,Activity activity,String tag)throws Exception{View[] v=new View[1];for(int i=0;i<100;i++){test.runOnMainSync(()->v[0]=activity.getWindow().getDecorView().findViewWithTag(tag));if(v[0]!=null)return v[0];Thread.sleep(100);}throw new AssertionError("Missing "+tag);}
    static void click(Instrumentation test,Activity activity,String tag)throws Exception{View view=find(test,activity,tag);test.runOnMainSync(view::performClick);test.waitForIdleSync();}
    static void shot(Instrumentation test,String name)throws Exception{Thread.sleep(500);Bitmap b=test.getUiAutomation().takeScreenshot();File dir=new File(test.getTargetContext().getExternalFilesDir(null),"ui-checks");dir.mkdirs();try(OutputStream out=new FileOutputStream(new File(dir,name+".png"))){b.compress(Bitmap.CompressFormat.PNG,100,out);}b.recycle();}
    static void pinch(Instrumentation test,View chart){test.runOnMainSync(()->{
        long now=SystemClock.uptimeMillis();float w=chart.getWidth(),h=chart.getHeight();
        MotionEvent.PointerProperties[] props=new MotionEvent.PointerProperties[2];MotionEvent.PointerCoords[] coords=new MotionEvent.PointerCoords[2];
        for(int i=0;i<2;i++){props[i]=new MotionEvent.PointerProperties();props[i].id=i;props[i].toolType=MotionEvent.TOOL_TYPE_FINGER;coords[i]=new MotionEvent.PointerCoords();coords[i].y=h/2;coords[i].pressure=1;coords[i].size=1;}
        coords[0].x=w*.3f;coords[1].x=w*.7f;
        send(chart,now,now,MotionEvent.ACTION_DOWN,1,props,coords);
        send(chart,now,now+20,MotionEvent.ACTION_POINTER_DOWN|(1<<8),2,props,coords);
        coords[0].x=w*.1f;coords[1].x=w*.9f;send(chart,now,now+40,MotionEvent.ACTION_MOVE,2,props,coords);
        coords[0].x=w*.05f;coords[1].x=w*.95f;send(chart,now,now+60,MotionEvent.ACTION_MOVE,2,props,coords);
        send(chart,now,now+80,MotionEvent.ACTION_POINTER_UP|(1<<8),2,props,coords);send(chart,now,now+100,MotionEvent.ACTION_UP,1,props,coords);
    });test.waitForIdleSync();}
    static void send(View view,long down,long time,int action,int count,MotionEvent.PointerProperties[] p,MotionEvent.PointerCoords[] c){MotionEvent event=MotionEvent.obtain(down,time,action,count,p,c,0,0,1,1,0,0,android.view.InputDevice.SOURCE_TOUCHSCREEN,0);view.dispatchTouchEvent(event);event.recycle();}
    static void run(Instrumentation test){Bundle result=new Bundle();try{
        MainActivity activity=(MainActivity)test.startActivitySync(new Intent(test.getTargetContext(),MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));test.waitForIdleSync();Thread.sleep(1000);shot(test,"home");
        click(test,activity,"nav:Record");shot(test,"record");click(test,activity,"nav:Sessions");
        List<SessionStore.Session> sessions=new SessionStore(new File(test.getTargetContext().getFilesDir(),"recordings")).list();List<SessionStore.Session> valid=new ArrayList<>();for(SessionStore.Session s:sessions)if(s.stats!=null)valid.add(s);check(valid.size()>=2,"Two saved recordings available");
        SessionStore.Session s=valid.get(0);find(test,activity,"detail:"+s.csv.getName());shot(test,"sessions");click(test,activity,"detail:"+s.csv.getName());View chart=find(test,activity,"analysis-acceleration");TextView range=(TextView)find(test,activity,"analysis-range");String before=range.getText().toString();pinch(test,chart);Thread.sleep(500);check(!before.equals(range.getText().toString()),"Pinch updates selected time range");
        View axis=find(test,activity,"axis:0:0");click(test,activity,"axis:0:0");check(!((CheckBox)axis).isChecked(),"Axis toggle updates");shot(test,"analysis");
        test.runOnMainSync(()->{android.view.ViewParent parent=chart.getParent();while(parent!=null&&!(parent instanceof ScrollView))parent=parent.getParent();if(parent instanceof ScrollView){ScrollView scroll=(ScrollView)parent;android.graphics.Rect rect=new android.graphics.Rect();chart.getDrawingRect(rect);scroll.offsetDescendantRectToMyCoords(chart,rect);scroll.scrollTo(0,Math.max(0,rect.top-30));}});shot(test,"charts");
        click(test,activity,"nav:Sessions");click(test,activity,"select:"+valid.get(0).csv.getName());click(test,activity,"select:"+valid.get(1).csv.getName());click(test,activity,"compare");find(test,activity,"comparison-title");shot(test,"compare");
        result.putString("stream","PASS: real saved-session analysis, native pinch/time-range interaction, axis toggles, two-session comparison; five UI screenshots saved\n");test.finish(Activity.RESULT_OK,result);
    }catch(Throwable failure){result.putString("stream","FAIL: "+android.util.Log.getStackTraceString(failure));test.finish(Activity.RESULT_CANCELED,result);}}
}
