package com.vector.motion;

import android.content.Context;
import android.graphics.*;
import android.view.*;
import java.util.*;
import java.util.concurrent.*;

/** Time-based min/max envelope chart. Statistics never use these reduced points. */
public final class MotionChartView extends View {
    public interface RangeListener{void changed(double start,double end);}
    private static final ExecutorService PLOTS=Executors.newSingleThreadExecutor();
    private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
    private final int[] colors={SportUi.CORAL,SportUi.MINT,SportUi.BLUE};
    private final boolean[] axes={true,true,true};
    private final int offset;
    private List<MotionAnalytics.Sample> data=Collections.emptyList();
    private double start,end=1,total=1;private volatile long generation;
    private double[][][] envelopes;private double scale=1;
    private RangeListener listener;private float touchX;private boolean interactive=true;
    private final ScaleGestureDetector pinch;
    public MotionChartView(Context c,int offset){super(c);this.offset=offset;setContentDescription(offset==0?"Acceleration including gravity chart":"Angular velocity chart");
        pinch=new ScaleGestureDetector(c,new ScaleGestureDetector.SimpleOnScaleGestureListener(){
            @Override public boolean onScale(ScaleGestureDetector detector){double width=(end-start)/detector.getScaleFactor();change((start+end-width)/2,width);return true;}
        });
    }
    public void setListener(RangeListener listener){this.listener=listener;}
    public void setInteractive(boolean enabled){interactive=enabled;}
    public void setData(List<MotionAnalytics.Sample> data){this.data=data;total=data.size()>1?(data.get(data.size()-1).micros-data.get(0).micros)/1e6:1;total=Math.max(.001,total);start=0;end=total;reduce();}
    public void setAxis(int axis,boolean enabled){axes[axis]=enabled;invalidate();}
    public void range(double from,double to){start=Math.max(0,from);end=Math.min(total,Math.max(from+.001,to));reduce();}
    public void reset(){change(0,total);}
    private void change(double from,double width){width=Math.max(Math.min(.05,total),Math.min(total,width));start=Math.max(0,Math.min(total-width,from));end=start+width;reduce();if(listener!=null)listener.changed(start,end);}
    private void reduce(){
        final long task=++generation;final List<MotionAnalytics.Sample> source=data;final double left=start,right=end;
        final int bins=Math.max(24,Math.min(1000,getWidth()>0?getWidth():400));
        PLOTS.execute(()->{
            if(task!=generation)return;double[][][] result=new double[3][][];double max=offset==0?1:10;
            for(int axis=0;axis<3;axis++) {result[axis]=MotionAnalytics.envelope(source,offset+axis,left,right,bins);
                for(double[] b:result[axis])if(!Double.isNaN(b[1]))max=Math.max(max,Math.max(Math.abs(b[1]),Math.abs(b[2])));}
            final double limit=max*1.1;
            post(()->{if(task!=generation)return;envelopes=result;scale=limit;invalidate();});
        });
    }
    @Override protected void onSizeChanged(int w,int h,int ow,int oh){super.onSizeChanged(w,h,ow,oh);if(w>0)reduce();}
    @Override protected void onDraw(Canvas canvas){super.onDraw(canvas);
        float left=SportUi.dp(getContext(),12),right=getWidth()-left,top=SportUi.dp(getContext(),30),bottom=getHeight()-SportUi.dp(getContext(),32),center=(top+bottom)/2,half=(bottom-top)/2;
        paint.setTextSize(SportUi.dp(getContext(),11));paint.setStrokeWidth(1);
        for(int i=0;i<=4;i++){float x=left+(right-left)*i/4;paint.setColor(Color.rgb(44,59,79));canvas.drawLine(x,top,x,bottom,paint);
            paint.setColor(SportUi.MUTED);String text=String.format(Locale.US,"%.1fs",start+(end-start)*i/4);canvas.drawText(text,Math.min(right-paint.measureText(text),x),getHeight()-8,paint);}
        paint.setColor(SportUi.MUTED);canvas.drawText(String.format(Locale.US,"±%.1f %s",scale,offset==0?"g":"deg/s"),left,top-10,paint);
        paint.setColor(Color.rgb(60,75,94));canvas.drawLine(left,center,right,center,paint);
        if(envelopes==null)return;
        for(int axis=0;axis<3;axis++)if(axes[axis]) {
            paint.setColor(colors[axis]);paint.setStrokeWidth(SportUi.dp(getContext(),1));float px=0,py=0;boolean previous=false;
            for(double[] b:envelopes[axis]){
                if(Double.isNaN(b[1])){previous=false;continue;}
                float x=left+(float)((b[0]-start)/(end-start))*(right-left);
                float lo=center-(float)(b[1]/scale)*half,hi=center-(float)(b[2]/scale)*half,mid=(lo+hi)/2;
                if(previous)canvas.drawLine(px,py,x,mid,paint);
                canvas.drawLine(x,lo,x,hi,paint);canvas.drawCircle(x,mid,SportUi.dp(getContext(),.8f),paint);
                px=x;py=mid;previous=true;
            }
        }
    }
    @Override public boolean onTouchEvent(android.view.MotionEvent event){
        if(!interactive)return false;pinch.onTouchEvent(event);
        switch(event.getActionMasked()){
            case MotionEvent.ACTION_DOWN:touchX=event.getX();getParent().requestDisallowInterceptTouchEvent(true);return true;
            case MotionEvent.ACTION_MOVE:
                if(!pinch.isInProgress() && event.getPointerCount()==1){double dx=(event.getX()-touchX)/Math.max(1,getWidth())*(end-start);touchX=event.getX();change(start-dx,end-start);}return true;
            case MotionEvent.ACTION_UP:case MotionEvent.ACTION_CANCEL:getParent().requestDisallowInterceptTouchEvent(false);performClick();return true;
        }return true;
    }
    @Override public boolean performClick(){super.performClick();return true;}
    @Override protected void onDetachedFromWindow(){generation++;super.onDetachedFromWindow();}
}
