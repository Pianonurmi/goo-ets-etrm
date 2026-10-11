import {
  ActionIcon,
  Badge,
  Button,
  Group,
  Modal,
  Select,
  Stack,
  Table,
  TagsInput,
  Text,
  TextInput,
  Tooltip,
} from '@mantine/core';
import { IconMapPin, IconPencil, IconPlus } from '@tabler/icons-react';
import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { useSearchParams } from 'react-router';
import { $api } from '@/shared/api/client';
import type { Schema } from '@/shared/api/types';
import { Can } from '@/shared/auth/Can';
import { notifySuccess } from '@/shared/components/notify';
import { PageHeader } from '@/shared/components/PageHeader';
import { ProblemAlert } from '@/shared/components/ProblemAlert';
import { QueryState } from '@/shared/components/QueryState';
import { formatDate } from '@/shared/format/format';
import { useInvalidateReference } from './PartyDetails';

type Node = Schema<'TaxonomyNodeView'>;
type Scheme = Node['scheme'];
const SCHEMES: Scheme[] = ['ENERGY_SOURCE', 'TECHNOLOGY', 'LABEL', 'REGION', 'SUPPORT_SCHEME'];

function NodeModal({
  scheme,
  parent,
  node,
  onClose,
}: {
  scheme: Scheme;
  parent?: string | null;
  node?: Node;
  onClose: () => void;
}) {
  const { t } = useTranslation();
  const invalidate = useInvalidateReference();
  const [code, setCode] = useState('');
  const [name, setName] = useState(node?.name ?? '');
  const [description, setDescription] = useState(node?.description ?? '');
  const [validTo, setValidTo] = useState(node?.validPeriod.validTo ?? '');
  const done = () => {
    notifySuccess(t('admin.saved'));
    void invalidate();
    onClose();
  };
  const create = $api.useMutation('post', '/api/v1/taxonomy/{scheme}', { meta: { notify: false }, onSuccess: done });
  const update = $api.useMutation('patch', '/api/v1/taxonomy/{scheme}/{code}', {
    meta: { notify: false },
    onSuccess: done,
  });
  return (
    <Modal
      opened
      onClose={onClose}
      title={
        node ? t('admin.editNode', { code: node.code }) : parent ? t('admin.addChild', { parent }) : t('admin.addRoot')
      }
    >
      <Stack>
        <ProblemAlert error={create.error ?? update.error} />
        {!node && (
          <TextInput
            label={t('marketData.code')}
            description={t('admin.codeHint')}
            value={code}
            onChange={(e) => setCode(e.currentTarget.value.toUpperCase())}
            data-testid="node-code"
          />
        )}
        <TextInput
          label={t('ets.name')}
          value={name}
          onChange={(e) => setName(e.currentTarget.value)}
          data-testid="node-name"
        />
        <TextInput
          label={t('marketData.descriptionColumn')}
          value={description}
          onChange={(e) => setDescription(e.currentTarget.value)}
        />
        {node && (
          <TextInput
            type="date"
            label={t('risk.validTo')}
            value={validTo ?? ''}
            onChange={(e) => setValidTo(e.currentTarget.value)}
          />
        )}
        <Button
          disabled={!name.trim() || (!node && !code.trim())}
          loading={create.isPending || update.isPending}
          onClick={() =>
            node
              ? update.mutate({
                  params: { path: { scheme, code: node.code } },
                  body: {
                    name,
                    description: description || undefined,
                    validTo: validTo || undefined,
                    expectedVersion: node.version,
                  },
                })
              : create.mutate({
                  params: { path: { scheme } },
                  body: { code, name, description: description || undefined, parentCode: parent ?? undefined },
                })
          }
          data-testid="node-save"
        >
          {t('ets.save')}
        </Button>
      </Stack>
    </Modal>
  );
}

function RegionCountries({ code, onClose }: { code: string; onClose: () => void }) {
  const { t } = useTranslation();
  const invalidate = useInvalidateReference();
  const countries = $api.useQuery('get', '/api/v1/taxonomy/REGION/{code}/countries', { params: { path: { code } } });
  const [value, setValue] = useState<string[] | null>(null);
  const save = $api.useMutation('put', '/api/v1/taxonomy/REGION/{code}/countries', {
    meta: { notify: false },
    onSuccess: () => {
      notifySuccess(t('admin.saved'));
      void invalidate();
      onClose();
    },
  });
  const current = value ?? countries.data ?? [];
  return (
    <Modal opened onClose={onClose} title={t('admin.regionCountries', { code })}>
      <Stack>
        <ProblemAlert error={save.error} />
        <QueryState isPending={countries.isPending} error={countries.error}>
          <TagsInput
            label={t('admin.countries')}
            description={t('admin.countriesHint')}
            value={current}
            onChange={(next) => setValue(next.map((c) => c.trim().toUpperCase()).filter((c) => c.length === 2))}
          />
        </QueryState>
        <Can op="taxonomy_setRegionCountries">
          <Button loading={save.isPending} onClick={() => save.mutate({ params: { path: { code } }, body: current })}>
            {t('ets.save')}
          </Button>
        </Can>
      </Stack>
    </Modal>
  );
}

/** Code sets used by devices, criteria and buckets; nodes are versioned and retired by end date, never deleted. */
export function TaxonomyPage() {
  const { t } = useTranslation();
  const [params, setParams] = useSearchParams();
  const scheme = SCHEMES.find((s) => s === params.get('scheme')) ?? 'ENERGY_SOURCE';
  const [editing, setEditing] = useState<{ node?: Node; parent?: string | null } | null>(null);
  const [region, setRegion] = useState<string | null>(null);
  const nodes = $api.useQuery('get', '/api/v1/taxonomy/{scheme}', {
    params: { path: { scheme }, query: { all: true } },
  });
  return (
    <Stack>
      <PageHeader title={t('admin.taxonomyTitle')} description={t('admin.taxonomyDescription')} />
      <Group justify="space-between" align="flex-end">
        <Select
          label={t('admin.scheme')}
          data={SCHEMES.map((value) => ({ value, label: t(`admin.schemes.${value}`) }))}
          value={scheme}
          allowDeselect={false}
          onChange={(value) => value && setParams({ scheme: value }, { replace: true })}
          w={260}
          data-testid="scheme"
        />
        <Can op="taxonomy_create">
          <Button leftSection={<IconPlus size={16} />} onClick={() => setEditing({ parent: null })}>
            {t('admin.addRoot')}
          </Button>
        </Can>
      </Group>
      <QueryState isPending={nodes.isPending} error={nodes.error}>
        <Table striped highlightOnHover withTableBorder data-testid="taxonomy">
          <Table.Thead>
            <Table.Tr>
              <Table.Th>{t('marketData.code')}</Table.Th>
              <Table.Th>{t('ets.name')}</Table.Th>
              <Table.Th>{t('admin.externalCodes')}</Table.Th>
              <Table.Th>{t('risk.validity')}</Table.Th>
              <Table.Th />
            </Table.Tr>
          </Table.Thead>
          <Table.Tbody>
            {(nodes.data ?? []).map((node) => (
              <Table.Tr key={node.id}>
                <Table.Td style={{ paddingLeft: 12 + node.depth * 20 }} ff="monospace">
                  {node.code}
                </Table.Td>
                <Table.Td>
                  <Text size="sm">{node.name}</Text>
                  {node.description && (
                    <Text size="xs" c="dimmed">
                      {node.description}
                    </Text>
                  )}
                </Table.Td>
                <Table.Td>
                  {Object.entries(node.externalCodes).map(([system, value]) => (
                    <Badge key={system} size="xs" variant="light" mr={4}>
                      {system}: {value}
                    </Badge>
                  ))}
                </Table.Td>
                <Table.Td>
                  <Text size="xs">
                    {formatDate(node.validPeriod.validFrom)} –{' '}
                    {node.validPeriod.validTo ? formatDate(node.validPeriod.validTo) : '∞'}
                  </Text>
                </Table.Td>
                <Table.Td>
                  <Group gap={2} wrap="nowrap" justify="flex-end">
                    {scheme === 'REGION' && (
                      <Tooltip label={t('admin.countries')}>
                        <ActionIcon
                          variant="subtle"
                          onClick={() => setRegion(node.code)}
                          aria-label={t('admin.countries')}
                        >
                          <IconMapPin size={16} />
                        </ActionIcon>
                      </Tooltip>
                    )}
                    <Can op="taxonomy_create">
                      <Tooltip label={t('admin.addChild', { parent: node.code })}>
                        <ActionIcon
                          variant="subtle"
                          onClick={() => setEditing({ parent: node.code })}
                          aria-label={t('admin.addChild', { parent: node.code })}
                        >
                          <IconPlus size={16} />
                        </ActionIcon>
                      </Tooltip>
                    </Can>
                    <Can op="taxonomy_update">
                      <Tooltip label={t('admin.editNode', { code: node.code })}>
                        <ActionIcon
                          variant="subtle"
                          onClick={() => setEditing({ node })}
                          aria-label={t('admin.editNode', { code: node.code })}
                        >
                          <IconPencil size={16} />
                        </ActionIcon>
                      </Tooltip>
                    </Can>
                  </Group>
                </Table.Td>
              </Table.Tr>
            ))}
          </Table.Tbody>
        </Table>
      </QueryState>
      {editing && (
        <NodeModal scheme={scheme} parent={editing.parent} node={editing.node} onClose={() => setEditing(null)} />
      )}
      {region && <RegionCountries code={region} onClose={() => setRegion(null)} />}
    </Stack>
  );
}
