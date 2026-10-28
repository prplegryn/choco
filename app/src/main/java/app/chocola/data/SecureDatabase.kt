package app.chocola.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class Crypto(private val logs: Diagnostics? = null) {
    private val key: SecretKey by lazy {
        traceStorage(logs, "crypto.key") {
            val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            (store.getKey("chocola.v1", null) as? SecretKey) ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
                init(KeyGenParameterSpec.Builder("chocola.v1", KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setRandomizedEncryptionRequired(true).build())
                generateKey()
            }
        }
    }
    fun seal(text: String): String = traceStorage(logs, "crypto.encrypt") {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key)
        Base64.encodeToString(c.iv + c.doFinal(text.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
    }
    fun open(value: String): String = traceStorage(logs, "crypto.decrypt") {
        val b = Base64.decode(value, Base64.NO_WRAP)
        require(b.size > 28) { "数据不完整" }
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, b.copyOfRange(0, 12)))
        String(c.doFinal(b.copyOfRange(12, b.size)), Charsets.UTF_8)
    }
}

class SecureDatabase(context: Context, private val logs: Diagnostics? = null) : SQLiteOpenHelper(context, "chocola.db", null, 1) {
    private val crypto = Crypto(logs)
    init { setWriteAheadLoggingEnabled(true) }
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE records(kind TEXT NOT NULL,id TEXT NOT NULL,parent TEXT NOT NULL DEFAULT '',created INTEGER NOT NULL DEFAULT 0,payload TEXT NOT NULL,PRIMARY KEY(kind,id))")
        db.execSQL("CREATE INDEX records_parent ON records(kind,parent,created)")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
    @Synchronized fun <T> transaction(block: () -> T): T = traceStorage(logs, "db.transaction") {
        val db = writableDatabase
        db.beginTransaction()
        try { block().also { db.setTransactionSuccessful() } } finally { db.endTransaction() }
    }
    @Synchronized fun put(kind: String, id: String, json: JSONObject, parent: String = "", created: Long = 0): Unit = traceStorage(logs, "db.put") {
        val values = ContentValues().apply {
            put("kind", kind); put("id", id); put("parent", parent); put("created", created); put("payload", crypto.seal(json.toString()))
        }
        // Keep rowid stable when streamed messages share the same creation millisecond.
        val db = writableDatabase
        if (db.update("records", values, "kind=? AND id=?", arrayOf(kind, id)) == 0) db.insertOrThrow("records", null, values)
        Unit
    }
    @Synchronized fun load(kind: String, parent: String? = null): List<JSONObject> = traceStorage(logs, "db.load") {
        val where = if (parent == null) "kind=?" else "kind=? AND parent=?"
        val args = if (parent == null) arrayOf(kind) else arrayOf(kind, parent)
        readableDatabase.query("records", arrayOf("payload"), where, args, null, null, "created ASC, rowid ASC").use { c ->
            buildList { while (c.moveToNext()) add(JSONObject(crypto.open(c.getString(0)))) }
        }
    }
    @Synchronized fun remove(kind: String, id: String) { writableDatabase.delete("records", "kind=? AND id=?", arrayOf(kind, id)) }
    @Synchronized fun page(parent: String, limit: Int = 80, offset: Int = 0): List<JSONObject> = traceStorage(logs, "db.page") { readableDatabase.query("records", arrayOf("payload"),
        "kind='message' AND parent=?", arrayOf(parent), null, null, "created DESC, rowid DESC", "${offset.coerceAtLeast(0)},${limit.coerceIn(1, 200)}").use { c ->
        buildList { while (c.moveToNext()) add(JSONObject(crypto.open(c.getString(0)))) }.asReversed()
    } }
    @Synchronized fun count(parent: String): Int = readableDatabase.rawQuery("SELECT COUNT(*) FROM records WHERE kind='message' AND parent=?", arrayOf(parent)).use { it.moveToFirst(); it.getInt(0) }
    @Synchronized fun first(parent: String, limit: Int = 8): List<JSONObject> = readableDatabase.query("records", arrayOf("payload"),
        "kind='message' AND parent=?", arrayOf(parent), null, null, "created ASC, rowid ASC", limit.coerceIn(1, 80).toString()).use { c ->
        buildList { while (c.moveToNext()) add(JSONObject(crypto.open(c.getString(0)))) }
    }
    @Synchronized fun before(parent: String, anchor: String, limit: Int = 80): List<JSONObject> {
        val position = readableDatabase.rawQuery("SELECT created,rowid FROM records WHERE kind='message' AND id=? AND parent=?", arrayOf(anchor, parent)).use { c -> if (c.moveToFirst()) c.getLong(0) to c.getLong(1) else null } ?: return emptyList()
        return readableDatabase.query("records", arrayOf("payload"), "kind='message' AND parent=? AND (created<? OR (created=? AND rowid<?))",
            arrayOf(parent, position.first.toString(), position.first.toString(), position.second.toString()), null, null, "created DESC, rowid DESC", limit.coerceIn(1, 200).toString()).use { c ->
            buildList { while (c.moveToNext()) add(JSONObject(crypto.open(c.getString(0)))) }.asReversed()
        }
    }
    @Synchronized fun get(kind: String, id: String): JSONObject? = readableDatabase.query("records", arrayOf("payload"), "kind=? AND id=?", arrayOf(kind, id), null, null, null).use { c ->
        if (c.moveToFirst()) JSONObject(crypto.open(c.getString(0))) else null
    }
    @Synchronized fun after(parent: String, anchor: String): List<JSONObject> {
        if (anchor.isBlank()) return load("message", parent)
        val position = readableDatabase.rawQuery("SELECT created,rowid FROM records WHERE kind='message' AND id=?", arrayOf(anchor)).use { c -> if (c.moveToFirst()) c.getLong(0) to c.getLong(1) else null }
            ?: return load("message", parent)
        return readableDatabase.query("records", arrayOf("payload"), "kind='message' AND parent=? AND (created>? OR (created=? AND rowid>?))",
            arrayOf(parent, position.first.toString(), position.first.toString(), position.second.toString()), null, null, "created ASC, rowid ASC").use { c ->
            buildList { while (c.moveToNext()) add(JSONObject(crypto.open(c.getString(0)))) }
        }
    }
    @Synchronized fun removeChildren(parent: String, kind: String? = null) {
        if (kind == null) writableDatabase.delete("records", "parent=?", arrayOf(parent))
        else writableDatabase.delete("records", "parent=? AND kind=?", arrayOf(parent, kind))
    }
    @Synchronized fun exportRecords(write: (JSONObject) -> Unit) {
        readableDatabase.query("records", arrayOf("kind", "id", "parent", "created", "payload"), "kind NOT IN ('task','restore','codex-login')", null, null, null, "rowid ASC").use { cursor ->
            while (cursor.moveToNext()) write(JSONObject().put("kind", cursor.getString(0)).put("id", cursor.getString(1)).put("parent", cursor.getString(2))
                .put("created", cursor.getLong(3)).put("value", JSONObject(crypto.open(cursor.getString(4)))))
        }
    }
    @Synchronized fun replaceRecords(records: Sequence<JSONObject>, restoreId: String) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete("records", null, null)
            records.forEach { record ->
                val values = ContentValues().apply {
                    put("kind", record.getString("kind")); put("id", record.getString("id")); put("parent", record.optString("parent")); put("created", record.optLong("created"))
                    put("payload", crypto.seal(record.getJSONObject("value").toString()))
                }
                db.insertOrThrow("records", null, values)
            }
            put("restore", "marker", JSONObject().put("id", restoreId))
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }
}

private inline fun <T> traceStorage(logs: Diagnostics?, component: String, block: () -> T): T {
    val started = System.nanoTime()
    try { return block() }
    catch (error: Exception) { logs?.error("storage.failed", error, mapOf("component" to component)); throw error }
    finally {
        val duration = System.nanoTime() - started
        logs?.timing(component, duration)
        if (duration >= 50_000_000) logs?.event("storage.slow", mapOf("component" to component, "durationMs" to duration / 1_000_000.0))
    }
}
