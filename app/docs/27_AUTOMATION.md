# 27. Automation Architecture

## WHAT
Autonomous scheduling, background workers, and real-time compliance auditors.

## MODULES
- `AccountingScheduler`: Central coordinator orchestrating daily, monthly, and yearly cycles.
- `DailyJobs`: Outbox sync, failed sync detection, suspense zero-balance audit, liquidity snapshots.
- `MonthlyJobs`: Monthly Profit & Loss / Balance Sheet generation, GSTIN compliance checks.
- `YearlyJobs`: Period close eligibility audits, opening balance roll-forward verification.
- `EventDrivenJobs`: Real-time handlers triggered on `VoucherPosted`, `VoucherDeleted`, `LedgerCreated`, `PeriodLocked`.
- `AutomationNotificationCenter`: SharedFlow notification pipeline delivering actionable alerts.
