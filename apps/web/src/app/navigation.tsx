import { IconArrowsExchange, IconCertificate, IconHome, IconInbox } from '@tabler/icons-react';
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

/** Navigation; each milestone of increment 4 adds its screens here. */
export const NAVIGATION: NavSection[] = [
  {
    items: [
      { path: '/', label: 'nav.home', icon: <IconHome size={ICON} /> },
      { path: '/inbox', label: 'nav.inbox', icon: <IconInbox size={ICON} />, badge: 'inbox' },
    ],
  },
  {
    title: 'nav.sectionTrading',
    items: [{ path: '/trades', label: 'nav.trades', icon: <IconArrowsExchange size={ICON} /> }],
  },
  {
    title: 'nav.sectionOperations',
    items: [{ path: '/inventory', label: 'nav.inventory', icon: <IconCertificate size={ICON} /> }],
  },
];

export function visibleSections(roles: readonly Role[]): NavSection[] {
  const allowed = (item: NavItem) =>
    !item.roles || roles.includes('ADMIN') || item.roles.some((role) => roles.includes(role));
  return NAVIGATION.map((section) => ({ ...section, items: section.items.filter(allowed) })).filter(
    (section) => section.items.length > 0,
  );
}
