package com.lsl.kotlin_agent_app.smb_media

import android.content.Context

object SmbMediaRuntime {
    private val lock = Any()

    @Volatile
    private var ticketStore: SmbMediaTicketStore? = null

    @Volatile
    private var readerFactory: SmbMediaReaderFactory? = null

    fun ticketStore(context: Context): SmbMediaTicketStore {
        val existing = ticketStore
        if (existing != null) return existing
        synchronized(lock) {
            val again = ticketStore
            if (again != null) return again
            val created = SmbMediaTicketStore()
            ticketStore = created
            return created
        }
    }

    fun readerFactory(context: Context): SmbMediaReaderFactory {
        val existing = readerFactory
        if (existing != null) return existing
        synchronized(lock) {
            val again = readerFactory
            if (again != null) return again
            val fileHandleFactory = SmbjSmbMediaFileHandleFactory(context.applicationContext)
            val created = SmbMediaReaderFactory(fileHandleFactory)
            readerFactory = created
            return created
        }
    }
}

