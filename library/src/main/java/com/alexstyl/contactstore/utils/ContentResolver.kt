package com.alexstyl.contactstore.utils

import android.content.ContentResolver
import android.database.Cursor
import android.net.Uri
import com.alexstyl.contactstore.uriFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import timber.log.Timber

internal fun ContentResolver.runQuery(
    contentUri: Uri,
    projection: Array<String>? = null,
    selection: String? = null,
    selectionArgs: Array<String>? = null,
    sortOrder: String? = null
): Cursor? {
    var cursor: Cursor? = null
    return try {
        cursor = query(
            contentUri,
            projection,
            selection,
            selectionArgs,
            sortOrder
        )
        cursor
    } catch (e: Exception) {
        Timber.e(e, "Error running query")
        if (cursor?.isClosed == false) cursor.close()
        null

    }
}

internal fun ContentResolver.runQueryFlow(
    contentUri: Uri,
    projection: Array<String>? = null,
    selection: String? = null,
    selectionArgs: Array<String>? = null,
    sortOrder: String? = null
): Flow<Cursor?> {
    return uriFlow(contentUri)
        .startImmediately()
        .map {
            var cursor: Cursor? = null
            try {
                cursor = query(
                    contentUri,
                    projection,
                    selection,
                    selectionArgs,
                    sortOrder
                )
                cursor
            } catch (e: Exception) {
                Timber.e(e, "Error running flow query")
                if (cursor?.isClosed == false) cursor.close()
                null
            }
        }

}

internal fun valueIn(values: List<Any>): String {
    return values.joinToString(",", prefix = "(", postfix = ")")
}
