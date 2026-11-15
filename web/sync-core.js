/* Shared by the viewer and the dependency-free Node checks. */
(function(root){
'use strict';
function parseCSV(text){
 const lines=text.replace(/^\uFEFF/,'').split(/\r?\n/).filter(x=>x.trim()&&!x.trim().startsWith('#'));
 if(lines.length<2)throw Error('CSV needs a header and at least two readings.');
 const delimiter=lines[0].includes('\t')?'\t':lines[0].includes(';')?';':',';
 function fields(line){let cells=[],s='',quoted=false;for(let i=0;i<line.length;i++){const c=line[i];if(c==='"'){if(quoted&&line[i+1]==='"'){s+='"';i++;}else quoted=!quoted;}else if(c===delimiter&&!quoted){cells.push(s.trim());s='';}else s+=c;}cells.push(s.trim());return cells;}
 const headers=fields(lines.shift());
 if(new Set(headers).size!==headers.length)throw Error('CSV column names must be unique.');
 const rows=lines.map(fields);return {headers,rows};
}
function buildIMU(csv,timeColumn,unit,axes,hz){
 if(!Number.isFinite(hz)||hz<=0)throw Error('Sample rate must be greater than zero.');
 if(axes.some(i=>i<0))throw Error('Map all six acceleration and gyroscope columns.');
 const scale={s:1,ms:1e-3,us:1e-6,ns:1e-9}[unit];
 const values=[],times=[];let first=null;
 for(let n=0;n<csv.rows.length;n++){
  const row=csv.rows[n];const cells=axes.map(i=>row[i]);
  if(cells.some(v=>v===undefined||v===''))throw Error(`Missing sensor value at CSV line ${n+2}.`);
  const v=cells.map(Number);if(!v.every(Number.isFinite))throw Error(`Invalid sensor value at CSV line ${n+2}.`);
  let raw;if(timeColumn<0)raw=n/hz;else{if(!row[timeColumn])throw Error(`Missing timestamp at CSV line ${n+2}.`);raw=Number(row[timeColumn]);}
  if(!Number.isFinite(raw))throw Error(`Invalid timestamp at CSV line ${n+2}.`);
  if(first===null)first=raw;const t=(raw-first)*(timeColumn<0?1:scale);
  if(times.length&&t<=times[times.length-1])throw Error(`Timestamps must increase strictly (CSV line ${n+2}).`);
  times.push(t);values.push(v);
 }
 if(times.length<2)throw Error('At least two IMU readings are required.');
 const intervals=times.slice(1).map((t,i)=>t-times[i]).sort((a,b)=>a-b);const mid=Math.floor(intervals.length/2);const median=intervals.length%2?intervals[mid]:(intervals[mid-1]+intervals[mid])/2;
 return {times:Float64Array.from(times),values,duration:times[times.length-1],hz:(times.length-1)/times[times.length-1],localHz:1/median};
}
function lowerBound(times,t){let l=0,r=times.length;while(l<r){let m=(l+r)>>>1;if(times[m]<t)l=m+1;else r=m;}return l;}
function nearest(times,t){let i=lowerBound(times,t);if(i===0)return 0;if(i===times.length)return i-1;return t-times[i-1]<=times[i]-t?i-1:i;}
function overlap(videos,imuDuration=Infinity){return {start:Math.max(0,...videos.map(v=>-v.offset/(v.scale??1))),end:Math.min(imuDuration,...videos.map(v=>(v.duration-v.offset)/(v.scale??1)))};}
function fitAlignment(first,second){
 const dt=second.session-first.session;
 if(![first.session,first.video,second.session,second.video].every(Number.isFinite)||dt<=0)throw Error('Anchor 2 must be later than anchor 1 in session time.');
 const scale=(second.video-first.video)/dt;
 if(scale<.9||scale>1.1)throw Error('Clock rate differs by more than 10%; check that both anchors show the same events.');
 return {scale,offset:first.video-first.session*scale};
}
function frameStep(times,t,direction){
 if(!times.length)return t;
 if(direction>0)return times[Math.min(times.length-1,lowerBound(times,t+1e-7))];
 return times[Math.max(0,lowerBound(times,t-1e-7)-1)];
}
function shiftOffset(offset,scale,sessionDelta){return offset-scale*sessionDelta;}
const api={frameStep,shiftOffset,parseCSV,buildIMU,lowerBound,nearest,overlap,fitAlignment};if(typeof module!=='undefined')module.exports=api;else root.SyncCore=api;
})(globalThis);
