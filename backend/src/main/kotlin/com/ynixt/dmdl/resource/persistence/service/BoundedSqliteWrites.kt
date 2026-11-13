package com.ynixt.dmdl.resource.persistence.service

import com.ynixt.dmdl.domain.model.DownloadFailure
import org.springframework.dao.TransientDataAccessException
import org.springframework.transaction.support.TransactionTemplate
import java.sql.SQLException

/** Retry an entire short transaction: a WAL snapshot cannot be upgraded by retrying only its statement. */
class BoundedSqliteWrites(
    private val transaction: TransactionTemplate,
) {
    fun <T> run(action: () -> T): T {
        repeat(4) { attempt ->
            try {
                return transaction.execute { action() }
            } catch (error: RuntimeException) {
                val busy =
                    error is TransientDataAccessException ||
                        generateSequence<Throwable>(error) { it.cause }.any {
                            it is SQLException && (it.errorCode and 255) in setOf(5, 6)
                        }
                if (!busy) throw error
                if (attempt == 3) throw DownloadFailure("Database is busy; retry the operation")
                Thread.sleep(25L shl attempt)
            }
        }
        error("Unreachable SQLite retry state")
    }
}
