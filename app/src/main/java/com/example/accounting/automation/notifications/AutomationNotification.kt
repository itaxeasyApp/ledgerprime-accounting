package com.example.accounting.automation.notifications

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.util.UUID

enum class NotificationPriority {
    LOW,
    NORMAL,
    HIGH,
    CRITICAL
}

enum class NotificationCategory {
    COMPLIANCE_ALERT,
    SUSPENSE_WARNING,
    SYNC_STATUS,
    UNRECONCILED_TRANSACTIONS,
    PERIOD_LOCK,
    YEAR_END_ALERT,
    INVOICE_REMINDER,
    RECURRING_VOUCHER,
    /** Phase 8A, Part 1 - GSTR-1 draft preparation/validation errors and statutory filing-due-date
     * reminders. Distinct from [COMPLIANCE_ALERT] (the existing GSTIN-readiness check) - this
     * category is specifically about a prepared RETURN's own state, not general ledger hygiene. */
    GST_RETURN_FILING
}

data class AutomationNotification(
    val notificationId: String = UUID.randomUUID().toString(),
    val companyId: String,
    val title: String,
    val message: String,
    val priority: NotificationPriority,
    val category: NotificationCategory,
    val timestamp: Long = System.currentTimeMillis(),
    val actionDeepLink: String? = null,
    val isRead: Boolean = false
)

object AutomationNotificationCenter {
    private val _notifications = MutableSharedFlow<AutomationNotification>(replay = 50, extraBufferCapacity = 50)
    val notifications: SharedFlow<AutomationNotification> = _notifications.asSharedFlow()

    private val notificationList = mutableListOf<AutomationNotification>()

    fun emitNotification(notification: AutomationNotification) {
        synchronized(notificationList) {
            notificationList.add(0, notification)
            if (notificationList.size > 200) {
                notificationList.removeAt(notificationList.size - 1)
            }
        }
        _notifications.tryEmit(notification)
    }

    fun getAllNotifications(companyId: String): List<AutomationNotification> {
        return synchronized(notificationList) {
            notificationList.filter { it.companyId == companyId }
        }
    }
}
