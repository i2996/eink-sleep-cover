package io.github.i2996.sleepcover

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import org.json.JSONObject
import java.io.IOException

/**
 * GitHub의 "latest" 릴리스에서 새 버전을 확인하고, 받아서 설치 화면을 띄운다.
 * (안드로이드 정책상 마지막 "설치" 확인은 사용자가 눌러야 한다.)
 */
object Updater {
    private const val RELEASE_API = "https://api.github.com/repos/i2996/eink-sleep-cover/releases/latest"
    private const val APK_URL = "https://github.com/i2996/eink-sleep-cover/releases/download/latest/eink-sleep-cover.apk"

    fun currentVersion(ctx: Context): Long {
        val info = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
        return if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else @Suppress("DEPRECATION") info.versionCode.toLong()
    }

    /** 릴리스 제목 "최신 빌드 #N" 의 N. 확인 실패 시 예외. */
    fun latestVersion(): Long {
        val json = JSONObject(Http.text(RELEASE_API))
        val name = json.optString("name")
        return Regex("""#(\d+)""").find(name)?.groupValues?.get(1)?.toLong()
            ?: throw IOException("버전 정보를 읽지 못했어요")
    }

    /** 새 버전 번호, 없으면 null. */
    fun newerVersion(ctx: Context): Long? {
        val latest = latestVersion()
        return if (latest > currentVersion(ctx)) latest else null
    }

    fun canInstall(ctx: Context) =
        Build.VERSION.SDK_INT < 26 || ctx.packageManager.canRequestPackageInstalls()

    fun openInstallPermission(ctx: Context) {
        if (Build.VERSION.SDK_INT >= 26) {
            ctx.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    /** APK를 받아 설치를 시작한다 (백그라운드 스레드에서 호출). */
    fun downloadAndInstall(ctx: Context) {
        val bytes = Http.bytes(APK_URL)
        val installer = ctx.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        val id = installer.createSession(params)
        installer.openSession(id).use { session ->
            session.openWrite("update.apk", 0, bytes.size.toLong()).use {
                it.write(bytes)
                session.fsync(it)
            }
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
            val pending = PendingIntent.getBroadcast(
                ctx, id, Intent(ctx, UpdateReceiver::class.java), flags
            )
            session.commit(pending.intentSender)
        }
    }
}

/** 설치 진행 결과를 받아, 사용자 확인이 필요하면 설치 화면을 띄운다. */
class UpdateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT) ?: return
                context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            PackageInstaller.STATUS_SUCCESS -> {}
            else -> {
                val msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "알 수 없는 오류"
                Toast.makeText(context, "업데이트 실패: $msg", Toast.LENGTH_LONG).show()
            }
        }
    }
}
