import { createBrowserRouter } from 'react-router';
import { AppLayout } from './layout/AppLayout';
import { NotFoundPage } from './pages/NotFoundPage';
import { RouteErrorPage } from './pages/RouteErrorPage';
import { registerSubjectScreens } from '@/features/inbox/model/subjects';

registerSubjectScreens(
  'TRADE',
  'ALLOCATION_PLAN',
  'CURVE_SNAPSHOT',
  'VALUATION_RUN',
  'RISK_LIMIT',
  'LIMIT_BREACH',
  'CREDIT_SUPPORT',
  'EMISSION_RECORD',
  'COMPLIANCE_DEADLINE',
  'ACCOUNTING_POLICY_CHANGE',
  'POSTING_RULE',
  'CHANGE_REQUEST',
  'INVOICE',
  'VAT_RULE',
  'REGISTRY_INSTRUCTION',
  'RECONCILIATION_BREAK',
  'TRADE_CONFIRMATION',
  'HEDGE',
);

/** Feature screens load lazily, so the first page only pays for the shell. */
export const router = createBrowserRouter([
  {
    element: <AppLayout />,
    errorElement: <RouteErrorPage />,
    children: [
      {
        index: true,
        lazy: async () => ({ Component: (await import('@/features/home/HomePage')).HomePage }),
      },
      {
        path: 'inbox',
        lazy: async () => ({ Component: (await import('@/features/inbox/InboxPage')).InboxPage }),
      },
      {
        path: 'inbox/:taskId',
        lazy: async () => ({ Component: (await import('@/features/inbox/TaskDetailPage')).TaskDetailPage }),
      },
      {
        path: 'trades',
        lazy: async () => ({ Component: (await import('@/features/trades/TradesPage')).TradesPage }),
      },
      {
        path: 'trades/new',
        lazy: async () => ({ Component: (await import('@/features/trades/TradeFormPage')).TradeFormPage }),
      },
      {
        path: 'trades/:tradeId',
        lazy: async () => ({ Component: (await import('@/features/trades/TradeDetailPage')).TradeDetailPage }),
      },
      {
        path: 'trades/:tradeId/amend',
        lazy: async () => ({ Component: (await import('@/features/trades/TradeFormPage')).TradeFormPage }),
      },
      {
        path: 'allocation',
        lazy: async () => ({ Component: (await import('@/features/allocation/AllocationPage')).AllocationPage }),
      },
      {
        path: 'allocation/plans/:planId',
        lazy: async () => ({ Component: (await import('@/features/allocation/PlanDetailPage')).PlanDetailPage }),
      },
      {
        path: 'positions',
        lazy: async () => ({ Component: (await import('@/features/positions/PositionsPage')).PositionsPage }),
      },
      {
        path: 'market-data',
        lazy: async () => ({ Component: (await import('@/features/marketdata/MarketDataPage')).MarketDataPage }),
      },
      {
        path: 'market-data/curves',
        lazy: async () => ({ Component: (await import('@/features/marketdata/CurvesPage')).CurvesPage }),
      },
      {
        path: 'market-data/snapshots/:snapshotId',
        lazy: async () => ({ Component: (await import('@/features/marketdata/CurvesPage')).SnapshotPage }),
      },
      {
        path: 'valuation',
        lazy: async () => ({ Component: (await import('@/features/valuation/ValuationPage')).ValuationPage }),
      },
      {
        path: 'valuation/runs/:runId',
        lazy: async () => ({ Component: (await import('@/features/valuation/ValuationPage')).RunDetailPage }),
      },
      {
        path: 'risk',
        lazy: async () => ({ Component: (await import('@/features/risk/RiskPage')).RiskPage }),
      },
      {
        path: 'risk/limits/:limitId',
        lazy: async () => ({ Component: (await import('@/features/risk/LimitDetailPage')).LimitDetailPage }),
      },
      {
        path: 'ets',
        lazy: async () => ({ Component: (await import('@/features/ets/EtsPage')).EtsPage }),
      },
      {
        path: 'ets/installations/:installationId',
        lazy: async () => ({ Component: (await import('@/features/ets/InstallationPage')).InstallationPage }),
      },
      {
        path: 'ledger',
        lazy: async () => ({ Component: (await import('@/features/ledger/LedgerPage')).LedgerPage }),
      },
      {
        path: 'ledger/hedges',
        lazy: async () => ({ Component: (await import('@/features/ledger/HedgesPage')).HedgesPage }),
      },
      {
        path: 'ledger/hedges/:hedgeId',
        lazy: async () => ({ Component: (await import('@/features/ledger/HedgeDetailPage')).HedgeDetailPage }),
      },
      {
        path: 'month-end',
        lazy: async () => ({ Component: (await import('@/features/monthend/MonthEndPage')).MonthEndPage }),
      },
      {
        path: 'reports',
        lazy: async () => ({ Component: (await import('@/features/reports/ReportsPage')).ReportsPage }),
      },
      {
        path: 'settlement',
        lazy: async () => ({ Component: (await import('@/features/settlement/SettlementPage')).SettlementPage }),
      },
      {
        path: 'settlement/invoices/:invoiceId',
        lazy: async () => ({
          Component: (await import('@/features/settlement/InvoiceDetailPage')).InvoiceDetailPage,
        }),
      },
      {
        path: 'settlement/netting/:nettingId',
        lazy: async () => ({
          Component: (await import('@/features/settlement/NettingDetailPage')).NettingDetailPage,
        }),
      },
      {
        path: 'registry',
        lazy: async () => ({ Component: (await import('@/features/registry/RegistryPage')).RegistryPage }),
      },
      {
        path: 'registry/instructions/:instructionId',
        lazy: async () => ({
          Component: (await import('@/features/registry/InstructionDetailPage')).InstructionDetailPage,
        }),
      },
      {
        path: 'reconciliation',
        lazy: async () => ({
          Component: (await import('@/features/reconciliation/ReconciliationPage')).ReconciliationPage,
        }),
      },
      {
        path: 'reconciliation/breaks/:breakId',
        lazy: async () => ({
          Component: (await import('@/features/reconciliation/BreakDetailPage')).BreakDetailPage,
        }),
      },
      {
        path: 'confirmations',
        lazy: async () => ({
          Component: (await import('@/features/confirmations/ConfirmationsPage')).ConfirmationsPage,
        }),
      },
      {
        path: 'confirmations/:confirmationId',
        lazy: async () => ({
          Component: (await import('@/features/confirmations/ConfirmationDetailPage')).ConfirmationDetailPage,
        }),
      },
      {
        path: 'admin/reference',
        lazy: async () => ({ Component: (await import('@/features/admin/ReferenceDataPage')).ReferenceDataPage }),
      },
      {
        path: 'admin/taxonomy',
        lazy: async () => ({ Component: (await import('@/features/admin/TaxonomyPage')).TaxonomyPage }),
      },
      {
        path: 'admin/products',
        lazy: async () => ({ Component: (await import('@/features/admin/ProductsPage')).ProductsPage }),
      },
      {
        path: 'admin/change-requests',
        lazy: async () => ({ Component: (await import('@/features/admin/ChangeRequestsPage')).ChangeRequestsPage }),
      },
      {
        path: 'audit',
        lazy: async () => ({ Component: (await import('@/features/audit/AuditPage')).AuditPage }),
      },
      {
        path: 'guide',
        lazy: async () => ({ Component: (await import('@/features/guide/GuidePage')).GuidePage }),
      },
      {
        path: 'help/:page?',
        lazy: async () => ({ Component: (await import('./help/HelpPage')).HelpPage }),
      },
      {
        path: 'admin/demo',
        lazy: async () => ({ Component: (await import('@/features/admin/DemoPage')).DemoPage }),
      },
      {
        path: 'inventory',
        lazy: async () => ({ Component: (await import('@/features/inventory/InventoryPage')).InventoryPage }),
      },
      {
        path: 'inventory/lots/:lotId',
        lazy: async () => ({ Component: (await import('@/features/inventory/LotDetailPage')).LotDetailPage }),
      },
      { path: '*', element: <NotFoundPage /> },
    ],
  },
]);
