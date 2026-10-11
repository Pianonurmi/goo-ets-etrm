import { ActionIcon, Badge, Kbd, Tooltip } from '@mantine/core';
import { useDebouncedValue } from '@mantine/hooks';
import { Spotlight, spotlight } from '@mantine/spotlight';
import { IconArrowsExchange, IconSearch } from '@tabler/icons-react';
import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { useNavigate } from 'react-router';
import { $api } from '@/shared/api/client';
import { useSession } from '@/shared/auth/SessionContext';
import { useEntity } from '@/shared/context/EntityContext';
import { formatDate } from '@/shared/format/format';
import { visibleSections } from '../navigation';
import { matchScreens, searchesTrades, type PaletteScreen } from './model/palette';

/** Opens the palette; also bound to mod+K. */
export function PaletteButton() {
  const { t } = useTranslation();
  return (
    <Tooltip
      label={
        <>
          {t('palette.open')} <Kbd size="xs">Ctrl K</Kbd>
        </>
      }
    >
      <ActionIcon
        variant="default"
        size="lg"
        onClick={() => spotlight.open()}
        aria-label={t('palette.open')}
        data-testid="palette-button"
      >
        <IconSearch size={18} />
      </ActionIcon>
    </Tooltip>
  );
}

/** Jump to any screen the user may see, or to a trade by number, external reference or UTI (mod+K). */
export function CommandPalette() {
  const { t } = useTranslation();
  const navigate = useNavigate();
  const { me } = useSession();
  const { entity } = useEntity();
  const [query, setQuery] = useState('');
  const [debounced] = useDebouncedValue(query.trim(), 250);
  const screens: PaletteScreen[] = visibleSections(me.roles).flatMap((section) =>
    section.items.map((item) => ({
      path: item.path,
      label: t(item.label),
      section: section.title ? t(section.title) : undefined,
    })),
  );
  const matches = matchScreens(screens, query);
  const trades = $api.useQuery(
    'get',
    '/api/v1/trades/search',
    { params: { query: { q: debounced, legalEntityId: entity?.id, limit: 6 } } },
    { enabled: searchesTrades(debounced) },
  );
  const tradeHits = searchesTrades(debounced) ? (trades.data?.items ?? []) : [];
  const go = (path: string) => {
    void navigate(path);
    spotlight.close();
  };
  return (
    <Spotlight.Root query={query} onQueryChange={setQuery} shortcut={['mod + K', '/']} scrollable maxHeight={420}>
      <Spotlight.Search
        placeholder={t('palette.placeholder')}
        leftSection={<IconSearch size={18} />}
        data-testid="palette-search"
      />
      <Spotlight.ActionsList>
        {matches.length > 0 && (
          <Spotlight.ActionsGroup label={t('palette.screens')}>
            {matches.map((screen) => (
              <Spotlight.Action
                key={screen.path}
                label={screen.label}
                description={screen.section}
                onClick={() => go(screen.path)}
              />
            ))}
          </Spotlight.ActionsGroup>
        )}
        {tradeHits.length > 0 && (
          <Spotlight.ActionsGroup label={t('palette.trades')}>
            {tradeHits.map((trade) => (
              <Spotlight.Action
                key={trade.id}
                label={trade.tradeNumber}
                description={[formatDate(trade.tradeDate), trade.side, trade.product, trade.externalRef]
                  .filter(Boolean)
                  .join(' · ')}
                leftSection={<IconArrowsExchange size={16} />}
                rightSection={
                  <Badge size="xs" variant="light">
                    {trade.status.replace(/_/g, ' ')}
                  </Badge>
                }
                onClick={() => go(`/trades/${trade.id}`)}
              />
            ))}
          </Spotlight.ActionsGroup>
        )}
        {matches.length === 0 && tradeHits.length === 0 && (
          <Spotlight.Empty>{trades.isFetching ? t('palette.searching') : t('palette.nothing')}</Spotlight.Empty>
        )}
      </Spotlight.ActionsList>
    </Spotlight.Root>
  );
}
