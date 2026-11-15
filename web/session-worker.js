/* Parse only the selected recording, off the UI thread. */
importScripts('sync-core.js');
onmessage=async({data:request})=>{try{
 const text=request.file?await request.file.text():await fetch(request.url).then(r=>{if(!r.ok)throw Error('CSV request failed: '+r.status);return r.text();});
 const csv=SyncCore.parseCSV(text),map={timestampColumn:csv.headers.indexOf('t_us'),timestampUnits:'us',axes:['ax_g','ay_g','az_g','gx_dps','gy_dps','gz_dps'].map(a=>csv.headers.indexOf(a)),headers:csv.headers};
 const data=SyncCore.buildIMU(csv,map.timestampColumn,map.timestampUnits,map.axes,1200);
 if(map.timestampColumn<0)throw Error('Expected t_us column. Load this CSV individually using custom column mapping.');
 data.originUs=Number(csv.rows[0][map.timestampColumn]);data.processedValues=data.values;
 const seq=csv.headers.indexOf('seq');data.sequences=seq<0?null:Float64Array.from(csv.rows,r=>Number(r[seq]));
 const raw=['ax_g','ay_g','az_g','gx_dps','gy_dps','gz_dps'].map(a=>csv.headers.indexOf('raw_'+a));
 if(raw.every(i=>i>=0))data.rawValues=SyncCore.buildIMU(csv,map.timestampColumn,'us',raw,1200).values;
 data.segments=[{name:request.name,start:0,end:data.duration}];postMessage({data,map});
}catch(error){postMessage({error:error.message});}};
