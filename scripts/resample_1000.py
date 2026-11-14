#!/usr/bin/env python3
"""Create a separately labelled, filtered 1000Hz CSV from contiguous high-rate data.
Refuses gaps or <=1000Hz input; never edits the raw recording. Standard library only.
"""
import argparse, bisect, csv, hashlib, json, math, statistics
from pathlib import Path
COLUMNS=['seq','t_us','ax_g','ay_g','az_g','gx_dps','gy_dps','gz_dps','clipped']
def resample(rows):
    if len(rows)<200:raise ValueError('Need at least 200 high-rate samples')
    if any(not all(math.isfinite(float(v)) for v in row) for row in rows):raise ValueError('Non-finite sample')
    times=[row[1] for row in rows];steps=[b-a for a,b in zip(times,times[1:])];dt=statistics.median(steps)
    if dt<=0 or dt>=1000:raise ValueError('Input must be genuinely above 1000Hz')
    if any(d<=0 or d>dt*1.8 for d in steps):raise ValueError('Timestamp gap detected; refusing to interpolate missing motion')
    if any(((int(b[0])-int(a[0]))&0xffffffff)!=1 for a,b in zip(rows,rows[1:])):raise ValueError('Sequence gap detected')
    half=48;radius=half*dt;cutoff=.0004 # cycles per microsecond, 400Hz
    start=math.ceil((times[0]+radius)/1000)*1000;end=times[-1]-radius;output=[]
    for target in range(int(start),int(end)+1,1000):
        lo=bisect.bisect_left(times,target-radius);hi=bisect.bisect_right(times,target+radius)
        weights=[]
        for j in range(lo,hi):
            distance=times[j]-target;x=2*cutoff*distance
            sinc=1 if x==0 else math.sin(math.pi*x)/(math.pi*x)
            weights.append(sinc*.5*(1+math.cos(math.pi*distance/radius)))
        total=sum(weights)
        if abs(total)<1e-12:raise ValueError('Invalid filter support')
        axes=[sum(w*rows[j][k] for w,j in zip(weights,range(lo,hi)))/total for k in range(2,8)]
        output.append([len(output),target]+axes+[int(any(rows[j][8] for j in range(lo,hi)))])
    return output

def main():
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('input',type=Path);parser.add_argument('output',type=Path);args=parser.parse_args()
    with args.input.open(newline='') as f:
        reader=csv.DictReader(f)
        if reader.fieldnames!=COLUMNS:parser.error('Unexpected CSV columns')
        rows=[[int(row['seq']),int(row['t_us'])]+[float(row[k]) for k in COLUMNS[2:8]]+[int(row['clipped'])] for row in reader]
    try:result=resample(rows)
    except ValueError as e:parser.error(str(e))
    metadata=args.output.with_suffix(args.output.suffix+'.derived.json')
    if args.output.exists() or metadata.exists():parser.error('Output already exists; choose a new filename')
    with args.output.open('x',newline='') as f:writer=csv.writer(f);writer.writerow(COLUMNS);writer.writerows(result)
    metadata.write_text(json.dumps({'derived':True,'source':args.input.name,'sourceSha256':hashlib.sha256(args.input.read_bytes()).hexdigest(),'outputHz':1000,'method':'400Hz low-pass windowed-sinc interpolation; Hann window, 48 nominal input periods each side','edgeHandling':'trimmed; no padding','samples':len(result),'clipped':'conservative OR across filter support'},indent=2))
    print(f'{args.output}: {len(result)} derived samples; original retained')
if __name__=='__main__':main()
