const assert=require('node:assert/strict'),A=require('../web/imu-analysis-core.js');
let rows=['seq,t_us,ax_g,ay_g,az_g,gx_dps,gy_dps,gz_dps,clipped,session_id,raw_ax_g,raw_ay_g,raw_az_g,raw_gx_dps,raw_gy_dps,raw_gz_dps'];
for(let i=0;i<20;i++){const stamp=1000000+(i<10?i*1000:23000000+(i-10)*1000);rows.push([i,stamp,.5,0,1,0,0,0,0,i<10?'one':'two',i===1?16:1,0,1,2,0,0].join(','));}
const data=A.createDataset(rows.join('\n'),'sample.csv');
assert.equal(data.times.length,20);assert.equal(data.sessions.length,2);assert.equal(data.gaps.length,1);
assert.equal(data.raw[0][0],1);assert.equal(data.processed[0][0],.5);assert.equal(data.clipped[1],1);
assert.ok(Math.abs(data.localHz-1000)<1e-6);
assert.equal(A.sampleAt(data,10).present,false);assert.equal(A.sampleAt(data,.001).index,1);
assert.equal(A.stats(data,0,.009).count,10);assert.equal(A.stats(data,0,.009).clipped,1);
const exported=A.createDataset(A.exportCSV(data,0,.009),'roundtrip.csv');
assert.equal(exported.times.length,10);assert.equal(exported.raw[1][0],16);assert.equal(exported.processed[1][0],.5);
assert.throws(()=>A.setProcessed(data,[[1,2,3,4,5,6]]),/Invalid/);
A.setProcessed(data,null);assert.equal(data.processed,null);
console.log('Detailed IMU: provenance, gaps, nearest sample, clipping, raw/processed stats and CSV roundtrip passed.');
