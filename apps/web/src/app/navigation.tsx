import {
  IconArrowsExchange,
  IconCertificate,
  IconChartBar,
  IconChartLine,
  IconBuildingBank,
  IconCategory,
  IconFileCheck,
  IconFileInvoice,
  IconBuildingArch,
  IconListDetails,
  IconPackages,
  IconBook2,
  IconCalendarCheck,
  IconReportAnalytics,
  IconBuildingFactory2,
  IconShieldCheck,
  IconHome,
  IconInbox,
  IconPuzzle,
  IconScale,
  IconTimeline,
} from '@tabler/icons-react';
import type { ReactNode } from 'react';
import type { Role } from '@/shared/api/types';

export interface NavItem {
  path: string;
  /** i18n key of the label. */
  label: string;
  icon: ReactNode;
  /** Shown only to these roles (ADMIN sees everything); everyone when absent. */
  roles?: readonly Role[];
  /** A live count shown next to the label. */
  badge?: 'inbox';
}

export interface NavSection {
  /** i18n key of the section title; the first section has none. */
  title?: string;
  items: NavItem[];
}

const ICON = 18;

/** Navigation; each milestone adds its screens here. */
const VALUATION_READERS: readonly Role[] = ['RISK', 'MIDDLE_OFFICE', 'ACCOUNTANT', 'AUDITOR', 'TRADER'];
const FINANCE: readonly Role[] = ['ACCOUNTANT', 'AUDITOR', 'BACK_OFFICE'];
const AUDIT_READERS: readonly Role[] = ['AUDITOR', 'ACCOUNTANT', 'MIDDLE_OFFICE', 'BACK_OFFICE', 'RISK'];
const MASTER_DATA: readonly Role[] = ['MIDDLE_OFFICE', 'BACK_OFFICE', 'REGISTRY_OPERATOR', 'ACCOUNTANT', 'AUDITOR'];
const SETTLEMENT_READERS: readonly Role[] = ['BACK_OFFICE', 'ACCOUNTANT', 'MIDDLE_OFFICE', 'RISK', 'AUDITOR'];
const REGISTRY_READERS: readonly Role[] = [
  'REGISTRY_OPERATOR',
  'BACK_OFFICE',
  'MIDDLE_OFFICE',
  'ACCOUNTANT',
  'RISK',
  'AUDITOR',
];
const RISK_READERS: readonly Role[] = ['RISK', 'MIDDLE_OFFICE', 'TRADER', 'AUDITOR'];

export const NAVIGATION: NavSection[] = [
  {
    items: [
      { path: '/', label: 'nav.home', icon: <IconHome size={ICON} /> },
      { path: '/inbox', label: 'nav.inbox', icon: <IconInbox size={ICON} />, badge: 'inbox' },
    ],
  },
  {
    title: 'nav.sectionTrading',
    items: [
      { path: '/trades', label: 'nav.trades', icon: <IconArrowsExchange size={ICON} /> },
      { path: '/market-data', label: 'nav.marketData', icon: <IconChartLine size={ICON} /> },
      { path: '/market-data/curves', label: 'nav.curves', icon: <IconTimeline size={ICON} /> },
    ],
  },
  {
    title: 'nav.sectionOperations',
    items: [
      { path: '/inventory', label: 'nav.inventory', icon: <IconCertificate size={ICON} /> },
      { path: '/allocation', label: 'nav.allocation', icon: <IconPuzzle size={ICON} /> },
      { path: '/positions', label: 'nav.positions', icon: <IconChartBar size={ICON} /> },
      {
        path: '/registry',
        label: 'nav.registry',
        icon: <IconBuildingArch size={ICON} />,
        roles: REGISTRY_READERS,
      },
      {
        path: '/settlement',
        label: 'nav.settlement',
        icon: <IconFileInvoice size={ICON} />,
        roles: SETTLEMENT_READERS,
      },
    ],
  },
  {
    title: 'nav.sectionFinance',
    items: [
      { path: '/valuation', label: 'nav.valuation', icon: <IconScale size={ICON} />, roles: VALUATION_READERS },
      { path: '/risk', label: 'nav.risk', icon: <IconShieldCheck size={ICON} />, roles: RISK_READERS },
      { path: '/ets', label: 'nav.ets', icon: <IconBuildingFactory2 size={ICON} /> },
      { path: '/ledger', label: 'nav.ledger', icon: <IconBook2 size={ICON} />, roles: FINANCE },
      { path: '/month-end', label: 'nav.monthEnd', icon: <IconCalendarCheck size={ICON} />, roles: FINANCE },
      { path: '/reports', label: 'nav.reports', icon: <IconReportAnalytics size={ICON} /> },
    ],
  },
  {
    title: 'nav.sectionAdmin',
    items: [
      {
        path: '/admin/reference',
        label: 'nav.referenceData',
        icon: <IconBuildingBank size={ICON} />,
        roles: MASTER_DATA,
      },
      { path: '/admin/taxonomy', label: 'nav.taxonomy', icon: <IconCategory size={ICON} />, roles: MASTER_DATA },
      { path: '/admin/products', label: 'nav.products', icon: <IconPackages size={ICON} />, roles: MASTER_DATA },
      {
        path: '/admin/change-requests',
        label: 'nav.changeRequests',
        icon: <IconFileCheck size={ICON} />,
        roles: MASTER_DATA,
      },
      { path: '/audit', label: 'nav.audit', icon: <IconListDetails size={ICON} />, roles: AUDIT_READERS },
    ],
  },
];

export function visibleSections(roles: readonly Role[]): NavSection[] {
  const allowed = (item: NavItem) =>
    !item.roles || roles.includes('ADMIN') || item.roles.some((role) => roles.includes(role));
  return NAVIGATION.map((section) => ({ ...section, items: section.items.filter(allowed) })).filter(
    (section) => section.items.length > 0,
  );
}
