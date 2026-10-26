#!/usr/bin/env python3
"""Guarded task-AVD reboot acceptance. Public watch reads only; no payment or synthetic notification."""
import subprocess,time
adb='/Library/Android/sdk/platform-tools/adb';serial='emulator-5580';pkg='com.massivex.android.dev'
def call(*args):return subprocess.check_output([adb,'-s',serial,*args],text=True,stderr=subprocess.STDOUT)
def sh(*args):return call('shell',*args)
assert call('emu','avd','name').splitlines()[0].strip()=='MassiveX_Dev'
assert sh('getprop','ro.kernel.qemu').strip()=='1'
subprocess.check_call(['./tools/prepare-dev-emulator.sh',serial])
prior_airplane=sh('settings','get','global','airplane_mode_on').strip()
permission_line=next(x for x in sh('dumpsys','package',pkg).splitlines() if 'android.permission.POST_NOTIFICATIONS: granted=' in x)
prior_permission='granted=true' in permission_line
prior_user_set='USER_SET' in permission_line
def test(name):
 result=sh('am','instrument','-w','-e','class','com.massivex.android.BalanceSchedulerRestartRuntimeTest#'+name,pkg+'.test/android.test.InstrumentationTestRunner')
 print(result,flush=True)
 assert 'OK (1 test)' in result or 'OK (1 tests)' in result
try:
 sh('cmd','connectivity','airplane-mode','enable')
 sh('pm','grant',pkg,'android.permission.POST_NOTIFICATIONS')
 sh('pm','set-permission-flags',pkg,'android.permission.POST_NOTIFICATIONS','user-set')
 test('testPreparePersistedJobAndPermissionOptIn')
 sh('input','keyevent','3');sh('am','kill',pkg);time.sleep(3)
 call('reboot')
 for _ in range(90):
  time.sleep(1)
  try:
   if sh('getprop','sys.boot_completed').strip()=='1':break
  except subprocess.CalledProcessError:pass
 else:raise AssertionError('Task AVD reboot timed out')
 subprocess.check_call(['./tools/prepare-dev-emulator.sh',serial,'keep-process'])
 # The ordinary helper and am instrument force-stop the package and cancel jobs.
 # Inspect the OS-restored queue BEFORE either destructive harness action.
 restored=sh('dumpsys','jobscheduler',pkg)
 assert 'BalanceCheckJob' in restored,'Persisted balance job not restored by OS after reboot'
 print('PASS: OS queue contains the restored balance job before instrumentation restart',flush=True)
 test('testResumeAfterProcessDeathAndRebootThenOptOut')
 print('PASS: permission opt-in, unique persisted periodic network job, process death/reboot restoration, opt-out cancellation. No balance event fabricated or transaction submitted.',flush=True)
finally:
 sh('am','force-stop',pkg)
 if not prior_permission:sh('pm','revoke',pkg,'android.permission.POST_NOTIFICATIONS')
 if not prior_user_set:sh('pm','clear-permission-flags',pkg,'android.permission.POST_NOTIFICATIONS','user-set')
 if prior_airplane=='0':sh('cmd','connectivity','airplane-mode','disable')
