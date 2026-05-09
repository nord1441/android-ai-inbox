package uk.nordtek.aiinbox.sync

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Thin wrapper over DocumentFile that hides the SAF noise from the
 * sync engine. All methods operate on a tree URI provided by
 * [FsSyncFolderStore]; missing-permission scenarios (user revoked the
 * grant from system settings) surface as [SafAccessException].
 */
@Singleton
class SafFolderAccess @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    class SafAccessException(message: String, cause: Throwable? = null) : Exception(message, cause)

    /**
     * Resolve a tree URI to a writable [DocumentFile] root.
     *
     * Production callers pass a `content://...tree/...` URI from
     * `OpenDocumentTree`; tests pass a `file://` URI obtained from
     * [DocumentFile.fromFile]. The two need different factory entry points,
     * so we branch on scheme rather than blindly calling [DocumentFile.fromTreeUri].
     */
    internal fun resolveTreeRoot(treeUri: String): DocumentFile {
        val uri = Uri.parse(treeUri)
        val resolved = if (uri.scheme == "file") {
            uri.path?.let { DocumentFile.fromFile(java.io.File(it)) }
        } else {
            DocumentFile.fromTreeUri(context, uri)
        }
        return resolved ?: throw SafAccessException("could not resolve tree URI $treeUri")
    }

    private fun root(treeUri: String): DocumentFile = resolveTreeRoot(treeUri)

    /** Returns the user-friendly display name of the tree, e.g. "Inbox" or "Sync/Inbox". */
    fun displayName(treeUri: String): String? =
        runCatching { root(treeUri).name }.getOrNull()

    /** List all `.md` files at the root of the tree (does not descend). */
    fun listMarkdownFiles(treeUri: String): List<DocumentFile> {
        val r = root(treeUri)
        return r.listFiles().filter { it.isFile && (it.name?.endsWith(".md") == true) }
    }

    /** Find a child file by exact name; returns null if absent. */
    fun findChild(treeUri: String, name: String): DocumentFile? {
        return root(treeUri).listFiles().firstOrNull { it.name == name }
    }

    /** Find or create the `attachments/` subdirectory. */
    fun attachmentsDir(treeUri: String): DocumentFile {
        val r = root(treeUri)
        return r.listFiles().firstOrNull { it.isDirectory && it.name == "attachments" }
            ?: r.createDirectory("attachments")
            ?: throw SafAccessException("could not create attachments/")
    }

    /** Read the full bytes of [doc]. */
    @Throws(IOException::class)
    fun readBytes(doc: DocumentFile): ByteArray {
        val input = context.contentResolver.openInputStream(doc.uri)
            ?: throw IOException("could not open ${doc.uri}")
        return input.use { it.readBytes() }
    }

    /**
     * Atomically write [bytes] as a child of [parent] named [name]:
     * write to a `.tmp` sibling first, then rename to [name].
     * On any exception, delete the `.tmp` to avoid orphans.
     */
    @Throws(IOException::class)
    fun writeAtomically(parent: DocumentFile, name: String, mime: String, bytes: ByteArray) {
        val tmpName = "$name.tmp"
        // If a leftover .tmp from a crashed previous write exists, delete it first
        // (createFile would otherwise duplicate-name to "<tmpName> (1)").
        parent.listFiles().firstOrNull { it.name == tmpName }?.delete()
        // Same for the eventual target — the rename below assumes the slot is free.
        parent.listFiles().firstOrNull { it.name == name }?.delete()

        val tmp = parent.createFile(mime, tmpName)
            ?: throw IOException("could not createFile $tmpName")
        try {
            context.contentResolver.openOutputStream(tmp.uri, "w")?.use { it.write(bytes) }
                ?: throw IOException("could not openOutputStream for $tmpName")
            if (!tmp.renameTo(name)) {
                throw IOException("renameTo $name failed")
            }
        } catch (t: Throwable) {
            runCatching { tmp.delete() }
            throw t
        }
    }

    /** Delete a child by exact name. No-op if absent. */
    fun deleteByName(treeUri: String, name: String): Boolean {
        val doc = findChild(treeUri, name) ?: return false
        return doc.delete()
    }

    /** Delete a child of [parent] by exact name. */
    fun deleteByName(parent: DocumentFile, name: String): Boolean {
        val doc = parent.listFiles().firstOrNull { it.name == name } ?: return false
        return doc.delete()
    }
}
