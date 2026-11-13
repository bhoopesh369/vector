#!/usr/bin/env python3
"""Install watch factory-build tools only within this repository."""
from pathlib import Path
import os,shutil,subprocess,sys
ROOT=Path(__file__).resolve().parents[1]
SDK_COMMIT='b774170ff46c393eeb5e495ea37936038d3f4f4f'
sdk=ROOT/'.tools/esp-idf'
if not sdk.exists():
 subprocess.run(['git','clone','--branch','v5.5.5','--depth','1','--shallow-submodules','--recursive','https://github.com/espressif/esp-idf.git',str(sdk)],check=True)
actual=subprocess.check_output(['git','-C',str(sdk),'rev-parse','HEAD'],text=True).strip()
if actual!=SDK_COMMIT:raise SystemExit('Unexpected ESP-IDF checkout; inspect it before continuing')
python=next((shutil.which(name) for name in ('python3.13','python3.12','python3.11','python3.10') if shutil.which(name)),None)
if not python:raise SystemExit('Use an existing Python3.10–3.13 interpreter; no system Python will be installed')
env=os.environ.copy()
for key,path in {'IDF_TOOLS_PATH':'.tools/idf-tools','IDF_PYTHON_ENV_PATH':'.tools/idf-python','PIP_CACHE_DIR':'.cache/pip','XDG_CACHE_HOME':'.cache','IDF_COMPONENT_CACHE_PATH':'.cache/idf-components'}.items():
 env[key]=str(ROOT/path);Path(env[key]).mkdir(parents=True,exist_ok=True)
for args in [['install','--targets','esp32s3'],['install','cmake','ninja'],['install-python-env']]:
 subprocess.run([python,str(sdk/'tools/idf_tools.py'),*args],env=env,check=True,cwd=ROOT)
print('Local watch tools ready; build with bash scripts/build-watch.sh')
