import { createBrowserRouter } from 'react-router';
import { AppLayout } from './layout/AppLayout';
import { NotFoundPage } from './pages/NotFoundPage';
import { RouteErrorPage } from './pages/RouteErrorPage';

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
