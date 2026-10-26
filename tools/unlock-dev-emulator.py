#!/usr/bin/env python3
"""Enter the EXISTING public task-AVD PIN only after validating SystemUI's PIN field.
Never changes credentials/enrollment, bypasses authentication, or targets a phone.
"""
import re,subprocess,sys,time,uuid,xml.etree.ElementTree as E
adb,serial=sys.argv[1:]
def cmd(*args):return subprocess.check_output([adb,'-s',serial,*args],text=True,stderr=subprocess.STDOUT,timeout=12)
def sh(*args):return cmd('shell',*args)
assert cmd('emu','avd','name').splitlines()[0].strip()=='MassiveX_Dev','Refusing another AVD/device'
assert sh('getprop','ro.kernel.qemu').strip()=='1','Refusing a phone'
def unlocked():return 'State: RUNNING_UNLOCKED' in sh('dumpsys','user')
if unlocked():raise SystemExit(0)
sh('input','keyevent','KEYCODE_WAKEUP');sh('input','keyevent','KEYCODE_MENU')
entry=None
for attempt in range(5):
 if unlocked():raise SystemExit(0)
 path='/data/local/tmp/mxa-emulator-unlock-'+uuid.uuid4().hex+'.xml'
 try:
  sh('uiautomator','dump',path)
  root=E.fromstring(sh('cat',path))
  entry=next((n for n in root.iter('node') if n.get('package')=='com.android.systemui' and n.get('resource-id')=='com.android.systemui:id/pinEntry' and n.get('enabled')=='true'),None)
  if entry is not None:break
  if any(n.get('package')=='com.android.phone' for n in root.iter('node')):sh('input','keyevent','KEYCODE_BACK')
 except (subprocess.SubprocessError,E.ParseError):pass
 finally:sh('rm','-f',path)
 sh('input','keyevent','KEYCODE_MENU');time.sleep(.5)
if entry is None:raise SystemExit('Task AVD PIN field not ready; no digits entered or security setting changed.')
x1,y1,x2,y2=map(int,re.findall(r'\d+',entry.get('bounds','')))
assert x2>x1 and y2>y1
sh('input','tap',str((x1+x2)//2),str((y1+y2)//2))
sh('input','keyevent',*(['KEYCODE_DEL']*12))
sh('input','text','246810') # Public existing fixture credential, never an owner credential.
sh('input','keyevent','KEYCODE_ENTER')
for _ in range(12):
 if unlocked():raise SystemExit(0)
 time.sleep(.5)
raise SystemExit('Task AVD did not unlock; no repeated PIN attempt or security setting change.')
