package com.elect3.mobdev.personalfinance.data.remote

import com.elect3.mobdev.personalfinance.data.auth.InMemoryAuthRepository
import com.elect3.mobdev.personalfinance.domain.reports.MonthlyReport
import com.elect3.mobdev.personalfinance.domain.reports.MonthlySnapshot
import com.elect3.mobdev.personalfinance.domain.reports.ReportRepository
import com.elect3.mobdev.personalfinance.domain.repository.FinanceFailure
import com.elect3.mobdev.personalfinance.domain.repository.FinanceResult
import com.elect3.mobdev.personalfinance.domain.repository.NotificationPage
import com.elect3.mobdev.personalfinance.domain.repository.NotificationRepository
import java.time.YearMonth

/** `api/notifications` reads plus C3 mark-read (POST, no body, 204 only). Error bodies are never read. */
internal class RemoteNotificationRepository(
    private val transport: AuthenticatedFinanceTransport,
    private val configuration: ApiConfiguration,
    auth: InMemoryAuthRepository,
    engine: FinanceWriteEngine,
) : NotificationRepository {
    private val writer = FinanceWriter(auth, engine)

    override suspend fun list(unreadOnly: Boolean, page: Int, pageSize: Int): FinanceResult<NotificationPage> {
        if (page < 1 || pageSize !in 1..100) return FinanceResult.Failure(FinanceFailure.InvalidInput)
        return transport.get(FinanceResource.Notifications, queryString("unreadOnly" to unreadOnly, "page" to page, "pageSize" to pageSize)) {
            FinanceJson.notifications(it, unreadOnly, page, pageSize)
        }
    }

    override suspend fun markRead(notificationId: Int): FinanceResult<Unit> {
        if (notificationId <= 0) return FinanceResult.Failure(FinanceFailure.InvalidInput)
        return writer.send(WriteMethod.POST, configuration.notificationReadUrl(notificationId), null, setOf(204)) { }
    }

    override suspend fun markAllRead(): FinanceResult<Unit> =
        writer.send(WriteMethod.POST, configuration.notificationReadUrl(null), null, setOf(204)) { }
}

/** C6 monthly report and C4 stored summaries; read-only. */
internal class RemoteReportRepository(private val transport: AuthenticatedFinanceTransport) : ReportRepository {
    override suspend fun monthly(month: YearMonth): FinanceResult<MonthlyReport> =
        transport.get(FinanceResource.MonthlyReport, queryString("month" to month.atDay(1))) { FinanceJson.monthlyReport(it, month) }

    override suspend fun summaries(page: Int, pageSize: Int): FinanceResult<List<MonthlySnapshot>> {
        if (page < 1 || pageSize !in 1..100) return FinanceResult.Failure(FinanceFailure.InvalidInput)
        return transport.get(FinanceResource.ReportSummaries, queryString("page" to page, "pageSize" to pageSize)) {
            FinanceJson.summaries(it, page, pageSize)
        }
    }
}
