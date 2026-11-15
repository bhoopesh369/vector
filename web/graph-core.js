/* View-only geometry and peak-preserving traces. Never changes recorded samples. */
(function(root){
'use strict';
const clamp=(v,a,b)=>Math.max(a,Math.min(b,v));
function windowBounds(start,end,duration,minSpan=.01){
 if(![start,end,duration,minSpan].every(Number.isFinite)||duration<=0||end<=start||minSpan<=0)throw Error('Choose a finite interval with end after start.');
 const span=clamp(end-start,Math.min(minSpan,duration),duration),a=clamp(start,0,duration-span);return {start:a,end:a+span};
}
function zoom(view,factor,anchor,duration,minSpan=.01){if(!Number.isFinite(factor)||factor<=0)throw Error('Invalid zoom factor');const fraction=clamp((anchor-view.start)/(view.end-view.start),0,1),span=clamp((view.end-view.start)*factor,Math.min(minSpan,duration),duration);return windowBounds(anchor-fraction*span,anchor+(1-fraction)*span,duration,minSpan);}
function pan(view,delta,duration,minSpan=.01){return windowBounds(view.start+delta,view.end+delta,duration,minSpan);}
function zone(a,b,duration,minSpan=.001){if(![a,b,duration].every(Number.isFinite)||duration<=0)throw Error('Invalid replay zone');const start=clamp(Math.min(a,b),0,duration),end=clamp(Math.max(a,b),0,duration);if(end-start<minSpan)throw Error('Select a longer replay zone.');return {start,end};}
function advance(t,bounds,loop){if(t<bounds.end)return {time:t,ended:false,looped:false};return loop?{time:bounds.start+(t-bounds.start)%(bounds.end-bounds.start),ended:false,looped:true}:{time:bounds.end,ended:true,looped:false};}
function lowerBound(a,t){let l=0,r=a.length;while(l<r){const m=(l+r)>>>1;if(a[m]<t)l=m+1;else r=m;}return l;}
function trace(times,value,start,end,pixels,gap=.02){
 const lo=lowerBound(times,start),hi=lowerBound(times,end+1e-9),out=[];let bucket=-1,first=-1,last=-1,min=-1,max=-1,previous=-1;
 function flush(){if(first<0)return;const points=[...new Set([first,min,max,last])].sort((a,b)=>a-b);out.push(...points);first=-1;}
 for(let i=lo;i<hi;i++){
  const v=value(i);if(!Number.isFinite(v)){flush();if(out.length&&out.at(-1)!==null)out.push(null);previous=-1;bucket=-1;continue;}
  const p=Math.floor((times[i]-start)/(end-start)*Math.max(1,pixels)),broken=previous>=0&&times[i]-times[previous]>gap;
  if(p!==bucket||broken){flush();if(broken)out.push(null);bucket=p;}
  if(first<0){first=min=max=i;}else{if(v<value(min))min=i;if(v>value(max))max=i;}last=i;previous=i;
 }
 flush();return out;
}
function paint(ctx,indices,times,value,px,py){let connected=false;ctx.beginPath();for(const i of indices){if(i===null){connected=false;continue;}const x=px(times[i]),y=py(value(i));if(connected)ctx.lineTo(x,y);else{ctx.moveTo(x,y);ctx.lineTo(x+.4,y);}connected=true;}ctx.stroke();}
const api={windowBounds,zoom,pan,zone,advance,trace,paint};if(typeof module!=='undefined')module.exports=api;else root.GraphCore=api;
})(globalThis);
