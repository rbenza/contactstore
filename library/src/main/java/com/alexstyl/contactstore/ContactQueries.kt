package com.alexstyl.contactstore

import android.provider.ContactsContract.CommonDataKinds.Email as EmailColumns
import android.provider.ContactsContract.CommonDataKinds.Event as EventColumns
import android.provider.ContactsContract.CommonDataKinds.GroupMembership as GroupColumns
import android.provider.ContactsContract.CommonDataKinds.Nickname as NicknameColumns
import android.provider.ContactsContract.CommonDataKinds.Note as NoteColumns
import android.provider.ContactsContract.CommonDataKinds.Organization as OrganizationColumns
import android.provider.ContactsContract.CommonDataKinds.Phone as PhoneColumns
import android.provider.ContactsContract.CommonDataKinds.Photo as PhotoColumns
import android.provider.ContactsContract.CommonDataKinds.StructuredName as NameColumns
import android.provider.ContactsContract.CommonDataKinds.StructuredPostal as PostalColumns
import android.provider.ContactsContract.CommonDataKinds.Website as WebColumns
import android.content.ContentResolver
import android.content.ContentUris
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.provider.ContactsContract
import android.provider.ContactsContract.CommonDataKinds.BaseTypes
import android.provider.ContactsContract.Contacts
import android.provider.ContactsContract.Data
import android.provider.ContactsContract.FullNameStyle
import android.provider.ContactsContract.PhoneticNameStyle
import com.alexstyl.contactstore.ContactPredicate.ContactLookup
import com.alexstyl.contactstore.ContactPredicate.MailLookup
import com.alexstyl.contactstore.ContactPredicate.PhoneLookup
import com.alexstyl.contactstore.utils.DateParser
import com.alexstyl.contactstore.utils.get
import com.alexstyl.contactstore.utils.iterate
import com.alexstyl.contactstore.utils.mapEachRow
import com.alexstyl.contactstore.utils.runQuery
import com.alexstyl.contactstore.utils.runQueryFlow
import com.alexstyl.contactstore.utils.valueIn
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.io.InputStream

private val PHONE_LOOKUP_CONTACT_ID = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
    ContactsContract.PhoneLookup.CONTACT_ID
} else {
    ContactsContract.PhoneLookup._ID
}

internal class ContactQueries(
    private val contentResolver: ContentResolver,
    private val dateParser: DateParser
) {
    fun queryContacts(
        predicate: ContactPredicate?,
        columnsToFetch: List<ContactColumn>
    ): Flow<List<Contact>> {
        return queryContacts(predicate)
            .map { contacts ->
                if (columnsToFetch.isEmpty()) {
                    contacts
                } else {
                    val contactIds = contacts.map { it.contactId }
                    fetchAdditionalColumns(contactIds, columnsToFetch)
                }
            }
    }

    private fun queryContacts(predicate: ContactPredicate?): Flow<List<PartialContact>> {
        return when (predicate) {
            null -> queryAllContacts()
            is ContactLookup -> fetchContactsMatchingPredicate(predicate)
            is MailLookup -> lookupFromMail(predicate.mailAddress)
            is PhoneLookup -> lookupFromPhone(predicate.phoneNumber)
        }
    }

    private fun fetchContactsMatchingPredicate(predicate: ContactLookup): Flow<List<PartialContact>> {
        return contentResolver.runQueryFlow(
            contentUri = Contacts.CONTENT_URI,
            projection = SimpleQuery.PROJECTION,
            selection = buildSelection(predicate),
            sortOrder = Contacts.DISPLAY_NAME_PRIMARY
        ).map { cursor ->
            cursor.mapEachRow {
                PartialContact(
                    contactId = SimpleQuery.getContactId(it),
                    displayName = SimpleQuery.getDisplayName(it),
                    isStarred = SimpleQuery.getIsStarred(it),
                    columns = emptyList()
                )
            }
        }
    }

    private fun lookupFromMail(mailAddress: MailAddress): Flow<List<PartialContact>> {
        return contentResolver.runQueryFlow(
            contentUri = EmailColumns.CONTENT_FILTER_URI.buildUpon()
                .appendEncodedPath(mailAddress.raw)
                .build(),
            projection = arrayOf(
                EmailColumns.CONTACT_ID,
                EmailColumns.DISPLAY_NAME_PRIMARY,
                EmailColumns.STARRED
            ),
            selection = null,
            sortOrder = Contacts.DISPLAY_NAME_PRIMARY
        ).map { cursor ->
            cursor.mapEachRow {
                PartialContact(
                    contactId = it.getLong(0),
                    displayName = it.getString(1),
                    isStarred = it.getInt(2) == 1,
                    columns = emptyList()
                )
            }
        }
    }

    private fun buildSelection(predicate: ContactLookup): String {
        return buildString {
            append("${Contacts.IN_VISIBLE_GROUP} = 1")
            predicate.inContactIds?.let { contactIds ->
                if (isNotEmpty()) {
                    append(" AND")
                }
                append(" ${Contacts._ID} IN ${valueIn(contactIds)}")
            }
            predicate.isFavorite?.let { isTrue ->
                if (isNotEmpty()) {
                    append(" AND")
                }
                append(" ${Contacts.STARRED} = ${isTrue.toBoolInt()}")
            }
        }
    }

    private fun lookupFromPhone(phoneNumber: PhoneNumber): Flow<List<PartialContact>> {

        return contentResolver.runQueryFlow(
            contentUri = ContactsContract.PhoneLookup.CONTENT_FILTER_URI.buildUpon()
                .appendEncodedPath(phoneNumber.raw)
                .build(),
            projection = arrayOf(
                PHONE_LOOKUP_CONTACT_ID,
                ContactsContract.PhoneLookup.DISPLAY_NAME_PRIMARY,
                ContactsContract.PhoneLookup.STARRED
            ),
            selection = null,
            sortOrder = Contacts.DISPLAY_NAME_PRIMARY
        ).map { cursor ->
            cursor.mapEachRow {
                PartialContact(
                    contactId = it.getLong(0),
                    displayName = it.getString(1),
                    isStarred = it.getInt(2) == 1,
                    columns = emptyList()
                )
            }
        }

    }

    private fun queryAllContacts(): Flow<List<PartialContact>> {
        return contentResolver.runQueryFlow(
            contentUri = Contacts.CONTENT_URI,
            projection = SimpleQuery.PROJECTION,
            selection = null,
            sortOrder = Contacts.DISPLAY_NAME_PRIMARY
        ).map { cursor ->
            cursor.mapEachRow {
                PartialContact(
                    contactId = SimpleQuery.getContactId(it),
                    displayName = SimpleQuery.getDisplayName(it),
                    isStarred = SimpleQuery.getIsStarred(it),
                    columns = emptyList()
                )
            }
        }
    }

    private fun fetchAdditionalColumns(
        forContactIds: List<Long>,
        columnsToFetch: List<ContactColumn>
    ): List<Contact> {
        return forContactIds.map { contactId ->
            var firstName: String? = null
            var middleName: String? = null
            var lastName: String? = null
            var prefix: String? = null
            var suffix: String? = null
            var fullNameStyle: Int = FullNameStyle.UNDEFINED
            var nickname: String? = null
            var phoneticFirstName: String? = null
            var phoneticMiddleName: String? = null
            var phoneticLastName: String? = null
            var phoneticNameStyle: Int = PhoneticNameStyle.UNDEFINED
            var imageData: ImageData? = null
            val phones = mutableSetOf<LabeledValue<PhoneNumber>>()
            val mails = mutableSetOf<LabeledValue<MailAddress>>()
            val webAddresses = mutableSetOf<LabeledValue<WebAddress>>()
            val events = mutableSetOf<LabeledValue<EventDate>>()
            val postalAddresses = mutableSetOf<LabeledValue<PostalAddress>>()
            var organization: String? = null
            var jobTitle: String? = null
            var note: Note? = null
            var displayName: String? = null
            var isStarred = false
            val groupIds = mutableListOf<GroupMembership>()

            contentResolver.runQuery(
                contentUri = Data.CONTENT_URI,
                selection = buildSelection(contactId, columnsToFetch)
            ).iterate { row ->
                displayName = row[Data.DISPLAY_NAME]
                isStarred = row[Data.STARRED].toInt() == 1

                when (val mimeType = row[Contacts.Data.MIMETYPE]) {
                    NicknameColumns.CONTENT_ITEM_TYPE -> {
                        nickname = row[NicknameColumns.NAME]
                    }
                    GroupColumns.CONTENT_ITEM_TYPE -> {
                        val groupId = row[GroupColumns.GROUP_ROW_ID].toLong()
                        val id = row[GroupColumns._ID].toLong()
                        groupIds.add(GroupMembership(_id = id, groupId = groupId))
                        Unit
                    }
                    NameColumns.CONTENT_ITEM_TYPE -> {
                        firstName = row[NameColumns.GIVEN_NAME]
                        middleName = row[NameColumns.MIDDLE_NAME]
                        lastName = row[NameColumns.FAMILY_NAME]
                        prefix = row[NameColumns.PREFIX]
                        suffix = row[NameColumns.SUFFIX]
                        fullNameStyle = row[NameColumns.FULL_NAME_STYLE].toInt()
                        phoneticFirstName = row[NameColumns.PHONETIC_GIVEN_NAME]
                        phoneticMiddleName = row[NameColumns.PHONETIC_MIDDLE_NAME]
                        phoneticLastName = row[NameColumns.PHONETIC_FAMILY_NAME]
                        phoneticNameStyle = row[NameColumns.PHONETIC_NAME_STYLE].toInt()
                    }
                    PhotoColumns.CONTENT_ITEM_TYPE -> {
                        imageData = loadContactPhoto(contactId)
                    }
                    PhoneColumns.CONTENT_ITEM_TYPE -> {
                        val phoneNumberString = row[PhoneColumns.NUMBER]
                        val id = row[PhoneColumns._ID].toLong()
                        if (phoneNumberString.isNotBlank()) {
                            val value = PhoneNumber(phoneNumberString)
                            val phoneEntry = LabeledValue(value, phoneLabelFrom(row), id)
                            phones.add(phoneEntry)
                        }
                    }
                    EmailColumns.CONTENT_ITEM_TYPE -> {
                        val mailAddressString = row[EmailColumns.ADDRESS]
                        val id = row[EmailColumns._ID].toLong()
                        if (mailAddressString.isNotBlank()) {
                            val mailAddress = MailAddress(mailAddressString)
                            mails.add(
                                LabeledValue(
                                    mailAddress,
                                    mailLabelFrom(row),
                                    id
                                )
                            )
                        }
                    }
                    WebColumns.CONTENT_ITEM_TYPE -> {
                        val webAddressString = row[WebColumns.URL]
                        val id = row[WebColumns._ID].toLong()
                        if (webAddressString.isNotBlank()) {
                            val mailAddress = WebAddress(webAddressString)
                            webAddresses.add(
                                LabeledValue(
                                    mailAddress,
                                    webLabelFrom(row),
                                    id
                                )
                            )
                        }
                    }
                    NoteColumns.CONTENT_ITEM_TYPE -> {
                        val noteString = row[NoteColumns.NOTE]
                        if (noteString.isNotBlank()) {
                            note = Note(noteString)
                        }
                    }
                    EventColumns.CONTENT_ITEM_TYPE -> {
                        val parsedDate = dateParser.parse(row[EventColumns.START_DATE])
                        val id = row[EventColumns._ID].toLong()
                        if (parsedDate != null) {
                            val entry = LabeledValue(parsedDate, eventLabelFrom(row), id)
                            events.add(entry)
                        }
                    }
                    PostalColumns.CONTENT_ITEM_TYPE -> {
                        val formattedAddress = row[PostalColumns.FORMATTED_ADDRESS]
                        if (formattedAddress.isNotBlank()) {
                            val street = row[PostalColumns.STREET].trim()
                            val poBox = row[PostalColumns.POBOX].trim()
                            val neighborhood = row[PostalColumns.NEIGHBORHOOD].trim()
                            val city = row[PostalColumns.CITY].trim()
                            val region = row[PostalColumns.REGION].trim()
                            val postCode = row[PostalColumns.POSTCODE].trim()
                            val country = row[PostalColumns.COUNTRY].trim()
                            val id = row[PostalColumns._ID].toLong()
                            val value = PostalAddress(
                                street = street,
                                poBox = poBox,
                                neighborhood = neighborhood,
                                city = city,
                                region = region,
                                postCode = postCode,
                                country = country,
                            )
                            val postalAddressEntry = LabeledValue(
                                value,
                                label = postalAddressLabelFrom(row),
                                id
                            )
                            postalAddresses.add(postalAddressEntry)
                        }
                    }
                    OrganizationColumns.CONTENT_ITEM_TYPE -> {
                        organization = row[OrganizationColumns.COMPANY]
                        jobTitle = row[OrganizationColumns.TITLE]
                    }
                    else -> error("No mapping found for $mimeType")
                }
            }
            PartialContact(
                contactId = contactId,
                columns = columnsToFetch,
                isStarred = isStarred,
                displayName = displayName.orEmpty(),
                firstName = firstName,
                lastName = lastName,
                imageData = imageData,
                organization = organization,
                jobTitle = jobTitle,
                webAddresses = webAddresses.toList(),
                phones = phones.toList(),
                postalAddresses = postalAddresses.toList(),
                mails = mails.toList(),
                events = events.toList(),
                note = note,
                prefix = prefix,
                middleName = middleName,
                suffix = suffix,
                phoneticLastName = phoneticLastName,
                phoneticFirstName = phoneticFirstName,
                fullNameStyle = fullNameStyle,
                nickname = nickname,
                phoneticMiddleName = phoneticMiddleName,
                phoneticNameStyle = phoneticNameStyle,
                groups = groupIds
            )
        }
    }

    private fun loadContactPhoto(id: Long): ImageData? {
        val uri: Uri = ContentUris.withAppendedId(Contacts.CONTENT_URI, id)
        val inputStream: InputStream? =
            Contacts.openContactPhotoInputStream(contentResolver, uri, true)
        return inputStream
            ?.toByteArray()
            ?.let { ImageData(it) }
    }

    private fun InputStream.toByteArray(): ByteArray {
        return buffered().use { it.readBytes() }
    }

    private fun buildSelection(
        forContactId: Long,
        columnsToFetch: List<ContactColumn>
    ): String {
        return "${Data.CONTACT_ID} = $forContactId" +
                " AND ${Data.MIMETYPE} IN ${
                    valueIn(
                        columnsToFetch.map { col ->
                            when (col) {
                                ContactColumn.PHONES -> PhoneColumns.CONTENT_ITEM_TYPE
                                ContactColumn.MAILS -> EmailColumns.CONTENT_ITEM_TYPE
                                ContactColumn.NOTE -> NoteColumns.CONTENT_ITEM_TYPE
                                ContactColumn.EVENTS -> EventColumns.CONTENT_ITEM_TYPE
                                ContactColumn.POSTAL_ADDRESSES -> PostalColumns.CONTENT_ITEM_TYPE
                                ContactColumn.IMAGE -> PhotoColumns.CONTENT_ITEM_TYPE
                                ContactColumn.NAMES -> NameColumns.CONTENT_ITEM_TYPE
                                ContactColumn.WEB_ADDRESSES -> WebColumns.CONTENT_ITEM_TYPE
                                ContactColumn.ORGANIZATION -> OrganizationColumns.CONTENT_ITEM_TYPE
                                ContactColumn.NICKNAME -> NicknameColumns.CONTENT_ITEM_TYPE
                                ContactColumn.GROUP_MEMBERSHIPS -> GroupColumns.CONTENT_ITEM_TYPE
                            }
                        }
                    )
                }"
    }

    private fun postalAddressLabelFrom(cursor: Cursor): Label {
        return when (
            cursor[PostalColumns.TYPE]
                .ifBlank { "${PostalColumns.TYPE_CUSTOM}" }.toInt()) {
            BaseTypes.TYPE_CUSTOM -> Label.Custom(cursor[PostalColumns.LABEL])
            PostalColumns.TYPE_HOME -> Label.LocationHome
            PostalColumns.TYPE_WORK -> Label.LocationWork
            PostalColumns.TYPE_OTHER -> Label.Other
            else -> Label.Other
        }
    }

    private fun eventLabelFrom(cursor: Cursor): Label {
        return when (cursor[EventColumns.TYPE].ifBlank { "${EventColumns.TYPE_CUSTOM}" }.toInt()) {
            BaseTypes.TYPE_CUSTOM -> Label.Custom(cursor[EventColumns.LABEL])
            EventColumns.TYPE_ANNIVERSARY -> Label.DateAnniversary
            EventColumns.TYPE_BIRTHDAY -> Label.DateBirthday
            EventColumns.TYPE_OTHER -> Label.Other
            else -> Label.Other
        }
    }

    private fun mailLabelFrom(cursor: Cursor): Label {
        return when (cursor[EmailColumns.TYPE].ifBlank { "${EmailColumns.TYPE_OTHER}" }.toInt()) {
            BaseTypes.TYPE_CUSTOM -> Label.Custom(cursor[EmailColumns.LABEL])
            EmailColumns.TYPE_HOME -> Label.LocationHome
            EmailColumns.TYPE_WORK -> Label.LocationWork
            EmailColumns.TYPE_OTHER -> Label.Other
            else -> Label.Other
        }
    }

    private fun webLabelFrom(cursor: Cursor): Label {
        return when (cursor[WebColumns.TYPE].ifBlank { "${WebColumns.TYPE_OTHER}" }.toInt()) {
            BaseTypes.TYPE_CUSTOM -> Label.Custom(cursor[WebColumns.LABEL])
            WebColumns.TYPE_HOME -> Label.LocationHome
            WebColumns.TYPE_HOMEPAGE -> Label.WebsiteHomePage
            WebColumns.TYPE_BLOG -> Label.WebsiteBlog
            WebColumns.TYPE_FTP -> Label.WebsiteFtp
            WebColumns.TYPE_PROFILE -> Label.WebsiteProfile
            WebColumns.TYPE_WORK -> Label.LocationWork
            else -> Label.Other
        }
    }

    private fun phoneLabelFrom(cursor: Cursor): Label {
        return when (cursor[PhoneColumns.TYPE].ifBlank { "${PhoneColumns.TYPE_OTHER}" }.toInt()) {
            BaseTypes.TYPE_CUSTOM -> Label.Custom(cursor[PhoneColumns.LABEL])
            PhoneColumns.TYPE_HOME -> Label.LocationHome
            PhoneColumns.TYPE_MOBILE -> Label.PhoneNumberMobile
            PhoneColumns.TYPE_WORK -> Label.LocationWork
            PhoneColumns.TYPE_FAX_WORK -> Label.PhoneNumberFaxWork
            PhoneColumns.TYPE_FAX_HOME -> Label.PhoneNumberFaxHome
            PhoneColumns.TYPE_PAGER -> Label.PhoneNumberPager
            PhoneColumns.TYPE_OTHER -> Label.Other
            PhoneColumns.TYPE_CALLBACK -> Label.PhoneNumberCallback
            PhoneColumns.TYPE_CAR -> Label.PhoneNumberCar
            PhoneColumns.TYPE_COMPANY_MAIN -> Label.PhoneNumberCompanyMain
            PhoneColumns.TYPE_ISDN -> Label.PhoneNumberIsdn
            PhoneColumns.TYPE_MAIN -> Label.Main
            PhoneColumns.TYPE_OTHER_FAX -> Label.PhoneNumberOtherFax
            PhoneColumns.TYPE_RADIO -> Label.PhoneNumberRadio
            PhoneColumns.TYPE_TELEX -> Label.PhoneNumberTelex
            PhoneColumns.TYPE_TTY_TDD -> Label.PhoneNumberTtyTdd
            PhoneColumns.TYPE_WORK_MOBILE -> Label.PhoneNumberWorkMobile
            PhoneColumns.TYPE_WORK_PAGER -> Label.PhoneNumberWorkPager
            PhoneColumns.TYPE_ASSISTANT -> Label.PhoneNumberAssistant
            PhoneColumns.TYPE_MMS -> Label.PhoneNumberMms
            else -> Label.Other
        }
    }

    private fun Boolean.toBoolInt(): String {
        return if (this) {
            "1"
        } else {
            "0"
        }
    }

    private object SimpleQuery {
        val PROJECTION = arrayOf(
            Contacts._ID,
            Contacts.DISPLAY_NAME_PRIMARY,
            Contacts.STARRED
        )

        fun getContactId(it: Cursor): Long {
            return it.getLong(0)
        }

        fun getDisplayName(it: Cursor): String? {
            return it.getString(1)
        }

        fun getIsStarred(it: Cursor): Boolean {
            return it.getInt(2) == 1
        }
    }
}