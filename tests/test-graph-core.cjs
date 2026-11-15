const assert=require('node:assert/strict'),G=require('../web/graph-core');
assert.deepEqual(G.windowBounds(-2,3,10),{start:0,end:5});assert.deepEqual(G.windowBounds(9,14,10),{start:5,end:10});assert.deepEqual(G.windowBounds(0,20,10),{start:0,end:10});
assert.deepEqual(G.zoom({start:2,end:6},.5,3,10),{start:2.5,end:4.5});assert.deepEqual(G.pan({start:2,end:6},20,10),{start:6,end:10});assert.throws(()=>G.windowBounds(2,1,10));assert.throws(()=>G.zoom({start:0,end:2},NaN,1,10));
assert.deepEqual(G.zone(4,2,10),{start:2,end:4});assert.deepEqual(G.zone(-1,2,10),{start:0,end:2});assert.throws(()=>G.zone(2,2,10));
assert.deepEqual(G.advance(4.2,{start:2,end:4},true),{time:2.2,ended:false,looped:true});assert.deepEqual(G.advance(9,{start:2,end:4},true),{time:3,ended:false,looped:true});assert.deepEqual(G.advance(4,{start:2,end:4},false),{time:4,ended:true,looped:false});
const times=Float64Array.from([0,.001,.002,.003,.004,.005,.1,.101,.102]),values=[0,1,-8,12,2,0,3,4,2];const original=values.slice(),trace=G.trace(times,i=>values[i],0,.102,1,.02);
assert.ok(trace.includes(2),'negative peak retained');assert.ok(trace.includes(3),'positive peak retained');assert.ok(trace.includes(null),'gap remains disconnected');assert.deepEqual(trace.filter(i=>i!==null),[0,2,3,5,6,7,8]);assert.deepEqual(values,original,'view never changes sensor values');
const fine=G.trace(times,i=>values[i],0,.005,100,.02);assert.deepEqual(fine,[0,1,2,3,4,5]);
const invalid=G.trace([0,.001,.002],i=>i===1?NaN:i,0,.002,1);assert.ok(invalid.includes(null),'invalid values break the line');

const denseTimes=Float64Array.from({length:100000},(_,i)=>i/100000),denseValues=Float64Array.from(denseTimes,(_,i)=>i===12345?-500:i===67890?900:0),dense=G.trace(denseTimes,i=>denseValues[i],0,1,1,.02);assert.ok(dense.length<=4,'trace work after reduction stays bounded per pixel');assert.ok(dense.includes(12345)&&dense.includes(67890));
const commands=[],ctx={beginPath(){},moveTo(x,y){commands.push(['M',x,y]);},lineTo(x,y){commands.push(['L',x,y]);},stroke(){}};G.paint(ctx,[0,1,null,2],Float64Array.from([0,.001,.1]),i=>i,x=>x,x=>x);assert.equal(commands.filter(c=>c[0]==='M').length,2,'renderer restarts after a gap');

console.log('Graph core: anchored zoom, bounded pan, reverse zones, replay stop/loop, peak retention, gaps and unchanged raw data passed.');
