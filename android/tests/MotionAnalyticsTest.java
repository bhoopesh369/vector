import com.vector.motion.*;
import java.util.*;

public class MotionAnalyticsTest {
 static void equal(double want,double got){if(Math.abs(want-got)>0.00001)throw new AssertionError("want "+want+" got "+got);}
 static MotionAnalytics.Sample s(long seq,long us,int clip,double...axes){return new MotionAnalytics.Sample(seq,us,axes,clip);}
 static void rejects(Runnable r){try{r.run();}catch(IllegalArgumentException expected){return;}throw new AssertionError("expected invalid data rejection");}
 public static void main(String[] args) {
  List<MotionAnalytics.Sample> data=Arrays.asList(s(0xfffffffeL,1000000,0,3,4,0,0,0,2),s(1,2000000,1,0,0,1,0,3,4),s(2,4000000,0,-3,0,0,0,0,-2));
  MotionAnalytics.Stats all=MotionAnalytics.calculate(data,Double.NEGATIVE_INFINITY,Double.POSITIVE_INFINITY);
  equal(3,all.count);equal(3,all.durationSeconds);equal(2.0/3,all.receivedHz);equal(5,all.peakAcceleration);equal(5,all.peakRotation);
  equal(2,all.missing);equal(2000000,all.maxGapUs);equal(100.0/3,all.clippedPercent());equal(-3,all.min[0]);equal(3,all.max[0]);equal(Math.sqrt(6),all.rms[0]);
  MotionAnalytics.Stats range=MotionAnalytics.calculate(data,1,3);equal(2,range.count);equal(0,range.missing);equal(2,range.durationSeconds);equal(3,range.peakAcceleration);
  MotionAnalytics.Stats empty=MotionAnalytics.calculate(Collections.emptyList(),0,3);equal(0,empty.count);equal(0,empty.receivedHz);
  equal(0,MotionAnalytics.calculate(Collections.singletonList(data.get(0)),0,0).durationSeconds);
  rejects(()->MotionAnalytics.calculate(Arrays.asList(data.get(1),data.get(0)),0,100));
  rejects(()->MotionAnalytics.calculate(Arrays.asList(data.get(0),data.get(0)),0,100));
  List<MotionAnalytics.Sample> dense=new ArrayList<>();
  for(int i=0;i<1000;i++)dense.add(s(i,i*1000,0,i==501?99:0,0,0,0,0,0));
  double[][] envelope=MotionAnalytics.envelope(dense,0,0,1,10);
  double peak=0;for(double[] bucket:envelope)peak=Math.max(peak,bucket[2]);equal(99,peak);
  System.out.println("Analytics: units, RMS, ranges, gaps/wrap, clipping, invalid data, peak preservation PASS");
 }
}
