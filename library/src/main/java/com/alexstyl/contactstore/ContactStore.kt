package com.alexstyl.contactstore

import android.accounts.AccountManager
import android.content.Context
import com.alexstyl.contactstore.ContactStore.Companion.newInstance
import com.alexstyl.contactstore.utils.DateTimeFormatParser
import kotlinx.coroutines.flow.Flow

/**
 * A store that can be used to retrieve information about the contacts of the device (via [fetchContacts]) or edit them (via [execute]).
 *
 * @see [newInstance]
 *
 */
interface ContactStore {

    @Deprecated(
        "Prefer the version of this function that receives a lambda",
        ReplaceWith("execute {}")
    )
    suspend fun execute(request: SaveRequest)

    /**
     * Returns a [Flow] that emits the contacts of the device matching the given [predicate].
     *
     * The Flow will continue emitting once a change is detected (i.e. an other app adds a new contact or a Content Provider syncs a new account) and never completes.
     * Changes caused by other apps might take some seconds to register, as the underlying implementation uses Android's ContentObserver.
     *
     * @param predicate The conditions that a contact need to meet in order to be fetched
     * @param columnsToFetch The columns of the contact you need to be fetched
     */
    fun fetchContacts(
        predicate: ContactPredicate? = null,
        columnsToFetch: List<ContactColumn> = emptyList()
    ): Flow<List<Contact>>

    companion object {
        /**
         * The entry point to ContactStore
         */
        fun newInstance(context: Context): ContactStore {
            val contentResolver = context.contentResolver
            val resources = context.resources
            val contactsQueries = ContactQueries(
                contentResolver = contentResolver,
                dateParser = DateTimeFormatParser(),
                resources = context.resources,
                accountInfoResolver = AccountInfoResolver(
                    context,
                    context.getSystemService(Context.ACCOUNT_SERVICE) as AccountManager,
                    context.packageManager
                )
            )
            return AndroidContactStore(
                contentResolver = contentResolver,
                newContactOperationsFactory = NewContactOperationsFactory(),
                existingContactOperationsFactory = ExistingContactOperationsFactory(
                    contentResolver,
                    resources,
                    contactsQueries
                ),
                contactQueries = contactsQueries,
            )
        }
    }
}
