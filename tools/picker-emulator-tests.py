#!/usr/bin/env python3
"""Dedicated AVD only; verify the native picker using public/masked Home semantics. Requires a retained long synthetic catalog; never clears or decrypts wallets."""
import subprocess,time,re,xml.etree.ElementTree as E
adb='/Library/Android/sdk/platform-tools/adb';serial='emulator-5580';pkg='com.massivex.android.dev'
def cmd(*args):return subprocess.check_output([adb,'-s',serial,*args],text=True)
def sh(*args):return cmd('shell',*args)
assert cmd('emu','avd','name').splitlines()[0].strip()=='MassiveX_Dev'
assert sh('getprop','ro.kernel.qemu').strip()=='1'
prior=sh('settings','get','global','airplane_mode_on').strip()
def tree():
 path='/data/local/tmp/mxa005-picker-public.xml'
 sh('uiautomator','dump',path)
 try:return E.fromstring(sh('cat',path))
 finally:sh('rm','-f',path)
def nodes():return list(tree().iter('node'))
def tap(n):
 x1,y1,x2,y2=map(int,re.findall(r'\d+',n.get('bounds')));assert x2>x1 and y2>y1
 sh('input','tap',str((x1+x2)//2),str((y1+y2)//2));time.sleep(1)
try:
 sh('am','force-stop',pkg);sh('cmd','connectivity','airplane-mode','enable')
 sh('am','start','-W','-n',pkg+'/com.massivex.android.MainActivity');time.sleep(2)
 pid=sh('pidof',pkg).strip()
 tap(next(n for n in nodes() if n.get('text','').endswith(' ⌄')))
 assert any(n.get('text')=='Choose Wallet' for n in nodes())
 assert any(n.get('text')=='Add Wallet' and n.get('bounds')!='[0,0][0,0]' for n in nodes()),'Fixed Add Wallet action unavailable'
 scrolling=next((n for n in nodes() if n.get('scrollable')=='true'),None)
 assert scrolling is not None,'Expected retained long wallet list to be scrollable'
 before=[n.get('text') for n in nodes() if n.get('text')]
 x1,y1,x2,y2=map(int,re.findall(r'\d+',scrolling.get('bounds')))
 sh('input','swipe',str((x1+x2)//2),str(y2-30),str((x1+x2)//2),str(y1+30),'350');time.sleep(.5)
 after=[n.get('text') for n in nodes() if n.get('text')]
 assert before!=after,'Long picker list did not scroll'
 assert any(n.get('text')=='Add Wallet' and n.get('bounds')!='[0,0][0,0]' for n in nodes()),'Add Wallet did not remain available'
 for _ in range(3):
  sh('input','keyevent','4');time.sleep(1)
  if any(n.get('text','').endswith(' ⌄') for n in nodes()):break
 assert any(n.get('text','').endswith(' ⌄') for n in nodes()),'Home selector did not return after sheet dismissal'
 assert not any(n.get('text')=='Choose Wallet' for n in nodes())
 assert sh('pidof',pkg).strip()==pid
 print('PASS: native picker opens, long catalog scroll exposes Add Wallet, dismiss returns without process restart; public semantic checks only')
finally:
 sh('am','force-stop',pkg)
 if prior=='0':sh('cmd','connectivity','airplane-mode','disable')
