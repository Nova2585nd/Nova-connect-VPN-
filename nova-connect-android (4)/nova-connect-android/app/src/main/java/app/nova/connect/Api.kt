package app.nova.connect

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL

class ApiError(val code: Int, msg: String) : Exception(msg)
class UserInfo(val total: Double, val used: Double, val remaining: Double, val exhausted: Boolean)
class Registered(val code: Int, val user: UserInfo)
class Ann(val id: Int, val text: String, val createdAt: String)
class UpdateInfo(val available: Boolean, val mandatory: Boolean, val name: String, val code: Int, val link: String, val changelog: String)

// همه درخواست‌ها فقط به بک‌اند Nova می‌روند؛ برنامه از VPN مستثنا است و این درخواست‌ها مستقیم انجام می‌شوند
object Api {
    const val BASE = "https://nova2585nd.ir/api"

    private fun call(method: String, path: String, body: JSONObject? = null): String {
        val c = URL(BASE + path).openConnection() as HttpURLConnection
        try {
            c.requestMethod = method; c.connectTimeout = 10000; c.readTimeout = 10000
            c.setRequestProperty("Accept", "application/json"); c.setRequestProperty("User-Agent", "NovaConnect-Android")
            if (body != null) { c.doOutput = true; c.setRequestProperty("Content-Type", "application/json"); c.outputStream.use { it.write(body.toString().toByteArray()) } }
            val code = c.responseCode
            val text = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
            if (code !in 200..299) {
                val d = try { JSONObject(text).optString("detail") } catch (e: Exception) { "" }
                throw ApiError(code, if (d.isNotEmpty() && !d.startsWith("[")) d else "خطای سرور ($code)")
            }
            return text
        } catch (e: ApiError) { throw e
        } catch (e: SocketTimeoutException) { throw ApiError(0, "زمان پاسخ سرور تمام شد.")
        } catch (e: Exception) { throw ApiError(0, "اتصال به سرور برقرار نشد.")
        } finally { c.disconnect() }
    }

    private fun toUser(j: JSONObject): UserInfo {
        val t = j.optDouble("total_gig", 0.0); val u = j.optDouble("used_gig", 0.0)
        return UserInfo(t, u, j.optDouble("remaining_gig", maxOf(0.0, t - u)), j.optBoolean("exhausted", u >= t))
    }

    // ثبت‌نام خودکار: بدون ورودی؛ کد ۶ رقمی یکتا و حجم اولیه را «سرور» می‌سازد (اپ هرگز کد نمی‌سازد)
    fun register(): Registered {
        val j = JSONObject(call("POST", "/users/", JSONObject()))
        val c = j.optInt("subscription_code", 0)
        if (c < 100000 || c > 999999) throw ApiError(0, "پاسخ سرور برای ساخت حساب نامعتبر بود.")
        return Registered(c, toUser(j))
    }

    // کد دعوت = شناسه‌ی دعوت‌کننده؛ سرور X گیگ هدیه را به گیگ کل او اضافه می‌کند
    fun invite(inviterCode: Int) { call("POST", "/users/invite/$inviterCode", JSONObject()) }
    fun inviteBonus(): Double? {
        val j = JSONObject(call("GET", "/settings/invite-bonus"))
        for (k in listOf("gig", "invite_bonus", "value")) if (j.has(k)) return j.optDouble(k)
        return null
    }

    fun user(code: Int): UserInfo = toUser(JSONObject(call("GET", "/users/$code")))

    // total_bytes تجمعی است؛ تکرار یک گزارش چیزی را دوبار حساب نمی‌کند
    fun report(code: Int, deviceId: String, totalBytes: Long): UserInfo =
        toUser(JSONObject(call("POST", "/usage/report", JSONObject().put("code", code).put("device_id", deviceId).put("total_bytes", totalBytes))))

    fun subscriptions(): List<String> {
        val a = JSONArray(call("GET", "/subscriptions/")); val out = ArrayList<String>()
        for (i in 0 until a.length()) { val l = a.getJSONObject(i).optString("link"); if (l.isNotEmpty()) out.add(l) }
        return out
    }

    fun announcements(): List<Ann> {
        val a = JSONArray(call("GET", "/announcements/")); val out = ArrayList<Ann>()
        for (i in 0 until a.length()) { val o = a.getJSONObject(i); out.add(Ann(o.optInt("id"), o.optString("text"), o.optString("created_at"))) }
        return out
    }

    fun priceUnit(): Int = JSONObject(call("GET", "/settings/price")).optInt("price_per_gig", 0)

    fun checkVersion(current: Int): UpdateInfo {
        val j = JSONObject(call("GET", "/versions/check?current_code=$current"))
        val l = j.optJSONObject("latest")
        return UpdateInfo(j.optBoolean("update_available"), j.optBoolean("mandatory"), l?.optString("version_name") ?: "", l?.optInt("version_code") ?: 0,
            l?.optString("download_link") ?: "", l?.optString("changelog") ?: "")
    }
}
