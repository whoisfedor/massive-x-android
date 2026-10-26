#!/usr/bin/env python3
"""Offline UI acceptance on the NEW disposable MassiveX_Dev AVD only. No secret output."""
import json
import os
import re
import subprocess
import time
import xml.etree.ElementTree as ET
from pathlib import Path

ADB = str(Path(os.environ.get('ANDROID_SDK_ROOT', '/Library/Android/sdk')) / 'platform-tools/adb')
SERIAL = 'emulator-5580'
PACKAGE = 'com.massivex.android.dev'

def adb(*args):
    return subprocess.check_output([ADB, '-s', SERIAL, *args], text=True, stderr=subprocess.STDOUT)

def shell(*args):
    return adb('shell', *args)

def tree():
    # Public/masked screens only; never call while backup key is revealed or private input is filled.
    path = '/data/local/tmp/massivex-public-ui.xml'
    for attempt in range(4):
        try:
            shell('uiautomator', 'dump', path)
            raw = shell('cat', path)
            return ET.fromstring(raw[raw.index('<?xml'):raw.rindex('</hierarchy>') + len('</hierarchy>')])
        except (subprocess.CalledProcessError, ValueError, ET.ParseError):
            if attempt == 3:
                raise AssertionError('Public UI hierarchy did not become ready') from None
            time.sleep(.7)
        finally:
            shell('rm', '-f', path)

def tap_node(n):
    x1,y1,x2,y2 = map(int, re.findall(r'\d+', n.attrib['bounds']))
    shell('input','tap',str((x1+x2)//2),str((y1+y2)//2))
    time.sleep(.7)

def click(label):
    management = ('Add Wallet','Rename Wallet','Remove Wallet','Backup')
    if label in management:
        visible = any(n.get('text') == label and n.get('bounds') != '[0,0][0,0]' for n in tree().iter('node'))
        if not visible:
            plain_click('Settings')
            if not any(n.get('text') == label for n in tree().iter('node')):
                plain_click('Wallets and Backup')
    plain_click(label)

def plain_click(label):
    for _ in range(7):
        root = tree()
        matches = [n for n in root.iter('node') if (n.get('text') == label or n.get('content-desc') == label) and n.get('enabled') == 'true' and n.get('bounds') != '[0,0][0,0]']
        if matches:
            tap_node(matches[-1]); return
        shell('input','swipe','540','1900','540','650','350')
    raise AssertionError('Expected UI action unavailable: ' + label)

def fill(index, text):
    fields = [n for n in tree().iter('node') if n.get('class') == 'android.widget.EditText']
    tap_node(fields[index]); shell('input','text',text)
    shell('input','keyevent','4')
    time.sleep(.8)  # Let IME dismissal restore cached public button geometry; never dump filled secret fields.

def consent():
    for _ in range(5):
        boxes = [n for n in tree().iter('node') if n.get('checkable') == 'true']
        if boxes: tap_node(boxes[0]); return
        shell('input','swipe','540','1800','540','700','300')
    raise AssertionError('Confirmation control unavailable')

def authorize(cancel=False):
    for _ in range(15):
        root = tree()
        pin = [n for n in root.iter('node') if n.get('package') in ('com.android.systemui','com.android.settings') and ('pinEntry' in n.get('resource-id','') or n.get('class') == 'android.widget.EditText')]
        if pin:
            if cancel:
                shell('input','keyevent','4'); time.sleep(.5); shell('input','keyevent','4')
            else:
                shell('input','text','246810'); shell('input','keyevent','66')
            time.sleep(2); return
        use = [n for n in root.iter('node') if 'Use PIN' in n.get('text','')]
        if use: tap_node(use[0])
        time.sleep(.5)
    raise AssertionError('System credential prompt unavailable')

def text_present(value):
    return any(n.get('text') in (value,value+' ⌄') for n in tree().iter('node'))

def launch():
    shell('am','start','-W','-n',PACKAGE+'/com.massivex.android.MainActivity'); time.sleep(2)

def report(name): print('PASS: '+name, flush=True)

assert adb('emu','avd','name').splitlines()[0].strip() == 'MassiveX_Dev', 'Refusing another AVD/device'
subprocess.check_call(['./tools/prepare-dev-emulator.sh', SERIAL])
prior_airplane = shell('settings','get','global','airplane_mode_on').strip()
shell('cmd','connectivity','airplane-mode','enable')
try:
    launch()
    had_neighbors = not text_present('Create a wallet or add an address to watch.')
    click('Add Wallet'); click('Watch-only wallet')
    fill(0,'Watched'); fill(1,'AU12MBEEKVAFZhxYzeyNYpAAcKEu667KKjHBos9i65htLwPkHyQG4'); click('Add Wallet')
    assert text_present('Watched'); report('watch-only onboarding')
    shell('am','force-stop',PACKAGE); launch(); assert text_present('Watched'); report('catalog survives force-stop')
    click('Rename Wallet'); fill(0,'Renamed'); click('Save Name'); assert text_present('Renamed'); report('rename selected')
    click('Remove Wallet'); consent(); click('Remove Only This Wallet'); assert text_present('Wallet' if had_neighbors else 'Create a wallet or add an address to watch.'); assert not text_present('Renamed'); report('confirmed watch removal')
    click('Add Wallet'); click('Create Wallet'); click('Create Wallet'); authorize(cancel=True)
    assert text_present('Authorization cancelled. No sensitive action was completed.'); report('native create cancellation')
    click('Create Wallet'); authorize(); click('Balance information'); assert text_present('Total balance'); click('Done'); report('native create with actual credential authorization')
    click('Backup'); click('Show private key'); authorize()
    # Never dump accessibility/screenshot while a key is revealed.
    shell('input','keyevent','3'); time.sleep(1); launch()
    root = tree()
    assert not any(re.fullmatch(r'S[1-9A-HJ-NP-Za-km-z]{35,60}', n.get('text','')) for n in root.iter('node'))
    report('backup is masked after background/resume')
    click('Back to Wallets')
    shell('settings','put','system','accelerometer_rotation','0'); shell('settings','put','system','user_rotation','1'); time.sleep(2)
    shell('settings','put','system','user_rotation','0'); time.sleep(2)
    assert text_present('Wallet'); report('rotation preserves public catalog')
    shell('am','force-stop',PACKAGE); launch(); assert text_present('Wallet'); report('signing wallet catalog survives process restart')
    click('Backup'); click('Copy private key'); authorize()
    assert text_present('Clear copied key'); click('Clear copied key'); click('Back to Wallets')
    report('fresh authenticated backup/copy after process restart')
    click('Add Wallet'); click('Import Private Key')
    fixture = json.loads(Path('massa-core/src/test/resources/ios-parity.json').read_text())['accounts'][0]
    fill(0,'Imported')
    continue_node = [n for n in tree().iter('node') if n.get('text') == 'Add Wallet'][-1]
    fill(1,fixture['privateKey']); tap_node(continue_node); authorize(); assert text_present('Imported'); report('native synthetic private-key import')
    click('Remove Wallet'); consent(); click('Remove Only This Wallet'); authorize()
    assert not text_present('Imported'); assert any(n.get('text','').endswith(' ⌄') and not n.get('text','').startswith('Imported') for n in tree().iter('node')); report('delete selected signing wallet preserves neighbor')
    click('Settings'); assert text_present('Settings'); report('offline settings navigation')
    print('UI smoke completed; disposable preview wallets remain only on MassiveX_Dev.', flush=True)
finally:
    shell('am','force-stop','--user','0',PACKAGE)
    if prior_airplane == '0': shell('cmd','connectivity','airplane-mode','disable')
