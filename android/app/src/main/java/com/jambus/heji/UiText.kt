package com.jambus.heji

import android.content.Context

/**
 * Localizes only developer-authored presentation labels passed through explicit call sites.
 * It must never be applied to Vault names, note text, previews, paths, or other user content.
 */
object UiText {
    private val english = mapOf(
        "设置" to "Settings", "切换 Vault" to "Switch Vault", "文件" to "Files", "文件夹" to "Folders", "笔记" to "Notes",
        "图片" to "Image", "视频" to "Video", "其他文件" to "Other files", "附件目录" to "Attachments",
        "搜索笔记" to "Search notes",
        "‹  文件" to "‹  Files", "‹  设置" to "‹  Settings", "‹  回收站" to "‹  Trash", "‹  上一级" to "‹  Up",
        "更多选项" to "More options", "新建" to "New", "打开今日笔记" to "Open today’s note",
        "取消" to "Cancel", "保存" to "Save", "重试" to "Retry", "重新选择 Vault" to "Choose Vault again",
        "重试附件清理" to "Retry attachment cleanup",
        "返回文件库" to "Back to files", "返回设置" to "Back to Settings", "刷新状态" to "Refresh status",
        "同步" to "Sync", "同步详情" to "Sync details", "取消同步" to "Cancel sync", "回收站" to "Trash",
        "永久删除" to "Delete permanently", "清空回收站" to "Empty Trash", "重命名" to "Rename",
        "移到回收站" to "Move to Trash", "移动到…" to "Move to…", "移动" to "Move",
        "拍摄" to "Capture", "拍照" to "Take photo", "录视频" to "Record video", "插入照片" to "Insert photo",
        "插入视频" to "Insert video", "重新录制" to "Record again", "确认视频" to "Confirm video",
        "图片操作" to "Image actions", "视频操作" to "Video actions", "删除引用和附件" to "Delete reference and attachment",
        "仅从正文删除（保留附件）" to "Remove from note only (keep attachment)",
        "删除正文引用并永久删除附件" to "Delete reference and attachment permanently",
        "永久删除附件" to "Delete attachment permanently",
        "正文会先保存；确认没有其他笔记引用后，将永久删除这个附件文件。无法安全确认时文件会保留。" to "The note is saved first. This attachment file is permanently deleted only when no other note references it; otherwise it is retained.",
        "调整照片" to "Adjust photo", "矩形裁剪" to "Rectangle crop", "四点校正" to "Perspective correction",
        "撤销" to "Undo", "重做" to "Redo", "加粗" to "Bold", "斜体" to "Italic", "添加标签" to "Add tag",
        "添加链接" to "Add link", "插入表格" to "Insert table", "标题格式" to "Heading format",
        "创建" to "Create", "新建笔记" to "New note", "新建文件夹" to "New folder",
        "选择 Vault" to "Choose Vault", "选择此目录" to "Use this folder", "上一级" to "Up",
        "知道了" to "Got it", "确认同步" to "Confirm sync", "开始同步" to "Start sync",
        "Google Drive" to "Google Drive", "同步状态" to "Sync status",
        "请在应用内查看失败详情" to "See failure details in the app",
        "操作已展开" to "Actions expanded", "操作已收起" to "Actions collapsed",
        "同步详情" to "Sync details", "每日笔记" to "Daily notes", "今日笔记目录" to "Daily note folder",
        "关于" to "About", "版本" to "Version", "选择今日笔记目录" to "Choose daily note folder",
        "正在读取 Vault 文件夹…" to "Reading Vault folders…", "今日笔记" to "Today’s note",
        "标题匹配" to "Title match", "标签匹配" to "Tag match", "正文匹配" to "Content match",
        "尚无同步记录。开始 Google Drive 同步后，可在这里查看进度和结果。" to "No sync record yet. Start a Google Drive sync to see progress and results here.",
        "存储" to "Storage", "显示" to "Appearance", "语言" to "Language", "关于" to "About",
        "日间模式" to "Day mode", "夜间模式" to "Night mode", "浅色背景与深色文字" to "Light background and dark text",
        "深色工作区与深色编辑纸面" to "Dark workspace and editor surface", "已启用  ✓" to "Enabled  ✓",
        "提示：左滑条目可重命名或移到回收站；长按也可操作。" to "Tip: swipe an item to rename or move it to Trash; long press also opens actions.",
        "左滑条目可重命名或移到回收站；长按也可操作" to "Swipe an item to rename or move it to Trash; long press also opens actions.",
        "设置不会移动已有笔记或附件。新建的每日笔记会按 yyyy-MM-dd.md 写入所选目录。" to "Settings do not move existing notes or attachments. New daily notes are written as yyyy-MM-dd.md in the selected folder.",
        "当前目录没有其他文件" to "No other files in this folder",
        "无法打开此文件，请安装支持该格式的查看器。" to "This file cannot be opened. Install an app that supports this format."
    )

    fun label(context: Context, source: String): String =
        if (UiLanguage.locale(context).language == "zh") source else english[source] ?: source
}
