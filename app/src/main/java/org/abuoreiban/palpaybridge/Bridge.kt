
package org.abuoreiban.palpaybridge

import android.app.*
import android.content.*
import android.os.*
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.widget.*
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.math.BigDecimal
import java.util.concurrent.Executors

private const val PREF = "bridge_prefs"
private const val PALPAY_PACKAGE = "com.pcnc.wallet"
private const val ENDPOINT =
    "https://moood.eyad2000515.workers.dev/palpay/ingest"

private val executor = Executors.newSingleThreadExecutor()

class MainActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        render()
    }

    private fun render() {
        val p = getSharedPreferences(PREF, MODE_PRIVATE)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 35, 32, 20)
        }

        val title = TextView(this).apply {
            text = "مراقبة تحويلات PalPay الواردة — مخيم أبو عريبان"
            textSize = 20f
        }
        root.addView(title)

        val packageInfo = TextView(this).apply {
            text = "تطبيق PalPay المحدد: $PALPAY_PACKAGE"
            textSize = 16f
        }
        root.addView(packageInfo)

        val token = EditText(this).apply {
            hint = "رمز الربط السري"
            setSingleLine(true)
            inputType = 129
            setText(p.getString("token", "") ?: "")
        }
        root.addView(token)

        val info = TextView(this).apply {
            text = """
                المعلّق محلياً: ${QueueStore.count(this@MainActivity)}

                تتم معالجة تحويلات PalPay الواردة فقط.
                التحويلات تُرسل للمراجعة ولا تؤكد السداد.
            """.trimIndent()
        }
        root.addView(info)

        fun button(label: String, action: () -> Unit) {
            val b = Button(this)
            b.text = label
            b.setOnClickListener { action() }
            root.addView(b)
        }

        button("حفظ الإعدادات") {
            p.edit()
                .remove("candidate")
                .remove("package")
                .putString("token", token.text.toString().trim())
                .apply()

            Toast.makeText(
                this,
                "تم حفظ الإعدادات",
                Toast.LENGTH_SHORT
            ).show()
        }

        button("فتح صلاحية قراءة الإشعارات") {
            startActivity(
                Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
            )
        }

        button("إعادة إرسال الإشعارات المعلقة") {
            Sender.flush(this)
            Toast.makeText(
                this,
                "بدأت محاولة الإرسال",
                Toast.LENGTH_SHORT
            ).show()
        }

        button("تحديث الحالة") {
            render()
        }

        val scroll = ScrollView(this)
        scroll.addView(root)
        setContentView(scroll)
    }
}

class PalPayListener : NotificationListenerService() {

    override fun onNotificationPosted(sbn: StatusBarNotification) {

        // Ignore all other apps before accessing notification content.
        if (sbn.packageName != PALPAY_PACKAGE) return

        val extras = sbn.notification.extras

        val title = extras
            .getCharSequence(Notification.EXTRA_TITLE)
            ?.toString()
            .orEmpty()

        val body = extras
            .getCharSequence(Notification.EXTRA_BIG_TEXT)
            ?.toString()
            ?: extras
                .getCharSequence(Notification.EXTRA_TEXT)
                ?.toString()
                .orEmpty()

        val combined = "$title\n$body"

        // Reject outgoing or ambiguous notifications.
        val parsed = Parser.parse(combined) ?: return

        val rawId =
            "${sbn.packageName}|${sbn.key}|${sbn.postTime}|$combined"

        val digest = MessageDigest
            .getInstance("SHA-256")
            .digest(rawId.toByteArray())

        val id = "ntf_" + digest
            .joinToString("") { "%02x".format(it) }
            .take(40)

        val json = JSONObject()
            .put("eventId", id)
            .put("senderName", parsed.first)
            .put("amountAgorot", parsed.second)
            .put("currency", "ILS")
            .put("direction", "incoming")
            .put("notificationTime", sbn.postTime)

        QueueStore.add(this, id, json.toString())
        Sender.flush(this)
    }
}

object Parser {

    fun parse(text: String): Pair<String, Int>? {

        val t = text
            .replace('٫', '.')
            .replace('،', ',')

        val incoming = Regex(
            "(استلمت|استقبال|تم استلام|تم استقبال|وصلتك|received|you received|incoming|تحويل وارد)",
            RegexOption.IGNORE_CASE
        ).containsMatchIn(t)

        val outgoing = Regex(
            "(أرسلت|تم إرسال|تم تحويل منك|sent to|you sent|outgoing)",
            RegexOption.IGNORE_CASE
        ).containsMatchIn(t)

        if (!incoming || outgoing) return null

        val amount = Regex(
            "(?:ILS|NIS|₪|شيكل|شواكل)\\s*([0-9]+(?:\\.[0-9]{1,2})?)|([0-9]+(?:\\.[0-9]{1,2})?)\\s*(?:ILS|NIS|₪|شيكل|شواكل)",
            RegexOption.IGNORE_CASE
        ).find(t) ?: return null

        val amountText = amount.groupValues[1]
            .ifBlank { amount.groupValues[2] }

        val agorot = try {
            BigDecimal(amountText)
                .movePointRight(2)
                .intValueExact()
        } catch (_: Exception) {
            return null
        }

        if (agorot < 100 || agorot > 10000000) return null

        val sender = Regex(
            "(?:من|from)\\s+([^\\n،,:]{3,90})",
            RegexOption.IGNORE_CASE
        ).find(t)
            ?.groupValues
            ?.get(1)
            ?.trim()
            ?: return null

        if (sender.contains(Regex("\\d"))) return null

        return sender to agorot
    }
}

object QueueStore {

    private fun pref(c: Context) =
        c.getSharedPreferences(
            "palpay_queue",
            Context.MODE_PRIVATE
        )

    @Synchronized
    fun add(c: Context, id: String, json: String) {
        val p = pref(c)
        if (!p.contains(id)) {
            p.edit().putString(id, json).apply()
        }
    }

    @Synchronized
    fun all(c: Context): List<Pair<String, String>> =
        pref(c).all.mapNotNull { (k, v) ->
            if (v is String) k to v else null
        }

    @Synchronized
    fun remove(c: Context, id: String) {
        pref(c).edit().remove(id).apply()
    }

    fun count(c: Context) = pref(c).all.size
}

object Sender {

    fun flush(c: Context) {

        val app = c.applicationContext

        executor.execute {

            val token = app
                .getSharedPreferences(PREF, Context.MODE_PRIVATE)
                .getString("token", "")
                .orEmpty()

            if (token.isBlank()) return@execute

            for ((id, json) in QueueStore.all(app)) {

                try {
                    val conn =
                        URL(ENDPOINT).openConnection() as HttpURLConnection

                    conn.requestMethod = "POST"
                    conn.connectTimeout = 12000
                    conn.readTimeout = 12000
                    conn.doOutput = true

                    conn.setRequestProperty(
                        "Content-Type",
                        "application/json"
                    )

                    conn.setRequestProperty(
                        "X-PalPay-Token",
                        token
                    )

                    conn.outputStream.use {
                        it.write(json.toByteArray(Charsets.UTF_8))
                    }

                    val code = conn.responseCode
                    conn.disconnect()

                    if (code in 200..299) {
                        QueueStore.remove(app, id)
                    }

                } catch (_: Exception) {
                    // Keep queued notification for a later retry.
                }
            }

            RetryReceiver.schedule(app)
        }
    }
}

class RetryReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        Sender.flush(context)
    }

    companion object {

        fun schedule(c: Context) {

            val am = c.getSystemService(
                Context.ALARM_SERVICE
            ) as AlarmManager

            val pi = PendingIntent.getBroadcast(
                c,
                1,
                Intent(c, RetryReceiver::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or
                    PendingIntent.FLAG_IMMUTABLE
            )

            am.set(
                AlarmManager.ELAPSED_REALTIME,
                SystemClock.elapsedRealtime() + 15 * 60 * 1000,
                pi
            )
        }
    }
}
