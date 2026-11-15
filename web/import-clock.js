/* Small, bounded ISO-BMFF reads: skip mdat instead of loading multi-GB videos. */
(function(root){
'use strict';
const UNIX_1904=2082844800,MIN_UTC=Date.UTC(2000,0,1),MAX_UTC=Date.UTC(2100,0,1),decoder=new TextDecoder();
function offsetMinutes(value){if(value==='UTC'||value==='Z')return 0;const m=/^([+-])(\d{2}):(\d{2})$/.exec(value);if(!m||+m[2]>14||+m[3]>59)throw Error('Filename time zone must be UTC or an offset such as +05:30.');return (m[1]==='-'?-1:1)*(+m[2]*60 + +m[3]);}
function filenameStart(name,offset='+05:30'){
 const m=/(?:VID|IMG)[_-]?(\d{4})(\d{2})(\d{2})[_-]?(\d{2})(\d{2})(\d{2})/i.exec(name);if(!m)return null;
 const parts=m.slice(1).map(Number),utc=Date.UTC(parts[0],parts[1]-1,parts[2],parts[3],parts[4],parts[5]),d=new Date(utc);
 if(d.getUTCFullYear()!==parts[0]||d.getUTCMonth()!==parts[1]-1||d.getUTCDate()!==parts[2]||d.getUTCHours()!==parts[3]||d.getUTCMinutes()!==parts[4]||d.getUTCSeconds()!==parts[5])return null;
 return utc-offsetMinutes(offset)*60000;
}
function videoClock(meta,name,zone='+05:30',policy='auto'){
 const tagged=meta.creationUtcMs,filename=filenameStart(name,zone),explicit=meta.explicitCreationUtcMs;
 const valid=t=>Number.isFinite(t)&&t>=MIN_UTC&&t<MAX_UTC;
 if(policy==='filename'){return valid(filename)?{utcMs:filename,basis:'filename start · '+zone,confidence:'estimate',warning:'Filename time, rounded to seconds; verify one visible event.'}:{utcMs:null,basis:'No recognized filename timestamp',confidence:'missing'};}
 if(policy==='creation'){return valid(tagged)?{utcMs:tagged,basis:'container creation assumed start',confidence:'low',warning:'Creation can mean finalization rather than capture start.'}:{utcMs:null,basis:'No valid creation timestamp',confidence:'missing'};}
 if(valid(explicit))return {utcMs:explicit,basis:'explicit QuickTime creation date',confidence:'estimate',warning:'Calendar metadata is not a verified exposure-start clock.'};
 if(valid(filename)&&valid(tagged)){
  const startError=Math.abs(tagged-filename)/1000,endError=Math.abs(tagged-(filename+meta.duration*1000))/1000;
  if(meta.duration>6&&endError<=3&&endError<startError)return {utcMs:filename,basis:'Android filename start · '+zone,confidence:'estimate',endTagged:true,warning:'Creation tag matches recording end ('+endError.toFixed(2)+' s from filename + duration). Using filename start; allow seconds of uncertainty.'};
  if(startError<=3)return {utcMs:filename,basis:'filename + creation agree · '+zone,confidence:'estimate',warning:'Second-resolution start estimate; verify against visible motion.'};
  return {utcMs:null,basis:'Conflicting video clocks',confidence:'conflict',warning:'Filename and creation time disagree. Select a clock source or adjust manually.'};
 }
 if(valid(filename))return {utcMs:filename,basis:'filename start · '+zone,confidence:'low',warning:'No independent metadata start reference was available.'};
 if(valid(tagged))return {utcMs:tagged,basis:'container creation assumed start',confidence:'low',warning:'Start convention is unknown; creation can be file finalization time.'};
 return {utcMs:null,basis:'No usable video clock',confidence:'missing',warning:'File modification time is deliberately not used for alignment.'};
}
function csvClock(firstUs,metadata={},name=''){
 const valid=t=>Number.isFinite(t)&&t>=MIN_UTC&&t<MAX_UTC,unit=metadata.timestampUnit||metadata.timestamp_unit||'';
 const first=Number(firstUs),epoch=first/1000;
 if(valid(epoch))return {utcMs:epoch,basis:unit==='unix_microseconds'?'Unix µs in CSV':'Unix µs inferred from CSV',confidence:unit==='unix_microseconds'?'estimate':'low',warning:metadata.clockSource==='phone_ble_receive_estimate'?'Phone BLE receive estimate; acquisition and transport latency remain.':'CSV clock origin is inferred unless declared in metadata.'};
 if(unit==='unix_microseconds')return {utcMs:null,basis:'Metadata / CSV clock mismatch',confidence:'conflict',warning:'Metadata declares Unix microseconds but t_us is not a plausible epoch. No automatic alignment.'};
 const started=Date.parse(metadata.startedUtc||metadata.started_utc||''),boot=Number(metadata.bootStartUs);
 if(valid(started)&&Number.isFinite(boot)&&metadata.bootStartUs!==undefined)return {utcMs:started+(first-boot)/1000,basis:'archive UTC + bootStartUs',confidence:'estimate',warning:'Recording UTC may be quantized; phone clock setting latency is unmeasured.'};
 if(valid(started))return {utcMs:started,basis:'recording-start UTC proxy',confidence:'low',warning:'Boot-relative CSV: first-sample UTC is approximated from recording start. Refine manually.'};
 const m=/(\d{4}-\d{2}-\d{2})T(\d{2})-(\d{2})-(\d{2}(?:\.\d+)?)Z/.exec(name);
 const inferred=m?Date.parse(m[1]+'T'+m[2]+':'+m[3]+':'+m[4]+'Z'):NaN;
 return valid(inferred)?{utcMs:inferred,basis:'CSV filename recording-start proxy',confidence:'low',warning:'Recording start is not guaranteed to equal the first sample time.'}:{utcMs:null,basis:'Boot-relative CSV · needs a UTC anchor',confidence:'missing',warning:'Add its session JSON or align manually. A file copy/modification date is not a capture timestamp.'};
}
function proposal(csv,video,duration){
 if(!Number.isFinite(csv?.utcMs)||!Number.isFinite(video?.clock?.utcMs))return null;
 const offset=(csv.utcMs-video.clock.utcMs)/1000,start=Math.max(0,-offset),end=Math.min(duration,video.duration-offset);
 return {offset,scale:1,start,end,overlap:Math.max(0,end-start),enabled:end>start,basis:video.clock.basis,confidence:csv.confidence==='low'||video.clock.confidence==='low'?'low':'estimate'};
}
function boxes(bytes,start,end){const view=new DataView(bytes.buffer,bytes.byteOffset,bytes.byteLength),result=[];let p=start,count=0;while(p+8<=end){if(++count>100000)throw Error('Too many metadata boxes');let size=view.getUint32(p),header=8;const type=String.fromCharCode(...bytes.subarray(p+4,p+8));if(size===1){if(p+16>end)throw Error('Truncated large box');size=Number(view.getBigUint64(p+8));header=16;}if(size===0)size=end-p;if(!Number.isSafeInteger(size)||size<header||p+size>end)throw Error('Malformed video metadata box');result.push({type,start:p,end:p+size,content:p+header});p+=size;}return result;}
function parseMovie(bytes){
 const view=new DataView(bytes.buffer,bytes.byteOffset,bytes.byteLength),rootBoxes=boxes(bytes,0,bytes.length),moov=rootBoxes.find(b=>b.type==='moov');if(!moov)throw Error('Movie metadata not found');
 const child=b=>boxes(bytes,b.content,b.end),top=child(moov),mvhd=top.find(b=>b.type==='mvhd');
 function header(b){if(!b)return {};const p=b.content,version=bytes[p],creation=version===1?Number(view.getBigUint64(p+4)):view.getUint32(p+4),scale=view.getUint32(p+(version===1?20:12)),duration=version===1?Number(view.getBigUint64(p+24)):view.getUint32(p+16);return {creation,scale,duration};}
 const movie=header(mvhd);let track=null,media=null,table=null,edit=null;
 for(const candidate of top.filter(b=>b.type==='trak')){const parts=child(candidate),mdia=parts.find(b=>b.type==='mdia');if(!mdia)continue;const subs=child(mdia),hdlr=subs.find(b=>b.type==='hdlr');if(!hdlr||String.fromCharCode(...bytes.subarray(hdlr.content+8,hdlr.content+12))!=='vide')continue;track=candidate;media=header(subs.find(b=>b.type==='mdhd'));const minf=subs.find(b=>b.type==='minf');table=minf?child(minf).find(b=>b.type==='stbl'):null;const edts=parts.find(b=>b.type==='edts');edit=edts?child(edts).find(b=>b.type==='elst'):null;break;}
 if(!track||!media?.scale||!table)throw Error('A usable video track was not found');
 const tables=child(table),stts=tables.find(b=>b.type==='stts'),ctts=tables.find(b=>b.type==='ctts');if(!stts)throw Error('Video has no sample timing table');
 function runs(b,signed=false){const n=view.getUint32(b.content+4);if(n>1000000||b.content+8+n*8>b.end)throw Error('Invalid sample timing table');let out=[],total=0;for(let i=0;i<n;i++){const p=b.content+8+i*8,count=view.getUint32(p),value=signed?view.getInt32(p+4):view.getUint32(p+4);total+=count;if(total>2000000)throw Error('Video has too many frames for this viewer');out.push([count,value]);}return {out,total};}
 const timing=runs(stts),pts=new Float64Array(timing.total);let tick=0,index=0;for(const [count,delta]of timing.out)for(let i=0;i<count;i++){pts[index++]=tick;tick+=delta;}
 // QuickTime also stores wrapped negative offsets in version-0 ctts; interpret its 32-bit offsets as signed.
 if(ctts){const composition=runs(ctts,true);if(composition.total!==pts.length)throw Error('Composition timing count differs from frame count');index=0;for(const [count,offset]of composition.out)for(let i=0;i<count;i++)pts[index++]+=offset;}
 const rawFrameCount=pts.length;let editSummary=[];let frames=Array.from(pts,t=>t/media.scale),duration=movie.scale?movie.duration/movie.scale:media.duration/media.scale;
 if(edit){const version=bytes[edit.content],count=view.getUint32(edit.content+4),size=version===1?20:12;let movieCursor=0,mapped=[];if(count>10000||edit.content+8+count*size>edit.end)throw Error('Invalid edit list');for(let i=0;i<count;i++){const p=edit.content+8+i*size,segment=version===1?Number(view.getBigUint64(p)):view.getUint32(p),mediaStart=version===1?Number(view.getBigInt64(p+8)):view.getInt32(p+4),rate=view.getInt16(p+(version===1?16:8));if(rate!==1)throw Error('Non-unit playback edit rate needs manual frame stepping');const seconds=segment/movie.scale;editSummary.push({seconds,mediaStart,rate});if(mediaStart!==-1)for(const t of frames){const relative=t-mediaStart/media.scale;if(relative>=-1e-7&&relative<seconds-1e-7)mapped.push(movieCursor+Math.max(0,relative));}movieCursor+=seconds;}frames=mapped;}
 frames.sort((a,b)=>a-b);if(!frames.length||frames.some((t,i)=>!Number.isFinite(t)||(i>0&&t<=frames[i-1])))throw Error('Non-increasing frame presentation times');
 let explicitCreationUtcMs=null;
 // QuickTime mdta keys/ilst values, including timezone-qualified creationdate.
 const metas=[];for(const b of top){if(b.type==='meta')metas.push(b);if(b.type==='udta')for(const sub of child(b))if(sub.type==='meta')metas.push(sub);}
 for(const original of metas)for(const shift of [0,4]){const meta={...original,content:original.content+shift};try{const parts=boxes(bytes,meta.content,meta.end),keys=parts.find(b=>b.type==='keys'),ilst=parts.find(b=>b.type==='ilst');if(!keys||!ilst)continue;const names=[],count=view.getUint32(keys.content+4);let p=keys.content+8;for(let i=0;i<count&&p+8<=keys.end;i++){const size=view.getUint32(p);if(size<8||p+size>keys.end)break;names.push(decoder.decode(bytes.subarray(p+8,p+size)));p+=size;}for(const item of child(ilst)){const key=names[view.getUint32(item.start+4)-1],value=child(item).find(b=>b.type==='data');if(key==='com.apple.quicktime.creationdate'&&value){const date=decoder.decode(bytes.subarray(value.content+8,value.end)).replace(/\0+$/,'').trim();if(/(?:Z|[+-]\d\d:?\d\d)$/.test(date)){const time=Date.parse(date);if(Number.isFinite(time))explicitCreationUtcMs=time;}}}}catch(_){/* Optional metadata does not invalidate valid frame tables. */}}
 const creationUtcMs=(movie.creation-UNIX_1904)*1000;
 return {duration,rawFrameCount,media,movie,editSummary,creationUtcMs:creationUtcMs>=MIN_UTC&&creationUtcMs<MAX_UTC?creationUtcMs:null,explicitCreationUtcMs,timeScale:media.scale,frames:Float64Array.from(frames),frameCount:frames.length,averageFps:frames.length/duration};
}
async function probeVideo(file){
 let p=0,count=0;while(p+8<=file.size){if(++count>10000)throw Error('Too many top-level video boxes');const bytes=new Uint8Array(await file.slice(p,Math.min(p+16,file.size)).arrayBuffer()),view=new DataView(bytes.buffer);if(bytes.length<8)break;let size=view.getUint32(0),header=8;const type=String.fromCharCode(...bytes.subarray(4,8));if(size===1){if(bytes.length<16)throw Error('Truncated video box');size=Number(view.getBigUint64(8));header=16;}if(size===0)size=file.size-p;if(!Number.isSafeInteger(size)||size<header||p+size>file.size)throw Error('Not a supported MP4/MOV file (or a Git LFS pointer).');if(type==='moov'){if(size>64*1024*1024)throw Error('Video metadata exceeds the 64 MiB inspection limit');return parseMovie(new Uint8Array(await file.slice(p,p+size).arrayBuffer()));}p+=size;}throw Error('No MP4/MOV movie metadata found; manual alignment remains available.');
}
const api={offsetMinutes,filenameStart,videoClock,csvClock,proposal,parseMovie,probeVideo};if(typeof module!=='undefined')module.exports=api;else root.ImportClock=api;
})(globalThis);
