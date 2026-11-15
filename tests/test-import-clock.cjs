const assert=require('node:assert/strict'),fs=require('node:fs/promises'),Clock=require('../web/import-clock'),Sync=require('../web/sync-core'),Markers=require('../web/marker-core');
const start=Date.parse('2026-11-15T07:42:30Z');
assert.equal(Clock.filenameStart('VID20261115131230.mp4','+05:30'),start);
assert.equal(Clock.filenameStart('VID20260230131230.mp4'),null);
assert.throws(()=>Clock.offsetMinutes('India'));
const detected=Clock.videoClock({duration:362.9153,creationUtcMs:Date.parse('2026-11-15T07:48:34Z')},'VID20261115131230.mp4');
assert.equal(detected.utcMs,start);assert.equal(detected.endTagged,true);
assert.equal(Clock.videoClock({duration:10,creationUtcMs:start+100000},'VID20261115131230.mp4').utcMs,null);
assert.equal(Clock.videoClock({},'unknown.mp4').utcMs,null);
assert.equal(Clock.csvClock(1234,{timestampUnit:'unix_microseconds'}).confidence,'conflict');
const epoch=1794728549338522,csv=Clock.csvClock(epoch,{timestampUnit:'unix_microseconds',clockSource:'phone_ble_receive_estimate'});
const p=Clock.proposal(csv,{clock:detected,duration:362.9153},357.416642);
assert.ok(Math.abs(p.offset+.661478)<.000001);assert.equal(p.enabled,true);
assert.equal(Clock.proposal(csv,{clock:{utcMs:start+1e7},duration:10},100).enabled,false);
assert.equal(Clock.csvClock(2000000,{startedUtc:'2026-11-15T00:00:00Z',bootStartUs:1000000}).utcMs,Date.parse('2026-11-15T00:00:01Z'));
const data=Sync.buildIMU({headers:[],rows:[[epoch,1,2,3,4,5,6],[epoch+558,1,2,3,4,5,6]]},0,'us',[1,2,3,4,5,6],1200);assert.equal(data.duration,558e-6);
assert.equal(Markers.sameProject('["x",2,1,2,[1],[2]]','["x",2,1,2.0000001,[1],[2]]'),true);assert.equal(Markers.sameProject('["x",2,1,2,[1],[2]]','["y",2,1,2,[1],[2]]'),false);
(async()=>{let checked=0;for(const name of ['IMG_3302.MOV','IMG_3303.MOV','IMG_3304.MOV']){
 const path='samples/videos/'+name;let handle;try{handle=await fs.open(path);}catch(error){if(error.code==='ENOENT')continue;throw error;}const stat=await handle.stat();let read=0;
 try{const parsed=await Clock.probeVideo({size:stat.size,slice:(a,b)=>({arrayBuffer:async()=>{read+=b-a;const bytes=Buffer.alloc(b-a);await handle.read(bytes,0,bytes.length,a);return bytes.buffer;}})});
 const audit=JSON.parse(await fs.readFile('recordings/timing-audit/'+name.replace('.MOV','-frames.json')));assert.equal(parsed.frames.length,audit.frames.length,name);
 for(let i=0;i<parsed.frames.length;i++)assert.ok(Math.abs(parsed.frames[i]-audit.frames[i].pts_s)<1e-8,name+' frame '+i);
 assert.ok(read<2*1024*1024,'Metadata read should be bounded');checked++;
 }finally{await handle.close();}}
 console.log('Import clocks: end tags, conflicts, timezone, epoch precision and saved labels passed.');console.log(checked===3?'All 8,927 audited local MOV frame PTS passed.':`Optional local media checks: ${checked}/3 videos available; absent videos skipped.`);
})().catch(e=>{console.error(e);process.exitCode=1;});
