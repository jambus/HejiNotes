package com.jambus.heji

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.ActivityNotFoundException
import android.view.ContextThemeWrapper
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.media.MediaMetadataRetriever
import android.os.Bundle
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.webkit.JavascriptInterface
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.google.android.gms.common.api.ApiException
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.ceil

class MainActivity : Activity() {
    private lateinit var repository: VaultRepository
    private lateinit var drivePreferences: DriveSyncPreferences
    private lateinit var driveAuth: GoogleDriveAuth
    private val driveExecutor = Executors.newSingleThreadExecutor()
    private val noteIoExecutor = AppNoteSaveCoordinator
    private val structuralIoExecutor = Executors.newSingleThreadExecutor()
    private var webView: WebView? = null
    private var statusView: TextView? = null
    private var saveActionView: TextView? = null
    private var editorContextText = ""
    private var editorStatusActions: LinearLayout? = null
    private val formatActions = mutableMapOf<String, ImageButton>()
    private val handler = Handler(Looper.getMainLooper())
    private val autosave = Runnable { saveCurrentNote() }
    private val saveCoordinator = RevisionSaveCoordinator()
    private var restoredSaveCoordinatorNoteUri: String? = null
    private val saveWaiters = mutableListOf<(Boolean) -> Unit>()
    private var editorGeneration = 0L
    private val editorReleaseCoordinator = EditorReleaseCoordinator<WebView>()
    private var currentNote: VaultDocument? = null
    private var browserDirectory: VaultDocument? = null
    private var currentVaultName = "Vault"
    private val browserPath = mutableListOf<String>()
    private val browserHistory = mutableListOf<VaultDocument>()
    private var browserScrollY = 0
    private var browserLoadGeneration = 0L
    private var browserMutationPending = false
    private var browserMutationMessage: String? = null
    private var browserSnapshot: BrowserSnapshot? = null
    private var openSwipeRow: SwipeActionRow? = null
    private var swipeDismissTouch: SwipeDismissTouchPolicy.State? = null
    private var dailyFolderDirectory: VaultDocument? = null
    private val dailyFolderPath = mutableListOf<String>()
    private val dailyFolderHistory = mutableListOf<VaultDocument>()
    private var dailyFolderLoadGeneration = 0L
    private var trashCount: Int? = null
    private var trashLoadFailed = false
    private var trashClearPending = false
    private var trashStatusMessage: String? = null
    private var trashStatusView: TextView? = null
    private var trashRowView: View? = null
    private var trashLoadGeneration = 0L
    private var trashBrowserGeneration = 0L
    private var trashBrowserScrollY = 0
    private var trashDocuments: List<VaultDocument> = emptyList()
    private var trashBrowserMessage: String? = null
    private var trashMutationPending = false
    private var captureFile: File? = null
    private var captureUri: Uri? = null
    private var editorView: PhotoEditorView? = null
    private var photoStatusView: TextView? = null
    private var photoInsertAction: TextView? = null
    private var photoModeActions: List<TextView> = emptyList()
    private var photoSavePending = false
    private var photoContextContent: String? = null
    private var photoSavedBodyHash: String? = null
    private var photoContextCaretOffset = 0
    private var photoContextScrollY = 0
    private var pendingVideoSession: PendingVideoCaptureSession? = null
    private var videoMetadata: VideoCapturePolicy.Metadata? = null
    private var videoStatusView: TextView? = null
    private var videoInsertAction: TextView? = null
    private var videoCopyCancelled = AtomicBoolean(false)
    private var videoInsertPending = false
    private var pendingEditorScrollY: Int? = null
    private var pendingCaretImagePath: String? = null
    private var pendingCaretLinkPath: String? = null
    private var searchQuery = ""
    private var searchResults: List<VaultSearchHit> = emptyList()
    private var searchMessage = "输入标题、正文或标签"
    private var searchGeneration = 0L
    private var searchScrollY = 0
    private var searchResultsView: LinearLayout? = null
    private var searchResultFocus = -1
    private var openedFromSearch = false
    private val searchDebounceToken = Any()
    private var screen = Screen.WELCOME
    private var driveFolderDirectory = DriveVaultRoot("root", "我的云端硬盘")
    private val driveFolderHistory = mutableListOf<DriveVaultRoot>()
    private var drivePickerFolders: List<DriveItem>? = null
    private var drivePickerError: String? = null
    private var drivePickerMessage: String? = null

    override fun attachBaseContext(newBase: android.content.Context) {
        // Apply the saved app-only language before any view or resource is created.
        super.attachBaseContext(UiLanguage.localizedContext(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        repository = VaultRepository(this)
        drivePreferences = DriveSyncPreferences(this)
        driveAuth = GoogleDriveAuth(this)
        if (!BackgroundSyncService.isActive()) SyncTaskStateStore(this).markInterruptedIfRunning()
        applyWindowColors()
        if (repository.savedVaultUri() == null) {
            showWelcome()
        } else if (savedInstanceState != null) {
            restoreInstanceState(savedInstanceState)
        } else {
            recoverVaultAndOpenBrowser()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(SavedStateBundle.KEY_SCREEN, screen.name)

        // Browser state
        outState.putStringArrayList(SavedStateBundle.KEY_BROWSER_PATH, ArrayList(browserPath))
        outState.putInt(SavedStateBundle.KEY_BROWSER_SCROLL_Y, browserScrollY)
        browserDirectory?.let { SavedStateBundle.putDocument(outState, "heji_browser_dir", it) }

        // Editor state
        val note = currentNote
        if (note != null) {
            SavedStateBundle.putDocument(outState, SavedStateBundle.PREFIX_NOTE, note)
            val scrollY = webView?.scrollY ?: pendingEditorScrollY ?: 0
            outState.putInt(SavedStateBundle.KEY_EDITOR_SCROLL_Y, scrollY)
            outState.putBoolean(SavedStateBundle.KEY_OPENED_FROM_SEARCH, openedFromSearch)
            outState.putInt(SavedStateBundle.KEY_SEARCH_RESULT_FOCUS, searchResultFocus)
            outState.putLong("heji_save_curr_rev", saveCoordinator.revision)
            outState.putLong("heji_save_persisted_rev", saveCoordinator.persistedRevision)
            outState.putBoolean("heji_save_failed", saveCoordinator.state == RevisionSaveCoordinator.State.FAILED)
        }

        // Search state
        outState.putString(SavedStateBundle.KEY_SEARCH_QUERY, searchQuery)
        outState.putInt(SavedStateBundle.KEY_SEARCH_SCROLL_Y, searchScrollY)
        outState.putInt(SavedStateBundle.KEY_SEARCH_RESULT_FOCUS, searchResultFocus)
        outState.putBoolean(SavedStateBundle.KEY_OPENED_FROM_SEARCH, openedFromSearch)

        // Photo state
        captureFile?.let {
            outState.putString(SavedStateBundle.KEY_PHOTO_CAPTURE_PATH, it.absolutePath)
        }
        editorView?.let { ev ->
            outState.putString(SavedStateBundle.KEY_PHOTO_MODE, ev.mode.name)
            SavedStateBundle.putHandles(outState, ev.getHandles())
        }
        outState.putBoolean(SavedStateBundle.KEY_PHOTO_SAVE_PENDING, photoSavePending)
        photoSavedBodyHash?.let { outState.putString(SavedStateBundle.KEY_PHOTO_SAVED_BODY_HASH, it) }
        outState.putInt(SavedStateBundle.KEY_PHOTO_CARET_OFFSET, photoContextCaretOffset)
        outState.putInt(SavedStateBundle.KEY_PHOTO_CONTEXT_SCROLL_Y, photoContextScrollY)

        // Trash state
        outState.putInt(SavedStateBundle.KEY_TRASH_SCROLL_Y, trashBrowserScrollY)
    }

    private fun restoreInstanceState(savedInstanceState: Bundle) {
        val screenName = savedInstanceState.getString(SavedStateBundle.KEY_SCREEN)
        val savedScreen = screenName?.let {
            try { Screen.valueOf(it) } catch (_: Exception) { null }
        } ?: Screen.BROWSER

        val pathList = savedInstanceState.getStringArrayList(SavedStateBundle.KEY_BROWSER_PATH)
        if (pathList != null) {
            browserPath.clear()
            browserPath.addAll(pathList)
        }
        browserScrollY = savedInstanceState.getInt(SavedStateBundle.KEY_BROWSER_SCROLL_Y, 0)
        val savedBrowserDir = SavedStateBundle.getDocument(savedInstanceState, "heji_browser_dir")
        if (savedBrowserDir != null) {
            browserDirectory = savedBrowserDir
        }

        val currRev = savedInstanceState.getLong("heji_save_curr_rev", 0L)
        val persRev = savedInstanceState.getLong("heji_save_persisted_rev", 0L)
        val isFailed = savedInstanceState.getBoolean("heji_save_failed", false)
        val savedNote = SavedStateBundle.getDocument(savedInstanceState, SavedStateBundle.PREFIX_NOTE)
        if (currRev > 0 || persRev > 0 || isFailed) {
            saveCoordinator.restore(currRev, persRev, isFailed)
            restoredSaveCoordinatorNoteUri = savedNote?.uri?.toString()
        }

        searchQuery = savedInstanceState.getString(SavedStateBundle.KEY_SEARCH_QUERY, "")
        searchScrollY = savedInstanceState.getInt(SavedStateBundle.KEY_SEARCH_SCROLL_Y, 0)
        searchResultFocus = savedInstanceState.getInt(SavedStateBundle.KEY_SEARCH_RESULT_FOCUS, -1)
        openedFromSearch = savedInstanceState.getBoolean(SavedStateBundle.KEY_OPENED_FROM_SEARCH, false)

        trashBrowserScrollY = savedInstanceState.getInt(SavedStateBundle.KEY_TRASH_SCROLL_Y, 0)

        when (savedScreen) {
            Screen.BROWSER -> {
                showVaultBrowser(resetToRoot = false)
            }
            Screen.EDITOR, Screen.EDITOR_LOADING -> {
                val capturePath = savedInstanceState.getString(SavedStateBundle.KEY_PHOTO_CAPTURE_PATH)
                val capture = capturePath?.let { File(it) }
                if (capture != null && capture.exists()) {
                    captureFile = capture
                    captureUri = Uri.parse("content://$FILE_PROVIDER_AUTHORITY/capture/${capture.name}")
                    photoSavedBodyHash = savedInstanceState.getString(SavedStateBundle.KEY_PHOTO_SAVED_BODY_HASH)
                    photoContextCaretOffset = savedInstanceState.getInt(SavedStateBundle.KEY_PHOTO_CARET_OFFSET, 0)
                    photoContextScrollY = savedInstanceState.getInt(SavedStateBundle.KEY_PHOTO_CONTEXT_SCROLL_Y, 0)
                }
                val note = SavedStateBundle.getDocument(savedInstanceState, SavedStateBundle.PREFIX_NOTE)
                if (note != null) {
                    currentNote = note
                    if (capture == null || !capture.exists()) {
                        pendingEditorScrollY = savedInstanceState.getInt(SavedStateBundle.KEY_EDITOR_SCROLL_Y, 0)
                        openNote(note)
                    }
                } else {
                    showVaultBrowser(resetToRoot = false)
                }
            }
            Screen.PHOTO -> {
                val capturePath = savedInstanceState.getString(SavedStateBundle.KEY_PHOTO_CAPTURE_PATH)
                val note = SavedStateBundle.getDocument(savedInstanceState, SavedStateBundle.PREFIX_NOTE)
                val file = capturePath?.let { File(it) }
                val wasSaving = savedInstanceState.getBoolean(SavedStateBundle.KEY_PHOTO_SAVE_PENDING, false)
                if (file != null && file.exists() && note != null) {
                    captureFile = file
                    currentNote = note
                    // The Vault remains the only persisted body source; photo body context is not bundled.
                    photoContextContent = null
                    photoSavedBodyHash = savedInstanceState.getString(SavedStateBundle.KEY_PHOTO_SAVED_BODY_HASH)
                    photoContextCaretOffset = savedInstanceState.getInt(SavedStateBundle.KEY_PHOTO_CARET_OFFSET, 0)
                    photoContextScrollY = savedInstanceState.getInt(SavedStateBundle.KEY_PHOTO_CONTEXT_SCROLL_Y, 0)
                    val bitmap = decodeCapturePreview(file)
                    if (bitmap != null) {
                        showPhotoEditor(bitmap)
                        val modeName = savedInstanceState.getString(SavedStateBundle.KEY_PHOTO_MODE)
                        val mode = modeName?.let {
                            try { PhotoEditMode.valueOf(it) } catch (_: Exception) { null }
                        } ?: PhotoEditMode.RECTANGLE
                        editorView?.let { ev ->
                            setPhotoMode(ev, mode)
                            val handles = SavedStateBundle.getHandles(savedInstanceState)
                            if (handles != null) {
                                ev.restoreHandles(handles)
                            }
                        }
                        if (wasSaving || AppNoteSaveCoordinator.hasPhotoSession(file.absolutePath)) {
                            photoSavePending = true
                            photoInsertAction?.isEnabled = false
                            photoModeActions.forEach { it.isEnabled = false }
                            photoStatusView?.text = "正在写入原图、校正图和笔记…"
                            AppNoteSaveCoordinator.observePhotoSession(file.absolutePath) { result ->
                                when (result) {
                                    is AppNoteSaveCoordinator.PhotoSessionResult.Success -> {
                                        if (!bitmap.isRecycled) bitmap.recycle()
                                        discardCaptureFile()
                                        currentNote = result.refreshedNote
                                        photoSavePending = false
                                        pendingEditorScrollY = photoContextScrollY
                                        pendingCaretImagePath = result.attachmentName
                                        openNote(result.refreshedNote)
                                    }
                                    is AppNoteSaveCoordinator.PhotoSessionResult.Failure -> {
                                        photoSavePending = false
                                        photoInsertAction?.isEnabled = true
                                        photoModeActions.forEach { it.isEnabled = true }
                                        photoStatusView?.text = result.message
                                    }
                                    AppNoteSaveCoordinator.PhotoSessionResult.InProgress -> Unit
                                }
                            }
                        }
                    } else {
                        openNote(note)
                    }
                } else if (note != null) {
                    openNote(note)
                } else {
                    showVaultBrowser(resetToRoot = false)
                }
            }
            Screen.SEARCH -> {
                showSearch()
            }
            Screen.SETTINGS -> {
                showSettings()
            }
            Screen.TRASH, Screen.TRASH_VIEWER -> {
                showTrashBrowser()
            }
            Screen.DAILY_FOLDER_PICKER -> {
                showDailyFolderPicker()
            }
            Screen.DRIVE_SETUP -> {
                showDriveSetup()
            }
            Screen.DRIVE_DETAILS -> {
                showSyncDetails()
            }
            Screen.DRIVE_FOLDER_PICKER, Screen.DRIVE_CONFIRM -> {
                showDriveSetup()
            }
            Screen.VIDEO -> {
                val note = SavedStateBundle.getDocument(savedInstanceState, SavedStateBundle.PREFIX_NOTE)
                if (note != null) openNote(note) else showVaultBrowser(resetToRoot = false)
            }
            Screen.WELCOME, Screen.EDITOR_ERROR -> {
                recoverVaultAndOpenBrowser()
            }
        }
    }

    override fun onPause() {
        super.onPause()
        if (screen == Screen.EDITOR && webView != null) saveCurrentNote()
    }

    override fun onDestroy() {
        handler.removeCallbacks(autosave)
        driveExecutor.shutdownNow()
        // Never interrupt an already-confirmed structural mutation; its repository lease cleans up in finally.
        structuralIoExecutor.shutdown()
        if (screen == Screen.EDITOR && webView != null && (saveCoordinator.hasUnsavedChanges || saveCoordinator.hasInFlightSave)) {
            saveCurrentNote()
        }
        releaseEditor()
        super.onDestroy()
    }

    /**
     * Editor screens create a new WebView every time. Releasing the previous instance keeps
     * repeated navigation from accumulating renderer processes.
     */
    private fun releaseEditor() {
        editorGeneration += 1L
        val editor = webView
        webView = null
        statusView = null
        saveActionView = null
        editorStatusActions = null
        handler.removeCallbacks(autosave)
        if (editor == null) return
        (editor.parent as? android.view.ViewGroup)?.removeView(editor)
        editorReleaseCoordinator.release(editor) { target ->
            try {
                target.stopLoading()
                target.destroy()
            } catch (_: Exception) {}
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        when (screen) {
            Screen.EDITOR -> returnToBrowser()
            Screen.EDITOR_LOADING, Screen.EDITOR_ERROR -> if (openedFromSearch) showSearch() else showVaultBrowser()
            Screen.BROWSER -> {
                if (closeOpenSwipeRow()) return
                if (browserPath.isNotEmpty()) showParentDirectory() else super.onBackPressed()
            }
            Screen.PHOTO -> restoreEditorScreen()
            Screen.VIDEO -> cancelVideoCapture()
            Screen.SETTINGS -> showVaultBrowser()
            Screen.TRASH -> showSettings()
            Screen.TRASH_VIEWER -> showTrashBrowser()
            Screen.DAILY_FOLDER_PICKER -> showSettings()
            Screen.DRIVE_SETUP, Screen.DRIVE_DETAILS -> showSettings()
            Screen.DRIVE_FOLDER_PICKER, Screen.DRIVE_CONFIRM -> showDriveSetup()
            Screen.SEARCH -> showVaultBrowser()
            Screen.WELCOME -> super.onBackPressed()
        }
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (screen == Screen.BROWSER) {
            if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                openSwipeRow?.let { row ->
                    if (!row.containsRawPoint(event.rawX, event.rawY)) {
                        closeOpenSwipeRow()
                        swipeDismissTouch = SwipeDismissTouchPolicy.begin(event.rawX, event.rawY)
                    }
                }
            }
            if (event.actionMasked == MotionEvent.ACTION_MOVE) {
                swipeDismissTouch?.let {
                    swipeDismissTouch = SwipeDismissTouchPolicy.onMove(
                        it,
                        event.rawX,
                        event.rawY,
                        ViewConfiguration.get(this).scaledTouchSlop.toFloat()
                    )
                }
            }
        }
        if (
            (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) &&
            SwipeDismissTouchPolicy.shouldCancelTargetOnFinish(swipeDismissTouch)
        ) {
            val cancelEvent = MotionEvent.obtain(event).apply { action = MotionEvent.ACTION_CANCEL }
            super.dispatchTouchEvent(cancelEvent)
            cancelEvent.recycle()
            swipeDismissTouch = null
            return true
        }
        val dispatched = super.dispatchTouchEvent(event)
        if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
            swipeDismissTouch = null
        }
        return dispatched
    }

    private fun recoverVaultAndOpenBrowser() {
        releaseEditor()
        screen = Screen.BROWSER
        val root = pageRoot(COLOR_BACKGROUND).apply {
            gravity = Gravity.CENTER
            setPadding(dp(24), dp(32), dp(24), dp(24))
        }
        root.addView(TextView(this).apply {
            text = "正在检查 Vault…"
            textSize = 16f
            gravity = Gravity.CENTER
            setTextColor(COLOR_SECONDARY_TEXT)
        }, matchWrap())
        setContentView(root)
        noteIoExecutor.execute {
            val vaultId = repository.savedVaultUri()?.toString().orEmpty()
            val lease = VaultMutationLease.tryAcquire(vaultId, VaultMutationLease.Kind.STRUCTURAL)
            val result = if (lease == null) {
                VaultRecoveryResult.Failure(VaultFailureKind.READ_FAILED)
            } else try {
                repository.recoverVault()
            } finally {
                VaultMutationLease.release(lease)
            }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                when (result) {
                    VaultRecoveryResult.Success -> showVaultBrowser(true)
                    is VaultRecoveryResult.Failure -> showWelcome(
                        if (result.kind == VaultFailureKind.PERMISSION_DENIED) {
                            "无法访问 Vault，请重新选择"
                        } else {
                            "无法检查当前 Vault，原文件未被修改，请重新选择或稍后重试"
                        }
                    )
                }
            }
        }
    }

    private fun showWelcome(message: String? = null) {
        releaseEditor()
        screen = Screen.WELCOME
        currentNote = null
        val root = pageRoot(COLOR_BACKGROUND).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(24), dp(32), dp(24), dp(24))
        }
        root.addView(TextView(this).apply {
            text = getString(R.string.brand_mark)
            textSize = 28f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(COLOR_BACKGROUND)
            background = rounded(COLOR_ACCENT, dp(18))
        }, LinearLayout.LayoutParams(dp(64), dp(64)))
        root.addView(TextView(this).apply {
            text = getString(R.string.app_name)
            textSize = 30f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(COLOR_PRIMARY_TEXT)
            setPadding(0, dp(20), 0, dp(6))
        }, matchWrap())
        root.addView(TextView(this).apply {
            text = "把现场记录直接写进你的 Obsidian Vault"
            textSize = 16f
            gravity = Gravity.CENTER
            setTextColor(COLOR_SECONDARY_TEXT)
            setPadding(dp(18), 0, dp(18), dp(28))
        }, matchWrap())
        if (message != null) {
            root.addView(infoBanner(message), matchWrap().apply { bottomMargin = dp(16) })
        }
        root.addView(action("选择 Obsidian Vault", true) { chooseVault() }, matchWrap())
        root.addView(TextView(this).apply {
            text = "选择已有文件夹后，你可以浏览笔记、编辑 Markdown，并直接插入照片。"
            textSize = 13f
            gravity = Gravity.CENTER
            setTextColor(COLOR_MUTED_TEXT)
            setPadding(dp(12), dp(16), dp(12), 0)
        }, matchWrap())
        setContentView(root)
    }

    private fun showVaultBrowser(resetToRoot: Boolean = false) {
        closeOpenSwipeRow(animated = false)
        swipeDismissTouch = null
        if (resetToRoot) {
            browserDirectory = null
            browserPath.clear()
            browserHistory.clear()
            browserScrollY = 0
        }
        releaseEditor()
        browserMutationPending = false
        screen = Screen.BROWSER
        val generation = ++browserLoadGeneration
        val requestedDirectory = browserDirectory
        val loadingRoot = pageRoot(COLOR_BACKGROUND)
        loadingRoot.addView(browserHeader(), matchWrap())
        loadingRoot.addView(emptyState("正在读取 Vault…"), matchWrap().apply {
            leftMargin = dp(16)
            rightMargin = dp(16)
            topMargin = dp(20)
        })
        setContentView(loadingRoot)
        noteIoExecutor.execute {
            val rootDirectory = repository.vaultRoot()
            if (rootDirectory == null) {
                runOnUiThread {
                    if (browserRequestIsCurrent(generation)) showWelcome("无法访问 Vault，请重新选择")
                }
                return@execute
            }
            val directory = requestedDirectory ?: rootDirectory
            val directoryReadable = repository.canReadDirectory(directory)
            val children = if (directoryReadable) {
                repository.children(directory)
                    .filterNot { VaultBrowserPolicy.isHidden(it.relativePath) }
                    .sortedWith(compareBy<VaultDocument> { !repository.isDirectory(it) }
                        .thenBy { it.name.lowercase() })
            } else {
                emptyList()
            }
            val previews = children
                .filter { !repository.isDirectory(it) && it.name.endsWith(".md", true) }
                .associate { it.uri.toString() to notePreview(it) }
            runOnUiThread {
                if (!browserRequestIsCurrent(generation)) return@runOnUiThread
                currentVaultName = rootDirectory.name
                if (requestedDirectory == null) browserDirectory = rootDirectory
                renderVaultBrowser(rootDirectory, directoryReadable, children, previews)
            }
        }
    }

    private fun browserRequestIsCurrent(generation: Long): Boolean =
        !isFinishing && !isDestroyed && screen == Screen.BROWSER && browserLoadGeneration == generation

    private fun renderVaultBrowser(
        rootDirectory: VaultDocument,
        directoryReadable: Boolean,
        children: List<VaultDocument>,
        previews: Map<String, String>
    ) {
        browserSnapshot = BrowserSnapshot(rootDirectory, directoryReadable, children, previews)
        val root = pageRoot(COLOR_BACKGROUND)
        root.addView(browserHeader(), matchWrap())
        root.addView(TextView(this).apply {
            text = if (browserPath.isEmpty()) "${rootDirectory.name}  ·  Vault" else browserPath.joinToString("  /  ")
            textSize = 13f
            setTextColor(COLOR_MUTED_TEXT)
            setPadding(dp(20), dp(6), dp(20), dp(10))
            maxLines = 1
        }, matchWrap())

        val scroll = ScrollView(this).apply {
            isFillViewport = true
            setOnScrollChangeListener { _, _, y, _, _ -> browserScrollY = y }
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), 0, dp(16), dp(16))
        }
        if (browserPath.isNotEmpty()) {
            content.addView(action("‹  上一级", false) { showParentDirectory() }, wrapWrap().apply {
                bottomMargin = dp(8)
            })
        }
        val folders = children.filter { repository.isDirectory(it) }
        val notes = children.filter {
            !repository.isDirectory(it) &&
                !VaultBrowserPolicy.isReadOnlyAttachmentPath(it.relativePath) &&
                VaultBrowserPolicy.fileKind(it.name, it.mimeType) == VaultBrowserPolicy.FileKind.MARKDOWN
        }
        val files = children.filter {
            !repository.isDirectory(it) &&
                (VaultBrowserPolicy.isReadOnlyAttachmentPath(it.relativePath) ||
                    VaultBrowserPolicy.fileKind(it.name, it.mimeType) != VaultBrowserPolicy.FileKind.MARKDOWN)
        }

        if (!directoryReadable) {
            content.addView(vaultAccessErrorState(), matchWrap())
        } else {
            browserMutationMessage?.let { message ->
                content.addView(infoBanner(message), matchWrap().apply { bottomMargin = dp(12) })
            }
            if (repository.dailyDirectoryResetNotice()) {
                content.addView(infoBanner("今日笔记目录已不可用，已改为 Vault 根目录。请在设置中重新选择目录。"),
                    matchWrap().apply { bottomMargin = dp(12) })
            }
            val eligibleRows = folders.any { !VaultBrowserPolicy.isReadOnlyAttachmentPath(it.relativePath) } || notes.isNotEmpty()
            if (eligibleRows && !repository.swipeDiscoveryHintSeen()) {
                content.addView(swipeDiscoveryHint(), matchWrap().apply { bottomMargin = dp(4) })
            }
            sectionLabel(content, "文件夹", folders.size)
            if (folders.isEmpty()) {
                content.addView(emptyState("当前目录没有文件夹"), matchWrap().apply { bottomMargin = dp(16) })
            } else {
                content.addView(vaultGroup(folders.map { folder ->
                    if (VaultBrowserPolicy.isReadOnlyAttachmentPath(folder.relativePath)) {
                        vaultRow(R.drawable.ic_browser_folder, folder.name, ui("只读附件目录"), ui("文件夹"), grouped = true) {
                            openDirectory(folder)
                        }
                    } else {
                        swipeableVaultRow(
                            R.drawable.ic_browser_folder, folder.name, ui("文件夹"), ui("文件夹"), folder
                        ) { openDirectory(folder) }
                    }
                }), matchWrap().apply { bottomMargin = dp(12) })
            }
            sectionLabel(content, "笔记", notes.size)
            if (notes.isEmpty()) {
                content.addView(emptyState("当前目录还没有 Markdown 笔记"), matchWrap())
            } else {
                content.addView(vaultGroup(notes.map { note ->
                    swipeableVaultRow(
                        R.drawable.ic_browser_note,
                        note.name.removeSuffix(".md"),
                        noteMetadata(note, previews[note.uri.toString()] ?: "空白笔记"),
                        "Markdown 笔记",
                        note
                    ) { openedFromSearch = false; openNote(note) }
                }), matchWrap())
            }
            sectionLabel(content, ui("其他文件"), files.size)
            if (files.isEmpty()) {
                content.addView(emptyState(ui("当前目录没有其他文件")), matchWrap())
            } else {
                content.addView(vaultGroup(files.map { file ->
                    val kind = VaultBrowserPolicy.fileKind(file.name, file.mimeType)
                    val type = when (kind) {
                        VaultBrowserPolicy.FileKind.IMAGE -> ui("图片")
                        VaultBrowserPolicy.FileKind.VIDEO -> ui("视频")
                        else -> ui("文件")
                    }
                    val icon = when (kind) {
                        VaultBrowserPolicy.FileKind.IMAGE -> R.drawable.ic_browser_image
                        VaultBrowserPolicy.FileKind.VIDEO -> R.drawable.ic_browser_video
                        else -> R.drawable.ic_browser_file
                    }
                    val metadata = listOfNotNull(type, file.size?.takeIf { it >= 0L }?.let(::formatBytes)).joinToString(" · ")
                    vaultRow(icon, file.name, metadata, type, grouped = true) { openVaultFile(file) }
                }), matchWrap())
            }
        }
        scroll.addView(content, LinearLayout.LayoutParams(-1, -2))
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        if (directoryReadable) root.addView(browserFooter(), matchWrap())
        setContentView(root)
        scroll.post { scroll.scrollTo(0, browserScrollY) }
    }

    /** Removes a confirmed move immediately; the provider directory is reconciled asynchronously. */
    private fun renderBrowserAfterConfirmedMove(document: VaultDocument) {
        val snapshot = browserSnapshot ?: return
        val remaining = snapshot.children.filterNot { it.uri == document.uri }
        val remainingPreviews = snapshot.previews.filterKeys { key -> remaining.any { it.uri.toString() == key } }
        renderVaultBrowser(snapshot.rootDirectory, snapshot.directoryReadable, remaining, remainingPreviews)
    }

    private fun refreshVaultBrowserAfterMove() {
        val requestedDirectory = browserDirectory ?: return
        val generation = ++browserLoadGeneration
        noteIoExecutor.execute {
            try {
                val rootDirectory = repository.vaultRoot() ?: throw IllegalStateException("Vault unavailable")
                if (!repository.canReadDirectory(requestedDirectory)) throw IllegalStateException("Directory unreadable")
                val directoryReadable = true
                val children = repository.verifiedChildren(requestedDirectory)
                    .filterNot { VaultBrowserPolicy.isHidden(it.relativePath) }
                    .sortedWith(compareBy<VaultDocument> { !repository.isDirectory(it) }.thenBy { it.name.lowercase() })
                val previews = children
                    .filter { !repository.isDirectory(it) && it.name.endsWith(".md", true) }
                    .associate { it.uri.toString() to notePreview(it) }
                runOnUiThread {
                    if (!browserRequestIsCurrent(generation)) return@runOnUiThread
                    browserMutationMessage = null
                    renderVaultBrowser(rootDirectory, directoryReadable, children, previews)
                }
            } catch (_: Exception) {
                runOnUiThread {
                    if (!browserRequestIsCurrent(generation)) return@runOnUiThread
                    browserMutationMessage = "已移到回收站，但列表刷新失败。请返回文件库后重试。"
                    browserSnapshot?.let { snapshot ->
                        renderVaultBrowser(snapshot.rootDirectory, snapshot.directoryReadable, snapshot.children, snapshot.previews)
                    }
                }
            }
        }
    }

    private fun browserHeader(): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(20), dp(14), dp(12), dp(8))
        background = colorBlock(COLOR_SURFACE)
        addView(TextView(this@MainActivity).apply {
            text = getString(R.string.app_name)
            textSize = 21f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(COLOR_PRIMARY_TEXT)
        }, LinearLayout.LayoutParams(0, -2, 1f))
        addView(iconAction(R.drawable.ic_search, "搜索笔记") { _ -> showSearch() })
        addView(iconAction(R.drawable.ic_more, "更多选项") { anchor -> showBrowserMore(anchor) })
    }

    private fun browserFooter(): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        setPadding(dp(16), dp(10), dp(16), dp(16))
        background = colorBlock(COLOR_SURFACE)
        addView(action("新建", false) { showCreateMenu() }, LinearLayout.LayoutParams(0, dp(48), 0.72f).apply {
            marginEnd = dp(8)
        })
        addView(action("打开今日笔记", true) { openDailyNote() }, LinearLayout.LayoutParams(0, dp(48), 1.28f))
    }

    private fun showBrowserMore(anchor: View) {
        PopupMenu(this, anchor).apply {
            menu.add(0, MENU_SETTINGS, 0, UiText.label(this@MainActivity, "设置"))
            menu.add(0, MENU_SWITCH_VAULT, 1, UiText.label(this@MainActivity, "切换 Vault"))
            setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    MENU_SETTINGS -> showSettings()
                    MENU_SWITCH_VAULT -> chooseVault()
                }
                true
            }
            show()
        }
    }

    private fun showSearch() {
        closeOpenSwipeRow(animated = false)
        releaseEditor()
        screen = Screen.SEARCH
        val root = pageRoot(COLOR_BACKGROUND)
        root.addView(simpleToolbar("‹  文件", "搜索笔记") { showVaultBrowser() }, matchWrap())
        val input = EditText(this).apply {
            hint = "搜索标题、正文或标签"
            setSingleLine(true)
            setText(searchQuery)
            setSelection(searchQuery.length)
            contentDescription = "搜索标题、正文或标签"
            setPadding(dp(20), dp(10), dp(20), dp(10))
            background = rippleBackground(COLOR_ROW, dp(12))
        }
        root.addView(input, matchWrap().apply { leftMargin = dp(16); rightMargin = dp(16); topMargin = dp(8) })
        val scroll = ScrollView(this).apply {
            setOnScrollChangeListener { _, _, y, _, _ -> searchScrollY = y }
        }
        searchResultsView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(24))
        }
        scroll.addView(searchResultsView, matchWrap())
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        scroll.post { scroll.scrollTo(0, searchScrollY) }
        input.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(value: android.text.Editable?) {
                searchQuery = value?.toString().orEmpty()
                scheduleSearch()
            }
        })
        renderSearchResults()
        if (searchQuery.isNotBlank()) scheduleSearch()
        if (searchResultFocus < 0) input.requestFocus()
    }

    private fun scheduleSearch() {
        searchGeneration += 1L
        handler.removeCallbacksAndMessages(searchDebounceToken)
        val request = Runnable { runSearch() }
        handler.postAtTime(request, searchDebounceToken, android.os.SystemClock.uptimeMillis() + 250L)
    }

    private fun runSearch() {
        val query = searchQuery.trim()
        val generation = searchGeneration
        if (query.isEmpty()) {
            searchResults = emptyList()
            searchMessage = "输入标题、正文或标签"
            renderSearchResults()
            return
        }
        searchMessage = "正在搜索…"
        renderSearchResults()
        noteIoExecutor.execute {
            val result = repository.searchNotes(query) { generation == searchGeneration && screen == Screen.SEARCH }
            runOnUiThread {
                if (screen != Screen.SEARCH || generation != searchGeneration || query != searchQuery.trim()) return@runOnUiThread
                when (result) {
                    is VaultSearchResult.Success -> {
                        searchResults = result.hits
                        searchMessage = when {
                            result.hits.isEmpty() && result.unreadableCount > 0 -> "搜索不完整；${result.unreadableCount} 个文件无法读取"
                            result.hits.isEmpty() -> "没有匹配的笔记"
                            result.unreadableCount > 0 -> "找到 ${result.hits.size} 项；${result.unreadableCount} 个文件无法读取"
                            else -> "找到 ${result.hits.size} 项"
                        }
                    }
                    is VaultSearchResult.Failure -> {
                        searchResults = emptyList()
                        searchMessage = if (result.kind == VaultFailureKind.PERMISSION_DENIED) "无法访问当前 Vault，请重新选择" else "无法完整搜索当前 Vault，请稍后重试"
                    }
                    VaultSearchResult.Cancelled -> return@runOnUiThread
                }
                renderSearchResults()
            }
        }
    }

    private fun renderSearchResults() {
        val content = searchResultsView ?: return
        content.removeAllViews()
        content.addView(TextView(this).apply {
            text = searchMessage
            textSize = 13f
            setTextColor(COLOR_MUTED_TEXT)
            setPadding(dp(4), dp(8), dp(4), dp(8))
        }, matchWrap())
        if (searchResults.isNotEmpty()) {
            content.addView(vaultGroup(searchResults.mapIndexed { index, hit ->
                val metadata = noteMetadata(hit.document, hit.match.snippet, hit.match.source)
                vaultRow(R.drawable.ic_browser_note, hit.document.name.removeSuffix(".md"), metadata, "搜索结果") {
                    openedFromSearch = true
                    searchResultFocus = index
                    openNote(hit.document)
                }.also { row -> if (index == searchResultFocus) row.post { row.requestFocus() } }
            }), matchWrap())
        }
    }

    private fun showSettings() {
        releaseEditor()
        screen = Screen.SETTINGS
        trashCount = null
        trashLoadFailed = false
        trashStatusMessage = null
        trashStatusView = null
        trashRowView = null
        val root = pageRoot(COLOR_BACKGROUND)
        val toolbar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(8))
            background = colorBlock(COLOR_SURFACE)
            addView(action("‹  文件", false) { showVaultBrowser() })
            addView(TextView(this@MainActivity).apply {
                text = ui("设置")
                textSize = 19f
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                setTextColor(COLOR_PRIMARY_TEXT)
            }, LinearLayout.LayoutParams(0, dp(44), 1f))
            addView(TextView(this@MainActivity), LinearLayout.LayoutParams(dp(76), dp(44)))
        }
        root.addView(toolbar, matchWrap())
        val scroll = ScrollView(this)
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(4), dp(16), dp(24))
        }
        sectionLabel(content, ui("同步"))
        val vaultId = repository.savedVaultUri()?.toString().orEmpty()
        val accountId = driveAuth.currentAccount()?.email.orEmpty()
        val driveRoot = drivePreferences.root(vaultId, accountId)
        val syncSnapshot = currentSyncSnapshot()
        val driveStatus = when {
            syncSnapshot?.isRunning == true -> getString(R.string.drive_status_running, driveRoot?.name ?: syncSnapshot.targetName, syncSnapshot.statusLabel(this))
            driveRoot == null -> getString(R.string.drive_status_disconnected)
            !driveAuth.isAuthorized(driveAuth.currentAccount()) -> getString(R.string.drive_status_sign_in, driveRoot.name)
            drivePreferences.lastSuccessAt(vaultId, driveRoot.id, accountId) > 0L -> getString(R.string.drive_status_last_sync, driveRoot.name, formatSyncTime(drivePreferences.lastSuccessAt(vaultId, driveRoot.id, accountId)))
            else -> getString(R.string.drive_status_selected, driveRoot.name)
        }
        content.addView(settingsRow("Google Drive", driveStatus, false) { showDriveSetup() }, matchWrap())
        syncSnapshot?.let { snapshot ->
            content.addView(settingsRow(ui("同步详情"), snapshot.statusLabel(this), false) { showSyncDetails() }, matchWrap().apply {
                topMargin = dp(8)
            })
        }
        sectionLabel(content, ui("每日笔记"))
        val path = repository.dailyNoteDirectoryPath().ifBlank { "Vault 根目录" }
        content.addView(settingsRow(ui("今日笔记目录"), path, false) { showDailyFolderPicker(true) }, matchWrap())
        content.addView(TextView(this).apply {
            text = ui("设置不会移动已有笔记或附件。新建的每日笔记会按 yyyy-MM-dd.md 写入所选目录。")
            textSize = 13f
            setTextColor(COLOR_MUTED_TEXT)
            setPadding(dp(6), dp(12), dp(6), 0)
        }, matchWrap())
        sectionLabel(content, ui("存储"))
        content.addView(trashSettingsRow(), matchWrap())
        sectionLabel(content, ui("显示"))
        val appearance = repository.appearanceMode()
        content.addView(settingsRow(ui("日间模式"), ui("浅色背景与深色文字"), appearance == VaultRepository.APPEARANCE_DAY) {
            setAppearance(VaultRepository.APPEARANCE_DAY)
        }, matchWrap().apply { bottomMargin = dp(8) })
        content.addView(settingsRow(ui("夜间模式"), ui("深色工作区与深色编辑纸面"), appearance == VaultRepository.APPEARANCE_NIGHT) {
            setAppearance(VaultRepository.APPEARANCE_NIGHT)
        }, matchWrap())
        sectionLabel(content, ui("语言"))
        val selectedLanguage = UiLanguage.selected(this)
        content.addView(settingsRow(getString(R.string.language_system), "中文 / English", selectedLanguage == UiLanguage.SYSTEM) {
            setLanguage(UiLanguage.SYSTEM)
        }, matchWrap().apply { bottomMargin = dp(8) })
        content.addView(settingsRow(getString(R.string.language_chinese), if (selectedLanguage == UiLanguage.CHINESE) getString(R.string.language_current_zh) else getString(R.string.language_switch_zh), selectedLanguage == UiLanguage.CHINESE) {
            setLanguage(UiLanguage.CHINESE)
        }, matchWrap().apply {
            bottomMargin = dp(8)
        })
        content.addView(settingsRow(getString(R.string.language_english), if (selectedLanguage == UiLanguage.ENGLISH) getString(R.string.language_current_en) else getString(R.string.language_switch_en), selectedLanguage == UiLanguage.ENGLISH) {
            setLanguage(UiLanguage.ENGLISH)
        }, matchWrap())
        sectionLabel(content, ui("关于"))
        content.addView(
            settingsStatusRow(
                ui("版本"),
                getString(R.string.about_build, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE),
                getString(R.string.app_name),
                false
            ),
            matchWrap()
        )
        scroll.addView(content, LinearLayout.LayoutParams(-1, -2))
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        if (!trashClearPending) loadTrashStatus()
    }

    private fun trashSettingsRow(): View = LinearLayout(this).apply {
        trashRowView = this
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = dp(68)
        setPadding(dp(16), dp(10), dp(12), dp(10))
        background = rounded(COLOR_ROW, dp(14))
        isClickable = true
        isFocusable = true
        setOnClickListener {
            if (!trashClearPending) showTrashBrowser()
        }
        addView(LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(this@MainActivity).apply {
                text = "回收站"
                textSize = 16f
                setTextColor(COLOR_PRIMARY_TEXT)
            }, matchWrap())
            trashStatusView = TextView(this@MainActivity).apply {
                textSize = 13f
                maxLines = 2
                setTextColor(COLOR_MUTED_TEXT)
                setPadding(0, dp(3), 0, 0)
            }
            addView(trashStatusView, matchWrap())
        }, LinearLayout.LayoutParams(0, -2, 1f))
        addView(ImageView(this@MainActivity).apply {
            setImageResource(R.drawable.ic_chevron_right)
            setColorFilter(COLOR_MUTED_TEXT)
            scaleType = android.widget.ImageView.ScaleType.CENTER
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(dp(44), dp(44)))
        updateTrashRow()
    }

    private fun loadTrashStatus() {
        if (trashClearPending) return
        val generation = ++trashLoadGeneration
        trashCount = null
        trashLoadFailed = false
        updateTrashRow()
        noteIoExecutor.execute {
            val result = repository.trashContents()
            runOnUiThread {
                if (isFinishing || isDestroyed || generation != trashLoadGeneration) return@runOnUiThread
                when (result) {
                    is TrashContentsResult.Success -> {
                        trashCount = result.count
                        trashLoadFailed = false
                    }
                    is TrashContentsResult.Failure -> {
                        trashCount = null
                        trashLoadFailed = true
                    }
                }
                if (screen == Screen.SETTINGS) updateTrashRow()
            }
        }
    }

    private fun updateTrashRow() {
        val status = when {
            trashClearPending -> "正在永久删除回收站内容…"
            trashStatusMessage != null -> trashStatusMessage.orEmpty()
            trashLoadFailed -> "无法读取回收站 · 点按重试"
            trashCount == null -> "正在统计回收站…"
            trashCount == 0 -> "回收站为空"
            else -> "${trashCount} 个项目 · 图片附件不会删除"
        }
        trashStatusView?.text = status
        val enabled = !trashClearPending
        trashRowView?.isEnabled = enabled
        trashRowView?.alpha = if (enabled) 1f else 0.62f
        trashRowView?.contentDescription = "回收站，$status"
    }

    private fun showTrashBrowser(completionMessage: String? = null) {
        releaseEditor()
        screen = Screen.TRASH
        val generation = ++trashBrowserGeneration
        val root = pageRoot(COLOR_BACKGROUND)
        root.addView(simpleToolbar("‹  设置", "回收站") { showSettings() }, matchWrap())
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(12), dp(16), dp(20)) }
        content.addView(emptyState("正在读取回收站…"), matchWrap())
        root.addView(content, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        noteIoExecutor.execute {
            val result = repository.trashDocuments()
            runOnUiThread {
                if (screen != Screen.TRASH || generation != trashBrowserGeneration) return@runOnUiThread
                when (result) {
                    is TrashDocumentsResult.Success -> { trashDocuments = result.documents; trashBrowserMessage = completionMessage }
                    is TrashDocumentsResult.Failure -> { trashDocuments = emptyList(); trashBrowserMessage = if (result.kind == VaultFailureKind.PERMISSION_DENIED) "无法访问回收站，请重新选择 Vault" else "无法读取回收站，点按重试" }
                }
                renderTrashBrowser()
            }
        }
    }

    private fun renderTrashBrowser() {
        val root = pageRoot(COLOR_BACKGROUND)
        root.addView(simpleToolbar("‹  设置", "回收站") { showSettings() }, matchWrap())
        val scroll = ScrollView(this).apply { setOnScrollChangeListener { _, _, y, _, _ -> trashBrowserScrollY = y } }
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(8), dp(16), dp(20)) }
        val message = trashBrowserMessage
        if (message != null) {
            content.addView(infoBanner(message), matchWrap())
            if (trashDocuments.isEmpty() && message.contains("重试")) content.addView(action("重新读取", true) { showTrashBrowser() }, matchWrap().apply { topMargin = dp(8) })
            if (trashDocuments.isEmpty() && message.contains("重新选择")) content.addView(action("重新选择 Vault", true) { chooseVault() }, matchWrap().apply { topMargin = dp(8) })
        }
        if (trashDocuments.isEmpty() && message == null) {
            content.addView(emptyState("回收站为空。移到回收站的笔记和文件夹会显示在这里。"), matchWrap())
        } else if (trashDocuments.isNotEmpty() && !trashMutationPending) {
            content.addView(infoBanner("仅显示 Vault 根 .trash 的直接项目。附件不会随永久删除而删除。"), matchWrap().apply { bottomMargin = dp(8) })
            content.addView(vaultGroup(trashDocuments.map { document ->
                val subtitle = if (repository.isDirectory(document)) "文件夹 · 永久删除会移除其中全部内容" else "来自回收站 · ${document.name}"
                vaultRow(if (repository.isDirectory(document)) R.drawable.ic_browser_folder else R.drawable.ic_browser_note, document.name.removeSuffix(".md"), subtitle, "回收站项目", trailingAccessibilityLabel = "永久删除 ${document.name}", trailingIcon = R.drawable.ic_note_delete, trailingAction = { confirmDeleteTrashItem(document) }) {
                    if (!repository.isDirectory(document) && document.name.endsWith(".md", true)) showTrashViewer(document)
                    else confirmDeleteTrashItem(document)
                }
            }), matchWrap())
            content.addView(action("清空回收站（${trashDocuments.size}）", true) { confirmDeleteTrashSnapshot(trashDocuments) }, matchWrap().apply { topMargin = dp(16) })
        }
        scroll.addView(content, matchWrap())
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        scroll.post { scroll.scrollTo(0, trashBrowserScrollY) }
    }

    private fun confirmDeleteTrashItem(document: VaultDocument) {
        if (trashMutationPending) return
        val folder = repository.isDirectory(document)
        dialogBuilder().setTitle("永久删除“${document.name}”？").setMessage(
            "此操作无法撤销。${if (folder) "文件夹及其中全部内容将被永久删除。" else ""}图片附件不会删除，Markdown 链接不会改写。"
        ).setNegativeButton("取消", null).setPositiveButton("永久删除") { _, _ -> deleteTrashSnapshot(listOf(document)) }.show()
    }

    private fun confirmDeleteTrashSnapshot(snapshot: List<VaultDocument>) {
        if (trashMutationPending) return
        dialogBuilder().setTitle("永久清空回收站？").setMessage(
            "将永久删除当前 Vault/.trash 中已列出的 ${snapshot.size} 个直接项目。此操作无法撤销；文件夹内容也会删除，图片附件不会删除。"
        ).setNegativeButton("取消", null).setPositiveButton("永久删除") { _, _ -> deleteTrashSnapshot(snapshot) }.show()
    }

    private fun deleteTrashSnapshot(snapshot: List<VaultDocument>) {
        if (trashMutationPending) return
        val confirmed = snapshot.map { it.uri.toString() }
        val generation = trashBrowserGeneration
        trashMutationPending = true
        trashBrowserMessage = "正在永久删除…"
        renderTrashBrowser()
        noteIoExecutor.execute {
            val result = repository.deleteTrashSnapshot(confirmed)
            runOnUiThread {
                trashMutationPending = false
                if (screen != Screen.TRASH || generation != trashBrowserGeneration) return@runOnUiThread
                val message = when {
                    result.failed == 0 -> "已永久删除 ${result.deleted} 个项目"
                    else -> "已删除 ${result.deleted} 个，${result.failed} 个失败；可重新确认重试"
                }
                showTrashBrowser(message)
            }
        }
    }

    private fun showTrashViewer(document: VaultDocument) {
        screen = Screen.TRASH_VIEWER
        val root = pageRoot(COLOR_EDITOR_BACKGROUND)
        root.addView(simpleToolbar("‹  回收站", document.name.removeSuffix(".md")) { showTrashBrowser() }, matchWrap())
        root.addView(TextView(this).apply { text = "来自回收站 · 只读"; textSize = 13f; setTextColor(COLOR_MUTED_TEXT); setPadding(dp(20), dp(8), dp(20), dp(8)) }, matchWrap())
        root.addView(TextView(this).apply { text = "正在读取笔记…"; gravity = Gravity.CENTER; setTextColor(COLOR_SECONDARY_TEXT) }, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        noteIoExecutor.execute {
            val content = repository.readText(document)
            runOnUiThread {
                if (screen != Screen.TRASH_VIEWER) return@runOnUiThread
                val view = TextView(this).apply { text = content ?: "无法读取此回收站笔记"; textSize = 17f; setTextColor(COLOR_PRIMARY_TEXT); setPadding(dp(20), dp(12), dp(20), dp(32)); setTextIsSelectable(true) }
                val page = pageRoot(COLOR_EDITOR_BACKGROUND)
                page.addView(simpleToolbar("‹  回收站", document.name.removeSuffix(".md")) { showTrashBrowser() }, matchWrap())
                page.addView(TextView(this).apply { text = "来自回收站 · 只读"; textSize = 13f; setTextColor(COLOR_MUTED_TEXT); setPadding(dp(20), dp(8), dp(20), dp(8)) }, matchWrap())
                page.addView(ScrollView(this).apply { addView(view) }, LinearLayout.LayoutParams(-1, 0, 1f))
                setContentView(page)
            }
        }
    }

    private fun setAppearance(mode: String) {
        repository.setAppearanceMode(mode)
        applyWindowColors()
        showSettings()
    }

    private fun setLanguage(language: String) {
        if (UiLanguage.selected(this) == language) return
        UiLanguage.set(this, language)
        // Recreation refreshes every screen, dialog and notification resource immediately.
        recreate()
    }

    private fun showDriveSetup(message: String? = null) {
        releaseEditor()
        screen = Screen.DRIVE_SETUP
        val root = pageRoot(COLOR_BACKGROUND)
        root.addView(simpleToolbar("‹  设置", "Google Drive") { showSettings() }, matchWrap())
        val scroll = ScrollView(this)
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(10), dp(16), dp(24))
        }
        message?.let { content.addView(infoBanner(it), matchWrap().apply { bottomMargin = dp(12) }) }
        val account = driveAuth.currentAccount()
        if (!driveAuth.isAuthorized(account)) {
            content.addView(emptyState("连接 Google 账号后，才能选择用于电脑 Obsidian 的 Drive Vault。"), matchWrap().apply {
                bottomMargin = dp(14)
            })
            content.addView(action("连接 Google Drive", true) {
                startActivityForResult(driveAuth.signInIntent(), DRIVE_SIGN_IN_REQUEST)
            }, matchWrap())
        } else {
            content.addView(TextView(this).apply {
                text = "已连接 ${account?.email ?: "Google 账号"}"
                textSize = 14f
                setTextColor(COLOR_SECONDARY_TEXT)
                setPadding(dp(6), dp(2), dp(6), dp(14))
            }, matchWrap())
            val selectedRoot = drivePreferences.root(repository.savedVaultUri()?.toString().orEmpty(), driveAuth.currentAccount()?.email.orEmpty())
            content.addView(settingsRow(
                "Drive Vault",
                selectedRoot?.name ?: "尚未选择远端文件夹",
                selectedRoot != null
            ) { showDriveFolderPicker(true) }, matchWrap().apply { bottomMargin = dp(12) })
            if (selectedRoot == null) {
                content.addView(action("选择 Drive Vault", true) { showDriveFolderPicker(true) }, matchWrap())
            } else {
                val syncSnapshot = currentSyncSnapshot()
                content.addView(TextView(this).apply {
                    text = "同步会比较 Markdown 和 assets；不会同步 .obsidian、.trash，也不会传播删除。"
                    textSize = 13f
                    setTextColor(COLOR_MUTED_TEXT)
                    setPadding(dp(6), dp(2), dp(6), dp(14))
                }, matchWrap())
                if (syncSnapshot?.isRunning == true) {
                    content.addView(action("查看后台同步详情", true) { showSyncDetails() }, matchWrap())
                } else {
                    content.addView(action("比较并同步", true) { showDriveSyncConfirmation(selectedRoot) }, matchWrap())
                }
                syncSnapshot?.let { snapshot ->
                    content.addView(settingsRow("同步详情", snapshot.statusLabel(this), false) { showSyncDetails() }, matchWrap().apply {
                        topMargin = dp(8)
                    })
                }
                content.addView(action("更换 Drive Vault", false) { showDriveFolderPicker(true) }, matchWrap().apply {
                    topMargin = dp(8)
                })
            }
        }
        scroll.addView(content, LinearLayout.LayoutParams(-1, -2))
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
    }

    private fun showDriveFolderPicker(reset: Boolean = false) {
        if (!driveAuth.isAuthorized(driveAuth.currentAccount())) {
            showDriveSetup("Google 账号需要重新登录")
            return
        }
        if (reset) {
            driveFolderDirectory = DriveVaultRoot("root", "我的云端硬盘")
            driveFolderHistory.clear()
        }
        drivePickerFolders = null
        drivePickerError = null
        drivePickerMessage = "正在读取 Drive 文件夹…"
        screen = Screen.DRIVE_FOLDER_PICKER
        renderDriveFolderPicker()
        loadDriveFolders()
    }

    private fun renderDriveFolderPicker() {
        val root = pageRoot(COLOR_BACKGROUND)
        val toolbar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(8))
            background = colorBlock(COLOR_SURFACE)
            addView(action("‹  Google Drive", false) { showDriveSetup() })
            addView(TextView(this@MainActivity).apply {
                text = "选择 Drive Vault"
                textSize = 17f
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                setTextColor(COLOR_PRIMARY_TEXT)
                maxLines = 1
            }, LinearLayout.LayoutParams(0, dp(44), 1f))
            addView(action("使用此目录", true) { confirmDriveFolder() })
        }
        root.addView(toolbar, matchWrap())
        root.addView(TextView(this).apply {
            text = driveFolderDirectory.name
            textSize = 13f
            setTextColor(COLOR_MUTED_TEXT)
            setPadding(dp(20), dp(8), dp(20), dp(8))
        }, matchWrap())
        val scroll = ScrollView(this)
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), 0, dp(16), dp(24))
        }
        if (driveFolderHistory.isNotEmpty()) {
            content.addView(action("‹  上一级", false) {
                driveFolderDirectory = driveFolderHistory.removeAt(driveFolderHistory.lastIndex)
                drivePickerFolders = null
                drivePickerError = null
                drivePickerMessage = "正在读取 Drive 文件夹…"
                renderDriveFolderPicker()
                loadDriveFolders()
            }, wrapWrap().apply { bottomMargin = dp(8) })
        }
        content.addView(action("新建文件夹", false) { promptCreateDriveFolder() }, matchWrap().apply {
            bottomMargin = dp(12)
        })
        when {
            drivePickerError != null -> content.addView(infoBanner(drivePickerError!!), matchWrap())
            drivePickerFolders == null -> content.addView(emptyState(drivePickerMessage ?: "正在读取 Drive 文件夹…"), matchWrap())
            drivePickerFolders!!.isEmpty() -> content.addView(emptyState("此目录没有子文件夹，仍可选择它作为 Vault。"), matchWrap())
            else -> {
                sectionLabel(content, "文件夹", drivePickerFolders!!.size)
                drivePickerFolders!!.forEach { folder ->
                    content.addView(vaultRow(R.drawable.ic_browser_folder, folder.name, "Google Drive 文件夹", "Google Drive 文件夹") {
                        driveFolderHistory += driveFolderDirectory
                        driveFolderDirectory = DriveVaultRoot(folder.id, folder.name)
                        drivePickerFolders = null
                        drivePickerError = null
                        drivePickerMessage = "正在读取 Drive 文件夹…"
                        renderDriveFolderPicker()
                        loadDriveFolders()
                    }, matchWrap().apply { bottomMargin = dp(8) })
                }
            }
        }
        scroll.addView(content, LinearLayout.LayoutParams(-1, -2))
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
    }

    private fun loadDriveFolders() {
        val location = driveFolderDirectory
        val account = driveAuth.currentAccount() ?: return
        driveExecutor.execute {
            try {
                val folders = GoogleDriveApi(driveAuth.accessToken(account)).listChildren(location.id)
                    .filter { it.mimeType == GoogleDriveApi.FOLDER_MIME_TYPE }
                    .sortedBy { it.name.lowercase() }
                runOnUiThread {
                    if (screen == Screen.DRIVE_FOLDER_PICKER && driveFolderDirectory.id == location.id) {
                        drivePickerFolders = folders
                        drivePickerMessage = null
                        renderDriveFolderPicker()
                    }
                }
            } catch (_: Exception) {
                runOnUiThread {
                    if (screen == Screen.DRIVE_FOLDER_PICKER && driveFolderDirectory.id == location.id) {
                        drivePickerError = "无法读取 Google Drive，请检查网络或重新登录后重试"
                        drivePickerMessage = null
                        renderDriveFolderPicker()
                    }
                }
            }
        }
    }

    private fun confirmDriveFolder() {
        val accountId = driveAuth.currentAccount()?.email ?: return
        val vaultId = repository.savedVaultUri()?.toString().orEmpty()
        if (currentSyncSnapshot()?.isRunning == true) {
            showDriveSetup("同步正在运行，完成后才能更换 Google Drive 目录。")
            return
        }
        if (LocalChangeJournal(this).changes(vaultId)?.isNotEmpty() == true) {
            showDriveSetup("存在尚未确认的本地搬运，请先完成或重试当前同步后再更换 Google Drive 目录。")
            return
        }
        drivePreferences.setRoot(driveFolderDirectory, vaultId, accountId)
        showDriveSetup("已选择 ${driveFolderDirectory.name}。同步前仍会再次确认范围。")
    }

    private fun promptCreateDriveFolder() {
        val input = EditText(dialogContext()).apply {
            hint = "文件夹名称"
            setSingleLine(true)
            setPadding(dp(20), dp(8), dp(20), dp(8))
        }
        val dialog = dialogBuilder()
            .setTitle("新建 Drive 文件夹")
            .setView(input)
            .setNegativeButton("取消", null)
            .setPositiveButton("创建", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val name = input.text.toString().trim()
                if (!validDriveFolderName(name)) {
                    input.error = "请输入不含 / 的文件夹名称"
                    return@setOnClickListener
                }
                dialog.dismiss()
                createDriveFolder(name)
            }
        }
        dialog.show()
    }

    private fun createDriveFolder(name: String) {
        val location = driveFolderDirectory
        val account = driveAuth.currentAccount() ?: run {
            showDriveSetup("Google 账号需要重新登录")
            return
        }
        drivePickerFolders = null
        drivePickerError = null
        drivePickerMessage = "正在创建 $name…"
        renderDriveFolderPicker()
        driveExecutor.execute {
            try {
                val api = GoogleDriveApi(driveAuth.accessToken(account))
                val folder = api.listChildren(location.id).firstOrNull {
                    it.name == name && it.mimeType == GoogleDriveApi.FOLDER_MIME_TYPE
                } ?: api.createFolder(location.id, name)
                runOnUiThread {
                    if (screen == Screen.DRIVE_FOLDER_PICKER && driveFolderDirectory.id == location.id) {
                        driveFolderHistory += location
                        driveFolderDirectory = DriveVaultRoot(folder.id, folder.name)
                        drivePickerFolders = null
                        drivePickerError = null
                        drivePickerMessage = "正在读取 Drive 文件夹…"
                        renderDriveFolderPicker()
                        loadDriveFolders()
                    }
                }
            } catch (_: Exception) {
                runOnUiThread {
                    if (screen == Screen.DRIVE_FOLDER_PICKER && driveFolderDirectory.id == location.id) {
                        drivePickerError = "无法创建文件夹，请检查网络或重新登录后重试"
                        drivePickerMessage = null
                        renderDriveFolderPicker()
                    }
                }
            }
        }
    }

    private fun validDriveFolderName(name: String): Boolean =
        name.isNotBlank() && name != "." && name != ".." && !name.contains('/') && !name.contains('\\')

    private fun showDriveSyncConfirmation(rootSelection: DriveVaultRoot) {
        screen = Screen.DRIVE_CONFIRM
        val root = pageRoot(COLOR_BACKGROUND)
        root.addView(simpleToolbar("‹  Google Drive", "确认同步") { showDriveSetup() }, matchWrap())
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(24))
        }
        content.addView(TextView(this).apply {
            text = "比较并同步"
            textSize = 22f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(COLOR_PRIMARY_TEXT)
        }, matchWrap())
        content.addView(TextView(this).apply {
            text = "本地 Vault：$currentVaultName\nGoogle Drive：${rootSelection.name}\n\n将比较 Markdown 与 assets。.obsidian、.trash、临时文件和本机同步信息不会上传。\n\n同步会在后台继续运行；开始后可立即返回文件库继续阅读或编辑。完成或失败会通过通知提醒，并可在设置中查看详情。\n\n同名但内容不同的文件会各保留一份冲突副本；本阶段不会删除任何一端的文件。"
            textSize = 15f
            setTextColor(COLOR_SECONDARY_TEXT)
            setPadding(0, dp(12), 0, dp(22))
        }, matchWrap())
        content.addView(action("开始同步", true) { startDriveSync(rootSelection) }, matchWrap())
        content.addView(action("取消", false) { showDriveSetup() }, matchWrap().apply { topMargin = dp(8) })
        root.addView(content, matchWrap())
        setContentView(root)
    }

    private fun startDriveSync(rootSelection: DriveVaultRoot) {
        val account = driveAuth.currentAccount()
        if (!driveAuth.isAuthorized(account) || account == null) {
            showDriveSetup("Google 账号需要重新登录")
            return
        }
        val vaultUri = repository.savedVaultUri()?.toString()
        if (vaultUri == null) {
            showDriveSetup("无法访问本地 Vault，请重新选择后再同步")
            return
        }
        BackgroundSyncService.startGoogleDrive(this, rootSelection, vaultUri)
        showDriveSetup("同步已在后台开始。你可以继续编辑本地文件；完成或失败时会通知你。")
    }

    private fun showSyncDetails() {
        screen = Screen.DRIVE_DETAILS
        val root = pageRoot(COLOR_BACKGROUND)
        root.addView(simpleToolbar(ui("‹  设置"), ui("同步详情")) { showSettings() }, matchWrap())
        val scroll = ScrollView(this)
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(24))
        }
        val snapshot = currentSyncSnapshot()
        if (snapshot == null) {
            content.addView(emptyState(ui("尚无同步记录。开始 Google Drive 同步后，可在这里查看进度和结果。")), matchWrap())
            content.addView(action("返回设置", true) { showSettings() }, matchWrap().apply { topMargin = dp(16) })
            scroll.addView(content, LinearLayout.LayoutParams(-1, -2))
            root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
            setContentView(root)
            return
        }
        content.addView(TextView(this).apply {
            text = snapshot.statusLabel(this@MainActivity)
            textSize = 22f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(COLOR_PRIMARY_TEXT)
        }, matchWrap())
        content.addView(TextView(this).apply {
            val finished = if (snapshot.finishedAt > 0) getString(R.string.sync_finished_prefix, formatSyncTime(snapshot.finishedAt)) else ""
            text = getString(
                R.string.sync_details,
                snapshot.providerName,
                snapshot.targetName,
                formatSyncTime(snapshot.startedAt),
                finished,
                snapshot.messageLabel(this@MainActivity),
                snapshot.completed,
                snapshot.total,
                snapshot.summary.uploaded,
                snapshot.summary.downloaded,
                snapshot.summary.unchanged,
                snapshot.summary.conflicts
            )
            textSize = 15f
            setTextColor(COLOR_SECONDARY_TEXT)
            setPadding(0, dp(12), 0, dp(12))
        }, matchWrap())
        if (snapshot.errors.isNotEmpty()) {
            val details = snapshot.errors.joinToString("\n") { error -> when (error.code) {
                SyncErrorCode.ITEM_FAILED -> getString(R.string.sync_error_item, error.path.ifBlank { snapshot.targetName })
                SyncErrorCode.INTERRUPTED -> getString(R.string.sync_error_interrupted)
            } }
            content.addView(infoBanner("${getString(R.string.sync_errors_heading)}\n$details"), matchWrap().apply {
                bottomMargin = dp(12)
            })
        }
        if (snapshot.isRunning) {
            content.addView(action("取消同步", false) {
                BackgroundSyncService.cancel(this@MainActivity)
                showSyncDetails()
            }, matchWrap().apply { bottomMargin = dp(8) })
        }
        content.addView(action("刷新状态", false) { showSyncDetails() }, matchWrap().apply { bottomMargin = dp(8) })
        content.addView(action("返回设置", true) { showSettings() }, matchWrap())
        scroll.addView(content, LinearLayout.LayoutParams(-1, -2))
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
    }

    private fun showDailyFolderPicker(reset: Boolean = false) {
        if (reset) {
            dailyFolderDirectory = null
            dailyFolderPath.clear()
            dailyFolderHistory.clear()
        }
        screen = Screen.DAILY_FOLDER_PICKER
        val generation = ++dailyFolderLoadGeneration
        val requestedDirectory = dailyFolderDirectory
        val loading = pageRoot(COLOR_BACKGROUND)
        loading.addView(simpleToolbar(ui("‹  设置"), ui("选择今日笔记目录")) { showSettings() }, matchWrap())
        loading.addView(emptyState(ui("正在读取 Vault 文件夹…")), matchWrap().apply {
            leftMargin = dp(16)
            rightMargin = dp(16)
            topMargin = dp(20)
        })
        setContentView(loading)
        noteIoExecutor.execute {
            val rootDirectory = repository.vaultRoot()
            val directory = requestedDirectory ?: rootDirectory
            val folders = if (directory == null) {
                emptyList()
            } else {
                repository.children(directory)
                    .filter { repository.isDirectory(it) && !isInternalDocument(it) }
                    .sortedBy { it.name.lowercase() }
            }
            runOnUiThread {
                if (isFinishing || isDestroyed || screen != Screen.DAILY_FOLDER_PICKER ||
                    generation != dailyFolderLoadGeneration) return@runOnUiThread
                if (rootDirectory == null || directory == null) {
                    showWelcome("无法访问 Vault，请重新选择")
                    return@runOnUiThread
                }
                if (requestedDirectory == null) dailyFolderDirectory = rootDirectory
                renderDailyFolderPicker(folders)
            }
        }
    }

    private fun renderDailyFolderPicker(folders: List<VaultDocument>) {
        val root = pageRoot(COLOR_BACKGROUND)
        val toolbar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(8))
            background = colorBlock(COLOR_SURFACE)
            addView(action("‹  设置", false) { showSettings() })
            addView(TextView(this@MainActivity).apply {
                text = "选择今日笔记目录"
                textSize = 17f
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                setTextColor(COLOR_PRIMARY_TEXT)
                maxLines = 1
            }, LinearLayout.LayoutParams(0, dp(44), 1f))
            addView(action("使用此目录", true) { confirmDailyFolder() })
        }
        root.addView(toolbar, matchWrap())
        root.addView(TextView(this).apply {
            text = if (dailyFolderPath.isEmpty()) "Vault 根目录" else dailyFolderPath.joinToString(" / ")
            textSize = 13f
            setTextColor(COLOR_MUTED_TEXT)
            setPadding(dp(20), dp(8), dp(20), dp(8))
        }, matchWrap())
        val scroll = ScrollView(this)
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), 0, dp(16), dp(24))
        }
        if (dailyFolderPath.isNotEmpty()) {
            content.addView(action("‹  上一级", false) { showDailyFolderParent() }, wrapWrap().apply {
                bottomMargin = dp(8)
            })
        }
        sectionLabel(content, "文件夹", folders.size)
        if (folders.isEmpty()) {
            content.addView(emptyState("当前目录没有可选子文件夹"), matchWrap())
        } else {
            folders.forEach { folder ->
                content.addView(vaultRow(R.drawable.ic_browser_folder, folder.name, "文件夹", "文件夹") { openDailyFolder(folder) }, matchWrap().apply {
                    bottomMargin = dp(8)
                })
            }
        }
        scroll.addView(content, LinearLayout.LayoutParams(-1, -2))
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
    }

    private fun openDailyFolder(folder: VaultDocument) {
        dailyFolderDirectory?.let { dailyFolderHistory.add(it) }
        dailyFolderPath.add(folder.name)
        dailyFolderDirectory = folder
        showDailyFolderPicker()
    }

    private fun showDailyFolderParent() {
        if (dailyFolderHistory.isEmpty()) return
        dailyFolderDirectory = dailyFolderHistory.removeAt(dailyFolderHistory.lastIndex)
        if (dailyFolderPath.isNotEmpty()) dailyFolderPath.removeAt(dailyFolderPath.lastIndex)
        showDailyFolderPicker()
    }

    private fun confirmDailyFolder() {
        repository.setDailyNoteDirectory(dailyFolderPath)
        showSettings()
    }

    private fun openDirectory(directory: VaultDocument) {
        browserDirectory?.let { browserHistory.add(it) }
        browserPath.add(directory.name)
        browserDirectory = directory
        browserScrollY = 0
        showVaultBrowser()
    }

    private fun showParentDirectory() {
        if (browserHistory.isEmpty()) return
        val parent = browserHistory.removeAt(browserHistory.lastIndex)
        browserDirectory = parent
        if (browserPath.isNotEmpty()) browserPath.removeAt(browserPath.lastIndex)
        browserScrollY = 0
        showVaultBrowser()
    }

    private fun openDailyNote() {
        openedFromSearch = false
        showNoteLoading(null, "正在打开今日笔记…")
        val generation = editorGeneration
        noteIoExecutor.execute {
            val result = repository.openDailyNote()
            runOnUiThread {
                if (!editorRequestIsCurrent(generation)) return@runOnUiThread
                when (result) {
                    is DailyNoteResult.Created -> {
                        currentNote = result.document
                        showEditor(result.document, "")
                    }
                    is DailyNoteResult.Existing -> openNote(result.document)
                    is DailyNoteResult.Failure -> showNoteOpenError(
                        null,
                        result.kind,
                        "今日笔记尚未打开，Vault 内容未被修改"
                    ) { openDailyNote() }
                }
            }
        }
    }

    private fun openNote(note: VaultDocument) {
        currentNote = note
        showNoteLoading(note, "正在读取笔记…")
        val generation = editorGeneration
        AppNoteSaveCoordinator.submitRead(repository, note) { result ->
            if (!editorRequestIsCurrent(generation, note)) return@submitRead
            when (result) {
                is NoteReadResult.Success -> when (val recovery = AppNoteSaveCoordinator.noteRecovery(note.uri.toString())) {
                    is AppNoteSaveCoordinator.NoteRecovery.Failed -> {
                        saveCoordinator.restore(
                            maxOf(saveCoordinator.revision, recovery.snapshot.revision),
                            saveCoordinator.persistedRevision,
                            failed = true
                        )
                        restoredSaveCoordinatorNoteUri = note.uri.toString()
                        showEditor(note, recovery.snapshot.content)
                    }
                    AppNoteSaveCoordinator.NoteRecovery.Active -> showNoteLoading(note, "正在恢复未完成的保存…")
                    AppNoteSaveCoordinator.NoteRecovery.Expired -> {
                        if (EditorRecoveryPolicy.requiresExplicitError(
                                restoredSaveCoordinatorNoteUri == note.uri.toString(),
                                saveCoordinator.revision,
                                saveCoordinator.persistedRevision,
                                AppNoteSaveCoordinator.getRevision(note.uri.toString()),
                                operationExpired = true
                            )) showUnsavedRecoveryError(note, result.content) else showEditor(note, result.content)
                    }
                    AppNoteSaveCoordinator.NoteRecovery.None -> {
                        val missingDirtySnapshot = EditorRecoveryPolicy.requiresExplicitError(
                            restoredSaveCoordinatorNoteUri == note.uri.toString(),
                            saveCoordinator.revision,
                            saveCoordinator.persistedRevision,
                            AppNoteSaveCoordinator.getRevision(note.uri.toString()),
                            operationExpired = false
                        )
                        if (missingDirtySnapshot) showUnsavedRecoveryError(note, result.content) else showEditor(note, result.content)
                    }
                }
                is NoteReadResult.Failure -> showNoteOpenError(
                    note,
                    result.kind,
                    "原笔记未被修改"
                ) { openNote(note) }
            }
        }
    }

    private fun showUnsavedRecoveryError(note: VaultDocument, savedContent: String) {
        releaseEditor()
        currentNote = note
        screen = Screen.EDITOR_ERROR
        val root = pageRoot(COLOR_EDITOR_BACKGROUND)
        root.addView(simpleToolbar("‹  文件", note.name.removeSuffix(".md")) {
            if (openedFromSearch) showSearch() else showVaultBrowser()
        }, matchWrap())
        root.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(36), dp(24), dp(24))
            addView(infoBanner("未保存内容无法恢复。Vault 中已保存的版本仍保持不变，应用不会自动覆盖它。"), matchWrap())
            addView(action("打开 Vault 中已保存版本", true) {
                AppNoteSaveCoordinator.discardNoteRecovery(note.uri.toString())
                restoredSaveCoordinatorNoteUri = null
                saveCoordinator.reset(AppNoteSaveCoordinator.getRevision(note.uri.toString()))
                showEditor(note, savedContent)
            }, matchWrap().apply { topMargin = dp(16) })
            addView(action("返回文件库", false) {
                if (openedFromSearch) showSearch() else showVaultBrowser()
            }, matchWrap().apply { topMargin = dp(8) })
        }, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
    }

    private fun showNoteLoading(note: VaultDocument?, message: String) {
        releaseEditor()
        currentNote = note
        screen = Screen.EDITOR_LOADING
        val root = pageRoot(COLOR_EDITOR_BACKGROUND)
        root.addView(simpleToolbar("‹  文件", note?.name?.removeSuffix(".md") ?: "今日笔记") {
            if (openedFromSearch) showSearch() else showVaultBrowser()
        }, matchWrap())
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(24), dp(44), dp(24), dp(24))
            addView(TextView(this@MainActivity).apply {
                text = message
                textSize = 16f
                gravity = Gravity.CENTER
                setTextColor(COLOR_SECONDARY_TEXT)
            }, matchWrap())
            addView(TextView(this@MainActivity).apply {
                text = "读取完成前不会显示默认正文或写入文件。"
                textSize = 13f
                gravity = Gravity.CENTER
                setTextColor(COLOR_MUTED_TEXT)
                setPadding(0, dp(10), 0, 0)
            }, matchWrap())
        }
        root.addView(content, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
    }

    private fun showNoteOpenError(
        note: VaultDocument?,
        kind: VaultFailureKind,
        preservationMessage: String,
        retry: () -> Unit
    ) {
        releaseEditor()
        currentNote = note
        screen = Screen.EDITOR_ERROR
        val root = pageRoot(COLOR_EDITOR_BACKGROUND)
        root.addView(simpleToolbar("‹  文件", note?.name?.removeSuffix(".md") ?: "今日笔记") {
            if (openedFromSearch) showSearch() else showVaultBrowser()
        }, matchWrap())
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(28), dp(20), dp(24))
            addView(TextView(this@MainActivity).apply {
                text = if (kind == VaultFailureKind.PERMISSION_DENIED) {
                    "无法访问当前 Vault"
                } else {
                    "无法读取这篇笔记"
                }
                textSize = 21f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(COLOR_PRIMARY_TEXT)
            }, matchWrap())
            addView(infoBanner("$preservationMessage。你可以重试、重新选择 Vault，或返回文件库。"),
                matchWrap().apply { topMargin = dp(14); bottomMargin = dp(16) })
            addView(action("重试", true, retry), matchWrap())
            addView(action("重新选择 Vault", false) { chooseVault() }, matchWrap().apply { topMargin = dp(8) })
            addView(action("返回文件库", false) { showVaultBrowser() }, matchWrap().apply { topMargin = dp(8) })
        }
        root.addView(content, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
    }

    private fun editorRequestIsCurrent(generation: Long, note: VaultDocument? = currentNote): Boolean {
        if (isFinishing || isDestroyed || generation != editorGeneration) return false
        if (screen != Screen.EDITOR_LOADING) return false
        return note == null || currentNote?.uri == note.uri
    }

    private fun showCreateMenu() {
        if (browserMutationPending) return
        browserMutationMessage = null
        dialogBuilder()
            .setTitle("新建")
            .setItems(arrayOf("新建笔记", "新建文件夹")) { _, index ->
                if (index == 0) showCreateDialog(VaultEntryKind.NOTE) else showCreateDialog(VaultEntryKind.FOLDER)
            }
            .show()
    }

    private fun showCreateDialog(kind: VaultEntryKind) {
        val parent = browserDirectory ?: return
        val input = EditText(dialogContext()).apply {
            hint = if (kind == VaultEntryKind.NOTE) "笔记名称" else "文件夹名称"
            setSingleLine(true)
            setPadding(dp(24), dp(4), dp(24), dp(4))
        }
        val verb = if (kind == VaultEntryKind.NOTE) "创建笔记" else "创建文件夹"
        val dialog = dialogBuilder()
            .setTitle(verb)
            .setView(mutationDialogView(input, "当前位置：${parent.relativePath.ifBlank { "Vault 根目录" }}" + if (kind == VaultEntryKind.NOTE) "\n笔记会自动添加 .md" else ""))
            .setNegativeButton("取消", null)
            .setPositiveButton("创建", null)
            .create()
        dialog.setOnShowListener {
            val submit = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            submit.setOnClickListener {
                when (val validation = VaultNamePolicy.validate(input.text.toString(), kind)) {
                    is VaultNameValidation.Invalid -> input.error = validation.message
                    is VaultNameValidation.Valid -> {
                        submit.isEnabled = false
                        submit.text = "创建中…"
                        dialog.setCancelable(false)
                        dialog.setCanceledOnTouchOutside(false)
                        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).isEnabled = false
                        browserMutationPending = true
                        val generation = browserLoadGeneration
                        val requestedName = input.text.toString()
                        structuralIoExecutor.execute {
                            val result = runStructuralMutation {
                                if (kind == VaultEntryKind.NOTE) repository.createNote(parent, requestedName)
                                else repository.createFolder(parent, requestedName)
                            }
                            runOnUiThread {
                                if (isFinishing || isDestroyed || screen != Screen.BROWSER || browserLoadGeneration != generation) return@runOnUiThread
                                browserMutationPending = false
                                when (result) {
                                    is VaultMutationResult.Success -> {
                                        dialog.dismiss()
                                        providerNameMessage(result)?.let(::toast)
                                        val document = result.document
                                        if (document == null) {
                                            showVaultBrowser()
                                        } else if (kind == VaultEntryKind.NOTE) openNote(document) else openDirectory(document)
                                    }
                                    is VaultMutationResult.Failure -> {
                                        submit.isEnabled = true
                                        submit.text = "创建"
                                        dialog.setCancelable(true)
                                        dialog.setCanceledOnTouchOutside(true)
                                        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).isEnabled = true
                                        input.error = mutationFailureMessage(result)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        dialog.show()
    }

    private fun showDocumentMenu(document: VaultDocument) {
        if (browserMutationPending) return
        browserMutationMessage = null
        val note = !repository.isDirectory(document) && document.name.endsWith(".md", true)
        val items = if (note) arrayOf("重命名", "移动到…", "移到回收站") else arrayOf("重命名", "移到回收站")
        dialogBuilder()
            .setTitle(document.name)
            .setItems(items) { _, index ->
                when {
                    index == 0 -> showRenameDialog(document)
                    note && index == 1 -> showMoveDestinationDialog(document)
                    else -> confirmMoveToTrash(document)
                }
            }
            .show()
    }

    private fun showMoveDestinationDialog(document: VaultDocument) {
        val vaultId = repository.savedVaultUri()?.toString().orEmpty()
        if (VaultMutationLease.isHeld(vaultId, VaultMutationLease.Kind.SYNC)) {
            dialogBuilder().setTitle("暂不能移动")
                .setMessage("当前 Vault 正在同步，完成后即可移动。")
                .setPositiveButton("知道了", null).show()
            return
        }
        val body = LinearLayout(dialogContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), 0, dp(8), 0)
        }
        val scroll = ScrollView(dialogContext()).apply { addView(body) }
        lateinit var dialog: AlertDialog
        var current: VaultDocument? = null
        val history = mutableListOf<VaultDocument>()
        var requestGeneration = 0L
        lateinit var showDirectory: (VaultDocument) -> Unit

        fun renderDirectory(directory: VaultDocument, folders: List<VaultDocument>, readable: Boolean) {
            if (!dialog.isShowing) return
            body.removeAllViews()
            body.addView(TextView(dialogContext()).apply {
                text = directory.relativePath.ifBlank { "Vault 根目录" }
                textSize = 13f
                setTextColor(COLOR_MUTED_TEXT)
                setPadding(dp(12), dp(2), dp(12), dp(8))
                contentDescription = "当前位置：$text"
            }, matchWrap())
            body.addView(infoBanner("选择笔记的新目录；图片和视频会随笔记移动。"), matchWrap().apply {
                leftMargin = dp(8); rightMargin = dp(8); bottomMargin = dp(8)
            })
            if (history.isNotEmpty()) {
                body.addView(action("‹  上一级", false) {
                    current = history.removeAt(history.lastIndex)
                    showDirectory(current!!)
                }, wrapWrap().apply { leftMargin = dp(8); bottomMargin = dp(6) })
            }
            if (!readable) {
                body.addView(infoBanner("无法读取此目录；文件没有被移动。"), matchWrap().apply {
                    leftMargin = dp(8); rightMargin = dp(8)
                })
                body.addView(action("重新读取", true) { showDirectory(directory) }, wrapWrap().apply {
                    leftMargin = dp(8); topMargin = dp(8)
                })
            } else if (folders.isEmpty()) {
                body.addView(emptyState("当前目录没有可进入的子文件夹"), matchWrap().apply {
                    leftMargin = dp(8); rightMargin = dp(8)
                })
            } else {
                sectionLabel(body, "子文件夹", folders.size)
                body.addView(vaultGroup(folders.map { folder ->
                    vaultRow(R.drawable.ic_browser_folder, folder.name, "进入文件夹", "文件夹", grouped = true) {
                        current?.let { history.add(it) }
                        current = folder
                        showDirectory(folder)
                    }
                }), matchWrap().apply { leftMargin = dp(8); rightMargin = dp(8) })
            }
            val select = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            val selectable = readable && directory.uri != document.parentUri
            select.isEnabled = selectable
            select.text = if (selectable) "选择此目录" else "当前笔记已在此目录"
        }

        showDirectory = { directory ->
            val request = ++requestGeneration
            current = directory
            body.removeAllViews()
            body.addView(emptyState("正在读取文件夹…"), matchWrap().apply {
                leftMargin = dp(8); rightMargin = dp(8)
            })
            noteIoExecutor.execute {
                val readable = repository.canReadDirectory(directory)
                val folders = if (readable) repository.children(directory)
                    .filter { repository.isDirectory(it) && !isInternalDocument(it) }
                    .sortedBy { it.name.lowercase(Locale.ROOT) }
                else emptyList()
                runOnUiThread {
                    if (isFinishing || isDestroyed || !dialog.isShowing || request != requestGeneration) return@runOnUiThread
                    renderDirectory(directory, folders, readable)
                }
            }
        }

        dialog = dialogBuilder()
            .setTitle("选择移动目标")
            .setView(scroll)
            .setNegativeButton("取消", null)
            .setPositiveButton("选择此目录", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val destination = current ?: return@setOnClickListener
                if (destination.uri == document.parentUri) return@setOnClickListener
                dialog.dismiss()
                confirmMoveNoteWithAssets(document, destination)
            }
            noteIoExecutor.execute {
                val root = repository.vaultRoot()
                runOnUiThread {
                    if (isFinishing || isDestroyed || !dialog.isShowing) return@runOnUiThread
                    if (root == null) {
                        body.removeAllViews()
                        body.addView(infoBanner("无法访问 Vault，请重新选择 Vault 后重试。"), matchWrap())
                        dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = false
                    } else showDirectory(root)
                }
            }
        }
        dialog.show()
    }

    private fun currentSyncSnapshot(): SyncTaskSnapshot? {
        val vaultId = repository.savedVaultUri()?.toString().orEmpty()
        return SyncTaskStateStore(this).snapshot()?.takeIf { it.vaultId == vaultId }
    }

    private fun runStructuralMutation(block: () -> VaultMutationResult): VaultMutationResult {
        if (repository.hasUnresolvedMoveTransactions()) {
            return VaultMutationResult.Failure(VaultMutationFailureKind.MOVE_UNSUPPORTED, "存在尚未恢复的笔记移动，请重启应用后重试")
        }
        val vaultId = repository.savedVaultUri()?.toString().orEmpty()
        val lease = VaultMutationLease.tryAcquire(vaultId, VaultMutationLease.Kind.STRUCTURAL)
            ?: return VaultMutationResult.Failure(VaultMutationFailureKind.MOVE_UNSUPPORTED, "当前 Vault 正在同步，请完成后重试")
        return try { block() } finally { VaultMutationLease.release(lease) }
    }

    private fun confirmMoveNoteWithAssets(document: VaultDocument, destination: VaultDocument) {
        val target = destination.relativePath.ifBlank { "Vault 根目录" }
        dialogBuilder().setTitle("移动笔记及附件？")
            .setMessage("将“${document.name.removeSuffix(".md")}”及其独占图片、视频等附件移到 $target，并更新该笔记的本地附件链接。其他笔记指向它的链接不会更新；若附件被其他笔记使用则不会移动。")
            .setNegativeButton("取消", null)
            .setPositiveButton("移动") { _, _ ->
                val generation = browserLoadGeneration
                browserMutationPending = true
                val progress = dialogBuilder().setTitle("正在移动笔记及附件…")
                    .setMessage("正在检查引用并更新 Vault；请勿重复操作。").create()
                progress.setCancelable(false)
                progress.show()
                structuralIoExecutor.execute {
                    val vaultId = repository.savedVaultUri()?.toString().orEmpty()
                    val lease = VaultMutationLease.tryAcquire(vaultId, VaultMutationLease.Kind.STRUCTURAL)
                    val result = if (lease == null) {
                        VaultMutationResult.Failure(VaultMutationFailureKind.MOVE_UNSUPPORTED, "当前 Vault 正在同步，请完成后重试")
                    } else try {
                        repository.moveNoteWithAssets(document, destination)
                    } finally {
                        VaultMutationLease.release(lease)
                    }
                    runOnUiThread {
                        progress.dismiss()
                        if (isFinishing || isDestroyed || screen != Screen.BROWSER || browserLoadGeneration != generation) return@runOnUiThread
                        browserMutationPending = false
                        when (result) {
                            is VaultMutationResult.Success -> { toast("笔记及附件已移动到 $target"); showVaultBrowser() }
                            is VaultMutationResult.Failure -> {
                                browserMutationMessage = "移动未完成，已保留 Vault 内容。${mutationFailureMessage(result)}"
                                showVaultBrowser()
                            }
                        }
                    }
                }
            }.show()
    }

    private fun showRenameDialog(document: VaultDocument) {
        val kind = if (repository.isDirectory(document)) VaultEntryKind.FOLDER else VaultEntryKind.NOTE
        val input = EditText(dialogContext()).apply {
            setText(if (kind == VaultEntryKind.NOTE) document.name.removeSuffix(".md") else document.name)
            selectAll()
            setSingleLine(true)
            setPadding(dp(24), dp(4), dp(24), dp(4))
        }
        val dialog = dialogBuilder()
            .setTitle("重命名")
            .setView(mutationDialogView(input, "当前位置：${document.parentRelativePath.ifBlank { "Vault 根目录" }}\n不会更新其他 Markdown 链接"))
            .setNegativeButton("取消", null)
            .setPositiveButton("保存", null)
            .create()
        dialog.setOnShowListener {
            val submit = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            submit.setOnClickListener {
                when (val validation = VaultNamePolicy.validate(input.text.toString(), kind)) {
                    is VaultNameValidation.Invalid -> input.error = validation.message
                    is VaultNameValidation.Valid -> {
                        submit.isEnabled = false
                        submit.text = "保存中…"
                        dialog.setCancelable(false)
                        dialog.setCanceledOnTouchOutside(false)
                        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).isEnabled = false
                        browserMutationPending = true
                        val generation = browserLoadGeneration
                        val requestedName = input.text.toString()
                        structuralIoExecutor.execute {
                            val result = runStructuralMutation { repository.rename(document, requestedName) }
                            runOnUiThread {
                                if (isFinishing || isDestroyed || screen != Screen.BROWSER || browserLoadGeneration != generation) return@runOnUiThread
                                browserMutationPending = false
                                when (result) {
                                    is VaultMutationResult.Success -> {
                                        dialog.dismiss()
                                        providerNameMessage(result)?.let(::toast)
                                        dailyDirectoryChangeMessage(result.dailyDirectoryChange)?.let(::toast)
                                        showVaultBrowser()
                                    }
                                    is VaultMutationResult.Failure -> {
                                        submit.isEnabled = true
                                        submit.text = "保存"
                                        dialog.setCancelable(true)
                                        dialog.setCanceledOnTouchOutside(true)
                                        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).isEnabled = true
                                        input.error = mutationFailureMessage(result)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        dialog.show()
    }

    private fun confirmMoveToTrash(document: VaultDocument) {
        val label = if (repository.isDirectory(document)) "文件夹“${document.name}”" else "笔记“${document.name.removeSuffix(".md")}”"
        dialogBuilder()
            .setTitle("移到回收站？")
            .setMessage("$label 将整体移入 Vault/.trash，不会同步到 Google Drive。Markdown 链接不会更新，图片附件不会自动删除。")
            .setNegativeButton("取消", null)
            .setPositiveButton("移到回收站") { _, _ ->
                val generation = browserLoadGeneration
                browserMutationPending = true
                val progress = dialogBuilder()
                    .setTitle("正在移到回收站…")
                    .setMessage("正在处理 Vault 文件；请勿重复操作。")
                    .create()
                progress.setCancelable(false)
                progress.setCanceledOnTouchOutside(false)
                progress.show()
                progress.setMessage("等待前序文件操作…")
                structuralIoExecutor.execute {
                    runOnUiThread {
                        if (!isFinishing && !isDestroyed) progress.setMessage("正在移到回收站…")
                    }
                    val result = runStructuralMutation { repository.moveToTrash(document) }
                    runOnUiThread {
                        progress.dismiss()
                        if (isFinishing || isDestroyed || screen != Screen.BROWSER || browserLoadGeneration != generation) return@runOnUiThread
                        browserMutationPending = false
                        when (result) {
                            is VaultMutationResult.Success -> {
                                browserMutationMessage = "已移到回收站，正在后台刷新列表…"
                                renderBrowserAfterConfirmedMove(document)
                                toast(dailyDirectoryChangeMessage(result.dailyDirectoryChange) ?: "已移到回收站")
                                refreshVaultBrowserAfterMove()
                            }
                            is VaultMutationResult.Failure -> {
                                browserMutationMessage = "移动未完成，源文件未改动。请左滑该条目后重试，或长按打开操作菜单。\n${mutationFailureMessage(result)}"
                                showVaultBrowser()
                            }
                        }
                    }
                }
            }
            .show()
    }

    private fun providerNameMessage(result: VaultMutationResult.Success): String? =
        when {
            result.actualName == null -> "操作已完成，名称待刷新"
            result.requestedName == result.actualName -> null
            else -> "Provider 已保存为“${result.actualName}”"
        }

    private fun mutationDialogView(input: EditText, message: String): View = LinearLayout(dialogContext()).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(8), 0, dp(8), 0)
        addView(TextView(dialogContext()).apply {
            text = message
            textSize = 13f
            setTextColor(COLOR_MUTED_TEXT)
            setPadding(dp(16), 0, dp(16), dp(8))
        }, matchWrap())
        addView(input, matchWrap())
    }

    private fun dailyDirectoryChangeMessage(change: DailyDirectoryChange): String? = when (change) {
        DailyDirectoryChange.UNCHANGED -> null
        DailyDirectoryChange.REWRITTEN -> "今日笔记目录已随文件夹重命名更新"
        DailyDirectoryChange.RESET_TO_ROOT -> "今日笔记目录已改为 Vault 根目录"
    }

    private fun mutationFailureMessage(result: VaultMutationResult.Failure): String = result.message ?: when (result.kind) {
        VaultMutationFailureKind.PERMISSION_DENIED -> "无法访问 Vault，请重新选择"
        VaultMutationFailureKind.READ_FAILED -> "无法读取当前目录，请稍后重试"
        VaultMutationFailureKind.INVALID_NAME -> "名称不合法"
        VaultMutationFailureKind.NAME_CONFLICT -> "当前目录已有同名项目"
        VaultMutationFailureKind.CREATE_FAILED -> "无法创建，请检查 Vault 权限"
        VaultMutationFailureKind.RENAME_FAILED -> "无法重命名，请检查 Vault 权限"
        VaultMutationFailureKind.TRASH_NAME_CONFLICT -> "回收站已有同名项目"
        VaultMutationFailureKind.MOVE_UNSUPPORTED -> "无法移到回收站：当前 Provider 不支持整体移动，原文件未改动"
    }

    private fun showEditor(note: VaultDocument, content: String) {
        releaseEditor()
        val noteUriString = note.uri.toString()
        val coordinatorRev = AppNoteSaveCoordinator.getRevision(noteUriString)
        if (restoredSaveCoordinatorNoteUri == noteUriString) {
            // Recreating this note's editor from savedInstanceState (e.g. rotation).
            // Preserve dirty or failed state if changes remain unpersisted.
            val actualPersisted = maxOf(saveCoordinator.persistedRevision, coordinatorRev)
            val actualCurrent = maxOf(saveCoordinator.revision, actualPersisted)
            val wasFailed = (saveCoordinator.state == RevisionSaveCoordinator.State.FAILED)
            val stillFailed = wasFailed && (coordinatorRev < actualCurrent)
            saveCoordinator.restore(actualCurrent, actualPersisted, stillFailed)
            if (saveCoordinator.hasUnsavedChanges && !wasFailed) {
                handler.removeCallbacks(autosave)
                handler.postDelayed(autosave, AUTOSAVE_DELAY_MS)
            }
        } else {
            val baseRev = maxOf(saveCoordinator.revision, coordinatorRev)
            saveCoordinator.reset(baseRev)
        }
        restoredSaveCoordinatorNoteUri = null
        saveWaiters.clear()
        screen = Screen.EDITOR
        val root = pageRoot(COLOR_EDITOR_BACKGROUND)
        val toolbar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(6))
            background = colorBlock(COLOR_SURFACE)
        }
        toolbar.addView(action("‹  文件", false) { returnToBrowser() })
        toolbar.addView(TextView(this).apply {
            text = note.name.removeSuffix(".md")
            textSize = 17f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(COLOR_PRIMARY_TEXT)
            gravity = Gravity.CENTER
            maxLines = 1
            contentDescription = note.name.removeSuffix(".md")
        }, LinearLayout.LayoutParams(0, dp(44), 1f))
        toolbar.addView(action("拍摄", false) { showCaptureChoices() })
        saveActionView = action("保存", true) { saveCurrentNote() }
        toolbar.addView(saveActionView)
        root.addView(toolbar, matchWrap())
        editorContextText = editorContext(note)
        val statusContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = colorBlock(COLOR_SURFACE)
        }
        statusView = TextView(this).apply {
            text = "已保存 · $editorContextText"
            textSize = 13f
            setTextColor(COLOR_SECONDARY_TEXT)
            setPadding(dp(20), dp(6), dp(20), dp(8))
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            contentDescription = "已保存，$editorContextText"
        }
        statusContainer.addView(statusView, matchWrap())
        editorStatusActions = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            setPadding(dp(20), 0, dp(20), dp(8))
        }
        statusContainer.addView(editorStatusActions, matchWrap())
        root.addView(statusContainer, matchWrap())
        val editor = WebView(this).apply {
            setBackgroundColor(COLOR_EDITOR_BACKGROUND)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            webViewClient = attachmentClient()
            addJavascriptInterface(EditorBridge(editorGeneration), "Android")
        }
        webView = editor
        root.addView(editor, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(markdownToolbar(), matchWrap())
        setContentView(root)
        editor.loadDataWithBaseURL(null, renderNote(note, content), "text/html", "UTF-8", null)
        updateSaveStatus()
        restorePendingVideoConfirmation(note)
    }

    private fun restorePendingVideoConfirmation(note: VaultDocument) {
        val session = repository.pendingVideoCapture() ?: return
        if (session.noteUri != note.uri.toString()) return
        if (repository.cleanupPendingVideoCaptureIfCommitted(note, session)) return
        if (session.stage == VIDEO_STAGE_LAUNCHED) return
        val cache = File(session.cachePath)
        if (!cache.isFile || cache.length() == 0L) return
        handler.post {
            if (screen != Screen.EDITOR || currentNote?.uri != note.uri) return@post
            val metadata = readVideoMetadata(cache, null)
            val validation = VideoCapturePolicy.validate(metadata) as? VideoCapturePolicy.Validation.Accepted ?: return@post
            captureFile = cache
            pendingVideoSession = session.copy(stage = VIDEO_STAGE_CONFIRMING)
            repository.savePendingVideoCapture(pendingVideoSession!!)
            videoMetadata = metadata
            showVideoConfirm(validation.extension)
        }
    }

    private fun renderNote(note: VaultDocument, content: String): String =
        MarkdownCodec.toHtml(content, { path ->
            repository.htmlAttachmentUrl(note, path) ?: "markbook://attachment/invalid"
        }, isNightTheme())

    private fun markdownToolbar(): View = HorizontalScrollView(this).apply {
        formatActions.clear()
        isHorizontalScrollBarEnabled = false
        background = colorBlock(COLOR_SURFACE)
        setPadding(dp(12), dp(6), dp(12), dp(10))
        addView(LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), 0, dp(4), 0)
            background = rounded(COLOR_ROW, dp(24))
            addView(formatIconAction("undo", R.drawable.ic_editor_undo, "撤销") { applyEditorFormat("undo") })
            addView(formatIconAction("redo", R.drawable.ic_editor_redo, "重做") { applyEditorFormat("redo") })
            addView(formatIconAction("heading", R.drawable.ic_editor_heading, "标题格式") { showHeadingPicker() })
            addView(formatIconAction("bold", R.drawable.ic_editor_bold, "加粗") { applyEditorFormat("bold") })
            addView(formatIconAction("italic", R.drawable.ic_editor_italic, "斜体") { applyEditorFormat("italic") })
            addView(formatIconAction("tag", R.drawable.ic_editor_tag, "添加标签") { promptForTag() })
            addView(formatIconAction("link", R.drawable.ic_editor_link, "添加链接") { promptForLink() })
            addView(formatIconAction("table", R.drawable.ic_editor_table, "插入表格") { applyEditorFormat("table") })
        }, LinearLayout.LayoutParams(-2, dp(44)))
    }

    private fun formatIconAction(key: String, icon: Int, label: String, onClick: () -> Unit): ImageButton = ImageButton(this).apply {
        setImageResource(icon)
        setColorFilter(COLOR_PRIMARY_TEXT)
        scaleType = android.widget.ImageView.ScaleType.CENTER
        minimumWidth = dp(44)
        minimumHeight = dp(44)
        setPadding(dp(10), dp(10), dp(10), dp(10))
        val ripple = android.util.TypedValue()
        this@MainActivity.theme.resolveAttribute(
            android.R.attr.selectableItemBackgroundBorderless,
            ripple,
            true
        )
        if (ripple.resourceId != 0) setBackgroundResource(ripple.resourceId)
        isClickable = true
        isFocusable = true
        contentDescription = label
        tooltipText = label
        setOnClickListener { onClick() }
        formatActions[key] = this
        if (key == "undo" || key == "redo") {
            isEnabled = false
            alpha = 0.42f
        }
    }

    private fun updateFormatActionState(key: String, selected: Boolean, enabled: Boolean = true) {
        val action = formatActions[key] ?: return
        action.isEnabled = enabled
        action.isSelected = selected
        action.alpha = if (enabled) 1f else 0.42f
        action.setColorFilter(if (selected) COLOR_ON_ACCENT else COLOR_PRIMARY_TEXT)
        action.background = rippleBackground(if (selected) COLOR_ACCENT else COLOR_ROW, dp(22))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            action.stateDescription = when {
                !enabled -> "不可用"
                selected -> "已启用"
                else -> "未启用"
            }
        }
    }

    private fun applyEditorFormat(command: String, value: String? = null) {
        val editor = webView ?: return
        val commandArgument = org.json.JSONObject.quote(command)
        val valueArgument = value?.let { org.json.JSONObject.quote(it) } ?: "null"
        editor.evaluateJavascript(
            "window.markbook && window.markbook.applyFormat ? window.markbook.applyFormat($commandArgument, $valueArgument) : false",
            null
        )
    }

    private fun showHeadingPicker() {
        val labels = arrayOf("正文", "一级标题 H1", "二级标题 H2", "三级标题 H3", "四级标题 H4", "五级标题 H5")
        val values = arrayOf("p", "h1", "h2", "h3", "h4", "h5")
        dialogBuilder()
            .setTitle("标题格式")
            .setItems(labels) { _, index -> applyEditorFormat("heading", values[index]) }
            .show()
    }

    private fun promptForTag() {
        promptForEditorValue("添加标签", "标签名，例如 项目", "添加") { value ->
            val tag = value.trim().removePrefix("#")
            when {
                tag.isBlank() -> "标签不能为空"
                tag.any { it == '/' || it == '\\' } -> "标签不能包含路径分隔符"
                tag.any { it.isWhitespace() } -> "标签不能包含空格"
                else -> {
                    applyEditorFormat("tag", tag)
                    null
                }
            }
        }
    }

    private fun promptForLink() {
        promptForEditorValue("添加链接", "https://example.com", "添加") { value ->
            val link = value.trim()
            if (link.isBlank()) "链接不能为空" else {
                applyEditorFormat("link", link)
                null
            }
        }
    }

    private fun promptForEditorValue(
        title: String,
        hint: String,
        confirmLabel: String,
        onConfirm: (String) -> String?
    ) {
        val input = EditText(dialogContext()).apply {
            this.hint = hint
            setSingleLine(true)
            setPadding(dp(24), dp(4), dp(24), dp(4))
        }
        val dialog = dialogBuilder()
            .setTitle(title)
            .setView(input)
            .setNegativeButton("取消", null)
            .setPositiveButton(confirmLabel, null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val error = onConfirm(input.text.toString())
                if (error == null) dialog.dismiss() else input.error = error
            }
        }
        dialog.show()
    }

    private fun returnToBrowser() {
        saveCurrentNote { success ->
            if (success) {
                if (openedFromSearch) showSearch() else showVaultBrowser()
            } else toast("保存失败，请先恢复 Vault 访问权限")
        }
    }

    private fun saveCurrentNote(onComplete: ((Boolean) -> Unit)? = null) {
        val editor = webView
        if (screen != Screen.EDITOR || editor == null) {
            onComplete?.invoke(false)
            return
        }
        if (!saveCoordinator.hasUnsavedChanges && !saveCoordinator.hasInFlightSave) {
            onComplete?.invoke(true)
            return
        }
        onComplete?.let(saveWaiters::add)
        handler.removeCallbacks(autosave)
        startNextSave()
    }

    private fun startNextSave() {
        val editor = webView ?: return
        val note = currentNote ?: return
        val request = saveCoordinator.beginSave() ?: run {
            updateSaveStatus()
            return
        }
        val generation = editorGeneration
        updateSaveStatus()
        editorReleaseCoordinator.onSerializationStarted(editor)
        val operationId = AppNoteSaveCoordinator.beginSerialization(note.uri.toString(), request.revision) {
            saveCoordinator.complete(request, false)
            editorReleaseCoordinator.onSerializationCompleted(editor) { target ->
                try { target.stopLoading(); target.destroy() } catch (_: Exception) {}
            }
            val pending = saveWaiters.toList()
            saveWaiters.clear()
            pending.forEach { it(false) }
            if (!isFinishing && !isDestroyed && generation == editorGeneration) updateSaveStatus()
        }
        if (operationId.isBlank()) {
            saveCoordinator.complete(request, false)
            editorReleaseCoordinator.onSerializationCompleted(editor) { target -> target.destroy() }
            updateSaveStatus()
            return
        }
        editor.evaluateJavascript("window.markbook && window.markbook.serialize ? window.markbook.serialize() : ''") { value ->
            try {
                if (value != null) {
                    val content = decodeJavascriptString(value)
                    if (!AppNoteSaveCoordinator.submitSnapshot(operationId, content)) return@evaluateJavascript
                    AppNoteSaveCoordinator.submitSave(repository, note, content, request.revision, operationId) { success, refreshed ->
                        if (isFinishing || isDestroyed || generation != editorGeneration || screen != Screen.EDITOR) {
                            return@submitSave
                        }
                        saveCoordinator.complete(request, success)
                        if (success && refreshed != null) currentNote = refreshed
                        updateSaveStatus()
                        if (!success) {
                            val pending = saveWaiters.toList()
                            saveWaiters.clear()
                            pending.forEach { it(false) }
                        } else if (saveCoordinator.hasUnsavedChanges) {
                            startNextSave()
                        } else {
                            val pending = saveWaiters.toList()
                            saveWaiters.clear()
                            pending.forEach { it(true) }
                        }
                    }
                } else {
                    AppNoteSaveCoordinator.failSerialization(operationId)
                    saveCoordinator.complete(request, false)
                    updateSaveStatus()
                }
            } catch (t: Throwable) {
                AppNoteSaveCoordinator.failSerialization(operationId)
                saveCoordinator.complete(request, false)
                throw t
            } finally {
                editorReleaseCoordinator.onSerializationCompleted(editor) { target ->
                    try {
                        target.stopLoading()
                        target.destroy()
                    } catch (_: Exception) {}
                }
            }
        }
    }

    private fun updateSaveStatus() {
        val state = saveCoordinator.state
        val message = when (state) {
            RevisionSaveCoordinator.State.SAVED -> "已保存"
            RevisionSaveCoordinator.State.DIRTY -> "未保存"
            RevisionSaveCoordinator.State.SAVING -> "正在保存…"
            RevisionSaveCoordinator.State.FAILED -> "保存失败 · 点按此处重试保存；请检查 Vault 权限或存储空间"
        }
        showEditorStatus(message, state == RevisionSaveCoordinator.State.FAILED)
        saveActionView?.isEnabled = !saveCoordinator.hasInFlightSave
        saveActionView?.alpha = if (saveCoordinator.hasInFlightSave) 0.55f else 1f
    }

    private fun showEditorStatus(message: String, retryable: Boolean = false) {
        val view = statusView ?: return
        view.text = if (retryable) "保存失败 · 请检查 Vault 权限或存储空间" else "$message · $editorContextText"
        view.maxLines = if (retryable) Int.MAX_VALUE else 1
        view.ellipsize = if (retryable) null else TextUtils.TruncateAt.END
        view.contentDescription = "$message，$editorContextText"
        view.isClickable = false
        view.isFocusable = false
        view.setOnClickListener(null)
        editorStatusActions?.apply {
            removeAllViews()
            visibility = if (retryable) View.VISIBLE else View.GONE
            if (retryable) addView(action("重试保存", true) { saveCurrentNote() }, matchWrap())
        }
        if (retryable) view.announceForAccessibility(message)
    }

    private fun showCaptureChoices() {
        if (photoSavePending || videoInsertPending) return
        dialogBuilder()
            .setTitle("拍摄")
            .setItems(arrayOf("拍照", "录视频")) { _, index ->
                if (index == 0) startPhotoCapture() else startVideoCapture()
            }
            .show()
    }

    private fun startPhotoCapture() {
        if (currentNote == null) {
            toast("请先打开一篇笔记")
            return
        }
        saveCurrentNote { success ->
            if (!success) {
                toast("请先保存笔记，再拍照")
                return@saveCurrentNote
            }
            capturePhotoContext { launchCamera() }
        }
    }

    private fun capturePhotoContext(onReady: () -> Unit) {
        val editor = webView
        val note = currentNote
        photoContextScrollY = editor?.scrollY ?: 0
        if (editor == null || note == null) {
            if (note != null) {
                noteIoExecutor.execute {
                    val persisted = repository.readText(note)
                    if (persisted == null) {
                        runOnUiThread {
                            if (!isFinishing && !isDestroyed) toast("无法读取笔记内容，已取消拍照")
                        }
                        return@execute
                    }
                    photoSavedBodyHash = repository.bodySha256(persisted)
                    runOnUiThread {
                        if (!isFinishing && !isDestroyed) onReady()
                    }
                }
            } else {
                onReady()
            }
            return
        }
        editor.evaluateJavascript(
            "window.markbook && window.markbook.serializeWithCaret ? window.markbook.serializeWithCaret() : ''"
        ) { value ->
            photoContextContent = decodeJavascriptString(value).ifBlank { null }
            val normOffset = photoContextContent
                ?.indexOf(MarkdownCodec.CARET_MARKER)
                ?.takeIf { it >= 0 } ?: 0
            noteIoExecutor.execute {
                val persisted = repository.readText(note)
                if (persisted == null) {
                    photoContextContent = null
                    photoSavedBodyHash = null
                    photoContextCaretOffset = 0
                    runOnUiThread {
                        if (!isFinishing && !isDestroyed) toast("无法读取笔记内容，已取消拍照")
                    }
                    return@execute
                }
                photoSavedBodyHash = repository.bodySha256(persisted)
                val normalized = photoContextContent?.replace(MarkdownCodec.CARET_MARKER, "")
                photoContextCaretOffset = PhotoCommitPolicy.safeInsertPoint(persisted, normOffset, normalized)
                runOnUiThread {
                    if (!isFinishing && !isDestroyed) onReady()
                }
            }
        }
    }

    private fun launchCamera() {
        val directory = File(cacheDir, "camera").apply { mkdirs() }
        captureFile = File(directory, "capture-${UUID.randomUUID()}.jpg")
        captureUri = Uri.parse("content://$FILE_PROVIDER_AUTHORITY/capture/${captureFile!!.name}")
        val intent = Intent("android.media.action.IMAGE_CAPTURE").apply {
            putExtra("output", captureUri)
            addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION)
            clipData = android.content.ClipData.newRawUri("photo", captureUri)
        }
        try {
            startActivityForResult(intent, PHOTO_CAMERA_REQUEST)
        } catch (_: Exception) {
            toast("系统相机不可用")
        }
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if ((requestCode == PHOTO_CAMERA_REQUEST || requestCode == VIDEO_CAMERA_REQUEST) && resultCode != RESULT_OK) {
            discardCaptureFile()
            if (requestCode == VIDEO_CAMERA_REQUEST) repository.clearPendingVideoCapture()
            return
        }
        if (resultCode != RESULT_OK) return
        if (requestCode == VAULT_REQUEST) {
            data?.data?.let {
                AppNoteSaveCoordinator.clearVaultScopedState()
                repository.rememberVault(it)
                browserDirectory = null
                browserPath.clear()
                recoverVaultAndOpenBrowser()
            }
            return
        }
        if (requestCode == DRIVE_SIGN_IN_REQUEST) {
            try {
                val account = driveAuth.accountFromResult(data)
                if (driveAuth.isAuthorized(account)) {
                    showDriveSetup("Google Drive 已连接，请选择远端 Vault。")
                } else {
                    showDriveSetup("Google 账号尚未授予 Drive 访问权限")
                }
            } catch (_: ApiException) {
                showDriveSetup("无法连接 Google Drive，请重试或确认该账号是测试用户")
            }
            return
        }
        if (requestCode == VIDEO_CAMERA_REQUEST) {
            handleVideoCameraResult(data)
            return
        }
        if (requestCode == PHOTO_CAMERA_REQUEST) {
            val bitmap = captureFile?.let { decodeCapturePreview(it) }
            if (bitmap == null) {
                toast("无法读取照片")
                discardCaptureFile()
            } else {
                showPhotoEditor(bitmap)
            }
        }
    }

    private fun decodeCapturePreview(file: File): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val longestSide = maxOf(bounds.outWidth, bounds.outHeight)
        var sampleSize = 1
        while (longestSide / sampleSize > MAX_PREVIEW_SIDE) sampleSize *= 2
        return BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply {
            inSampleSize = sampleSize
            inPreferredConfig = Bitmap.Config.ARGB_8888
        })
    }

    private fun startVideoCapture() {
        val note = currentNote ?: run {
            toast("请先打开一篇笔记")
            return
        }
        saveCurrentNote { saved ->
            if (!saved) {
                toast("请先保存笔记，再录视频")
                return@saveCurrentNote
            }
            capturePhotoContext {
                val markerOffset = photoContextCaretOffset
                noteIoExecutor.execute {
                    val persisted = repository.readText(note) ?: return@execute
                    val session = PendingVideoCaptureSession(
                        note.uri.toString(), note.relativePath, VideoCapturePolicy.sha256(persisted),
                        markerOffset.coerceIn(0, persisted.length), photoContextScrollY, "", VIDEO_STAGE_PREPARING
                    )
                    runOnUiThread {
                        if (!isFinishing && !isDestroyed && currentNote?.uri == note.uri) launchVideoCamera(session)
                    }
                }
            }
        }
    }

    private fun launchVideoCamera(prepared: PendingVideoCaptureSession? = repository.pendingVideoCapture()) {
        val session = prepared ?: return
        val directory = File(cacheDir, "camera").apply { mkdirs() }
        val output = File(directory, "capture-${UUID.randomUUID()}.mp4")
        val uri = Uri.parse("content://$FILE_PROVIDER_AUTHORITY/capture/${output.name}")
        captureFile = output
        captureUri = uri
        pendingVideoSession = session.copy(cachePath = output.absolutePath, stage = VIDEO_STAGE_LAUNCHED)
        repository.savePendingVideoCapture(pendingVideoSession!!)
        val intent = Intent(android.provider.MediaStore.ACTION_VIDEO_CAPTURE).apply {
            putExtra(android.provider.MediaStore.EXTRA_OUTPUT, uri)
            putExtra(android.provider.MediaStore.EXTRA_DURATION_LIMIT, VIDEO_DURATION_LIMIT_SECONDS)
            putExtra(android.provider.MediaStore.EXTRA_SIZE_LIMIT, VideoCapturePolicy.MAX_SIZE_BYTES)
            addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION)
            clipData = android.content.ClipData.newRawUri("video", uri)
        }
        try {
            startActivityForResult(intent, VIDEO_CAMERA_REQUEST)
        } catch (_: Exception) {
            discardCaptureFile()
            repository.clearPendingVideoCapture()
            toast("系统相机不可用")
        }
    }

    private fun handleVideoCameraResult(data: Intent?) {
        val session = repository.pendingVideoCapture() ?: return
        val output = File(session.cachePath)
        if ((!output.isFile || output.length() == 0L) && data?.data != null) {
            if (!copyReturnedVideoUri(data.data!!, output)) {
                discardCaptureFile()
                repository.clearPendingVideoCapture()
                toast("无法读取录制的视频")
                return
            }
        }
        val metadata = readVideoMetadata(output, data?.data)
        when (val validation = VideoCapturePolicy.validate(metadata)) {
            is VideoCapturePolicy.Validation.Rejected -> {
                discardCaptureFile()
                repository.clearPendingVideoCapture()
                toast(validation.message)
            }
            is VideoCapturePolicy.Validation.Accepted -> {
                captureFile = output
                pendingVideoSession = session.copy(cachePath = output.absolutePath, stage = VIDEO_STAGE_CONFIRMING)
                repository.savePendingVideoCapture(pendingVideoSession!!)
                videoMetadata = metadata
                showVideoConfirm(validation.extension)
            }
        }
    }

    private fun copyReturnedVideoUri(source: Uri, target: File): Boolean = try {
        contentResolver.openInputStream(source)?.use { input ->
            FileOutputStream(target).use { output ->
                val buffer = ByteArray(32 * 1024)
                var copied = 0L
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    copied += count
                    if (copied > VideoCapturePolicy.MAX_SIZE_BYTES) return false
                    output.write(buffer, 0, count)
                }
            }
        } != null
    } catch (_: Exception) {
        false
    }

    private fun readVideoMetadata(file: File, returnedUri: Uri?): VideoCapturePolicy.Metadata {
        var duration = 0L
        var hasVideo = false
        try {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(file.absolutePath)
                duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            } finally {
                retriever.release()
            }
            val extractor = android.media.MediaExtractor()
            try {
                extractor.setDataSource(file.absolutePath)
                hasVideo = (0 until extractor.trackCount).any { index ->
                    extractor.getTrackFormat(index).getString(android.media.MediaFormat.KEY_MIME)?.startsWith("video/") == true
                }
            } finally {
                extractor.release()
            }
        } catch (_: Exception) { }
        val header = ByteArray(64)
        val headerBytes = try {
            FileInputStream(file).use { it.read(header).coerceAtLeast(0) }
        } catch (_: Exception) {
            0
        }
        return VideoCapturePolicy.Metadata(
            file.length(), duration, returnedUri?.let(contentResolver::getType) ?: captureUri?.let(contentResolver::getType),
            VideoCapturePolicy.sniffContainer(header.copyOf(headerBytes)), hasVideo
        )
    }

    private fun showVideoConfirm(extension: String) {
        releaseEditor()
        screen = Screen.VIDEO
        val metadata = videoMetadata ?: return
        val root = pageRoot(COLOR_EDITOR_BACKGROUND)
        val header = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(6))
            addView(action("取消", false) { if (!videoInsertPending) cancelVideoCapture() })
            addView(TextView(this@MainActivity).apply {
                text = "确认视频"
                textSize = 18f
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                setTextColor(COLOR_PRIMARY_TEXT)
            }, LinearLayout.LayoutParams(0, dp(44), 1f))
            videoInsertAction = action("插入视频", true) { commitVideo(extension) }
            addView(videoInsertAction)
        }
        videoStatusView = TextView(this).apply {
            text = "大小 ${formatBytes(metadata.sizeBytes)} · 时长 ${VideoCapturePolicy.formatDuration(metadata.durationMs)}"
            textSize = 15f
            setTextColor(COLOR_SECONDARY_TEXT)
            setPadding(dp(20), dp(28), dp(20), dp(16))
        }
        root.addView(header, matchWrap())
        root.addView(videoStatusView, matchWrap())
        root.addView(action("重新录制", false) { if (!videoInsertPending) { discardCaptureFile(); launchVideoCamera(pendingVideoSession) } },
            matchWrap().apply { leftMargin = dp(20); rightMargin = dp(20) })
        setContentView(root)
    }

    private fun commitVideo(extension: String) {
        val note = currentNote ?: return
        val session = pendingVideoSession ?: return
        val cache = File(session.cachePath)
        if (videoInsertPending || !cache.isFile) return
        videoInsertPending = true
        videoCopyCancelled.set(false)
        videoInsertAction?.isEnabled = false
        videoStatusView?.text = "正在复制视频…"
        repository.savePendingVideoCapture(session.copy(stage = VIDEO_STAGE_COPYING))
        noteIoExecutor.execute {
            val attachment = repository.saveVideoAttachment(note, cache, extension) { copied ->
                runOnUiThread { videoStatusView?.text = "正在复制视频… ${formatBytes(copied)}（取消可返回）" }
                !videoCopyCancelled.get()
            }
            if (attachment == null) {
                runOnUiThread { showVideoFailure("视频尚未插入；缓存已保留，可重试或取消") }
                return@execute
            }
            val current = repository.readText(note)
            if (current == null || VideoCapturePolicy.sha256(current) != session.contentSha256) {
                runOnUiThread { showVideoFailure("笔记已在外部修改，未覆盖正文；视频缓存已保留") }
                return@execute
            }
            val relativePath = repository.relativeAttachmentPath(note, attachment)
            val attachmentSession = session.copy(
                stage = VIDEO_STAGE_ATTACHMENT_WRITTEN,
                attachmentVaultPath = "${attachment.relativeDirectory}/${attachment.name}"
            )
            repository.savePendingVideoCapture(attachmentSession)
            val content = insertVideoAtOffset(current, session.caretOffset, VideoCapturePolicy.videoLink(relativePath, videoMetadata?.durationMs ?: 0L))
            if (!repository.saveText(note, content)) {
                runOnUiThread { showVideoFailure("无法更新笔记，视频缓存已保留，可重试") }
                return@execute
            }
            AppNoteSaveCoordinator.advanceRevision(note.uri.toString())
            repository.savePendingVideoCapture(attachmentSession.copy(stage = VIDEO_STAGE_NOTE_SAVED))
            val refreshed = repository.refreshDocument(note) ?: note
            val cleaned = repository.confirmVideoAttachment(attachment, cache)
            if (cleaned) repository.clearPendingVideoCapture()
            runOnUiThread {
                currentNote = refreshed
                pendingEditorScrollY = session.scrollY
                pendingCaretLinkPath = relativePath
                pendingVideoSession = null
                videoMetadata = null
                videoStatusView = null
                videoInsertAction = null
                videoInsertPending = false
                captureFile = null
                captureUri = null
                showEditor(refreshed, content)
                showEditorStatus(if (cleaned) "视频已插入并保存" else "视频已保存；清理将在下次启动继续")
            }
        }
    }

    private fun insertVideoAtOffset(content: String, offset: Int, link: String): String {
        val point = offset.coerceIn(0, content.length)
        return content.substring(0, point) + "\n\n$link\n\n" + content.substring(point)
    }

    private fun cancelVideoCapture() {
        videoCopyCancelled.set(true)
        if (!videoInsertPending) {
            discardCaptureFile()
            repository.clearPendingVideoCapture()
            pendingVideoSession = null
            videoMetadata = null
            val note = currentNote
            if (note != null) {
                pendingEditorScrollY = photoContextScrollY
                openNote(note)
            } else showVaultBrowser()
        }
    }

    private fun showVideoFailure(message: String) {
        videoInsertPending = false
        videoInsertAction?.isEnabled = true
        videoStatusView?.text = message
    }

    private fun formatBytes(bytes: Long): String = when {
        bytes >= 1024L * 1024L -> String.format(Locale.ROOT, "%.1f MiB", bytes / (1024.0 * 1024.0))
        bytes >= 1024L -> String.format(Locale.ROOT, "%.1f KiB", bytes / 1024.0)
        else -> "$bytes B"
    }

    private fun showPhotoEditor(bitmap: Bitmap) {
        releaseEditor()
        screen = Screen.PHOTO
        photoSavePending = false
        val root = pageRoot(Color.BLACK)
        val header = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(6))
        }
        val view = PhotoEditorView(this, bitmap)
        editorView = view
        header.addView(action("取消", false) { restoreEditorScreen() })
        header.addView(TextView(this).apply {
            text = "调整照片"
            textSize = 18f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
        }, LinearLayout.LayoutParams(0, dp(44), 1f))
        photoInsertAction = action("插入照片", true) { commitPhoto(bitmap, view) }
        header.addView(photoInsertAction)
        photoStatusView = TextView(this).apply {
            text = "拖动四个角点调整裁剪范围"
            textSize = 13f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setPadding(dp(20), dp(2), dp(20), dp(10))
        }
        val modes = LinearLayout(this).apply {
            gravity = Gravity.CENTER
            setPadding(dp(16), 0, dp(16), dp(10))
        }
        val rectangleAction = action("矩形裁剪", false) { setPhotoMode(view, PhotoEditMode.RECTANGLE) }
        val perspectiveAction = action("四点校正", false) { setPhotoMode(view, PhotoEditMode.PERSPECTIVE) }
        photoModeActions = listOf(rectangleAction, perspectiveAction)
        modes.addView(rectangleAction, LinearLayout.LayoutParams(0, dp(44), 1f).apply { marginEnd = dp(8) })
        modes.addView(perspectiveAction, LinearLayout.LayoutParams(0, dp(44), 1f))
        setPhotoMode(view, PhotoEditMode.RECTANGLE)
        root.addView(header, matchWrap())
        root.addView(photoStatusView, matchWrap())
        root.addView(modes, matchWrap())
        root.addView(view, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
    }

    private fun commitPhoto(bitmap: Bitmap, view: PhotoEditorView) {
        if (photoSavePending) return
        val capture = captureFile
        val note = currentNote
        if (capture == null || note == null) {
            photoStatusView?.text = "照片已不可用，请返回笔记后重新拍摄"
            return
        }
        photoSavePending = true
        photoInsertAction?.isEnabled = false
        photoModeActions.forEach { it.isEnabled = false }
        photoStatusView?.text = "正在写入原图、校正图和笔记…"
        val transformRequest = view.freezeTransformRequest()
        val expectedSavedBodyHash = photoSavedBodyHash
        if (expectedSavedBodyHash.isNullOrBlank()) {
            photoSavePending = false
            photoInsertAction?.isEnabled = true
            photoModeActions.forEach { it.isEnabled = true }
            showPhotoSaveFailure("笔记基线已失效，请返回笔记后重新拍摄")
            return
        }
        AppNoteSaveCoordinator.startPhotoSession(capture.absolutePath, note.uri.toString())
        AppNoteSaveCoordinator.executeMedia {
            if (AppNoteSaveCoordinator.isPhotoSessionCancelled(capture.absolutePath)) {
                if (!bitmap.isRecycled) bitmap.recycle()
                return@executeMedia
            }
            val corrected = try {
                PhotoTransformer.transform(bitmap, transformRequest)
            } catch (_: Exception) {
                AppNoteSaveCoordinator.completePhotoSession(
                    capture.absolutePath,
                    false,
                    null,
                    null,
                    "无法处理照片，请调整后重试"
                )
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    showPhotoSaveFailure("无法处理照片，请调整后重试")
                }
                return@executeMedia
            }
            if (AppNoteSaveCoordinator.isPhotoSessionCancelled(capture.absolutePath)) {
                if (!bitmap.isRecycled) bitmap.recycle()
                return@executeMedia
            }
            noteIoExecutor.execute {
                if (!AppNoteSaveCoordinator.transitionPhotoSession(
                        capture.absolutePath,
                        AppNoteSaveCoordinator.PhotoPhase.PROCESSING,
                        AppNoteSaveCoordinator.PhotoPhase.ATTACHMENTS_WRITING
                    )) {
                    if (!bitmap.isRecycled) bitmap.recycle()
                    return@execute
                }
                val attachments = try {
                    FileInputStream(capture).use { repository.savePhotoPair(note, it, corrected) }
                } catch (_: Exception) {
                    null
                }
                if (attachments == null) {
                    AppNoteSaveCoordinator.completePhotoSession(
                        capture.absolutePath,
                        false,
                        null,
                        null,
                        "照片尚未插入，请检查 Vault 权限或存储空间后重试"
                    )
                    runOnUiThread {
                        if (isFinishing || isDestroyed) return@runOnUiThread
                        showPhotoSaveFailure("照片尚未插入，请检查 Vault 权限或存储空间后重试")
                    }
                    return@execute
                }
                if (!AppNoteSaveCoordinator.transitionPhotoSession(
                        capture.absolutePath,
                        AppNoteSaveCoordinator.PhotoPhase.ATTACHMENTS_WRITING,
                        AppNoteSaveCoordinator.PhotoPhase.READY_TO_COMMIT
                    )) {
                    repository.rollbackPhotoPair(attachments)
                    if (!bitmap.isRecycled) bitmap.recycle()
                    return@execute
                }
                val relativePath = repository.relativeAttachmentPath(note, attachments)
                val imageLink = "![${attachments.corrected}]($relativePath)"
                val savedBody = repository.readText(note)
                if (savedBody == null) {
                    repository.rollbackPhotoPair(attachments)
                    val readErrorMsg = "无法读取笔记内容，照片未插入；请检查权限或存储后重试"
                    AppNoteSaveCoordinator.completePhotoSession(
                        capture.absolutePath, false, null, null,
                        readErrorMsg
                    )
                    runOnUiThread {
                        if (!isFinishing && !isDestroyed) showPhotoSaveFailure(readErrorMsg)
                    }
                    return@execute
                }
                if (!PhotoCommitPolicy.canBeginCommit(expectedSavedBodyHash, savedBody)) {
                    repository.rollbackPhotoPair(attachments)
                    val changeMsg = "笔记已在外部更改，照片未插入；请返回笔记确认最新内容"
                    AppNoteSaveCoordinator.completePhotoSession(
                        capture.absolutePath, false, null, null,
                        changeMsg
                    )
                    runOnUiThread {
                        if (!isFinishing && !isDestroyed) showPhotoSaveFailure(changeMsg)
                    }
                    return@execute
                }
                val caret = PhotoCommitPolicy.adjustToSafeTokenBoundary(
                    savedBody,
                    photoContextCaretOffset.coerceIn(0, savedBody.length)
                )
                val originalContent = savedBody.substring(0, caret) +
                    MarkdownCodec.CARET_MARKER +
                    savedBody.substring(caret)
                val content = insertPhotoAtCapturePoint(originalContent, imageLink)
                if (!AppNoteSaveCoordinator.transitionPhotoSession(
                        capture.absolutePath,
                        AppNoteSaveCoordinator.PhotoPhase.READY_TO_COMMIT,
                        AppNoteSaveCoordinator.PhotoPhase.COMMITTING
                    )) {
                    repository.rollbackPhotoPair(attachments)
                    if (!bitmap.isRecycled) bitmap.recycle()
                    return@execute
                }
                repository.saveText(note, content)
                val committedNote = repository.refreshDocument(note)
                val committedBody = committedNote?.let(repository::readText)
                when (PhotoCommitPolicy.afterSave(committedBody, imageLink)) {
                PhotoPostSaveAction.CONFIRM -> {
                    AppNoteSaveCoordinator.advanceRevision(note.uri.toString())
                    val refreshed = checkNotNull(committedNote)
                    repository.confirmPhotoPair(attachments)
                    AppNoteSaveCoordinator.completePhotoSession(
                        capture.absolutePath,
                        true,
                        refreshed,
                        relativePath
                    )
                    runOnUiThread {
                        if (isFinishing || isDestroyed || AppNoteSaveCoordinator.isPhotoSessionCancelled(capture.absolutePath)) {
                            if (!bitmap.isRecycled) bitmap.recycle()
                            return@runOnUiThread
                        }
                        currentNote = refreshed
                        pendingEditorScrollY = photoContextScrollY
                        pendingCaretImagePath = relativePath
                        photoContextContent = null
                        photoSavedBodyHash = null
                        photoContextCaretOffset = 0
                        if (!bitmap.isRecycled) bitmap.recycle()
                        discardCaptureFile()
                        editorView = null
                        photoStatusView = null
                        photoInsertAction = null
                        photoModeActions = emptyList()
                        photoSavePending = false
                        showEditor(refreshed, content)
                        showEditorStatus("照片已插入并保存")
                    }
                }
                PhotoPostSaveAction.ROLLBACK -> {
                    repository.rollbackPhotoPair(attachments)
                    AppNoteSaveCoordinator.completePhotoSession(
                        capture.absolutePath,
                        false,
                        null,
                        null,
                        "无法更新笔记，照片尚未插入；可重试或返回笔记"
                    )
                    runOnUiThread {
                        if (isFinishing || isDestroyed) {
                            if (!bitmap.isRecycled) bitmap.recycle()
                            return@runOnUiThread
                        }
                        showPhotoSaveFailure("无法更新笔记，照片尚未插入；可重试或返回笔记")
                    }
                }
                PhotoPostSaveAction.RETAIN_FOR_RECOVERY -> {
                    AppNoteSaveCoordinator.requirePhotoRecovery(
                        capture.absolutePath,
                        "无法确认笔记写入结果；已保留照片事务，请重新打开 Vault 后检查"
                    )
                    runOnUiThread {
                        if (!isFinishing && !isDestroyed) showPhotoSaveFailure(
                            "无法确认笔记写入结果；已保留照片事务，请重新打开 Vault 后检查"
                        )
                    }
                }
                }
            }
        }
    }

    private fun restoreEditorScreen() {
        if (photoSavePending) {
            val cancel = captureFile?.let { AppNoteSaveCoordinator.requestPhotoCancel(it.absolutePath) }
            if (cancel != AppNoteSaveCoordinator.PhotoCancelResult.ACCEPTED) {
                photoStatusView?.text = if (cancel == AppNoteSaveCoordinator.PhotoCancelResult.TOO_LATE) {
                    "照片已开始提交，无法取消；正在确认写入结果…"
                } else {
                    "照片事务已经结束，正在确认结果…"
                }
                return
            }
            photoSavePending = false
        }
        discardCaptureFile()
        editorView = null
        pendingEditorScrollY = photoContextScrollY
        val restoredContent = photoContextContent?.replace(MarkdownCodec.CARET_MARKER, "")
        photoContextContent = null
        photoSavedBodyHash = null
        photoContextCaretOffset = 0
        photoStatusView = null
        photoInsertAction = null
        photoModeActions = emptyList()
        val note = currentNote
        if (note == null) {
            showVaultBrowser()
        } else if (restoredContent != null) {
            showEditor(note, restoredContent)
        } else {
            openNote(note)
        }
    }

    private fun setPhotoMode(view: PhotoEditorView, mode: PhotoEditMode) {
        if (photoSavePending) return
        view.mode = mode
        photoStatusView?.text = if (mode == PhotoEditMode.RECTANGLE) {
            "矩形裁剪：拖动任一角点调整范围"
        } else {
            "四点校正：分别拖动四个角点拉直画面"
        }
        photoModeActions.forEachIndexed { index, action ->
            val selected = (index == 0 && mode == PhotoEditMode.RECTANGLE) ||
                (index == 1 && mode == PhotoEditMode.PERSPECTIVE)
            action.isSelected = selected
            action.contentDescription = if (selected) "${action.text}，已选中" else action.text
            action.setTextColor(if (selected) COLOR_ON_ACCENT else COLOR_PRIMARY_TEXT)
            action.background = rounded(if (selected) COLOR_ACCENT else COLOR_ROW, dp(12))
        }
    }

    private fun showPhotoSaveFailure(message: String) {
        photoSavePending = false
        photoInsertAction?.isEnabled = true
        photoModeActions.forEach { it.isEnabled = true }
        photoStatusView?.text = message
    }

    private fun insertPhotoAtCapturePoint(base: String, imageLink: String): String =
        PhotoCommitPolicy.insertPhotoAtCapturePoint(base, imageLink)

    private fun removePhotoLink(content: String, imageLink: String): String {
        return content.replace("\n\n$imageLink\n\n", "\n\n")
            .replace("\n$imageLink\n", "\n")
            .replace(imageLink, "")
    }

    private fun attachmentClient(): WebViewClient = object : WebViewClient() {
        override fun onPageFinished(view: WebView?, url: String?) {
            super.onPageFinished(view, url)
            val scrollY = pendingEditorScrollY
            val imagePath = pendingCaretImagePath
            val linkPath = pendingCaretLinkPath
            pendingEditorScrollY = null
            pendingCaretImagePath = null
            pendingCaretLinkPath = null
            view?.post {
                if (scrollY != null) view.scrollTo(0, scrollY)
                if (imagePath != null) {
                    view.evaluateJavascript(
                        "window.markbook && window.markbook.focusAfterImage(${org.json.JSONObject.quote(imagePath)})",
                        null
                    )
                }
                if (linkPath != null) {
                    view.evaluateJavascript(
                        "window.markbook && window.markbook.focusAfterLink(${org.json.JSONObject.quote(linkPath)})",
                        null
                    )
                }
            }
        }

        override fun shouldInterceptRequest(view: WebView?, request: android.webkit.WebResourceRequest?): WebResourceResponse? {
            val uri = request?.url ?: return super.shouldInterceptRequest(view, request)
            if (uri.scheme != "markbook" || uri.host != "attachment") return super.shouldInterceptRequest(view, request)
            val path = Uri.decode(uri.path.orEmpty()).trimStart('/')
            val stream = repository.openRelativeAttachment(path) ?: return null
            val mime = when {
                path.endsWith(".png", true) -> "image/png"
                path.endsWith(".webp", true) -> "image/webp"
                else -> "image/jpeg"
            }
            return WebResourceResponse(mime, null, stream)
        }

        override fun shouldOverrideUrlLoading(view: WebView?, request: android.webkit.WebResourceRequest?): Boolean {
            val uri = request?.url ?: return false
            if (uri.scheme == "http" || uri.scheme == "https") return false
            val rawPath = Uri.decode(uri.toString())
            val resolved = currentNote?.let { repository.resolveAttachmentPath(it, rawPath) } ?: rawPath
            openAttachmentInSystemPlayer(resolved)
            return true
        }
    }

    private inner class EditorBridge(private val generation: Long) {
        @JavascriptInterface
        fun onChanged() {
            handler.post {
                if (screen != Screen.EDITOR || generation != editorGeneration) return@post
                saveCoordinator.markEdited()
                updateSaveStatus()
                handler.removeCallbacks(autosave)
                handler.postDelayed(autosave, AUTOSAVE_DELAY_MS)
            }
        }

        @JavascriptInterface
        fun onFormatState(value: String) {
            handler.post {
                if (screen != Screen.EDITOR || generation != editorGeneration) return@post
                val state = runCatching { org.json.JSONObject(value) }.getOrNull() ?: return@post
                updateFormatActionState("undo", false, state.optBoolean("undo"))
                updateFormatActionState("redo", false, state.optBoolean("redo"))
                updateFormatActionState("heading", state.optBoolean("heading"))
                updateFormatActionState("bold", state.optBoolean("bold"))
                updateFormatActionState("italic", state.optBoolean("italic"))
            }
        }

        @JavascriptInterface
        fun openAttachment(relativePath: String) {
            handler.post {
                val resolved = currentNote?.let { repository.resolveAttachmentPath(it, relativePath) } ?: relativePath
                openAttachmentInSystemPlayer(resolved)
            }
        }
    }

    private fun openAttachmentInSystemPlayer(relativePath: String) {
        if (!relativePath.lowercase(Locale.ROOT).endsWith(".mp4") && !relativePath.lowercase(Locale.ROOT).endsWith(".3gp")) return
        val suffix = relativePath.substringAfterLast('.', "mp4")
        val name = "play-${UUID.randomUUID()}.$suffix"
        val target = File(File(cacheDir, "camera").apply { mkdirs() }, name)
        val copied = try {
            repository.openRelativeAttachment(relativePath)?.use { input ->
                FileOutputStream(target).use { input.copyTo(it) }
            } != null
        } catch (_: Exception) { false }
        if (!copied) {
            toast("无法打开视频")
            return
        }
        val uri = Uri.parse("content://$FILE_PROVIDER_AUTHORITY/capture/$name")
        val mime = if (name.endsWith(".3gp", true)) "video/3gpp" else "video/mp4"
        try {
            startActivity(Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mime)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                clipData = android.content.ClipData.newRawUri("video", uri)
            })
        } catch (_: Exception) {
            toast("系统播放器不可用")
        }
    }

    private fun chooseVault() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }
        startActivityForResult(intent, VAULT_REQUEST)
    }

    private fun sectionLabel(container: LinearLayout, label: String, count: Int? = null) {
        container.addView(TextView(this).apply {
            text = count?.let { "$label  $it" } ?: label
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(COLOR_MUTED_TEXT)
            setPadding(dp(4), dp(14), dp(4), dp(8))
        }, matchWrap())
    }

    private fun vaultAccessErrorState(): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(18), dp(18), dp(18), dp(16))
        background = rounded(COLOR_ROW, dp(14))
        addView(TextView(this@MainActivity).apply {
            text = "无法读取当前 Vault"
            textSize = 17f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(COLOR_PRIMARY_TEXT)
        }, matchWrap())
        addView(TextView(this@MainActivity).apply {
            text = "文件没有被移动。请重新授予目录访问权限后再继续。"
            textSize = 13f
            setTextColor(COLOR_MUTED_TEXT)
            setPadding(0, dp(6), 0, dp(14))
        }, matchWrap())
        addView(action("重新选择 Vault", true) { chooseVault() }, wrapWrap())
    }

    private fun editorContext(note: VaultDocument): String {
        val location = note.parentRelativePath.ifBlank { "Vault 根目录" }
        return "$currentVaultName · $location"
    }

    private fun noteMetadata(
        note: VaultDocument,
        preview: String,
        source: VaultSearchSource? = null
    ): String {
        val today = SimpleDateFormat("yyyy-MM-dd'.md'", Locale.US).format(Date())
        val daily = if (VaultSearchPolicy.isDailyNote(note.relativePath, repository.dailyNoteDirectoryPath(), today)) ui("今日笔记") else null
        val time = VaultSearchPolicy.relativeTime(note.lastModified, System.currentTimeMillis())
        val sourceLabel = when (source) {
            VaultSearchSource.FILENAME -> ui("标题匹配")
            VaultSearchSource.TAG -> ui("标签匹配")
            VaultSearchSource.BODY -> ui("正文匹配")
            null -> null
        }
        return listOfNotNull(daily, time, sourceLabel, preview.takeIf { it.isNotBlank() }).joinToString(" · ")
    }

    private fun swipeDiscoveryHint(): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = dp(52)
        setPadding(dp(14), dp(6), dp(8), dp(6))
        background = rounded(COLOR_ROW, dp(12))
        contentDescription = ui("提示：左滑条目可重命名或移到回收站；长按也可操作。")
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        addView(TextView(this@MainActivity).apply {
            text = ui("左滑条目可重命名或移到回收站；长按也可操作")
            textSize = 13f
            setTextColor(COLOR_SECONDARY_TEXT)
        }, LinearLayout.LayoutParams(0, -2, 1f))
        addView(action("知道了", false) {
            (parent as? android.view.ViewGroup)?.removeView(this)
        }, wrapWrap())
        repository.markSwipeDiscoveryHintSeen()
        post { announceForAccessibility(ui("提示：左滑条目可重命名或移到回收站；长按也可操作。")) }
    }

    private fun vaultGroup(rows: List<View>): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = rounded(COLOR_ROW, dp(14))
        clipToOutline = true
        clipChildren = true
        rows.forEachIndexed { index, row ->
            addView(row, matchWrap())
            if (index < rows.lastIndex) {
                addView(View(this@MainActivity).apply { setBackgroundColor(COLOR_DIVIDER) },
                    LinearLayout.LayoutParams(-1, dp(1)).apply {
                        leftMargin = dp(54)
                    })
            }
        }
    }

    private fun vaultRow(
        icon: Int,
        title: String,
        subtitle: String,
        itemType: String = "",
        trailingLabel: String = "",
        trailingAccessibilityLabel: String = trailingLabel,
        trailingIcon: Int? = null,
        trailingAction: (() -> Unit)? = null,
        grouped: Boolean = false,
        action: () -> Unit
    ): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = dp(64)
        setPadding(dp(14), dp(8), dp(12), dp(8))
        background = rippleBackground(COLOR_ROW, if (grouped) 0 else dp(14))
        isClickable = true
        isFocusable = true
        contentDescription = listOf(itemType, title, subtitle).filter { it.isNotBlank() }.joinToString("，")
        setOnClickListener { action() }
        addView(ImageView(this@MainActivity).apply {
            setImageResource(icon)
            setColorFilter(COLOR_ACCENT)
            scaleType = android.widget.ImageView.ScaleType.CENTER
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(dp(32), dp(44)))
        addView(LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), 0, 0, 0)
            addView(TextView(this@MainActivity).apply {
                text = title
                textSize = 16f
                maxLines = 1
                setTextColor(COLOR_PRIMARY_TEXT)
            }, matchWrap())
            addView(TextView(this@MainActivity).apply {
                text = subtitle
                textSize = 13f
                maxLines = 1
                setTextColor(COLOR_MUTED_TEXT)
                setPadding(0, dp(3), 0, 0)
            }, matchWrap())
        }, LinearLayout.LayoutParams(0, -2, 1f))
        if (trailingAction == null) {
            addView(ImageView(this@MainActivity).apply {
                setImageResource(R.drawable.ic_chevron_right)
                setColorFilter(COLOR_MUTED_TEXT)
                scaleType = android.widget.ImageView.ScaleType.CENTER
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, LinearLayout.LayoutParams(dp(28), dp(44)))
        } else {
            if (trailingIcon != null) {
                addView(iconAction(trailingIcon, trailingAccessibilityLabel) { trailingAction() }, LinearLayout.LayoutParams(dp(44), dp(44)))
            } else {
                addView(action(trailingAccessibilityLabel, false, trailingAction).apply { text = trailingLabel }, wrapWrap())
            }
        }
    }

    private fun swipeableVaultRow(
        icon: Int,
        title: String,
        subtitle: String,
        itemType: String,
        document: VaultDocument,
        activate: () -> Unit
    ): View {
        val actionsWidth = swipeActionWidthPx() * 2
        val row = SwipeActionRow(
            this,
            actionsWidth,
            ViewConfiguration.get(this).scaledTouchSlop
        )
        val actionStrip = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(swipeAction("重命名", color = COLOR_RENAME_ACTION) {
                row.close(animated = false)
                showRenameDialog(document)
            }, LinearLayout.LayoutParams(0, -1, 1f))
            addView(swipeAction("删除", "删除，移到回收站", COLOR_TRASH_ACTION) {
                row.close(animated = false)
                confirmMoveToTrash(document)
            }, LinearLayout.LayoutParams(0, -1, 1f))
        }
        val label = listOf(itemType, title, subtitle).filter { it.isNotBlank() }.joinToString("，")
        val foreground = vaultRow(icon, title, subtitle, itemType, grouped = true, action = activate)
        row.bind(
            foreground = foreground,
            actionStrip = actionStrip,
            title = title,
            accessibilityLabel = label,
            onActivate = {
                if (!consumeSwipeDismissActivation()) activate()
            },
            onLongPress = { showDocumentMenu(document) },
            onRename = { showRenameDialog(document) },
            onMoveToTrash = { confirmMoveToTrash(document) },
            onOpenStateChanged = { changedRow, isOpen ->
                if (isOpen) {
                    openSwipeRow?.takeIf { it !== changedRow }?.close(animated = false)
                    openSwipeRow = changedRow
                } else if (openSwipeRow === changedRow) {
                    openSwipeRow = null
                }
            }
        )
        return row
    }

    private fun consumeSwipeDismissActivation(): Boolean {
        val suppress = SwipeDismissTouchPolicy.suppressActivation(swipeDismissTouch)
        if (suppress) swipeDismissTouch = null
        return suppress
    }

    private fun swipeActionWidthPx(): Int {
        val fontScale = resources.configuration.fontScale.coerceAtLeast(1f)
        val scaledMinimum = dp(SWIPE_ACTION_WIDTH_DP) * fontScale
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = SWIPE_ACTION_TEXT_SP * resources.displayMetrics.scaledDensity
            typeface = Typeface.DEFAULT_BOLD
        }
        val widestLabel = maxOf(textPaint.measureText("重命名"), textPaint.measureText("删除"))
        val textWidth = widestLabel + dp(SWIPE_ACTION_HORIZONTAL_PADDING_DP * 2)
        return ceil(maxOf(scaledMinimum, textWidth).toDouble()).toInt()
    }

    private fun swipeAction(
        label: String,
        accessibilityLabel: String = label,
        color: Int,
        onClick: () -> Unit
    ): TextView = TextView(this).apply {
        text = UiText.label(this@MainActivity, label)
        textSize = SWIPE_ACTION_TEXT_SP
        typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER
        setSingleLine(true)
        ellipsize = TextUtils.TruncateAt.END
        minHeight = dp(44)
        setPadding(dp(4), dp(8), dp(4), dp(8))
        setTextColor(Color.WHITE)
        background = rounded(color, 0)
        isClickable = true
        isFocusable = true
        contentDescription = UiText.label(this@MainActivity, accessibilityLabel)
        setOnClickListener { onClick() }
    }

    private fun closeOpenSwipeRow(animated: Boolean = true): Boolean {
        val row = openSwipeRow ?: return false
        openSwipeRow = null
        row.close(animated)
        return true
    }

    private fun settingsRow(title: String, subtitle: String, selected: Boolean, action: () -> Unit): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = dp(68)
        setPadding(dp(16), dp(10), dp(12), dp(10))
        background = rippleBackground(COLOR_ROW, dp(14))
        isClickable = true
        isFocusable = true
        contentDescription = "$title，$subtitle"
        setOnClickListener { action() }
        addView(LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(this@MainActivity).apply {
                text = title
                textSize = 16f
                setTextColor(COLOR_PRIMARY_TEXT)
            }, matchWrap())
            addView(TextView(this@MainActivity).apply {
                text = subtitle
                textSize = 13f
                maxLines = 1
                setTextColor(COLOR_MUTED_TEXT)
                setPadding(0, dp(3), 0, 0)
            }, matchWrap())
        }, LinearLayout.LayoutParams(0, -2, 1f))
        if (selected) {
            addView(TextView(this@MainActivity).apply {
                text = ui("已启用  ✓")
                textSize = 13f
                gravity = Gravity.CENTER
                setTextColor(COLOR_ACCENT)
            }, LinearLayout.LayoutParams(dp(72), dp(44)))
        } else {
            addView(ImageView(this@MainActivity).apply {
                setImageResource(R.drawable.ic_chevron_right)
                setColorFilter(COLOR_MUTED_TEXT)
                scaleType = android.widget.ImageView.ScaleType.CENTER
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, LinearLayout.LayoutParams(dp(72), dp(44)))
        }
    }

    private fun settingsStatusRow(title: String, subtitle: String, status: String, active: Boolean): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = dp(68)
        setPadding(dp(16), dp(10), dp(12), dp(10))
        background = rounded(COLOR_ROW, dp(14))
        contentDescription = "$title，$subtitle，$status"
        addView(LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(this@MainActivity).apply {
                text = title
                textSize = 16f
                setTextColor(COLOR_PRIMARY_TEXT)
            }, matchWrap())
            addView(TextView(this@MainActivity).apply {
                text = subtitle
                textSize = 13f
                maxLines = 1
                setTextColor(COLOR_MUTED_TEXT)
                setPadding(0, dp(3), 0, 0)
            }, matchWrap())
        }, LinearLayout.LayoutParams(0, -2, 1f))
        addView(TextView(this@MainActivity).apply {
            text = status
            textSize = 13f
            gravity = Gravity.CENTER
            setTextColor(if (active) COLOR_ACCENT else COLOR_MUTED_TEXT)
        }, LinearLayout.LayoutParams(dp(92), dp(44)))
    }

    private fun emptyState(message: String): View = TextView(this).apply {
        text = message
        textSize = 14f
        gravity = Gravity.CENTER
        setTextColor(COLOR_MUTED_TEXT)
        setPadding(dp(16), dp(22), dp(16), dp(22))
        background = rounded(COLOR_ROW, dp(14))
    }

    private fun infoBanner(message: String): View = TextView(this).apply {
        text = message
        textSize = 14f
        setTextColor(COLOR_PRIMARY_TEXT)
        setPadding(dp(14), dp(12), dp(14), dp(12))
        background = rounded(COLOR_ROW, dp(12))
    }

    private fun action(label: String, primary: Boolean, onClick: () -> Unit): TextView = TextView(this).apply {
        text = UiText.label(this@MainActivity, label)
        textSize = 14f
        typeface = if (primary) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        gravity = Gravity.CENTER
        minimumHeight = dp(44)
        setPadding(dp(12), 0, dp(12), 0)
        setTextColor(if (primary) COLOR_ON_ACCENT else COLOR_PRIMARY_TEXT)
        background = rippleBackground(if (primary) COLOR_ACCENT else COLOR_ROW, dp(12))
        isClickable = true
        isFocusable = true
        contentDescription = UiText.label(this@MainActivity, label)
        setOnClickListener { onClick() }
    }

    private fun iconAction(icon: Int, label: String, onClick: (View) -> Unit): ImageButton = ImageButton(this).apply {
        setImageResource(icon)
        setColorFilter(COLOR_PRIMARY_TEXT)
        scaleType = android.widget.ImageView.ScaleType.CENTER
        minimumWidth = dp(44)
        minimumHeight = dp(44)
        background = rippleBackground(COLOR_ROW, dp(12))
        isClickable = true
        isFocusable = true
        contentDescription = UiText.label(this@MainActivity, label)
        tooltipText = UiText.label(this@MainActivity, label)
        setOnClickListener { onClick(this) }
    }

    private fun openVaultFile(document: VaultDocument) {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(document.uri, VaultBrowserPolicy.viewerMimeType(document.name, document.mimeType))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            clipData = android.content.ClipData.newRawUri("vault-file", document.uri)
        }
        try {
            startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            toast(ui("无法打开此文件，请安装支持该格式的查看器。"))
        } catch (_: SecurityException) {
            toast(ui("无法打开此文件，请安装支持该格式的查看器。"))
        }
    }

    /** Attachment folders remain excluded from management-only directory pickers. */
    private fun isInternalDocument(document: VaultDocument): Boolean {
        val leaf = document.relativePath.substringAfterLast('/')
        return VaultPathPolicy.isProtected(document.relativePath) || leaf.startsWith(".markbook-")
    }

    private fun notePreview(note: VaultDocument): String {
        val value = repository.readPreview(note, NOTE_PREVIEW_BYTES).orEmpty()
            .lineSequence()
            .map { it.trim().removePrefix("#").trim() }
            .firstOrNull { it.isNotBlank() && !it.startsWith("![") }
            .orEmpty()
        return if (value.isBlank()) "空白笔记" else value.take(88)
    }

    /** Use only for developer-authored presentation strings; never pass Vault-derived values here. */
    private fun ui(source: String): String = UiText.label(this, source)

    private fun decodeJavascriptString(value: String): String = try {
        org.json.JSONTokener(value).nextValue() as? String ?: ""
    } catch (_: Exception) {
        ""
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()

    private fun discardCaptureFile() {
        captureFile?.let { file ->
            AppNoteSaveCoordinator.clearPhotoSession(file.absolutePath)
            file.delete()
        }
        captureFile = null
        captureUri = null
        photoSavedBodyHash = null
        photoContextCaretOffset = 0
    }

    private fun pageRoot(color: Int): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(color)
    }

    private fun simpleToolbar(backLabel: String, title: String, back: () -> Unit): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(12), dp(10), dp(12), dp(8))
        background = colorBlock(COLOR_SURFACE)
        addView(action(backLabel, false, back))
        addView(TextView(this@MainActivity).apply {
            text = title
            textSize = 19f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(COLOR_PRIMARY_TEXT)
            maxLines = 1
        }, LinearLayout.LayoutParams(0, dp(44), 1f))
        addView(TextView(this@MainActivity), LinearLayout.LayoutParams(dp(96), dp(44)))
    }

    private fun rounded(color: Int, radius: Int): GradientDrawable = GradientDrawable().apply {
        setColor(color)
        cornerRadius = radius.toFloat()
    }

    private fun rippleBackground(color: Int, radius: Int): android.graphics.drawable.RippleDrawable =
        android.graphics.drawable.RippleDrawable(
            android.content.res.ColorStateList.valueOf(
                if (isNightTheme()) COLOR_ACCENT else Color.argb(42, 47, 107, 79)
            ),
            rounded(color, radius),
            null
        )

    private fun colorBlock(color: Int): GradientDrawable = GradientDrawable().apply { setColor(color) }

    private fun matchWrap(): LinearLayout.LayoutParams = LinearLayout.LayoutParams(-1, -2)

    private fun wrapWrap(): LinearLayout.LayoutParams = LinearLayout.LayoutParams(-2, -2)

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun formatSyncTime(time: Long): String =
        java.text.DateFormat.getDateTimeInstance(
            java.text.DateFormat.SHORT,
            java.text.DateFormat.SHORT,
            UiLanguage.locale(this)
        ).format(Date(time))

    private fun isNightTheme(): Boolean = repository.appearanceMode() == VaultRepository.APPEARANCE_NIGHT

    private fun dialogContext(): ContextThemeWrapper = ContextThemeWrapper(
        this,
        if (isNightTheme()) R.style.AppTheme_Dialog_Night else R.style.AppTheme_Dialog_Light
    )

    private fun dialogBuilder(): AlertDialog.Builder = LocalizedAlertDialogBuilder(dialogContext())

    private fun applyWindowColors() {
        window.statusBarColor = COLOR_SURFACE
        window.navigationBarColor = COLOR_SURFACE
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val lightFlags = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
            window.decorView.systemUiVisibility = if (isNightTheme()) {
                window.decorView.systemUiVisibility and lightFlags.inv()
            } else {
                window.decorView.systemUiVisibility or lightFlags
            }
        }
    }

    private val COLOR_BACKGROUND: Int
        get() = if (isNightTheme()) Color.rgb(18, 28, 24) else Color.rgb(247, 247, 242)
    private val COLOR_SURFACE: Int
        get() = if (isNightTheme()) Color.rgb(27, 38, 33) else Color.WHITE
    private val COLOR_ROW: Int
        get() = if (isNightTheme()) Color.rgb(40, 54, 47) else Color.rgb(237, 241, 237)
    private val COLOR_DIVIDER: Int
        get() = if (isNightTheme()) Color.rgb(61, 78, 68) else Color.rgb(215, 223, 216)
    private val COLOR_EDITOR_BACKGROUND: Int
        get() = if (isNightTheme()) Color.rgb(24, 35, 30) else Color.rgb(247, 247, 242)
    private val COLOR_ACCENT: Int
        get() = if (isNightTheme()) Color.rgb(141, 207, 168) else Color.rgb(47, 107, 79)
    private val COLOR_PRIMARY_TEXT: Int
        get() = if (isNightTheme()) Color.rgb(239, 246, 241) else Color.rgb(24, 32, 28)
    private val COLOR_SECONDARY_TEXT: Int
        get() = if (isNightTheme()) Color.rgb(195, 211, 201) else Color.rgb(70, 81, 75)
    private val COLOR_MUTED_TEXT: Int
        get() = if (isNightTheme()) Color.rgb(158, 178, 166) else Color.rgb(102, 113, 107)
    private val COLOR_ON_ACCENT: Int
        get() = if (isNightTheme()) Color.rgb(24, 32, 28) else Color.WHITE
    private val COLOR_RENAME_ACTION: Int
        get() = if (isNightTheme()) Color.rgb(46, 94, 150) else Color.rgb(37, 105, 169)
    private val COLOR_TRASH_ACTION: Int
        get() = if (isNightTheme()) Color.rgb(190, 82, 74) else Color.rgb(176, 54, 47)

    private enum class Screen {
        WELCOME, BROWSER, SEARCH, EDITOR_LOADING, EDITOR_ERROR, EDITOR, PHOTO, VIDEO, SETTINGS, TRASH, TRASH_VIEWER, DAILY_FOLDER_PICKER,
        DRIVE_SETUP, DRIVE_FOLDER_PICKER, DRIVE_CONFIRM, DRIVE_DETAILS
    }

    private data class BrowserSnapshot(
        val rootDirectory: VaultDocument,
        val directoryReadable: Boolean,
        val children: List<VaultDocument>,
        val previews: Map<String, String>
    )

    companion object {
        private const val VAULT_REQUEST = 1002
        private const val PHOTO_CAMERA_REQUEST = 1003
        private const val VIDEO_CAMERA_REQUEST = 1005
        private const val DRIVE_SIGN_IN_REQUEST = 1004
        private const val FILE_PROVIDER_AUTHORITY = "com.jambus.heji.fileprovider"
        private const val MAX_PREVIEW_SIDE = 2560
        private const val NOTE_PREVIEW_BYTES = 4096
        private const val AUTOSAVE_DELAY_MS = 600L
        private const val SWIPE_ACTION_WIDTH_DP = 76
        private const val SWIPE_ACTION_TEXT_SP = 14f
        private const val SWIPE_ACTION_HORIZONTAL_PADDING_DP = 4
        private const val VIDEO_DURATION_LIMIT_SECONDS = 180
        private const val VIDEO_STAGE_PREPARING = "preparing"
        private const val VIDEO_STAGE_LAUNCHED = "launched"
        private const val VIDEO_STAGE_CONFIRMING = "confirming"
        private const val VIDEO_STAGE_COPYING = "copying"
        private const val VIDEO_STAGE_ATTACHMENT_WRITTEN = "attachment_written"
        private const val VIDEO_STAGE_NOTE_SAVED = "note_saved"
        private const val MENU_SETTINGS = 1
        private const val MENU_SWITCH_VAULT = 2
    }
}
