import { createBrowserRouter } from 'react-router';
import { AppLayout } from './layout/AppLayout';
import { NotFoundPage } from './pages/NotFoundPage';
import { RouteErrorPage } from './pages/RouteErrorPage';
import { registerSubjectScreens } from '@/features/inbox/model/subjects';

registerSubjectScreens('TRADE', 'ALLOCATION_PLAN', 'CURVE_SNAPSHOT', 'VALUATION_RUN');

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
