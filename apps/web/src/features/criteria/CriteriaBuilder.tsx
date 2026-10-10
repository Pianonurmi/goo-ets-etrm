import {
  ActionIcon,
  Alert,
  Badge,
  Box,
  Button,
  Group,
  Menu,
  Paper,
  SegmentedControl,
  Stack,
  Tabs,
  Text,
  Textarea,
  Tooltip,
} from '@mantine/core';
import { useDebouncedValue } from '@mantine/hooks';
import { IconDots, IconPlus, IconTrash } from '@tabler/icons-react';
import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { $api } from '@/shared/api/client';
import { asProblemError } from '@/shared/api/problem';
import { ConditionEditor } from './ConditionEditor';
import {
  addChild,
  incomplete,
  isGroup,
  newCondition,
  newSpecial,
  removeAt,
  replaceAt,
  toggleGroup,
  wrapAt,
  type Criterion,
  type Field,
  type Path,
} from './model/tree';
import { useCatalogue } from './useCriteriaData';

interface EditorProps {
  root: Criterion;
  node: Criterion;
  path: Path;
  fields: Map<string, Field>;
  onChange: (root: Criterion) => void;
}

function AddConditionMenu({ fields, onAdd }: { fields: Map<string, Field>; onAdd: (node: Criterion) => void }) {
  const { t } = useTranslation();
  return (
    <Menu position="bottom-start" withinPortal shadow="md" width={300}>
      <Menu.Target>
        <Button size="xs" variant="light" leftSection={<IconPlus size={14} />} data-testid="add-condition">
          {t('criteria.addCondition')}
        </Button>
      </Menu.Target>
      <Menu.Dropdown mah={420} style={{ overflowY: 'auto' }}>
        <Menu.Label>{t('criteria.attributes')}</Menu.Label>
        {[...fields.values()].flatMap((field) =>
          field.operators.map((operator) => (
            <Menu.Item key={`${field.attribute}-${operator}`} onClick={() => onAdd(newCondition(field, operator))}>
              {field.label}
              {field.operators.length > 1 ? ` · ${t(`criteria.operatorNames.${operator}`)}` : ''}
            </Menu.Item>
          )),
        )}
        <Menu.Divider />
        <Menu.Label>{t('criteria.relative')}</Menu.Label>
        <Menu.Item onClick={() => onAdd(newSpecial('countryInRegion'))}>
          {t('criteria.types.countryInRegion')}
        </Menu.Item>
        <Menu.Item onClick={() => onAdd(newSpecial('deviceAgeAtDelivery'))}>
          {t('criteria.types.deviceAgeAtDelivery')}
        </Menu.Item>
        <Menu.Item onClick={() => onAdd(newSpecial('matchAll'))}>{t('criteria.types.matchAll')}</Menu.Item>
      </Menu.Dropdown>
    </Menu>
  );
}

function NodeMenu({ root, path, onChange }: { root: Criterion; path: Path; onChange: (root: Criterion) => void }) {
  const { t } = useTranslation();
  return (
    <Menu position="bottom-end" withinPortal>
      <Menu.Target>
        <ActionIcon variant="subtle" color="gray" aria-label={t('criteria.more')}>
          <IconDots size={16} />
        </ActionIcon>
      </Menu.Target>
      <Menu.Dropdown>
        <Menu.Item onClick={() => onChange(wrapAt(root, path, 'not'))}>{t('criteria.negate')}</Menu.Item>
        <Menu.Item onClick={() => onChange(wrapAt(root, path, 'all'))}>{t('criteria.wrapAll')}</Menu.Item>
        <Menu.Item onClick={() => onChange(wrapAt(root, path, 'any'))}>{t('criteria.wrapAny')}</Menu.Item>
      </Menu.Dropdown>
    </Menu>
  );
}

function RemoveButton({ onClick }: { onClick: () => void }) {
  const { t } = useTranslation();
  return (
    <Tooltip label={t('criteria.remove')}>
      <ActionIcon variant="subtle" color="red" aria-label={t('criteria.remove')} onClick={onClick}>
        <IconTrash size={16} />
      </ActionIcon>
    </Tooltip>
  );
}

function NodeEditor({ root, node, path, fields, onChange }: EditorProps) {
  const { t } = useTranslation();
  const remove = () => onChange(removeAt(root, path));
  if (isGroup(node)) {
    return (
      <Paper withBorder p="sm" radius="sm" bg="var(--mantine-color-default-hover)">
        <Group justify="space-between" mb="xs">
          <Group gap="xs">
            <SegmentedControl
              size="xs"
              data={[
                { value: 'all', label: t('criteria.all') },
                { value: 'any', label: t('criteria.any') },
              ]}
              value={node.type}
              onChange={() => onChange(toggleGroup(root, path))}
              aria-label={t('criteria.groupKind')}
            />
            <AddConditionMenu fields={fields} onAdd={(child) => onChange(addChild(root, path, child))} />
            <Button
              size="xs"
              variant="subtle"
              onClick={() =>
                onChange(
                  addChild(root, path, { type: node.type === 'all' ? 'any' : 'all', of: [{ type: 'matchAll' }] }),
                )
              }
            >
              {t('criteria.addGroup')}
            </Button>
          </Group>
          <Group gap={4}>
            <NodeMenu root={root} path={path} onChange={onChange} />
            {path.length > 0 && <RemoveButton onClick={remove} />}
          </Group>
        </Group>
        <Stack gap="xs" pl="md" style={{ borderLeft: '2px solid var(--mantine-color-teal-4)' }}>
          {node.of.map((child, index) => (
            <NodeEditor
              key={index}
              root={root}
              node={child}
              path={[...path, index]}
              fields={fields}
              onChange={onChange}
            />
          ))}
        </Stack>
      </Paper>
    );
  }
  if (node.type === 'not') {
    return (
      <Paper withBorder p="sm" radius="sm">
        <Group justify="space-between" mb="xs">
          <Badge color="red" variant="light">
            {t('criteria.not')}
          </Badge>
          <RemoveButton onClick={() => onChange(replaceAt(root, path, node.of))} />
        </Group>
        <NodeEditor root={root} node={node.of} path={[...path, 0]} fields={fields} onChange={onChange} />
      </Paper>
    );
  }
  return (
    <Group justify="space-between" wrap="nowrap" align="center" data-testid="condition">
      <ConditionEditor node={node} fields={fields} onChange={(next) => onChange(replaceAt(root, path, next))} />
      <Group gap={4} wrap="nowrap">
        <NodeMenu root={root} path={path} onChange={onChange} />
        <RemoveButton onClick={remove} />
      </Group>
    </Group>
  );
}

/** Server-side reading of the criterion: canonical summary and specificity, or why it is invalid. */
function ValidationSummary({ value }: { value: Criterion }) {
  const { t } = useTranslation();
  const [debounced] = useDebouncedValue(value, 400);
  const missing = incomplete(debounced);
  const validation = $api.useQuery(
    'post',
    '/api/v1/products/criteria/validate',
    { body: debounced },
    { enabled: missing.length === 0, retry: false },
  );
  if (missing.length > 0) {
    return (
      <Text size="sm" c="dimmed" data-testid="criteria-incomplete">
        {t('criteria.incomplete', { count: missing.length })}
      </Text>
    );
  }
  if (validation.error) {
    return (
      <Alert color="red" p="xs" data-testid="criteria-invalid">
        {asProblemError(validation.error).message}
      </Alert>
    );
  }
  return (
    <Group gap="xs" data-testid="criteria-summary">
      <Text size="sm">{validation.data?.summary ?? '…'}</Text>
      {validation.data && (
        <Badge variant="outline" color="gray">
          {t('criteria.specificity', { value: validation.data.specificity })}
        </Badge>
      )}
    </Group>
  );
}

/** Mounted only while its tab is open (keepMounted false), so it starts from the current value each time. */
function JsonEditor({ value, onChange }: { value: Criterion; onChange: (value: Criterion) => void }) {
  const { t } = useTranslation();
  const [text, setText] = useState(() => JSON.stringify(value, null, 2));
  const [error, setError] = useState<string | null>(null);
  return (
    <Stack gap="xs">
      <Textarea
        aria-label={t('criteria.json')}
        value={text}
        onChange={(event) => setText(event.currentTarget.value)}
        onBlur={() => {
          try {
            onChange(JSON.parse(text) as Criterion);
            setError(null);
          } catch (cause) {
            setError(cause instanceof Error ? cause.message : String(cause));
          }
        }}
        autosize
        minRows={8}
        styles={{ input: { fontFamily: 'var(--mantine-font-family-monospace)' } }}
        error={error}
      />
      <Text size="xs" c="dimmed">
        {t('criteria.jsonHint')}
      </Text>
    </Stack>
  );
}

/** Edits a criterion as a tree of conditions, or as JSON; the server checks and describes it as you go. */
export function CriteriaBuilder({ value, onChange }: { value: Criterion; onChange: (value: Criterion) => void }) {
  const { t } = useTranslation();
  const { fields } = useCatalogue();
  const root: Criterion = value;
  return (
    <Stack gap="sm">
      <Tabs defaultValue="builder" keepMounted={false}>
        <Tabs.List>
          <Tabs.Tab value="builder">{t('criteria.builder')}</Tabs.Tab>
          <Tabs.Tab value="json">{t('criteria.json')}</Tabs.Tab>
        </Tabs.List>
        <Tabs.Panel value="builder" pt="sm">
          {isGroup(root) || root.type === 'not' ? (
            <NodeEditor root={root} node={root} path={[]} fields={fields} onChange={onChange} />
          ) : (
            <Stack gap="xs">
              {root.type !== 'matchAll' && (
                <NodeEditor root={root} node={root} path={[]} fields={fields} onChange={onChange} />
              )}
              <Box>
                <AddConditionMenu
                  fields={fields}
                  onAdd={(child) =>
                    onChange(
                      root.type === 'matchAll' ? { type: 'all', of: [child] } : { type: 'all', of: [root, child] },
                    )
                  }
                />
              </Box>
            </Stack>
          )}
        </Tabs.Panel>
        <Tabs.Panel value="json" pt="sm">
          <JsonEditor value={root} onChange={onChange} />
        </Tabs.Panel>
      </Tabs>
      <ValidationSummary value={root} />
    </Stack>
  );
}
