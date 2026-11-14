import importlib.util, math, unittest
from pathlib import Path
spec=importlib.util.spec_from_file_location('resample',Path(__file__).resolve().parents[1]/'scripts/resample_1000.py')
r=importlib.util.module_from_spec(spec);spec.loader.exec_module(r)
class ResampleTests(unittest.TestCase):
 def test_passband_and_alias_rejection(self):
  rows=[]
  for i in range(3588):
   t=i/1793.6
   rows.append([i,round(t*1e6)]+[math.sin(2*math.pi*20*t)+math.sin(2*math.pi*700*t)]*6+[0])
  out=r.resample(rows)
  self.assertGreater(len(out),1900)
  self.assertTrue(all(b[1]-a[1]==1000 for a,b in zip(out,out[1:])))
  error=sum((x[2]-math.sin(2*math.pi*20*x[1]/1e6))**2 for x in out)/len(out)
  self.assertLess(math.sqrt(error),.03)
 def test_gap_is_not_filled(self):
  rows=[[i,i*558]+[0]*6+[0] for i in range(200)]
  for row in rows[100:]:row[1]+=10000
  with self.assertRaisesRegex(ValueError,'gap'):r.resample(rows)
 def test_low_rate_not_upsampled_as_high_rate(self):
  with self.assertRaises(ValueError):r.resample([[i,i*9000]+[0]*6+[0] for i in range(200)])
