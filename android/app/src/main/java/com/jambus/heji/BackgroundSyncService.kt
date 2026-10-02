package com.jambus.heji

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

internal data class DriveSyncStartRequest(
    val vaultId: String,
    val accountId: String,
    val root: DriveVaultRoot
)

internal data class DriveSyncStartContext(
    val currentVaultId: String?,
    val binding: DriveVaultBinding?,
    val currentAccountId: String?,
    val authorized: Boolean
)

internal enum class DriveSyncStartRejection {
    MISSING_SELECTION,
    VAULT_MISMATCH,
    MISSING_BINDING,
    BINDING_ACCOUNT_MISMATCH,
    ROOT_ID_MISMATCH,
    ROOT_NAME_MISMATCH,
    CURRENT_ACCOUNT_MISMATCH,
    UNAUTHORIZED
}

internal sealed class DriveSyncStartDecision {
    object Accepted : DriveSyncStartDecision()
    data class Rejected(val reason: DriveSyncStartRejection) : DriveSyncStartDecision()
}

internal object DriveSyncStartPolicy {
    fun validate(request: DriveSyncStartRequest?, context: DriveSyncStartContext): DriveSyncStartDecision {
        if (request == null || request.vaultId.isBlank() || request.accountId.isBlank() || request.root.id.isBlank()) {
            return DriveSyncStartDecision.Rejected(DriveSyncStartRejection.MISSING_SELECTION)
        }
        if (context.currentVaultId != request.vaultId) return rejected(DriveSyncStartRejection.VAULT_MISMATCH)
        val binding = context.binding ?: return rejected(DriveSyncStartRejection.MISSING_BINDING)
        if (binding.accountId != request.accountId) return rejected(DriveSyncStartRejection.BINDING_ACCOUNT_MISMATCH)
        if (binding.root.id != request.root.id) return rejected(DriveSyncStartRejection.ROOT_ID_MISMATCH)
        if (binding.root.name != request.root.name) return rejected(DriveSyncStartRejection.ROOT_NAME_MISMATCH)
        if (context.currentAccountId != request.accountId) return rejected(DriveSyncStartRejection.CURRENT_ACCOUNT_MISMATCH)
        if (!context.authorized) return rejected(DriveSyncStartRejection.UNAUTHORIZED)
        return DriveSyncStartDecision.Accepted
    }

    private fun rejected(reason: DriveSyncStartRejection) = DriveSyncStartDecision.Rejected(reason)
}

internal data class OneDriveSyncStartContext(
    val currentVaultId: String?,
    val binding: DriveVaultBinding?
)

/** Pure tuple gate used before MSAL or Graph is touched. */
internal object OneDriveSyncStartPolicy {
    fun validate(request: DriveSyncStartRequest?, context: OneDriveSyncStartContext): DriveSyncStartDecision {
        if (request == null || request.vaultId.isBlank() || request.accountId.isBlank() || request.root.id.isBlank()) {
            return DriveSyncStartDecision.Rejected(DriveSyncStartRejection.MISSING_SELECTION)
        }
        if (context.currentVaultId != request.vaultId) return DriveSyncStartDecision.Rejected(DriveSyncStartRejection.VAULT_MISMATCH)
        val binding = context.binding ?: return DriveSyncStartDecision.Rejected(DriveSyncStartRejection.MISSING_BINDING)
        if (binding.accountId != request.accountId) return DriveSyncStartDecision.Rejected(DriveSyncStartRejection.BINDING_ACCOUNT_MISMATCH)
        if (binding.root.id != request.root.id) return DriveSyncStartDecision.Rejected(DriveSyncStartRejection.ROOT_ID_MISMATCH)
        if (binding.root.name != request.root.name) return DriveSyncStartDecision.Rejected(DriveSyncStartRejection.ROOT_NAME_MISMATCH)
        return DriveSyncStartDecision.Accepted
    }
}

/** Owns a sync run independently of a settings or editor Activity. */
class BackgroundSyncService : Service() {
    private val executor = Executors.newSingleThreadExecutor()
    private val coordinator = SyncCancellationCoordinator()
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
                if (coordinator.cancel()) {
                    stateStore.requestCancellation()?.let(::showOngoingNotification)
                }
            }
            ACTION_START_GOOGLE_DRIVE -> startGoogleDriveSync(startId, intent)
            ACTION_START_ONEDRIVE -> startOneDriveSync(startId, intent)
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        active = false
        coordinator.cancel()
        executor.shutdownNow()
        super.onDestroy()
    }

    private fun startGoogleDriveSync(startId: Int, intent: Intent) {
        if (rejectWhileRunning(intent, GOOGLE_DRIVE_PROVIDER, "Google Drive")) return
        val rootId = intent.getStringExtra(EXTRA_ROOT_ID)
        val rootName = intent.getStringExtra(EXTRA_ROOT_NAME)
        val expectedAccountId = intent.getStringExtra(EXTRA_ACCOUNT_ID)
        val vault = intent.getStringExtra(EXTRA_VAULT_URI)?.let(android.net.Uri::parse)
        val request = if (rootId != null && rootName != null && expectedAccountId != null && vault != null) {
            DriveSyncStartRequest(vault.toString(), expectedAccountId, DriveVaultRoot(rootId, rootName))
        } else null
        if (request == null) {
            recordStartFailure(GOOGLE_DRIVE_PROVIDER, "Google Drive", vault?.toString().orEmpty(), rootName.orEmpty())
            stopSelf(startId)
            return
        }
        val auth = GoogleDriveAuth(this)
        val account = auth.currentAccount()
        val decision = DriveSyncStartPolicy.validate(request, DriveSyncStartContext(
            VaultRepository(this).savedVaultUri()?.toString(),
            DriveSyncPreferences(this).binding(request.vaultId),
            account?.email,
            auth.isAuthorized(account)
        ))
        if (decision is DriveSyncStartDecision.Rejected) {
            recordStartFailure(GOOGLE_DRIVE_PROVIDER, "Google Drive", request.vaultId, request.root.name, SyncErrorReason.AUTH_REQUIRED)
            stopSelf(startId)
            return
        }
        val lease = VaultMutationLease.tryAcquire(request.vaultId, VaultMutationLease.Kind.SYNC)
        if (lease == null) {
            recordStartFailure(GOOGLE_DRIVE_PROVIDER, "Google Drive", request.vaultId, request.root.name, SyncErrorReason.SYNC_BUSY)
            stopSelf(startId)
            return
        }
        val root = request.root
        val started = stateStore.begin(GOOGLE_DRIVE_PROVIDER, "Google Drive", root.name, request.vaultId)
        if (started == null) {
            VaultMutationLease.release(lease)
            recordStartFailure(GOOGLE_DRIVE_PROVIDER, "Google Drive", request.vaultId, root.name, SyncErrorReason.SYNC_BUSY)
            return
        }
        coordinator.reset()
        showOngoingNotification(started)
        executor.execute {
            try {
                val result = runGoogleDriveSync(request)
                val final = stateStore.finish(result) ?: return@execute
                if (final.status == SyncTaskStatus.SUCCEEDED) DriveSyncPreferences(this).markSuccessful(request.vaultId, root.id, request.accountId)
                stopForeground(false)
                showFinishedNotification(final)
                stopSelf()
            } finally {
                VaultMutationLease.release(lease)
            }
        }
    }

    private fun startOneDriveSync(startId: Int, intent: Intent) {
        if (rejectWhileRunning(intent, ONEDRIVE_PROVIDER, "OneDrive")) return
        val request = syncRequest(intent)
        if (request == null) {
            recordStartFailure(ONEDRIVE_PROVIDER, "OneDrive", intent.getStringExtra(EXTRA_VAULT_URI).orEmpty(), intent.getStringExtra(EXTRA_ROOT_NAME).orEmpty())
            stopSelf(startId)
            return
        }
        val context = OneDriveSyncStartContext(
            VaultRepository(this).savedVaultUri()?.toString(),
            OneDriveSyncPreferences(this).binding(request.vaultId)
        )
        if (OneDriveSyncStartPolicy.validate(request, context) is DriveSyncStartDecision.Rejected) {
            recordStartFailure(ONEDRIVE_PROVIDER, "OneDrive", request.vaultId, request.root.name, SyncErrorReason.AUTH_REQUIRED)
            stopSelf(startId)
            return
        }
        val lease = VaultMutationLease.tryAcquire(request.vaultId, VaultMutationLease.Kind.SYNC)
        if (lease == null) {
            recordStartFailure(ONEDRIVE_PROVIDER, "OneDrive", request.vaultId, request.root.name, SyncErrorReason.SYNC_BUSY)
            stopSelf(startId)
            return
        }
        val started = stateStore.begin(ONEDRIVE_PROVIDER, "OneDrive", request.root.name, request.vaultId)
        if (started == null) {
            VaultMutationLease.release(lease)
            recordStartFailure(ONEDRIVE_PROVIDER, "OneDrive", request.vaultId, request.root.name, SyncErrorReason.SYNC_BUSY)
            return
        }
        coordinator.reset()
        showOngoingNotification(started)
        executor.execute {
            try {
                val result = runOneDriveSync(request)
                val final = stateStore.finish(result) ?: return@execute
                if (final.status == SyncTaskStatus.SUCCEEDED) {
                    OneDriveSyncPreferences(this).markSuccessful(request.vaultId, request.root.id, request.accountId)
                }
                stopForeground(false)
                showFinishedNotification(final)
                stopSelf()
            } finally {
                VaultMutationLease.release(lease)
            }
        }
    }

    private fun syncRequest(intent: Intent): DriveSyncStartRequest? {
        val rootId = intent.getStringExtra(EXTRA_ROOT_ID)
        val rootName = intent.getStringExtra(EXTRA_ROOT_NAME)
        val accountId = intent.getStringExtra(EXTRA_ACCOUNT_ID)
        val vault = intent.getStringExtra(EXTRA_VAULT_URI)?.let(android.net.Uri::parse)
        return if (rootId != null && rootName != null && accountId != null && vault != null) {
            DriveSyncStartRequest(vault.toString(), accountId, DriveVaultRoot(rootId, rootName))
        } else null
    }

    private fun runOneDriveSync(request: DriveSyncStartRequest): SyncRunResult = try {
        val currentContext = OneDriveSyncStartContext(
            VaultRepository(this).savedVaultUri()?.toString(),
            OneDriveSyncPreferences(this).binding(request.vaultId)
        )
        if (OneDriveSyncStartPolicy.validate(request, currentContext) is DriveSyncStartDecision.Rejected) {
            SyncRunResult(0, 0, 0, 0, listOf(getString(R.string.onedrive_sync_tuple_changed)), false)
        } else {
            val preferences = OneDriveSyncPreferences(this)
            val auth = OneDriveAuth(this)
            preferences.clearReloginRequired(request.vaultId)
            if (!OneDriveApi.cleanupOrphans(cacheDir)) {
                throw DriveApiException("OneDrive transfer cache cleanup failed")
            }
            RemoteDriveSyncEngine(
                VaultRepository(this, android.net.Uri.parse(request.vaultId)),
                OneDriveApi({ force -> auth.accessToken(request.accountId, force) }, cacheDir, coordinator.streamSignal),
                request.vaultId,
                request.accountId,
                LocalChangeJournal(this),
                LocalDriveSyncBaselineStore(this, ONEDRIVE_PROVIDER),
                coordinator
            ) { progress -> stateStore.updateProgress(progress)?.let(::showOngoingNotification) }
                .sync(request.root)
        }
    } catch (e: OneDriveReloginRequired) {
        Log.w(TAG, "OneDrive sync requires re-login")
        OneDriveSyncPreferences(this).markReloginRequired(request.vaultId)
        SyncRunResult(0, 0, 0, 0, listOf("AUTH_REQUIRED"), false,
            errorDetails = listOf(SyncFailurePolicy.fromException(e)))
    } catch (e: Exception) {
        Log.e(TAG, "OneDrive sync failed: ${e.javaClass.simpleName}")
        SyncRunResult(0, 0, 0, 0, listOf("SYNC_FAILED"), false,
            errorDetails = listOf(SyncFailurePolicy.fromException(e)))
    }

    private fun runGoogleDriveSync(request: DriveSyncStartRequest): DriveSyncResult = try {
        val auth = GoogleDriveAuth(this)
        val account = auth.currentAccount()
        val decision = DriveSyncStartPolicy.validate(request, DriveSyncStartContext(
            VaultRepository(this).savedVaultUri()?.toString(),
            DriveSyncPreferences(this).binding(request.vaultId),
            account?.email,
            auth.isAuthorized(account)
        ))
        if (decision is DriveSyncStartDecision.Rejected || account == null) {
            DriveSyncResult(0, 0, 0, 0, listOf("AUTH_REQUIRED"), false,
                errorDetails = listOf(SyncErrorDetail(SyncErrorCode.ITEM_FAILED, reason = SyncErrorReason.AUTH_REQUIRED)))
        } else {
            GoogleDriveSyncService(
                VaultRepository(this, android.net.Uri.parse(request.vaultId)),
                GoogleDriveApi(auth.accessToken(account)),
                request.vaultId,
                request.accountId,
                LocalChangeJournal(this),
                LocalDriveSyncBaselineStore(this),
                coordinator
            ) { progress ->
                stateStore.updateProgress(progress)?.let(::showOngoingNotification)
            }.sync(request.root)
        }
    } catch (e: Exception) {
        Log.e(TAG, "Google Drive sync failed: ${e.javaClass.simpleName}")
        DriveSyncResult(0, 0, 0, 0, listOf("SYNC_FAILED"), false,
            errorDetails = listOf(SyncFailurePolicy.fromException(e)))
    }

    private fun rejectWhileRunning(intent: Intent, providerId: String, providerName: String): Boolean {
        if (stateStore.snapshot()?.isRunning != true) return false
        recordStartFailure(providerId, providerName, intent.getStringExtra(EXTRA_VAULT_URI).orEmpty(),
            intent.getStringExtra(EXTRA_ROOT_NAME).orEmpty(), SyncErrorReason.SYNC_BUSY)
        return true
    }

    private fun recordStartFailure(providerId: String, providerName: String, vaultId: String = "", targetName: String = "",
                                   reason: SyncErrorReason = SyncErrorReason.UNKNOWN) {
        val target = targetName.ifBlank { getString(R.string.drive_target_unselected) }
        val final = stateStore.rejectStart(providerId, providerName, target, vaultId, reason)
        // Do not replace the ongoing foreground notification for the original run.
        notifications.notify(REJECTION_NOTIFICATION_ID, notification(final, ongoing = false))
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
        private const val TAG = "BackgroundSyncService"
        private const val GOOGLE_DRIVE_PROVIDER = "google_drive"
        private const val ONEDRIVE_PROVIDER = "onedrive"
        private const val CHANNEL_ID = "heji_notes_sync_status"
        private const val NOTIFICATION_ID = 2301
        private const val REJECTION_NOTIFICATION_ID = 2302
        private const val ACTION_START_GOOGLE_DRIVE = "com.jambus.heji.action.START_GOOGLE_DRIVE_SYNC"
        private const val ACTION_START_ONEDRIVE = "com.jambus.heji.action.START_ONEDRIVE_SYNC"
        private const val ACTION_CANCEL = "com.jambus.heji.action.CANCEL_SYNC"
        private const val EXTRA_ROOT_ID = "root_id"
        private const val EXTRA_ROOT_NAME = "root_name"
        private const val EXTRA_ACCOUNT_ID = "account_id"
        private const val EXTRA_VAULT_URI = "vault_uri"

        fun startGoogleDrive(context: Context, root: DriveVaultRoot, vaultUri: String, accountId: String) {
            val intent = Intent(context, BackgroundSyncService::class.java).setAction(ACTION_START_GOOGLE_DRIVE)
                .putExtra(EXTRA_ROOT_ID, root.id)
                .putExtra(EXTRA_ROOT_NAME, root.name)
                .putExtra(EXTRA_ACCOUNT_ID, accountId)
                .putExtra(EXTRA_VAULT_URI, vaultUri)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent) else context.startService(intent)
        }

        fun startOneDrive(context: Context, root: DriveVaultRoot, vaultUri: String, accountId: String) {
            val intent = Intent(context, BackgroundSyncService::class.java).setAction(ACTION_START_ONEDRIVE)
                .putExtra(EXTRA_ROOT_ID, root.id)
                .putExtra(EXTRA_ROOT_NAME, root.name)
                .putExtra(EXTRA_ACCOUNT_ID, accountId)
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
