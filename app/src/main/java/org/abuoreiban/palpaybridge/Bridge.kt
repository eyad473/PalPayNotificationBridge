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
 override fun onCreate(savedInstanceState:Bundle?){super.onCreate(savedInstanceState);render()}
 private fun render(){
  val p=getSharedPreferences(PREF,MODE_PRIVATE)
  val root=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(32,35,32,20)}
  val title=TextView(this).apply{text="مراقبة إشعارات PalPay — مخيم أبو عريبان";textSize=20f};root.addView(title)
  fun field(hint:String, value:String):EditText{val e=EditText(this);e.hint=hint;e.setSingleLine(true);e.setText(value);root.addView(e);return e}
  val pkg= "com.pcnc.wallet"
  root.addView(TextView(this).apply{text="تطبيق PalPay المحدد: $pkg";textSize=16f})
  val token=field("رمز الربط السري",p.getString("token","")?:"");token.inputType=InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
  val info=TextView(this).apply{text="المعلّق محلياً: ${QueueStore.count(this@MainActivity)}\nتتم معالجة تحويلات PalPay الواردة فقط.\nالتحويلات تُرسل للمراجعة ولا تؤكد السداد."};root.addView(info)
  root.addView(TextView(this).apply{text="سجل آخر الأحداث (بدون أسماء أو مبالغ):\n${EventLog.read(this@MainActivity)}";textSize=14f})
  fun btn(label:String,action:()->Unit){val b=Button(this);b.text=label;b.setOnClickListener{action()};root.addView(b)}
  btn("حفظ الإعدادات") {p.edit().putString("token",token.text.toString().trim()).apply();Toast.makeText(this,"تم الحفظ",Toast.LENGTH_SHORT).show()}
  btn("فتح صلاحية قراءة الإشعارات") {startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))}
  btn("إعادة إرسال الإشعارات المعلقة") {Sender.flush(this);Toast.makeText(this,"بدأت محاولة الإرسال",Toast.LENGTH_SHORT).show()}
  btn("تحديث الحالة") {render()}
  val scroll=ScrollView(this);scroll.addView(root);setContentView(scroll)
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
