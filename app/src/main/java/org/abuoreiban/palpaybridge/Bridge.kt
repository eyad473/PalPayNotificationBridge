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
import java.math.BigDecimal
import android.text.InputType
import java.security.MessageDigest
import java.util.concurrent.Executors

private const val PREF="bridge_prefs"
private const val ENDPOINT="https://moood.eyad2000515.workers.dev/palpay/ingest"
private val executor=Executors.newSingleThreadExecutor()

class MainActivity: Activity() {
 private lateinit var web:android.webkit.WebView
 private val bridge=object {
  @android.webkit.JavascriptInterface fun status():String {
   val enabled=android.provider.Settings.Secure.getString(contentResolver,"enabled_notification_listeners").orEmpty()
   val active=enabled.contains(packageName)
   return JSONObject().put("enabled",active).put("pending",QueueStore.count(this@MainActivity))
    .put("events",EventLog.read(this@MainActivity))
    .put("configured",!getSharedPreferences(PREF,MODE_PRIVATE).getString("token","").isNullOrBlank()).toString()
  }
  @android.webkit.JavascriptInterface fun saveToken(token:String){
   getSharedPreferences(PREF,MODE_PRIVATE).edit().putString("token",token.trim()).apply()
   runOnUiThread{refresh()}
  }
  @android.webkit.JavascriptInterface fun permission(){
   runOnUiThread{startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))}
  }
  @android.webkit.JavascriptInterface fun retry(){Sender.flush(this@MainActivity);runOnUiThread{refresh()}}
  @android.webkit.JavascriptInterface fun refresh(){runOnUiThread{
   web.evaluateJavascript("window.updateStatus("+status()+")",null)
  }}
  @android.webkit.JavascriptInterface fun chooseScreenshot(){runOnUiThread{
   val intent=Intent(Intent.ACTION_GET_CONTENT).apply{type="image/*";addCategory(Intent.CATEGORY_OPENABLE)}
   startActivityForResult(Intent.createChooser(intent,"اختيار لقطة الحوالة"),81)
  }}
 }
 override fun onCreate(savedInstanceState:Bundle?){
  super.onCreate(savedInstanceState)
  window.statusBarColor=android.graphics.Color.rgb(12,37,61)
  window.navigationBarColor=android.graphics.Color.rgb(12,37,61)
  web=android.webkit.WebView(this).apply{
   settings.javaScriptEnabled=true
   settings.allowFileAccess=false
   settings.allowContentAccess=false
   settings.domStorageEnabled=false
   addJavascriptInterface(bridge,"Bridge")
   webViewClient=object:android.webkit.WebViewClient(){
    override fun shouldOverrideUrlLoading(v:android.webkit.WebView?,r:android.webkit.WebResourceRequest?):Boolean=true
   }
  }
  setContentView(web)
  web.loadUrl("file:///android_asset/dashboard.html")
 }
 override fun onResume(){super.onResume();if(::web.isInitialized)web.postDelayed({bridge.refresh()},400)}
 override fun onActivityResult(requestCode:Int,resultCode:Int,data:Intent?){
  super.onActivityResult(requestCode,resultCode,data)
  if(requestCode==81&&resultCode==RESULT_OK){
   val selected=data?.data?.lastPathSegment.orEmpty().take(100)
   web.evaluateJavascript("window.screenshotChosen("+JSONObject.quote(selected)+")",null)
  }
 }
}

class PalPayListener:NotificationListenerService(){
 override fun onNotificationPosted(sbn:StatusBarNotification){
  val p=getSharedPreferences(PREF,MODE_PRIVATE)
  if(sbn.packageName!="com.pcnc.wallet")return
  val extras=sbn.notification.extras
  val title=extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
  val body=extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()
   ?:extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
  val combined="$title\n$body"
  // Fail closed: ambiguous or outgoing transactions are not transmitted.
  val parsed=Parser.parse(combined)
  if(parsed==null){EventLog.add(this,"تم تجاهل إشعار PalPay: صيغة غير مطابقة أو غير واضحة");return}
  EventLog.add(this,"تم التقاط تحويل مرشح للمراجعة")
  val digest=MessageDigest.getInstance("SHA-256").digest("${sbn.packageName}|${sbn.key}|${sbn.postTime}|$combined".toByteArray())
  val id="ntf_"+digest.joinToString(""){ "%02x".format(it) }.take(40)
  val json=JSONObject().put("eventId",id).put("senderName",parsed.first).put("amountAgorot",parsed.second)
   .put("currency","ILS").put("direction","unknown").put("notificationTime",sbn.postTime)
  QueueStore.add(this,id,json.toString());EventLog.add(this,"أُضيف إشعار إلى قائمة الإرسال");Sender.flush(this)
 }
}

object Parser {
 fun parse(text:String):Pair<String,Int>? {
  val t=text.replace('٫','.').replace('،',',').replace('：',':')
  val heading=Regex("الدفع\\s+لصديق").containsMatchIn(t)
  val pattern=Regex("تحويل\\s+دفع\\s+لصديق\\s*[:：]\\s*([^,،\\n]{3,100})\\s*[,،]\\s*بمبلغ",RegexOption.IGNORE_CASE)
  if(!heading)return null
  val sender=pattern.find(t)?.groupValues?.get(1)?.trim()?:return null
  if(sender.any{it.isDigit()} || sender.length>100)return null
  val match=Regex("(?:ILS|NIS|₪)\\s*([0-9]+(?:\\.[0-9]{1,2})?)|([0-9]+(?:\\.[0-9]{1,2})?)\\s*(?:ILS|NIS|₪)",RegexOption.IGNORE_CASE).find(t)?:return null
  val number=match.groupValues[1].ifBlank{match.groupValues[2]}
  val cents=runCatching{BigDecimal(number).movePointRight(2).intValueExact()}.getOrNull()?:return null
  if(cents<100||cents>10000000)return null
  return sender to cents
 }
}

object EventLog {
 private const val KEY="events"
 fun add(c:Context,message:String){
  val p=c.getSharedPreferences("palpay_diagnostics",Context.MODE_PRIVATE)
  val stamp=java.text.SimpleDateFormat("HH:mm:ss",java.util.Locale.ROOT).format(java.util.Date())
  val old=p.getString(KEY,"").orEmpty().lines().filter{it.isNotBlank()}.take(14)
  p.edit().putString(KEY, (listOf("$stamp - $message")+old).joinToString("\n")).apply()
 }
 fun read(c:Context)=c.getSharedPreferences("palpay_diagnostics",Context.MODE_PRIVATE).getString(KEY,"لا توجد أحداث بعد").orEmpty()
}

object QueueStore {
 private fun pref(c:Context)=c.getSharedPreferences("palpay_queue",Context.MODE_PRIVATE)
 @Synchronized fun add(c:Context,id:String,json:String){val p=pref(c);if(!p.contains(id))p.edit().putString(id,json).apply()}
 @Synchronized fun all(c:Context)=pref(c).all.mapNotNull{(k,v)->if(v is String)k to v else null}
 @Synchronized fun remove(c:Context,id:String){pref(c).edit().remove(id).apply()}
 fun count(c:Context)=pref(c).all.size
}
object Sender {
 fun flush(c:Context){val app=c.applicationContext;executor.execute{
  val token=app.getSharedPreferences(PREF,Context.MODE_PRIVATE).getString("token","").orEmpty()
  if(token.isBlank()){EventLog.add(app,"تعذر الإرسال: رمز الربط غير محفوظ");return@execute}
  for((id,json) in QueueStore.all(app))try{
   val conn=(URL(ENDPOINT).openConnection() as HttpURLConnection).apply{
    requestMethod="POST";connectTimeout=12000;readTimeout=12000;doOutput=true
    setRequestProperty("Content-Type","application/json");setRequestProperty("X-PalPay-Token",token)
   }
   conn.outputStream.use{it.write(json.toByteArray(Charsets.UTF_8))}
   val code=conn.responseCode;conn.disconnect()
   if(code in 200..299){QueueStore.remove(app,id);EventLog.add(app,"نجح تسليم إشعار للخادم ($code)")}
   else EventLog.add(app,"فشل إرسال إشعار: استجابة الخادم $code")
  }catch(_:Exception){EventLog.add(app,"فشل الاتصال بالخادم؛ سيُعاد المحاولة")}
  RetryReceiver.schedule(app)
 }}
}
class RetryReceiver: android.content.BroadcastReceiver(){
 override fun onReceive(context:Context,intent:Intent){Sender.flush(context)}
 companion object{
  fun schedule(c:Context){val am=c.getSystemService(Context.ALARM_SERVICE) as AlarmManager
   val pi=PendingIntent.getBroadcast(c,1,Intent(c,RetryReceiver::class.java),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
   am.set(AlarmManager.ELAPSED_REALTIME,android.os.SystemClock.elapsedRealtime()+15*60*1000,pi)
  }
 }
}
