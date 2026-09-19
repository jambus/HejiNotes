package com.jambus.heji

import android.graphics.PointF
import android.net.Uri
import android.os.Bundle

/**
 * Helper constants and serialization utilities for Activity instance state preservation.
 */
object SavedStateBundle {
    const val KEY_SCREEN = "heji_screen"

    // Browser
    const val KEY_BROWSER_PATH = "heji_browser_path"
    const val KEY_BROWSER_SCROLL_Y = "heji_browser_scroll_y"

    // Editor / Note
    const val PREFIX_NOTE = "heji_current_note"
    const val KEY_EDITOR_SCROLL_Y = "heji_editor_scroll_y"
    const val KEY_OPENED_FROM_SEARCH = "heji_opened_from_search"
    const val KEY_SEARCH_RESULT_FOCUS = "heji_search_result_focus"

    // Search
    const val KEY_SEARCH_QUERY = "heji_search_query"
    const val KEY_SEARCH_SCROLL_Y = "heji_search_scroll_y"

    // Photo
    const val KEY_PHOTO_CAPTURE_PATH = "heji_photo_capture_path"
    const val KEY_PHOTO_MODE = "heji_photo_mode"
    const val KEY_PHOTO_HANDLES = "heji_photo_handles"
    const val KEY_PHOTO_SAVE_PENDING = "heji_photo_save_pending"
    const val KEY_PHOTO_CONTEXT_CONTENT = "heji_photo_context_content"
    const val KEY_PHOTO_SAVED_BODY_HASH = "heji_photo_saved_body_hash"
    const val KEY_PHOTO_CARET_OFFSET = "heji_photo_caret_offset"
    const val KEY_PHOTO_CONTEXT_SCROLL_Y = "heji_photo_context_scroll_y"

    // Video
    const val KEY_VIDEO_INSERT_PENDING = "heji_video_insert_pending"

    // Trash
    const val KEY_TRASH_SCROLL_Y = "heji_trash_scroll_y"

    fun putDocument(bundle: Bundle, prefix: String, doc: VaultDocument?) {
        if (doc == null) return
        bundle.putString("${prefix}_uri", doc.uri.toString())
        bundle.putString("${prefix}_name", doc.name)
        bundle.putString("${prefix}_mime", doc.mimeType)
        bundle.putString("${prefix}_parent_uri", doc.parentUri?.toString())
        bundle.putString("${prefix}_relative_path", doc.relativePath)
        bundle.putLong("${prefix}_last_modified", doc.lastModified ?: -1L)
    }

    fun getDocument(bundle: Bundle, prefix: String): VaultDocument? {
        val uriStr = bundle.getString("${prefix}_uri") ?: return null
        val name = bundle.getString("${prefix}_name") ?: return null
        val mime = bundle.getString("${prefix}_mime")
        val parentUriStr = bundle.getString("${prefix}_parent_uri")
        val relPath = bundle.getString("${prefix}_relative_path") ?: ""
        val lastMod = bundle.getLong("${prefix}_last_modified", -1L)
        return VaultDocument(
            uri = Uri.parse(uriStr),
            name = name,
            mimeType = mime,
            parentUri = parentUriStr?.let { Uri.parse(it) },
            relativePath = relPath,
            lastModified = if (lastMod >= 0) lastMod else null
        )
    }

    fun putHandles(bundle: Bundle, handles: List<PointF>) {
        val array = FloatArray(handles.size * 2)
        handles.forEachIndexed { i, pt ->
            array[i * 2] = pt.x
            array[i * 2 + 1] = pt.y
        }
        bundle.putFloatArray(KEY_PHOTO_HANDLES, array)
    }

    fun getHandles(bundle: Bundle): List<PointF>? {
        val array = bundle.getFloatArray(KEY_PHOTO_HANDLES) ?: return null
        if (array.size % 2 != 0) return null
        val count = array.size / 2
        val list = ArrayList<PointF>(count)
        for (i in 0 until count) {
            list.add(PointF(array[i * 2], array[i * 2 + 1]))
        }
        return list
    }
}
