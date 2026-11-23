package com.infinity.comicreader.testing

import android.database.sqlite.SQLiteDatabase
import java.io.File
import java.util.WeakHashMap

/** The paths of the databases in folder [dir] with an open connection, read from `SQLiteDatabase.sActiveDatabases`. */
fun openDatabases(dir: String): List<String> {
    val field = SQLiteDatabase::class.java.getDeclaredField("sActiveDatabases").apply { isAccessible = true }
    @Suppress("UNCHECKED_CAST")
    val active = field.get(null) as WeakHashMap<SQLiteDatabase, Any?>
    return synchronized(active) { active.keys.toList() }.filter { it.isOpen && File(it.path).parent == dir }.map { it.path }
}
