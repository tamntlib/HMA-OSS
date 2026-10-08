// JVM fixtures only: production service methods are inserted by the runner.
object BuildConfig { const val APP_PACKAGE_NAME = "org.frknkrc44.hma_oss" }
class AppConfig {
    var useWhitelist = false
    var excludeSystemApps = true
    var invertActivityLaunchProtection = false
    val extraAppList = mutableSetOf<String>()
    val extraOppositeAppList = mutableSetOf<String>()
    val applyTemplates = mutableSetOf<String>()
    val applyPresets = mutableSetOf<String>()
}
class Template(val appList: Set<String>)
class Config {
    val scope = mutableMapOf<String, AppConfig>()
    val templates = mutableMapOf<String, Template>()
    val ignoredPackagesForPresets = mutableSetOf<String>()
    var webViewProtection = true
    var disableActivityLaunchProtection = false
}
object AppPresets {
    val instance = this
    fun containsPackage(preset: String, query: String) = false
}
fun getWebviewProvider(): String? = null
fun getDefaultBrowser(pmn: Any?, userId: Int): String? = null
class ActivityInfo(val packageName: String)
class ResolveInfo(val activityInfo: ActivityInfo?)
fun ResolveInfo.getPackageName(): String? = activityInfo?.packageName
class Frame(val args: Array<Any?>)
class ReturnValue(var result: Any?, val throwable: Throwable? = null)
inline fun <reified T> Array<Any?>.firstWithType(): T = filterIsInstance<T>().first()
fun getUserFromCallingUid(uid: Int) = uid / 100000
fun getCallingApps(pms: FakePackageManager, uid: Int) = pms.callers[uid].orEmpty().toTypedArray()
fun logV(tag: String, message: () -> String) {}
fun logD(tag: String, message: () -> String) {}
fun logI(tag: String, message: () -> String) {}
typealias IPackageManager = FakePackageManager
class FakeDataHolder {
    var cachedHidden = false
    var cacheWrites = 0
    fun shouldHideFromUid(uid: Int, query: String?) = cachedHidden
    fun putShouldHideUidCache(uid: Int, caller: String, query: String) {
        cacheWrites++
        cachedHidden = true
    }
}
class PmsHookFixture(val service: Service) {
    val pms get() = service.pms
    val TAG = "PmsTest"
    val dataHolder = FakeDataHolder()
    val lastFilteredApp = java.util.concurrent.atomic.AtomicReference<String?>(null)
    // PMS_METHOD
    fun query(uid: Int, target: String): Any? {
        val result = ReturnValue("visible")
        applyPackageHiding("getPackageInfo", result, { uid }, { target },
            { pm, id -> getCallingApps(pm, id) }, null)
        return result.result
    }
}
inline fun hookAfter(block: () -> Unit) = block()
class ActivityHookFixture(val service: Service) {
    val pms get() = service.pms
    val TAG = "ActivityTest"
    fun filter(uid: Int, input: List<ResolveInfo>): List<ResolveInfo> {
        val methodName = "applyPostResolutionFilter"
        val frame = Frame(arrayOf(uid))
        val returnValue = ReturnValue(input)
        hookAfter {
            // ACTIVITY_CALLBACK
        }
        @Suppress("UNCHECKED_CAST")
        return returnValue.result as List<ResolveInfo>
    }
}

class Intent(val action: String) {
    companion object {
        const val ACTION_MAIN = "android.intent.action.MAIN"
        const val CATEGORY_HOME = "android.intent.category.HOME"
    }
    var category: String? = null
    var targetPackage: String? = null
    fun addCategory(value: String) { category = value }
    fun setPackage(value: String) { targetPackage = value }
}
var frameworkPms = FakePackageManager()
fun queryIntentActivitiesAsUser(intent: Intent, userId: Int): List<ResolveInfo> {
    check(intent.action == Intent.ACTION_MAIN && intent.category == Intent.CATEGORY_HOME)
    val packages = frameworkPms.homes[userId].orEmpty().filter { it == intent.targetPackage }
    frameworkPms.homeLookups++
    if (frameworkPms.failHomeLookup) error("Binder failure")
    return packages.map { ResolveInfo(ActivityInfo(it)) }
}
// LAUNCHER_METHOD
class FakePackageManager {
    val callers = mutableMapOf<Int, List<String>>()
    val homes = mutableMapOf<Int, List<String>>()
    var failHomeLookup = false
    var homeLookups = 0
}
fun <T> binderLocalScope(block: () -> T): T = block()
fun logW(tag: String, cause: Throwable, message: () -> String) {}
class Service {
    val config = Config()
    val pms = FakePackageManager()
    val pmn: Any? = null
    val systemApps = mutableSetOf<String>()
    val TAG = "Test"
    val counts = mutableMapOf<String, Int>()
    fun isHookEnabled(caller: String?) = config.scope.containsKey(caller)
    fun increasePMFilterCount(caller: String?, amount: Int = 1) {
        if (caller != null) counts[caller] = (counts[caller] ?: 0) + amount
    }
    fun increasePMFilterCount(uid: Int) {}
    fun isAppInGMSIgnoredPackages(caller: String, query: String) = false
    // SERVICE_METHODS
}

fun main() {
    val service = Service()
    frameworkPms = service.pms
    check(service.shouldHide("example.unconfigured", BuildConfig.APP_PACKAGE_NAME, 0)) {
        "HMA must be hidden from an unconfigured caller"
    }
    println("PASS: HMA hidden without Enable/template/preset")
    check(service.shouldHideActivityLaunch("example.unconfigured", BuildConfig.APP_PACKAGE_NAME, 0)) {
        "HMA activities must be hidden from an unconfigured caller"
    }
    println("PASS: HMA activity protection without Enable/template/preset")
    val manager = ResolveInfo(ActivityInfo(BuildConfig.APP_PACKAGE_NAME))
    val other = ResolveInfo(ActivityInfo("example.other"))
    service.pms.callers[10123] = listOf("example.unconfigured")
    val filtered = ActivityHookFixture(service).filter(10123, listOf(manager, other))
    check(filtered == listOf(other)) { "Unconfigured activity queries must filter HMA only" }
    println("PASS: activity resolver filters unconfigured caller")
    service.pms.homes[0] = listOf("example.launcher")
    service.pms.callers[10124] = listOf("example.launcher")
    val pmsHook = PmsHookFixture(service)
    pmsHook.dataHolder.cachedHidden = true
    check(pmsHook.query(10124, BuildConfig.APP_PACKAGE_NAME) == "visible") {
        "A stale positive cache must not hide HMA from a new launcher"
    }
    println("PASS: package queries refresh launcher exemptions")
    pmsHook.query(10123, BuildConfig.APP_PACKAGE_NAME)
    pmsHook.query(10123, BuildConfig.APP_PACKAGE_NAME)
    check(pmsHook.dataHolder.cacheWrites == 0) { "Manager queries must not grow the bypassed cache" }
    println("PASS: manager queries do not accumulate cache entries")

    val exemptions = Constants.packagesShouldNotHide + setOf(
        BuildConfig.APP_PACKAGE_NAME, "com.android.settings", "com.android.packageinstaller",
        "com.google.android.packageinstaller", "com.samsung.android.packageinstaller", "example.launcher",
    )
    for (caller in exemptions) {
        check(!service.shouldHide(caller, BuildConfig.APP_PACKAGE_NAME, 0)) { "Exemption failed: $caller" }
        check(!service.shouldHideActivityLaunch(caller, BuildConfig.APP_PACKAGE_NAME, 0))
    }
    println("PASS: manager, launchers and management components remain accessible")

    check(!service.shouldHide(null, BuildConfig.APP_PACKAGE_NAME, 0))
    check(!service.shouldHide("example.unconfigured", null, 0))
    check(!service.shouldHideActivityLaunch(null, BuildConfig.APP_PACKAGE_NAME, 0))
    check(!service.shouldHide("example.unconfigured", "example.unconfigured", 0))
    println("PASS: null and self queries remain unchanged")

    service.systemApps.add("example.system-app")
    check(service.shouldHide("example.system-app", BuildConfig.APP_PACKAGE_NAME, 0))
    check(!service.shouldHide("example.unconfigured", "example.other", 0))
    check(service.pms.homeLookups > 0)
    val lookups = service.pms.homeLookups
    service.shouldHide("example.unconfigured", "example.other", 0)
    check(service.pms.homeLookups == lookups)
    println("PASS: no blanket system-app exemption or unrelated launcher lookups")

    service.pms.homes[10] = listOf("example.work-launcher")
    check(!service.shouldHide("example.work-launcher", BuildConfig.APP_PACKAGE_NAME, 10))
    check(service.shouldHide("example.work-launcher", BuildConfig.APP_PACKAGE_NAME, 0))
    service.pms.homes[0] = listOf("example.replacement-launcher")
    check(service.shouldHide("example.launcher", BuildConfig.APP_PACKAGE_NAME, 0))
    check(!service.shouldHide("example.replacement-launcher", BuildConfig.APP_PACKAGE_NAME, 0))
    println("PASS: launcher exemptions track user profiles and changes")

    service.pms.failHomeLookup = true
    check(!service.shouldHide("example.unconfigured", BuildConfig.APP_PACKAGE_NAME, 0))
    check(!service.shouldHideActivityLaunch("example.unconfigured", BuildConfig.APP_PACKAGE_NAME, 0))
    service.pms.failHomeLookup = false
    println("PASS: framework lookup failures keep manager accessible")

    val appConfig = AppConfig()
    service.config.scope["example.configured"] = appConfig
    check(service.shouldHide("example.configured", BuildConfig.APP_PACKAGE_NAME, 0))
    check(!service.shouldHide("example.configured", "example.other", 0))
    appConfig.extraAppList.add("example.other")
    check(service.shouldHide("example.configured", "example.other", 0))
    check(service.shouldHideActivityLaunch("example.configured", "example.other", 0))
    service.config.disableActivityLaunchProtection = true
    check(!service.shouldHideActivityLaunch("example.configured", "example.other", 0))
    appConfig.invertActivityLaunchProtection = true
    check(service.shouldHideActivityLaunch("example.configured", "example.other", 0))
    check(service.shouldHideActivityLaunch("example.unconfigured", BuildConfig.APP_PACKAGE_NAME, 0))
    println("PASS: legacy blacklist and activity toggles are preserved for other packages")

    appConfig.useWhitelist = true
    appConfig.extraAppList.clear()
    appConfig.extraAppList.add("example.allowed")
    check(!service.shouldHide("example.configured", "example.allowed", 0))
    check(service.shouldHide("example.configured", "example.other", 0))
    check(service.shouldHide("example.configured", BuildConfig.APP_PACKAGE_NAME, 0))
    appConfig.extraAppList.add(BuildConfig.APP_PACKAGE_NAME)
    check(service.shouldHide("example.configured", BuildConfig.APP_PACKAGE_NAME, 0))
    service.config.scope.clear()
    service.config.disableActivityLaunchProtection = false
    println("PASS: whitelist remains intact without overriding manager default")

    service.pms.callers[10125] = listOf("example.unconfigured", "example.second")
    val mutableInput = mutableListOf(manager, other)
    check(ActivityHookFixture(service).filter(10125, mutableInput) == listOf(other))
    check(mutableInput == listOf(other))
    check(service.counts["example.unconfigured"] == 4)
    println("PASS: shared-UID activity filtering, mutable lists and filter counters")

    service.pms.callers[10126] = listOf("example.replacement-launcher")
    check(ActivityHookFixture(service).filter(10126, listOf(manager, other)) == listOf(manager, other))
    check(ActivityHookFixture(service).filter(Constants.UID_SYSTEM, listOf(manager)) == listOf(manager))
    check(ActivityHookFixture(service).filter(10127, listOf(manager)) == listOf(manager))
    check(ActivityHookFixture(service).filter(10123, emptyList()).isEmpty())
    println("PASS: launcher, system UID, unknown callers and empty resolution results")

    val legacyPms = PmsHookFixture(service)
    legacyPms.dataHolder.cachedHidden = true
    check(legacyPms.query(10123, "example.other") == null)
    check(legacyPms.query(Constants.UID_SYSTEM, BuildConfig.APP_PACKAGE_NAME) == "visible")
    check(legacyPms.query(10127, BuildConfig.APP_PACKAGE_NAME) == "visible")
    println("PASS: legacy package cache and system/unknown UID behavior")

    service.config.scope["example.first"] = AppConfig()
    service.config.scope["example.second"] = AppConfig().apply { extraAppList.add("example.other") }
    service.pms.callers[10128] = listOf("example.first", "example.second")
    check(ActivityHookFixture(service).filter(10128, listOf(other)) == listOf(other)) {
        "Other packages must retain the first configured shared-UID caller behavior"
    }
    println("PASS: unrelated shared-UID resolution behavior is unchanged")
}
