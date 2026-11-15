(function(root){
'use strict';
const C=typeof module!=='undefined'?require('./sync-core.js'):root.SyncCore;
const AXES=['ax_g','ay_g','az_g','gx_dps','gy_dps','gz_dps'];
function createDataset(text,name){
 const csv=C.parseCSV(text),indices=AXES.map(a=>csv.headers.indexOf(a)),ti=csv.headers.indexOf('t_us');
 if(ti<0||indices.some(i=>i<0))throw Error('Expected t_us, ax_g, ay_g, az_g, gx_dps, gy_dps, gz_dps columns. Use the sync studio to map other formats.');
 const data=C.buildIMU(csv,ti,'us',indices,1200),rawIndices=AXES.map(a=>csv.headers.indexOf('raw_'+a));
 const hasRaw=rawIndices.every(i=>i>=0),raw=hasRaw?C.buildIMU(csv,ti,'us',rawIndices,1200).values:data.values;
 const dataset={...data,name,csv,raw,processed:hasRaw?data.values:null,fileProcessed:hasRaw?data.values:null,originUs:Number(csv.rows[0][ti]),stampIndex:ti};
 dataset.dt=Float64Array.from(data.times,(t,i)=>i?(t-data.times[i-1])*1000:NaN);
 dataset.rawMagnitude=magnitudes(raw);dataset.processedMagnitude=dataset.processed?magnitudes(dataset.processed):null;
 dataset.threshold=Math.max(.02,5/data.localHz);dataset.gaps=[];dataset.blocks=[];let begin=0;
 for(let i=1;i<data.times.length;i++){if(data.times[i]-data.times[i-1]>dataset.threshold){dataset.gaps.push({before:i-1,after:i,start:data.times[i-1],end:data.times[i],seconds:data.times[i]-data.times[i-1]});dataset.blocks.push([begin,i]);begin=i;}}
 dataset.blocks.push([begin,data.times.length]);
 const clipped=csv.headers.indexOf('clipped');
 dataset.clipped=Uint8Array.from(raw,(v,i)=>(clipped>=0&&csv.rows[i][clipped]==='1')||v.some((x,j)=>Math.abs(x)>=(j<3?15.8:1010))?1:0);
 dataset.sessions=[];const si=csv.headers.indexOf('session_id');begin=0;
 for(let i=1;i<=data.times.length;i++)if(i===data.times.length||(si>=0&&csv.rows[i][si]!==csv.rows[begin][si])){dataset.sessions.push({name:si>=0?csv.rows[begin][si]:name,start:data.times[begin],end:data.times[i-1],first:begin,last:i-1});begin=i;}
 return dataset;
}
function magnitudes(values){return [Float64Array.from(values,v=>Math.hypot(...v.slice(0,3))),Float64Array.from(values,v=>Math.hypot(...v.slice(3,6)))];}
function setProcessed(data,values){if(values!==null&&(values.length!==data.times.length||values.some(v=>v.length!==6||!v.every(Number.isFinite))))throw Error('Invalid filtered result');data.processed=values;data.processedMagnitude=values?magnitudes(values):null;}
function stats(data,from=0,to=data.duration){
 const first=C.lowerBound(data.times,from),last=C.lowerBound(data.times,to+1e-8),count=last-first;
 const axes=AXES.map((name,j)=>{let min=Infinity,max=-Infinity,sum=0,sum2=0;for(let i=first;i<last;i++){const v=data.raw[i][j];min=Math.min(min,v);max=Math.max(max,v);sum+=v;sum2+=v*v;}return {name,min:count?min:null,max:count?max:null,mean:count?sum/count:null,rms:count?Math.sqrt(sum2/count):null};});
 let clipped=0;for(let i=first;i<last;i++)clipped+=data.clipped[i];
 return {first,last,count,axes,clipped,gaps:data.gaps.filter(g=>g.end>=from&&g.start<=to)};
}
function sampleAt(data,t){const index=C.nearest(data.times,t),delta=data.times[index]-t;return {index,delta,present:Math.abs(delta)<=.02};}
function exportCSV(data,from=0,to=data.duration){
 const first=C.lowerBound(data.times,from),last=C.lowerBound(data.times,to+1e-8),si=data.csv.headers.indexOf('session_id');
 const header=['seq','t_us',...AXES,'clipped','session_id',...AXES.map(a=>'raw_'+a)];
 const quote=v=>'"'+String(v??'').replaceAll('"','""')+'"';
 const seq=data.csv.headers.indexOf('seq'),clip=data.csv.headers.indexOf('clipped');let lines=[header.join(',')];
 for(let i=first;i<last;i++){const row=data.csv.rows[i],values=data.processed?data.processed[i]:data.raw[i];lines.push([seq>=0?row[seq]:i,row[data.stampIndex],...values,clip>=0?row[clip]:data.clipped[i],si>=0?row[si]:data.name,...data.raw[i]].map(quote).join(','));}
 return lines.join('\n')+'\n';
}
const api={AXES,createDataset,magnitudes,setProcessed,stats,sampleAt,exportCSV};if(typeof module!=='undefined')module.exports=api;else root.IMUAnalysis=api;
})(globalThis);
