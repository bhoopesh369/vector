package com.vector.motion;

import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.zip.*;

/** Local sessions; writes only sidecars, never rewrites sensor CSVs. */
public final class SessionStore {
    public static final int VERSION=1;
    private static final Object LOCK=new Object();
    public final File directory;
    public static final class Session {
        public final File csv; public JSONObject metadata;public MotionAnalytics.Stats stats;public String error="";
        Session(File csv){this.csv=csv;}
        public String sport(){return metadata.optString("sport","Not specified");}
        public String wrist(){return metadata.optString("wrist","Not specified");}
        public String notes(){return metadata.optString("notes","");}
        public String outcome(){return metadata.optString("outcome","Not specified");}
        public String date(){return metadata.optString("startedUtc",csv.getName().replace(".csv",""));}
        public long startedMillis(){try{return Instant.parse(metadata.optString("startedUtc","")).toEpochMilli();}catch(Exception e){return csv.lastModified();}}
    }
    public SessionStore(File directory){this.directory=directory;directory.mkdirs();}
    private File sidecar(File csv,String suffix){return new File(csv.getPath()+suffix);}
    public JSONObject metadata(File csv) throws IOException {
        synchronized(LOCK) {
            File file=sidecar(csv,".session.json");
            if(file.exists()) {
                JSONObject value=readJson(file);
                if(value.optInt("version",-1)!=VERSION)throw new IOException("Unsupported session metadata version");
                return value;
            }
            JSONObject value=new JSONObject();
            try {
                value.put("version",VERSION).put("sport","Not specified").put("wrist","Not specified").put("notes","").put("markers",new JSONArray());
                File legacy=sidecar(csv,".status.txt");
                if(legacy.exists())try(BufferedReader reader=new BufferedReader(new FileReader(legacy))) {value.put("outcome",reader.readLine());}
                else value.put("outcome","Not specified");
            } catch(JSONException e){throw new IOException(e);}
            return value;
        }
    }
    public void create(File csv,String sport,String wrist) throws IOException {
        synchronized(LOCK){JSONObject m=metadata(csv);try{
            m.put("sport",sport).put("wrist",wrist).put("startedUtc",Instant.now().toString()).put("outcome","Recording");
            atomicJson(sidecar(csv,".session.json"),m);
        }catch(JSONException e){throw new IOException(e);}}
    }
    public void marker(File csv,String label,long micros) throws IOException {
        synchronized(LOCK){JSONObject m=metadata(csv);try{
            JSONArray a=m.optJSONArray("markers");if(a==null)a=new JSONArray();
            a.put(new JSONObject().put("label",label).put("watchUs",micros).put("createdUtc",Instant.now().toString()));
            m.put("markers",a);atomicJson(sidecar(csv,".session.json"),m);
        }catch(JSONException e){throw new IOException(e);}}
    }
    public void finish(File csv,String outcome,long count,long missing,long clipped,double duration) throws IOException {
        synchronized(LOCK){JSONObject m=metadata(csv);try{
            m.put("outcome",outcome).put("endedUtc",Instant.now().toString()).put("receivedSamples",count)
                .put("missingSequences",missing).put("clippedSamples",clipped).put("durationSeconds",duration);
            atomicJson(sidecar(csv,".session.json"),m);
        }catch(JSONException e){throw new IOException(e);}}
    }
    public void notes(Session session,String notes) throws IOException {
        synchronized(LOCK){JSONObject m=metadata(session.csv);try{
            m.put("notes",notes);atomicJson(sidecar(session.csv,".session.json"),m);session.metadata=m;
        }catch(JSONException e){throw new IOException(e);}}
    }
    public void recoverInterrupted() throws IOException {
        synchronized(LOCK) {
            File[] pending=directory.listFiles((d,n)->n.endsWith(".active"));
            if(pending==null)return;
            for(File marker:pending) {
                File csv=new File(marker.getPath().substring(0,marker.getPath().length()-7));
                String outcome="Interrupted: app process ended; buffered samples may be missing";
                MotionAnalytics.Stats stats=null;
                try{stats=summary(csv);}catch(Exception e){outcome+=". Raw CSV needs inspection: "+e.getMessage();}
                try {
                    if(stats!=null)finish(csv,outcome,stats.count,stats.missing,stats.clipped,stats.durationSeconds);
                    else {
                        JSONObject m=metadata(csv);m.put("outcome",outcome).put("endedUtc",Instant.now().toString());
                        atomicJson(sidecar(csv,".session.json"),m);
                    }
                }catch(Exception e){
                    // Preserve even malformed/future metadata; legacy outcome lets the raw file remain accessible.
                    try(FileOutputStream out=new FileOutputStream(sidecar(csv,".status.txt"))){out.write(outcome.getBytes(StandardCharsets.UTF_8));out.getFD().sync();}
                }
                if(!marker.delete()&&marker.exists())throw new IOException("Could not finalize interrupted session");
            }
        }
    }
    public List<Session> list() {
        List<Session> result=new ArrayList<>();File[] files=directory.listFiles((dir,name)->name.endsWith(".csv"));
        if(files==null)return result;
        Arrays.sort(files,(a,b)->b.getName().compareTo(a.getName()));
        for(File csv:files){if(sidecar(csv,".active").exists())continue;
            Session s=new Session(csv);
            try{s.metadata=metadata(csv);s.stats=summary(csv);}catch(Exception e){s.error=e.getMessage();if(s.metadata==null)s.metadata=new JSONObject();}
            result.add(s);
        }result.sort((a,b)->Long.compare(b.startedMillis(),a.startedMillis()));return result;
    }
    public MotionAnalytics.Stats summary(File csv) throws IOException {
        File cache=sidecar(csv,".analytics.json");
        try {
            JSONObject saved=readJson(cache);
            if(saved.getInt("analyticsVersion")==MotionAnalytics.VERSION && saved.getLong("sourceBytes")==csv.length()
                && saved.getLong("sourceModified")==csv.lastModified())return statsFromJson(saved.getJSONObject("summary"));
        }catch(Exception ignored){} // Derived cache is disposable; raw data is authoritative.
        MotionAnalytics.Stats stats;
        try {stats=MotionAnalytics.calculate(MotionAnalytics.read(csv),Double.NEGATIVE_INFINITY,Double.POSITIVE_INFINITY);}
        catch(IllegalArgumentException e){throw new IOException(e.getMessage(),e);}
        try{atomicJson(cache,new JSONObject().put("analyticsVersion",MotionAnalytics.VERSION).put("sourceBytes",csv.length())
            .put("sourceModified",csv.lastModified()).put("summary",statsJson(stats)));}
        catch(IOException|JSONException ignored){} // A cache write failure must not hide a readable session.
        return stats;
    }
    public File importWatch(File binary,long bytes,long crc,String id,String outcome)throws IOException {
        if(!id.matches("[0-9a-f]{16}"))throw new IOException("Invalid watch session identity");
        synchronized(LOCK){
            File target=new File(directory,"watch-"+id+".csv");
            if(target.exists()){
                JSONObject saved=metadata(target);
                if(id.equals(saved.optString("watchSessionId"))&&saved.optLong("watchCrc",-1)==crc)return target;
                throw new IOException("Existing session was not replaced");
            }
            File staged=File.createTempFile("watch-stage-",".tmp",directory);staged.delete();
            try{
                WatchRecording.Header header=WatchRecording.importCsv(binary,staged,bytes,crc,id);
                JSONObject m=new JSONObject().put("version",VERSION).put("sport",new String[]{"Badminton","Tennis","Running","Other"}[header.sport])
                    .put("wrist",header.wrist==0?"Right":"Left").put("markers",new JSONArray()).put("outcome",outcome)
                    .put("source","watch").put("watchSessionId",id).put("watchCrc",crc).put("watchBytes",bytes)
                    .put("startedUtc",header.startedUnix>1577836800L&&header.startedUnix<4102444800L?Instant.ofEpochSecond(header.startedUnix).toString():Instant.now().toString())
                    .put("notes",header.startedUnix>1577836800L?"Imported from watch internal flash.":"Imported from watch. Watch clock was not set; displayed date is import time.");
                atomicJson(sidecar(target,".session.json"),m);
                Files.move(staged.toPath(),target.toPath());return target;
            }catch(JSONException e){throw new IOException(e);}finally{staged.delete();}
        }
    }
    public void exportZip(Session s,OutputStream output) throws IOException {
        if(sidecar(s.csv,".active").exists())throw new IOException("Stop recording before exporting");
        JSONObject metadata=metadata(s.csv);
        MotionAnalytics.Stats full=summary(s.csv);
        try(ZipOutputStream zip=new ZipOutputStream(output)) {
            zip.putNextEntry(new ZipEntry(s.csv.getName()));try(InputStream in=new FileInputStream(s.csv)){copy(in,zip);}zip.closeEntry();
            zip.putNextEntry(new ZipEntry("session.json"));zip.write(metadata.toString().getBytes(StandardCharsets.UTF_8));zip.closeEntry();
            zip.putNextEntry(new ZipEntry("analytics.json"));try{zip.write(statsJson(full).toString().getBytes(StandardCharsets.UTF_8));}
            catch(JSONException e){throw new IOException(e);}zip.closeEntry();
        }
    }
    public static void copy(InputStream in,OutputStream out)throws IOException{byte[] b=new byte[8192];int n;while((n=in.read(b))!=-1)out.write(b,0,n);}
    public static JSONObject statsJson(MotionAnalytics.Stats s)throws JSONException{
        return new JSONObject().put("analyticsVersion",MotionAnalytics.VERSION).put("samples",s.count).put("durationSeconds",s.durationSeconds)
            .put("receivedHz",s.receivedHz).put("peakAccelerationIncludingGravityG",s.peakAcceleration).put("peakAngularSpeedDps",s.peakRotation)
            .put("missingSequences",s.missing).put("longestTimestampGapUs",s.maxGapUs).put("clippedSamples",s.clipped).put("clippedPercent",s.clippedPercent())
            .put("axisMinimum",array(s.min)).put("axisMaximum",array(s.max)).put("axisRms",array(s.rms));
    }
    private static JSONArray array(double[] values)throws JSONException{JSONArray a=new JSONArray();for(double v:values)a.put(v);return a;}
    private static MotionAnalytics.Stats statsFromJson(JSONObject j)throws JSONException{
        MotionAnalytics.Stats s=new MotionAnalytics.Stats();s.count=j.getLong("samples");s.durationSeconds=j.getDouble("durationSeconds");s.receivedHz=j.getDouble("receivedHz");
        s.peakAcceleration=j.getDouble("peakAccelerationIncludingGravityG");s.peakRotation=j.getDouble("peakAngularSpeedDps");s.missing=j.getLong("missingSequences");
        s.maxGapUs=j.getLong("longestTimestampGapUs");s.clipped=j.getLong("clippedSamples");
        for(int i=0;i<6;i++){s.min[i]=j.getJSONArray("axisMinimum").getDouble(i);s.max[i]=j.getJSONArray("axisMaximum").getDouble(i);s.rms[i]=j.getJSONArray("axisRms").getDouble(i);}return s;
    }
    private static JSONObject readJson(File file)throws IOException{
        try{return new JSONObject(new String(Files.readAllBytes(file.toPath()),StandardCharsets.UTF_8));}catch(JSONException e){throw new IOException("Invalid metadata/cache",e);}
    }
    private static void atomicJson(File file,JSONObject value)throws IOException{
        File temporary=File.createTempFile("vector-", ".tmp",file.getParentFile());
        try{
            try(FileOutputStream out=new FileOutputStream(temporary)){out.write(value.toString().getBytes(StandardCharsets.UTF_8));out.getFD().sync();}
            try{Files.move(temporary.toPath(),file.toPath(),StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);}
            catch(AtomicMoveNotSupportedException e){Files.move(temporary.toPath(),file.toPath(),StandardCopyOption.REPLACE_EXISTING);}
        }finally{temporary.delete();}
    }
}
