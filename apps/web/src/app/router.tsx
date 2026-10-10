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
      { path: '*', element: <NotFoundPage /> },
    ],
  },
]);
