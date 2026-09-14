package com.jambus.heji

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** Owns a sync run independently of a settings or editor Activity. */
class BackgroundSyncService : Service() {
    private val executor = Executors.newSingleThreadExecutor()
    private val cancellation = AtomicBoolean(false)
    private lateinit var stateStore: SyncTaskStateStore
    private lateinit var notifications: NotificationManager

    override fun onCreate() {
        super.onCreate()
        active = true
        stateStore = SyncTaskStateStore(this)
        stateStore.markInterruptedIfRunning()
        notifications = getSystemService(NotificationManager::class.java)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CANCEL -> {
                cancellation.set(true)
                stateStore.requestCancellation()?.let(::showOngoingNotification)
            }
            ACTION_START_GOOGLE_DRIVE -> startGoogleDriveSync(startId, intent)
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        active = false
        cancellation.set(true)
        executor.shutdownNow()
        super.onDestroy()
    }

    private fun startGoogleDriveSync(startId: Int, intent: Intent) {
        val rootId = intent.getStringExtra(EXTRA_ROOT_ID)
        val rootName = intent.getStringExtra(EXTRA_ROOT_NAME)
        val vault = intent.getStringExtra(EXTRA_VAULT_URI)?.let(android.net.Uri::parse)
        if (rootId == null || rootName == null || vault == null) {
            recordStartFailure("尚未选择 Google Drive Vault")
            stopSelf(startId)
            return
        }
        val lease = VaultMutationLease.tryAcquire(vault.toString(), VaultMutationLease.Kind.SYNC)
        if (lease == null) {
            recordStartFailure("当前 Vault 正在移动文件，请完成后重试同步")
            stopSelf(startId)
            return
        }
        val root = DriveVaultRoot(rootId, rootName)
        val started = stateStore.begin(GOOGLE_DRIVE_PROVIDER, "Google Drive", root.name, vault.toString())
        if (started == null) {
            VaultMutationLease.release(lease)
            stateStore.snapshot()?.let(::showOngoingNotification)
            return
        }
        cancellation.set(false)
        showOngoingNotification(started)
        executor.execute {
            try {
                val result = runGoogleDriveSync(root, vault)
                val final = stateStore.finish(result) ?: return@execute
                if (final.status == SyncTaskStatus.SUCCEEDED) DriveSyncPreferences(this).markSuccessful(vault.toString(), root.id, authAccountId())
                stopForeground(false)
                showFinishedNotification(final)
                stopSelf()
            } finally {
                VaultMutationLease.release(lease)
            }
        }
    }

    private fun runGoogleDriveSync(root: DriveVaultRoot, vault: android.net.Uri): DriveSyncResult = try {
        val auth = GoogleDriveAuth(this)
        val account = auth.currentAccount()
        if (!auth.isAuthorized(account) || account == null) {
            DriveSyncResult(0, 0, 0, 0, listOf("Google 账号需要重新登录"), false)
        } else {
            GoogleDriveSyncService(
                VaultRepository(this, vault),
                GoogleDriveApi(auth.accessToken(account)),
                vault.toString(),
                account.email.orEmpty(),
                LocalChangeJournal(this),
                LocalDriveSyncBaselineStore(this),
                cancellation
            ) { progress ->
                stateStore.updateProgress(progress)?.let(::showOngoingNotification)
            }.sync(root)
        }
    } catch (_: Exception) {
        DriveSyncResult(0, 0, 0, 0, listOf("无法连接 Google Drive，请检查网络或重新登录"), false)
    }

    private fun authAccountId(): String = GoogleDriveAuth(this).currentAccount()?.email.orEmpty()

    private fun recordStartFailure(message: String) {
        val started = stateStore.begin(GOOGLE_DRIVE_PROVIDER, "Google Drive", "未选择远端 Vault") ?: return
        val final = stateStore.finish(DriveSyncResult(0, 0, 0, 0, listOf(message), false)) ?: started
        showFinishedNotification(final)
    }

    private fun showOngoingNotification(snapshot: SyncTaskSnapshot) {
        startForeground(NOTIFICATION_ID, notification(snapshot, ongoing = true))
    }

    private fun showFinishedNotification(snapshot: SyncTaskSnapshot) {
        notifications.notify(NOTIFICATION_ID, notification(snapshot, ongoing = false))
    }

    private fun notification(snapshot: SyncTaskSnapshot, ongoing: Boolean) = android.app.Notification.Builder(this, CHANNEL_ID)
        .setSmallIcon(R.drawable.app_icon)
        .setContentTitle(getString(R.string.sync_notification_title, snapshot.providerName))
        .setContentText(if (ongoing) snapshot.statusLabel(this) else snapshot.messageLabel(this))
        .setStyle(android.app.Notification.BigTextStyle().bigText(notificationDetail(snapshot)))
        .setContentIntent(PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        ))
        .setOngoing(ongoing)
        .setAutoCancel(!ongoing)
        .build()

    private fun notificationDetail(snapshot: SyncTaskSnapshot): String = buildString {
        append(snapshot.statusLabel(this@BackgroundSyncService))
        if (!snapshot.isRunning) {
            append('\n').append(getString(
                R.string.sync_notification_detail,
                snapshot.summary.uploaded,
                snapshot.summary.downloaded,
                snapshot.summary.conflicts
            ))
            if (snapshot.errorCount > 0) append("\n").append(UiText.label(this@BackgroundSyncService, "请在应用内查看失败详情"))
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) createChannel()
    }

    private fun createChannel() {
        notifications.createNotificationChannel(NotificationChannel(
            CHANNEL_ID,
            UiText.label(this, "同步状态"),
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply { description = if (UiLanguage.locale(this@BackgroundSyncService).language == "zh") "禾记后台同步的进度、完成和错误通知" else "Heji background sync progress, completion, and error notifications" })
    }

    companion object {
        private const val GOOGLE_DRIVE_PROVIDER = "google_drive"
        private const val CHANNEL_ID = "heji_notes_sync_status"
        private const val NOTIFICATION_ID = 2301
        private const val ACTION_START_GOOGLE_DRIVE = "com.jambus.heji.action.START_GOOGLE_DRIVE_SYNC"
        private const val ACTION_CANCEL = "com.jambus.heji.action.CANCEL_SYNC"
        private const val EXTRA_ROOT_ID = "root_id"
        private const val EXTRA_ROOT_NAME = "root_name"
        private const val EXTRA_VAULT_URI = "vault_uri"

        fun startGoogleDrive(context: Context, root: DriveVaultRoot, vaultUri: String) {
            val intent = Intent(context, BackgroundSyncService::class.java).setAction(ACTION_START_GOOGLE_DRIVE)
                .putExtra(EXTRA_ROOT_ID, root.id)
                .putExtra(EXTRA_ROOT_NAME, root.name)
                .putExtra(EXTRA_VAULT_URI, vaultUri)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent) else context.startService(intent)
        }

        fun cancel(context: Context) {
            context.startService(Intent(context, BackgroundSyncService::class.java).setAction(ACTION_CANCEL))
        }

        /** Used only at app startup to distinguish a live foreground service from stale persisted state. */
        fun isActive(): Boolean = active

        @Volatile private var active = false
    }
}
