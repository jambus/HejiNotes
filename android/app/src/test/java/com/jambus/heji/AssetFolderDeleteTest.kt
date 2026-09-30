package com.jambus.heji

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AssetFolderDeleteTest {

    private class FakePort : AssetFolderDeletePort {
        var currentVault: String? = "content://vault"
        var isSafe: Boolean = true
        var nodes = mutableMapOf<String, AssetFolderDeletePort.Node>()
        var childMap = mutableMapOf<String, List<AssetFolderDeletePort.Node>>()
        var fileHashes = mutableMapOf<String, String>()
        var markdowns = mutableListOf<AssetFolderDeletePort.Markdown>()
        var deletedUris = mutableListOf<String>()
        var presenceMap = mutableMapOf<String, AssetFolderDeletePort.Presence>()
        var childrenFailurePaths = mutableSetOf<String>()
        var deleteResult = true
        var deleteThrows = false
        var markdownCalls = 0
        var markdownProvider: (() -> List<AssetFolderDeletePort.Markdown>)? = null

        override fun currentVaultUri(): String? = currentVault
        override fun safeToDelete(): Boolean = isSafe
        override fun resolve(path: String): AssetFolderDeletePort.Node? = nodes[path]
        override fun children(directory: AssetFolderDeletePort.Node): List<AssetFolderDeletePort.Node> {
            if (directory.path in childrenFailurePaths) throw IllegalStateException("unreadable directory")
            return childMap[directory.path].orEmpty()
        }
        override fun sha256(file: AssetFolderDeletePort.Node): String? = fileHashes[file.path]
        override fun markdownFiles(): List<AssetFolderDeletePort.Markdown> {
            markdownCalls += 1
            return markdownProvider?.invoke() ?: markdowns
        }
        override fun deleteWholeFolder(folderUri: String): Boolean {
            if (deleteThrows) throw IllegalStateException("provider uncertainty")
            deletedUris += folderUri
            return deleteResult
        }
        override fun presence(path: String): AssetFolderDeletePort.Presence =
            presenceMap[path] ?: AssetFolderDeletePort.Presence.ABSENT
    }

    @Test
    fun `policy correctly validates attachment folder paths`() {
        assertTrue(VaultBrowserPolicy.canPermanentlyDeleteAttachmentFolder("assets"))
        assertTrue(VaultBrowserPolicy.canPermanentlyDeleteAttachmentFolder("attachments"))
        assertTrue(VaultBrowserPolicy.canPermanentlyDeleteAttachmentFolder("assets/sub"))
        assertTrue(VaultBrowserPolicy.canPermanentlyDeleteAttachmentFolder("assets/sub/nested"))
        assertTrue(VaultBrowserPolicy.canPermanentlyDeleteAttachmentFolder("Projects/assets/2026-07-30"))

        assertFalse(VaultBrowserPolicy.canPermanentlyDeleteAttachmentFolder(""))
        assertFalse(VaultBrowserPolicy.canPermanentlyDeleteAttachmentFolder("notes"))
        assertFalse(VaultBrowserPolicy.canPermanentlyDeleteAttachmentFolder("notes/sub"))
        assertFalse(VaultBrowserPolicy.canPermanentlyDeleteAttachmentFolder(".obsidian"))
        assertFalse(VaultBrowserPolicy.canPermanentlyDeleteAttachmentFolder(".trash"))
        assertFalse(VaultBrowserPolicy.canPermanentlyDeleteAttachmentFolder(".markbook"))
        assertFalse(VaultBrowserPolicy.canPermanentlyDeleteAttachmentFolder("assets/.git"))
        assertFalse(VaultBrowserPolicy.canPermanentlyDeleteAttachmentFolder("assets/.cache"))
        assertFalse(VaultBrowserPolicy.canPermanentlyDeleteAttachmentFolder("assets/.markbook-txn"))
        assertFalse(VaultBrowserPolicy.canPermanentlyDeleteAttachmentFolder("Projects/Assets/note"))
    }

    @Test
    fun `prepare rejects when mutation barrier is not clear`() {
        val port = FakePort().apply { isSafe = false }
        val op = AssetFolderDeleteOperation(port)
        val result = op.prepare("assets/note")
        assertTrue(result is AssetFolderDeleteResult.Rejected)
        assertEquals("存在尚未安全完成的保存或媒体操作", (result as AssetFolderDeleteResult.Rejected).message)
    }

    @Test
    fun `prepare fails closed when a descendant directory cannot be enumerated`() {
        val port = FakePort().apply {
            val root = AssetFolderDeletePort.Node("assets/bundle", "content://assets/bundle", true, null)
            val sub = AssetFolderDeletePort.Node("assets/bundle/sub", "content://assets/bundle/sub", true, null)
            nodes[root.path] = root
            childMap[root.path] = listOf(sub)
            childrenFailurePaths += sub.path
        }

        val result = AssetFolderDeleteOperation(port).prepare("assets/bundle")
        assertTrue(result is AssetFolderDeleteResult.Rejected)
        assertTrue(port.deletedUris.isEmpty())
    }

    @Test
    fun `prepare rejects non attachment folders`() {
        val port = FakePort()
        val op = AssetFolderDeleteOperation(port)
        val result = op.prepare("documents/work")
        assertTrue(result is AssetFolderDeleteResult.Rejected)
        assertEquals("只能永久删除 assets 或 attachments 内的文件夹", (result as AssetFolderDeleteResult.Rejected).message)
    }

    @Test
    fun `prepare produces frozen snapshot with accurate subfolder and file counts`() {
        val port = FakePort().apply {
            val root = AssetFolderDeletePort.Node("assets/bundle", "content://assets/bundle", true, null)
            val sub = AssetFolderDeletePort.Node("assets/bundle/sub", "content://assets/bundle/sub", true, null)
            val f1 = AssetFolderDeletePort.Node("assets/bundle/f1.jpg", "content://assets/bundle/f1.jpg", false, 100L)
            val f2 = AssetFolderDeletePort.Node("assets/bundle/sub/f2.png", "content://assets/bundle/sub/f2.png", false, 200L)

            nodes["assets/bundle"] = root
            nodes["assets/bundle/sub"] = sub
            nodes["assets/bundle/f1.jpg"] = f1
            nodes["assets/bundle/sub/f2.png"] = f2

            childMap["assets/bundle"] = listOf(sub, f1)
            childMap["assets/bundle/sub"] = listOf(f2)

            fileHashes["assets/bundle/f1.jpg"] = "hash1"
            fileHashes["assets/bundle/sub/f2.png"] = "hash2"
        }

        val op = AssetFolderDeleteOperation(port)
        val result = op.prepare("assets/bundle")
        assertTrue(result is AssetFolderDeleteResult.Ready)
        val snapshot = (result as AssetFolderDeleteResult.Ready).snapshot

        assertEquals("assets/bundle", snapshot.folderPath)
        assertEquals(1, snapshot.directoryCount) // 'sub' only, not 'bundle'
        assertEquals(2, snapshot.fileCount) // f1 and f2
        assertEquals(300L, snapshot.totalSize)
    }

    @Test
    fun `prepare rejects when direct link references file or folder`() {
        val port = FakePort().apply {
            val root = AssetFolderDeletePort.Node("assets/bundle", "content://assets/bundle", true, null)
            val f1 = AssetFolderDeletePort.Node("assets/bundle/pic.jpg", "content://assets/bundle/pic.jpg", false, 50L)
            nodes["assets/bundle"] = root
            nodes["assets/bundle/pic.jpg"] = f1
            childMap["assets/bundle"] = listOf(f1)
            fileHashes["assets/bundle/pic.jpg"] = "hash"
            markdowns += AssetFolderDeletePort.Markdown("note.md", "Look at this: ![img](assets/bundle/pic.jpg)")
        }

        val op = AssetFolderDeleteOperation(port)
        val result = op.prepare("assets/bundle")
        assertTrue(result is AssetFolderDeleteResult.Rejected)
        assertEquals("Markdown 仍引用此目录或其中附件", (result as AssetFolderDeleteResult.Rejected).message)
    }

    @Test
    fun `prepare rejects encoded fragment query escaped definition and html references`() {
        val bodies = listOf(
            "![x](assets/bundle/a%20b.jpg)",
            "![x](assets/bundle/a b.jpg#preview)",
            "![x](<assets/bundle/a b.jpg?raw=1>)",
            "![x](<assets/bundle/a\\(b\\).jpg>)",
            "![x](assets/bundle/a%23b.jpg)",
            "[photo]: <assets/bundle/a b.jpg>",
            "<img src=\"assets/bundle/a%20b.jpg\">"
        )
        bodies.forEach { body ->
            val port = FakePort().apply {
                val root = AssetFolderDeletePort.Node("assets/bundle", "content://assets/bundle", true, null)
                val spaced = AssetFolderDeletePort.Node("assets/bundle/a b.jpg", "content://assets/bundle/a-b", false, 50L)
                val parenthesized = AssetFolderDeletePort.Node("assets/bundle/a(b).jpg", "content://assets/bundle/a-paren", false, 60L)
                val hashNamed = AssetFolderDeletePort.Node("assets/bundle/a#b.jpg", "content://assets/bundle/a-hash", false, 70L)
                nodes[root.path] = root
                childMap[root.path] = listOf(spaced, parenthesized, hashNamed)
                fileHashes[spaced.path] = "space-hash"
                fileHashes[parenthesized.path] = "paren-hash"
                fileHashes[hashNamed.path] = "hash-name-hash"
                markdowns += AssetFolderDeletePort.Markdown("note.md", body)
            }
            val result = AssetFolderDeleteOperation(port).prepare("assets/bundle")
            assertTrue("must reject reference: $body", result is AssetFolderDeleteResult.Rejected)
        }
    }

    @Test
    fun `prepare rejects when direct link references folder path`() {
        val port = FakePort().apply {
            val root = AssetFolderDeletePort.Node("assets/bundle", "content://assets/bundle", true, null)
            nodes["assets/bundle"] = root
            childMap["assets/bundle"] = emptyList()
            markdowns += AssetFolderDeletePort.Markdown("note.md", "Directory: [files](assets/bundle)")
        }

        val op = AssetFolderDeleteOperation(port)
        val result = op.prepare("assets/bundle")
        assertTrue(result is AssetFolderDeleteResult.Rejected)
        assertEquals("Markdown 仍引用此目录或其中附件", (result as AssetFolderDeleteResult.Rejected).message)
    }

    @Test
    fun `prepare rejects when conservative wiki basename matches a file in the folder`() {
        val port = FakePort().apply {
            val root = AssetFolderDeletePort.Node("assets/bundle", "content://assets/bundle", true, null)
            val f1 = AssetFolderDeletePort.Node("assets/bundle/photo.png", "content://assets/bundle/photo.png", false, 50L)
            nodes["assets/bundle"] = root
            nodes["assets/bundle/photo.png"] = f1
            childMap["assets/bundle"] = listOf(f1)
            fileHashes["assets/bundle/photo.png"] = "hash"
            markdowns += AssetFolderDeletePort.Markdown("note.md", "Image: ![[photo.png]]")
        }

        val op = AssetFolderDeleteOperation(port)
        val result = op.prepare("assets/bundle")
        assertTrue(result is AssetFolderDeleteResult.Rejected)
        assertEquals("Markdown 仍引用此目录或其中附件", (result as AssetFolderDeleteResult.Rejected).message)
    }

    @Test
    fun `prepare does not reject when wiki link targets a note matching folder name`() {
        val port = FakePort().apply {
            val root = AssetFolderDeletePort.Node("assets/work", "content://assets/work", true, null)
            val f1 = AssetFolderDeletePort.Node("assets/work/doc.pdf", "content://assets/work/doc.pdf", false, 50L)
            nodes["assets/work"] = root
            nodes["assets/work/doc.pdf"] = f1
            childMap["assets/work"] = listOf(f1)
            fileHashes["assets/work/doc.pdf"] = "hash"
            // Note links to note 'work', which is a note name, not doc.pdf
            markdowns += AssetFolderDeletePort.Markdown("note.md", "See [[work]] for details.")
        }

        val op = AssetFolderDeleteOperation(port)
        val result = op.prepare("assets/work")
        assertTrue(result is AssetFolderDeleteResult.Ready)
    }

    @Test
    fun `prepare does not reject when prose mentions word assets without slash`() {
        val port = FakePort().apply {
            val root = AssetFolderDeletePort.Node("assets", "content://assets", true, null)
            nodes["assets"] = root
            childMap["assets"] = emptyList()
            markdowns += AssetFolderDeletePort.Markdown("note.md", "Company total assets increased this quarter.")
        }

        val op = AssetFolderDeleteOperation(port)
        val result = op.prepare("assets")
        assertTrue(result is AssetFolderDeleteResult.Ready)
    }

    @Test
    fun `prepare rejects when code snippet mentions folder path`() {
        val port = FakePort().apply {
            val root = AssetFolderDeletePort.Node("assets/sub", "content://assets/sub", true, null)
            nodes["assets/sub"] = root
            childMap["assets/sub"] = emptyList()
            markdowns += AssetFolderDeletePort.Markdown("note.md", "```js\nconst p = 'assets/sub';\n```")
        }

        val op = AssetFolderDeleteOperation(port)
        val result = op.prepare("assets/sub")
        assertTrue(result is AssetFolderDeleteResult.Rejected)
        assertEquals("存在无法安全确认的 Markdown 引用", (result as AssetFolderDeleteResult.Rejected).message)
    }

    @Test
    fun `commit succeeds and deletes folder when preconditions are satisfied`() {
        val port = FakePort().apply {
            val root = AssetFolderDeletePort.Node("assets/sub", "content://assets/sub", true, null)
            nodes["assets/sub"] = root
            childMap["assets/sub"] = emptyList()
            presenceMap["assets/sub"] = AssetFolderDeletePort.Presence.ABSENT
        }

        val op = AssetFolderDeleteOperation(port)
        val ready = op.prepare("assets/sub") as AssetFolderDeleteResult.Ready
        val result = op.commit(ready.snapshot)

        assertEquals(AssetFolderDeleteResult.Deleted, result)
        assertEquals(listOf("content://assets/sub"), port.deletedUris)
    }

    @Test
    fun `commit catches a reference introduced immediately before delete`() {
        val port = FakePort().apply {
            val root = AssetFolderDeletePort.Node("assets/sub", "content://assets/sub", true, null)
            val file = AssetFolderDeletePort.Node("assets/sub/a.jpg", "content://assets/sub/a.jpg", false, 10L)
            nodes[root.path] = root
            childMap[root.path] = listOf(file)
            fileHashes[file.path] = "hash"
            markdownProvider = {
                if (markdownCalls >= 3) listOf(
                    AssetFolderDeletePort.Markdown("late.md", "![x](assets/sub/a.jpg)")
                ) else emptyList()
            }
        }
        val operation = AssetFolderDeleteOperation(port)
        val ready = operation.prepare("assets/sub") as AssetFolderDeleteResult.Ready

        val result = operation.commit(ready.snapshot)
        assertTrue(result is AssetFolderDeleteResult.Rejected)
        assertTrue(port.deletedUris.isEmpty())
    }

    @Test
    fun `provider false still succeeds only when strict presence says absent`() {
        val absentPort = FakePort().apply {
            val root = AssetFolderDeletePort.Node("assets/sub", "content://assets/sub", true, null)
            nodes[root.path] = root
            childMap[root.path] = emptyList()
            deleteResult = false
            presenceMap[root.path] = AssetFolderDeletePort.Presence.ABSENT
        }
        val absentOperation = AssetFolderDeleteOperation(absentPort)
        val absentReady = absentOperation.prepare("assets/sub") as AssetFolderDeleteResult.Ready
        assertEquals(AssetFolderDeleteResult.Deleted, absentOperation.commit(absentReady.snapshot))

        val presentPort = FakePort().apply {
            val root = AssetFolderDeletePort.Node("assets/sub", "content://assets/sub", true, null)
            nodes[root.path] = root
            childMap[root.path] = emptyList()
            deleteResult = false
            presenceMap[root.path] = AssetFolderDeletePort.Presence.PRESENT
        }
        val presentOperation = AssetFolderDeleteOperation(presentPort)
        val presentReady = presentOperation.prepare("assets/sub") as AssetFolderDeleteResult.Ready
        assertTrue(presentOperation.commit(presentReady.snapshot) is AssetFolderDeleteResult.Rejected)
    }

    @Test
    fun `provider exception is resolved only by strict presence`() {
        val absentPort = FakePort().apply {
            val root = AssetFolderDeletePort.Node("assets/sub", "content://assets/sub", true, null)
            nodes[root.path] = root
            childMap[root.path] = emptyList()
            deleteThrows = true
            presenceMap[root.path] = AssetFolderDeletePort.Presence.ABSENT
        }
        val absentOperation = AssetFolderDeleteOperation(absentPort)
        val absentReady = absentOperation.prepare("assets/sub") as AssetFolderDeleteResult.Ready
        assertEquals(AssetFolderDeleteResult.Deleted, absentOperation.commit(absentReady.snapshot))

        val unknownPort = FakePort().apply {
            val root = AssetFolderDeletePort.Node("assets/sub", "content://assets/sub", true, null)
            nodes[root.path] = root
            childMap[root.path] = emptyList()
            deleteThrows = true
            presenceMap[root.path] = AssetFolderDeletePort.Presence.UNKNOWN
        }
        val unknownOperation = AssetFolderDeleteOperation(unknownPort)
        val unknownReady = unknownOperation.prepare("assets/sub") as AssetFolderDeleteResult.Ready
        assertTrue(unknownOperation.commit(unknownReady.snapshot) is AssetFolderDeleteResult.Rejected)
    }

    @Test
    fun `commit rechecks save barrier immediately before delete`() {
        val port = FakePort().apply {
            val root = AssetFolderDeletePort.Node("assets/sub", "content://assets/sub", true, null)
            nodes[root.path] = root
            childMap[root.path] = emptyList()
            markdownProvider = {
                if (markdownCalls >= 3) isSafe = false
                emptyList()
            }
        }
        val operation = AssetFolderDeleteOperation(port)
        val ready = operation.prepare("assets/sub") as AssetFolderDeleteResult.Ready

        val result = operation.commit(ready.snapshot)
        assertTrue(result is AssetFolderDeleteResult.Rejected)
        assertTrue(port.deletedUris.isEmpty())
    }

    @Test
    fun `commit rejects when directory contents change after snapshot`() {
        val port = FakePort().apply {
            val root = AssetFolderDeletePort.Node("assets/sub", "content://assets/sub", true, null)
            nodes["assets/sub"] = root
            childMap["assets/sub"] = emptyList()
        }

        val op = AssetFolderDeleteOperation(port)
        val ready = op.prepare("assets/sub") as AssetFolderDeleteResult.Ready

        // New file added after snapshot
        val newFile = AssetFolderDeletePort.Node("assets/sub/new.jpg", "content://assets/sub/new.jpg", false, 10L)
        port.nodes["assets/sub/new.jpg"] = newFile
        port.childMap["assets/sub"] = listOf(newFile)
        port.fileHashes["assets/sub/new.jpg"] = "newhash"

        val result = op.commit(ready.snapshot)
        assertTrue(result is AssetFolderDeleteResult.Rejected)
        assertEquals("目录内容已变化，请重新确认", (result as AssetFolderDeleteResult.Rejected).message)
    }

    @Test
    fun `commit rejects when vault changes after snapshot`() {
        val port = FakePort().apply {
            val root = AssetFolderDeletePort.Node("assets/sub", "content://assets/sub", true, null)
            nodes["assets/sub"] = root
            childMap["assets/sub"] = emptyList()
        }

        val op = AssetFolderDeleteOperation(port)
        val ready = op.prepare("assets/sub") as AssetFolderDeleteResult.Ready

        port.currentVault = "content://another-vault"
        val result = op.commit(ready.snapshot)
        assertTrue(result is AssetFolderDeleteResult.Rejected)
        assertEquals("Vault 或保存状态已变化，请重新确认", (result as AssetFolderDeleteResult.Rejected).message)
    }
}
