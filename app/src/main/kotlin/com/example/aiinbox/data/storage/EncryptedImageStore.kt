package com.example.aiinbox.data.storage

import android.content.Context
import androidx.security.crypto.EncryptedFile
import androidx.security.crypto.MasterKey
import java.io.File
import java.io.InputStream
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 添付画像の暗号化保存。
 *
 * - 保存先: [baseDir] (デフォルトは `filesDir/attachments`)
 * - 暗号化: AES-256 GCM (`EncryptedFile`)、マスター鍵は Android Keystore 管理
 * - ファイル名: ランダム UUID + `.jpg.enc`（既存ファイルがあると EncryptedFile 構築が失敗する仕様のため、衝突回避目的）
 */
@Singleton
class EncryptedImageStore @Inject constructor(
    private val context: Context,
    private val baseDir: File,
) {

    constructor(context: Context) : this(
        context = context,
        baseDir = File(context.filesDir, "attachments").apply { mkdirs() },
    )

    private val masterKey: MasterKey by lazy {
        MasterKey.Builder(context, KEY_ALIAS_ATTACHMENT)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
    }

    /** [bytes] を保存しファイル名（baseDir 相対）を返す。 */
    fun save(bytes: ByteArray): String {
        baseDir.mkdirs()
        val name = "${UUID.randomUUID()}.jpg.enc"
        val file = File(baseDir, name)
        // EncryptedFile.Builder は対象ファイルが既に存在すると例外を投げる。UUID で衝突回避する。
        val encrypted = EncryptedFile.Builder(
            context,
            file,
            masterKey,
            EncryptedFile.FileEncryptionScheme.AES256_GCM_HKDF_4KB,
        ).build()
        try {
            encrypted.openFileOutput().use { it.write(bytes) }
        } catch (t: Throwable) {
            // 書き込み失敗時の orphan file を削除
            runCatching { file.delete() }
            throw t
        }
        return name
    }

    /** [name] のファイルを復号化した [InputStream] を返す。呼び出し側で `.use { }` 必須。 */
    fun read(name: String): InputStream {
        val file = File(baseDir, name)
        if (!file.exists()) throw java.io.IOException("attachment not found: $name")
        val encrypted = EncryptedFile.Builder(
            context,
            file,
            masterKey,
            EncryptedFile.FileEncryptionScheme.AES256_GCM_HKDF_4KB,
        ).build()
        return encrypted.openFileInput()
    }

    /** [name] のファイルを復号化して全バイトを返す。存在しなければ null。 */
    fun readBytes(name: String): ByteArray? {
        val file = File(baseDir, name)
        if (!file.exists()) return null
        return read(name).use { it.readBytes() }
    }

    /**
     * [name] で [bytes] を保存する。同名が既存なら上書き
     * (EncryptedFile.Builder は対象が存在すると例外なので、先に削除する)。
     * インポート経路で「DB の encryptedFilename と一致するファイル名」を要求されるため必要。
     */
    fun writeWithName(name: String, bytes: ByteArray) {
        baseDir.mkdirs()
        val file = File(baseDir, name)
        if (file.exists()) file.delete()
        val encrypted = EncryptedFile.Builder(
            context,
            file,
            masterKey,
            EncryptedFile.FileEncryptionScheme.AES256_GCM_HKDF_4KB,
        ).build()
        try {
            encrypted.openFileOutput().use { it.write(bytes) }
        } catch (t: Throwable) {
            runCatching { file.delete() }
            throw t
        }
    }

    /** [name] のファイルを削除。存在しなければ no-op。 */
    fun delete(name: String) {
        File(baseDir, name).delete()
    }

    private companion object {
        const val KEY_ALIAS_ATTACHMENT = "ai_inbox_attachment_master_key"
    }
}
