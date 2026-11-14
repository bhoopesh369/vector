package com.vector.motion;

import java.io.*;
import java.util.*;

/** Full-resolution statistics. Acceleration includes gravity; axes remain sensor-native. */
public final class MotionAnalytics {
    public static final int VERSION=1;
    public static final class Sample {
        public final long sequence, micros; public final double[] axes; public final int clipped;
        public Sample(long sequence,long micros,double[] axes,int clipped) {
            if(sequence<0 || sequence>0xffffffffL || micros<0 || axes.length!=6 || (clipped!=0 && clipped!=1))
                throw new IllegalArgumentException("Invalid motion sample");
            for(double v:axes) if(!Double.isFinite(v)) throw new IllegalArgumentException("Non-finite motion value");
            this.sequence=sequence;this.micros=micros;this.axes=axes.clone();this.clipped=clipped;
        }
        public static Sample from(MotionPacket p){double[] a=new double[6];for(int i=0;i<6;i++)a[i]=p.axes[i];return new Sample(p.sequence,p.micros,a,p.clipped);}
    }
    public static final class Stats {
        public long count,missing,clipped,maxGapUs; public double durationSeconds,receivedHz,peakAcceleration,peakRotation;
        public final double[] min=new double[6],max=new double[6],rms=new double[6];
        public double clippedPercent(){return count==0?0:100.0*clipped/count;}
    }
    public static List<Sample> read(File file) throws IOException {
        List<Sample> values=new ArrayList<>();
        Accumulator validator=new Accumulator(Double.NEGATIVE_INFINITY,Double.POSITIVE_INFINITY);
        scan(file,sample->{validator.add(sample);values.add(sample);});
        return values;
    }

    public static Stats summarize(File file) throws IOException {
        Accumulator accumulator=new Accumulator(Double.NEGATIVE_INFINITY,Double.POSITIVE_INFINITY);
        scan(file,accumulator::add);
        return accumulator.finish();
    }

    private static void scan(File file,java.util.function.Consumer<Sample> accept) throws IOException {
        try(BufferedReader reader=new BufferedReader(new FileReader(file))) {
            String line;
            int n=0;
            while((line=reader.readLine())!=null) {
                n++;
                line=line.trim();
                if(line.isEmpty()||line.startsWith("#")||line.equals(MotionPacket.HEADER))continue;
                try {
                    String[] fields=line.split(",",-1);
                    if(fields.length!=9)throw new IllegalArgumentException("Expected nine fields");
                    double[] axes=new double[6];
                    for(int i=0;i<6;i++)axes[i]=Double.parseDouble(fields[i+2]);
                    accept.accept(new Sample(Long.parseLong(fields[0]),Long.parseLong(fields[1]),axes,Integer.parseInt(fields[8])));
                } catch(IllegalArgumentException ex) {
                    throw new IOException("Invalid CSV sample at line "+n+": "+ex.getMessage(),ex);
                }
            }
        }
    }

    public static Stats calculate(List<Sample> data,double start,double end) {
        Accumulator accumulator=new Accumulator(start,end);
        for(Sample sample:data)accumulator.add(sample);
        return accumulator.finish();
    }

    private static final class Accumulator {
        private final Stats out=new Stats();
        private final double start,end;
        private Sample previous,selectedPrevious;
        private long origin,firstUs;

        Accumulator(double start,double end) {
            if(start>end)throw new IllegalArgumentException("Range start is after end");
            this.start=start;
            this.end=end;
            Arrays.fill(out.min,Double.POSITIVE_INFINITY);
            Arrays.fill(out.max,Double.NEGATIVE_INFINITY);
        }

        void add(Sample sample) {
            if(previous==null)origin=sample.micros;
            else {
                long delta=(sample.sequence-previous.sequence)&0xffffffffL;
                if(sample.micros<=previous.micros || delta==0 || delta>0x7fffffffL)
                    throw new IllegalArgumentException("Recording contains a restart or out-of-order samples");
            }
            previous=sample;
            double seconds=(sample.micros-origin)/1e6;
            if(seconds<start || seconds>end)return;
            if(selectedPrevious==null)firstUs=sample.micros;
            else {
                out.missing+=((sample.sequence-selectedPrevious.sequence)&0xffffffffL)-1;
                out.maxGapUs=Math.max(out.maxGapUs,sample.micros-selectedPrevious.micros);
            }
            selectedPrevious=sample;
            out.count++;
            out.clipped+=sample.clipped;
            for(int i=0;i<6;i++) {
                double v=sample.axes[i];
                out.min[i]=Math.min(out.min[i],v);
                out.max[i]=Math.max(out.max[i],v);
                out.rms[i]+=v*v;
            }
            out.peakAcceleration=Math.max(out.peakAcceleration,norm(sample.axes,0));
            out.peakRotation=Math.max(out.peakRotation,norm(sample.axes,3));
        }

        Stats finish() {
            if(out.count==0) {
                Arrays.fill(out.min,0);
                Arrays.fill(out.max,0);
            } else {
                for(int i=0;i<6;i++)out.rms[i]=Math.sqrt(out.rms[i]/out.count);
                if(out.count>1) {
                    out.durationSeconds=(selectedPrevious.micros-firstUs)/1e6;
                    out.receivedHz=(out.count-1)/out.durationSeconds;
                }
            }
            return out;
        }
    }

    private static double norm(double[] a,int offset){return Math.hypot(Math.hypot(a[offset],a[offset+1]),a[offset+2]);}
    /** Pixel-bucket envelope; empty buckets are NaN and must not be connected across. */
    public static double[][] envelope(List<Sample> data,int axis,double start,double end,int buckets) {
        if(axis<0||axis>=6||buckets<1||end<=start)throw new IllegalArgumentException("Invalid plot range");
        double[][] out=new double[buckets][3];
        for(int i=0;i<buckets;i++){out[i][0]=start+(i+.5)*(end-start)/buckets;out[i][1]=Double.NaN;out[i][2]=Double.NaN;}
        if(data.isEmpty())return out;long origin=data.get(0).micros;
        for(Sample s:data){double time=(s.micros-origin)/1e6;if(time<start||time>end)continue;
            int b=Math.min(buckets-1,(int)((time-start)/(end-start)*buckets));double v=s.axes[axis];
            if(Double.isNaN(out[b][1])){out[b][1]=v;out[b][2]=v;}else{out[b][1]=Math.min(out[b][1],v);out[b][2]=Math.max(out[b][2],v);}
        }return out;
    }
}
