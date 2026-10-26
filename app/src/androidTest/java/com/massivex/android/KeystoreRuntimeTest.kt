@file:Suppress("DEPRECATION")
package com.massivex.android

import android.content.ContextWrapper
import android.content.Intent
import android.test.InstrumentationTestCase
import android.view.accessibility.AccessibilityNodeInfo
import com.massivex.android.wallet.*
import kotlinx.coroutines.*
import java.io.File
import java.util.concurrent.atomic.AtomicReference

/** Dedicated disposable API34 emulator only. PIN is public test configuration, never an owner credential. */
class KeystoreRuntimeTest : InstrumentationTestCase() {
    private lateinit var activity: MainActivity
    private lateinit var secrets: AndroidSecrets
    private lateinit var auth: AndroidAuthentication
    private lateinit var repo: WalletRepository
    private lateinit var folder: File
    private var before = emptySet<String>()
    override fun setUp() {
        super.setUp()
        val target = instrumentation.targetContext
        activity = instrumentation.startActivitySync(Intent(target, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        secrets = AndroidSecrets(target, "massivex.instrumentation.seed.v1."); before = secrets.walletIds()
        auth = AndroidAuthentication(activity, secrets)
        folder = File(target.noBackupFilesDir, "instrumentation-${System.nanoTime()}").apply { mkdirs() }
        val context = object : ContextWrapper(target) { override fun getNoBackupFilesDir() = folder }
        repo = WalletRepository(AtomicCatalog(context), secrets, Randomness { ByteArray(32) { 42 } }, auth)
    }
    override fun tearDown() {
        instrumentation.runOnMainSync { auth.cancel(); activity.finish() }
        (secrets.walletIds() - before).forEach { secrets.delete(it) }
        folder.deleteRecursively() // This test's generated folder only.
        super.tearDown()
    }
    private fun shell(command: String) { instrumentation.uiAutomation.executeShellCommand(command).use { fd -> java.io.FileInputStream(fd.fileDescriptor).use { it.readBytes() } } }
    private fun nodes(node: AccessibilityNodeInfo?): List<AccessibilityNodeInfo> = if (node == null) emptyList() else listOf(node) + (0 until node.childCount).flatMap { nodes(node.getChild(it)) }
    private fun hasPin(): Boolean = nodes(instrumentation.uiAutomation.rootInActiveWindow).any { (it.packageName?.toString() == "com.android.systemui" || it.packageName?.toString() == "com.android.settings") && (it.viewIdResourceName?.contains("pinEntry") == true || it.isEditable) }
    private fun credential() {
        val deadline = System.currentTimeMillis() + 30000
        while (System.currentTimeMillis() < deadline) {
            val all = nodes(instrumentation.uiAutomation.rootInActiveWindow)
            all.firstOrNull { it.text?.toString()?.contains("Use PIN", true) == true }?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            if (hasPin()) {
                Thread.sleep(300) // A newly exposed PIN node can precede keyboard focus/IME readiness.
                nodes(instrumentation.uiAutomation.rootInActiveWindow).firstOrNull {
                    it.packageName?.toString() in setOf("com.android.systemui", "com.android.settings") &&
                        (it.viewIdResourceName?.contains("pinEntry") == true || it.isEditable)
                }?.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
                shell("input text 246810"); shell("input keyevent 66"); return
            }
            Thread.sleep(200)
        }
        fail("Disposable emulator credential prompt did not appear")
    }
    private fun <T> authenticated(block: suspend () -> T): T {
        val readyUntil = System.currentTimeMillis() + 10000
        while (System.currentTimeMillis() < readyUntil &&
            (!activity.hasWindowFocus() || instrumentation.uiAutomation.rootInActiveWindow?.packageName?.toString() != activity.packageName)) Thread.sleep(100)
        assertEquals("Previous system credential window did not dismiss", activity.packageName, instrumentation.uiAutomation.rootInActiveWindow?.packageName?.toString())
        val future = CoroutineScope(Dispatchers.Main).async { block() }
        credential()
        val result = runBlocking { withTimeout(30000) { future.await() } }
        instrumentation.waitForIdleSync()
        val deadline = System.currentTimeMillis() + 10000
        while ((!activity.hasWindowFocus() || instrumentation.uiAutomation.rootInActiveWindow?.packageName?.toString() != activity.packageName) && System.currentTimeMillis() < deadline) Thread.sleep(100)
        Thread.sleep(600) // Let the previous credential window finish dismissal before another fresh prompt.
        return result
    }
    fun testActualKeystoreReopenAadTamperAndExpiry() {
        val c = authenticated { repo.create("Runtime disposable") }; val w = c.selected!!
        val info = secrets.keyInfo(w.id)
        assertTrue(info.isUserAuthenticationRequired); assertEquals(5, info.userAuthenticationValidityDurationSeconds); assertEquals(256, info.keySize)
        // Only non-secret capability diagnostics. Emulator hardware backing is not assumed.
        android.util.Log.i("MXA002", "API=${android.os.Build.VERSION.SDK_INT} hardwareBacked=${info.isInsideSecureHardware} authSeconds=${info.userAuthenticationValidityDurationSeconds}")
        val reopened = WalletRepository(AtomicCatalog(object : ContextWrapper(activity) { override fun getNoBackupFilesDir() = folder }), secrets, Randomness { ByteArray(32) }, auth)
        var exported = false
        authenticated { reopened.backup(w.id, Purpose.REVEAL) { exported = it.startsWith("S") } }
        assertTrue(exported)
        val changed = w.copy(id = java.util.UUID.randomUUID().toString())
        // Same key, changed authenticated public identity.
        val bad = w.copy(address = w.address + "x")
        try { secrets.decrypt(bad); fail("Wrong AAD accepted") } catch (e: WalletException) { assertEquals(WalletError.CORRUPT, e.reason) }
        w.envelope!!.ciphertext[0] = (w.envelope.ciphertext[0].toInt() xor 1).toByte()
        try { secrets.decrypt(w); fail("Bad tag accepted") } catch (e: WalletException) { assertEquals(WalletError.CORRUPT, e.reason) }
        // Restore valid ciphertext so expiry is tested independently of integrity failure.
        w.envelope.ciphertext[0] = (w.envelope.ciphertext[0].toInt() xor 1).toByte()
        // Allow secure-clock/second granularity on the emulator; assert denial, never accept a tag error as expiry.
        Thread.sleep(11000)
        try { secrets.decrypt(w).fill(0); fail("Expired OS auth accepted") } catch (e: WalletException) { assertEquals(WalletError.AUTH, e.reason) }
        assertFalse(secrets.walletIds().contains(changed.id))
    }
    fun testCancelDoesNotCreateWallet() {
        val future = CoroutineScope(Dispatchers.Main).async { repo.create("Cancelled") }
        val deadline = System.currentTimeMillis() + 12000
        while (!hasPin() && System.currentTimeMillis() < deadline) Thread.sleep(200)
        repeat(4) {
            if (!future.isCompleted) { shell("input keyevent 4"); Thread.sleep(500) }
        }
        try { runBlocking { withTimeout(10000) { future.await() } }; fail("Cancellation accepted") }
        catch (e: WalletException) { assertEquals(WalletError.CANCELLED, e.reason) }
        assertTrue(repo.load().wallets.isEmpty()); assertEquals(before, secrets.walletIds())
    }
    fun testDeleteOnlySelectedAndAtomicReopen() {
        val first = authenticated { repo.create("First disposable") }.selected!!
        val watch = repo.addWatch("AU12MBEEKVAFZhxYzeyNYpAAcKEu667KKjHBos9i65htLwPkHyQG4", "Public fixture watch")
        assertTrue(watch.selected!!.watchOnly)
        repo.select(first.id)
        val remaining = authenticated { repo.remove(first.id, true) }
        assertEquals(1, remaining.wallets.size); assertTrue(remaining.selected!!.watchOnly)
        assertFalse(secrets.walletIds().contains(first.id)); assertEquals(remaining.selectedId, repo.load().selectedId)
    }
    fun testBackgroundRevokesApproval() {
        val future = CoroutineScope(Dispatchers.Main).async { auth.approve(Action("test", Purpose.REVEAL)) }
        val deadline = System.currentTimeMillis() + 12000
        while (!hasPin() && System.currentTimeMillis() < deadline) Thread.sleep(200)
        instrumentation.runOnMainSync { auth.cancel() }
        try { runBlocking { withTimeout(10000) { future.await() } }; fail("Revoked prompt accepted") }
        catch (e: WalletException) { assertTrue(e.reason == WalletError.CANCELLED || e.reason == WalletError.CHANGED) }
    }
    private fun clickText(text: String) {
        if(text==activity.getString(R.string.add_wallet) && androidx.lifecycle.ViewModelProvider(activity)[WalletModel::class.java].state.value.screen==Screen.HOME && nodes(instrumentation.uiAutomation.rootInActiveWindow).none{it.text?.toString()==text&&it.isVisibleToUser}){
            clickText(activity.getString(R.string.settings));clickText(activity.getString(R.string.ui_manage))
        }
        val readyUntil = System.currentTimeMillis() + 10000
        while (instrumentation.uiAutomation.rootInActiveWindow?.packageName?.toString() != activity.packageName && System.currentTimeMillis() < readyUntil) Thread.sleep(100)
        assertEquals("Expected app window", activity.packageName, instrumentation.uiAutomation.rootInActiveWindow?.packageName?.toString())
        repeat(20) { attempt ->
            val found = nodes(instrumentation.uiAutomation.rootInActiveWindow).lastOrNull { (it.text?.toString() == text || it.contentDescription?.toString() == text) && it.isVisibleToUser && it.isEnabled }
            if (found != null) {
                var clickable: AccessibilityNodeInfo = found
                clickable.refresh()
                while (!clickable.isClickable) {clickable = clickable.parent ?: break;clickable.refresh()}
                clickable.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN.id)
                Thread.sleep(150)
                if (clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK)) { Thread.sleep(300); return }
            }
            // A long retained catalog may require scrolling back after first-frame/layout changes.
            shell(if (attempt < 10) "input swipe 540 1600 540 1000 300" else "input swipe 540 1000 540 1600 300")
        }
        fail("Expected native action unavailable")
    }
    private fun enterField(index: Int, value: String) {
        val deadline = System.currentTimeMillis() + 6000
        while (System.currentTimeMillis() < deadline) {
            val fields = nodes(instrumentation.uiAutomation.rootInActiveWindow).filter { it.isEditable || it.className?.toString() == "android.widget.EditText" }
            if (fields.size > index) {
                assertTrue("Native field rejected text entry", fields[index].performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, android.os.Bundle().apply {
                    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value)
                }))
                Thread.sleep(200); return
            }
            Thread.sleep(200)
        }
        fail("Native editable field unavailable at index $index")
    }
    private fun awaitText(fragment: String) {
        val deadline = System.currentTimeMillis() + 30000
        while (System.currentTimeMillis() < deadline) {
            if (nodes(instrumentation.uiAutomation.rootInActiveWindow).any { it.text?.toString()?.contains(fragment) == true }) return
            Thread.sleep(150)
        }
        fail("Expected safe native result unavailable")
    }
    fun testNativeYamlWrongPasswordThenValidImport() {
        val fixture = instrumentation.context.assets.open("ios-parity.json").bufferedReader().use { org.json.JSONObject(it.readText()).getJSONArray("yamlCases").getJSONObject(0) }
        val production = WalletRepository(AtomicCatalog(instrumentation.targetContext), AndroidSecrets(instrumentation.targetContext), Randomness { ByteArray(32) }, auth)
        val count = production.load().wallets.size
        clickText(activity.getString(R.string.add_wallet)); clickText(activity.getString(R.string.import_yaml))
        clickText(activity.getString(R.string.ui_paste_yaml))
        enterField(0, "YAML runtime disposable"); enterField(1, fixture.getString("yaml")); enterField(2, "wrong public test password")
        clickText(activity.getString(R.string.add_wallet)); awaitText("YAML import failed")
        assertEquals(count, production.load().wallets.size)
        enterField(1, fixture.getString("yaml")); enterField(2, fixture.getString("password"))
        clickText(activity.getString(R.string.add_wallet)); credential(); awaitText("YAML runtime disposable")
        val added = production.load(); assertEquals(count + 1, added.wallets.size)
        assertEquals(fixture.getString("address"), added.selected!!.address)
        // Remove exactly this generated test entry with real fresh authorization; keep all pre-existing demo wallets.
        authenticated { production.remove(added.selectedId!!, true) }
        assertEquals(count, production.load().wallets.size)
    }

}
