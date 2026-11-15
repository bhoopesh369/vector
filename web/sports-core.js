/* Experimental wrist-motion proposals. Contact and shuttle force require external validation. */
(function(root){
'use strict';
const VERSION='badminton-wrist-heuristic-v1';
const DEFAULTS={accelG:3,gyroDps:180,jerkGps:60,minSeparation:.45,binSeconds:.005,contextSeconds:.16};
const median=a=>quantile(a,.5);
function quantile(a,p){if(!a.length)return null;const v=[...a].sort((x,y)=>x-y),at=(v.length-1)*p,lo=Math.floor(at);return v[lo]+(v[Math.ceil(at)]-v[lo])*(at-lo);}
function validate(config={}){const p={...DEFAULTS,...config};for(const [key,min,max]of [['accelG',1.05,30],['gyroDps',20,3000],['jerkGps',5,5000],['minSeparation',.15,2],['binSeconds',.002,.02],['contextSeconds',.05,.4]])if(!Number.isFinite(p[key])||p[key]<min||p[key]>max)throw Error('Invalid '+key+' threshold');return p;}
function lowerBound(a,t){let l=0,r=a.length;while(l<r){const m=(l+r)>>>1;if(a[m]<t)l=m+1;else r=m;}return l;}
function buildBins(times,values,clipped,packed=false,p=DEFAULTS,scope={start:times[0],end:times.at(-1)}){
 const bins=[];let bin=null,previous=-1;const start=lowerBound(times,scope.start),end=lowerBound(times,scope.end+1e-9);
 for(let i=start;i<end;i++){
  const t=times[i],v=packed?values.subarray(i*6,i*6+6):values[i];if(!Number.isFinite(t)||!v||v.length!==6||!Array.from(v).every(Number.isFinite))throw Error('Sports analysis needs finite timestamps and six raw axes.');
  if(previous>=0&&t<=times[previous])throw Error('Sports timestamps must increase strictly.');
  const slot=Math.floor(t/p.binSeconds);if(!bin||bin.slot!==slot){bin={slot,t:0,count:0,ax:0,ay:0,az:0,sumW2:0,sumA2:0,peakA:0,peakW:0,clipped:0,maxGap:0,first:i,last:i,jerk:0};bins.push(bin);}
  const a2=v[0]*v[0]+v[1]*v[1]+v[2]*v[2],w2=v[3]*v[3]+v[4]*v[4]+v[5]*v[5];bin.t+=t;bin.count++;bin.ax+=v[0];bin.ay+=v[1];bin.az+=v[2];bin.sumW2+=w2;bin.sumA2+=a2;bin.peakA=Math.max(bin.peakA,Math.sqrt(a2));bin.peakW=Math.max(bin.peakW,Math.sqrt(w2));bin.clipped+=clipped?.[i]||v.some((x,j)=>Math.abs(x)>=(j<3?15.8:1010))?1:0;bin.last=i;
  if(previous>=0)bin.maxGap=Math.max(bin.maxGap,t-times[previous]);previous=i;
 }
 for(const b of bins){b.t/=b.count;b.ax/=b.count;b.ay/=b.count;b.az/=b.count;}
 // Smooth the vector over ~8 ms. Missing bin spans reset the derivative, avoiding edge-generated impulses.
 let filtered=null,previousBin=null;
 for(const b of bins){const dt=previousBin?b.t-previousBin.t:Infinity;if(!filtered||dt>p.binSeconds*2.5||b.maxGap>.012){filtered=[b.ax,b.ay,b.az];b.derivativeValid=false;}else{const alpha=1-Math.exp(-dt/.008),next=[b.ax,b.ay,b.az].map((v,j)=>filtered[j]+alpha*(v-filtered[j]));b.jerk=Math.hypot(...next.map((v,j)=>(v-filtered[j])/dt));b.derivativeValid=true;filtered=next;}previousBin=b;}
 return bins;
}
function describe(bins,index,p=DEFAULTS){const center=bins[index],t=center.t;let count=0,w2=0,a2=0,peakA=0,peakW=0,clipped=0,maxGap=0;for(let i=index;i>=0&&t-bins[i].t<=p.contextSeconds;i--){const b=bins[i];count+=b.count;w2+=b.sumW2;a2+=b.sumA2;peakA=Math.max(peakA,b.peakA);peakW=Math.max(peakW,b.peakW);clipped+=b.clipped;maxGap=Math.max(maxGap,b.maxGap);}for(let i=index+1;i<bins.length&&bins[i].t-t<=p.contextSeconds;i++){const b=bins[i];count+=b.count;w2+=b.sumW2;a2+=b.sumA2;peakA=Math.max(peakA,b.peakA);peakW=Math.max(peakW,b.peakW);clipped+=b.clipped;maxGap=Math.max(maxGap,b.maxGap);}
 return {id:'auto-'+Math.round(t*1e6),time:t,start:Math.max(bins[0].t,t-p.contextSeconds),end:Math.min(bins.at(-1).t,t+p.contextSeconds),sampleIndex:center.first,peakAccelG:peakA,peakGyroDps:peakW,gyroRmsDps:Math.sqrt(w2/Math.max(1,count)),accelRmsG:Math.sqrt(a2/Math.max(1,count)),jerkGps:center.jerk,clippedRows:clipped,maxGapSeconds:maxGap,samples:count,origin:'automatic',review:'pending'};
}
function analyze(input,config={}){
 const p=validate(config),times=input.times;if(!times||times.length<20)throw Error('Load at least 20 IMU readings.');const duration=times.at(-1),scope=input.scope||{start:times[0],end:duration};if(![scope.start,scope.end].every(Number.isFinite)||scope.start<times[0]||scope.end>duration+1e-6||scope.end<=scope.start)throw Error('Choose a valid analysis interval.');
 const bins=buildBins(times,input.values||input.packed,input.clipped,!!input.packed,p,scope);if(bins.length<5)throw Error('The interval is too short to analyse.');
 const noise=bins.filter(b=>b.derivativeValid).map(b=>b.jerk),baseline=median(noise)||0,mad=median(noise.map(v=>Math.abs(v-baseline)))||0,threshold=Math.max(p.jerkGps,baseline+4*1.4826*mad),seeds=[];
 for(let i=1;i<bins.length-1;i++){const b=bins[i];if(!b.derivativeValid||b.jerk<threshold||b.jerk<bins[i-1].jerk||b.jerk<=bins[i+1].jerk)continue;
  // Require measured acceleration AND rotation in the surrounding swing context.
  const event=describe(bins,i,p);if(event.peakAccelG<p.accelG||event.peakGyroDps<p.gyroDps)continue;event.signalScore=event.jerkGps*Math.sqrt(Math.max(1,event.peakGyroDps/p.gyroDps));seeds.push(event);
 }
 // Time-based non-maximum suppression, not a row-count lockout; do not double-count a multi-peak swing.
 const accepted=[];for(const event of seeds.sort((a,b)=>b.signalScore-a.signalScore)){let lo=0,hi=accepted.length;while(lo<hi){const mid=(lo+hi)>>>1;if(accepted[mid].time<event.time)lo=mid+1;else hi=mid;}const at=lo;if((at>0&&event.time-accepted[at-1].time<p.minSeparation)||(at<accepted.length&&accepted[at].time-event.time<p.minSeparation))continue;accepted.splice(at,0,event);}

 return {version:VERSION,config:p,scope,events:accepted,thresholds:{jerkGps:threshold,baselineJerkGps:baseline,madJerkGps:mad},quality:{rows:bins.reduce((n,b)=>n+b.count,0),nonemptyBins:bins.length,binSeconds:p.binSeconds,observedSpanSeconds:scope.end-scope.start},notes:['Candidates are wrist-motion events, not verified shuttle contacts.','Timestamps of jerk peaks are approximate contact proposals.','Strength categories rank clean wrist gyro RMS within this analysis; they do not measure force or shuttle speed.']};
}
function rate(events){const eligible=events.filter(e=>e.review!=='rejected'&&!e.clippedRows).map(e=>e.gyroRmsDps),cuts=eligible.length>=6?[quantile(eligible,1/3),quantile(eligible,2/3)]:null;
 return {cuts,events:events.map(e=>({...e,intensity:e.clippedRows?'unrated-clipped':!cuts?'unrated-small-sample':Math.abs(cuts[1]-cuts[0])<1e-6?'medium':e.gyroRmsDps<cuts[0]?'weak':e.gyroRmsDps>cuts[1]?'strong':'medium'}))};
}
function summarize(events,scope){const included=events.filter(e=>e.review!=='rejected'),times=included.map(e=>e.time).sort((a,b)=>a-b),intervals=times.slice(1).map((t,i)=>t-times[i]),strength={weak:0,medium:0,strong:0,unrated:0};for(const e of included){if(e.intensity in strength)strength[e.intensity]++;else strength.unrated++;}
 return {estimatedHits:included.length,confirmedHits:included.filter(e=>e.review==='confirmed').length,pending:included.filter(e=>e.review==='pending'||e.review==='uncertain').length,rejected:events.length-included.length,strength,perMinute:scope.end-scope.start>=10?included.length/((scope.end-scope.start)/60):null,medianIntervalSeconds:median(intervals),maxMeasuredGyroDps:included.length?included.reduce((m,e)=>Math.max(m,e.peakGyroDps),0):null,clippedEvents:included.filter(e=>e.clippedRows>0).length};
}
function manualEvent(times,values,clipped,t){if(!Number.isFinite(t)||t<times[0]||t>times.at(-1))throw Error('The playhead must be inside this recording.');const a=Math.max(times[0],t-.2),b=Math.min(times.at(-1),t+.2),bins=buildBins(times,values,clipped,false,DEFAULTS,{start:a,end:b});if(!bins.length)throw Error('No samples near this event.');let index=0;for(let i=1;i<bins.length;i++)if(Math.abs(bins[i].t-t)<Math.abs(bins[index].t-t))index=i;if(Math.abs(bins[index].t-t)>.02)throw Error('No sample within 20 ms; choose a visible measured event.');const e=describe(bins,index);return {...e,id:'manual-'+Math.round(t*1e6),time:t,origin:'manual',review:'confirmed'};}
const api={VERSION,DEFAULTS,validate,analyze,rate,summarize,manualEvent,buildBins,describe};if(typeof module!=='undefined')module.exports=api;else root.SportsCore=api;
})(globalThis);
