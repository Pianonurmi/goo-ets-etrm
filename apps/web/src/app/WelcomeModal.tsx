import { Button, Group, List, Modal, Stack, Text } from '@mantine/core';
import { useQuery } from '@tanstack/react-query';
import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { useNavigate } from 'react-router';
import { markWelcomed, startTour } from '@/shared/tour/store';
import { loadTourTexts } from '@/shared/tour/texts';
import { ProvenaMark } from './brand/Logo';

/** First visit to the showcase: what the demo is, and a way in (orientation tour, demo guide, or on your own). */
export default function WelcomeModal() {
  const { t } = useTranslation('tour');
  const navigate = useNavigate();
  const [open, setOpen] = useState(true);
  const texts = useQuery({
    queryKey: ['tour-texts'],
    queryFn: async () => (await loadTourTexts(), true),
    staleTime: Infinity,
  });
  const close = () => {
    markWelcomed();
    setOpen(false);
  };
  if (!texts.data) return null;
  return (
    <Modal
      opened={open}
      onClose={close}
      size="lg"
      centered
      title={
        <Group gap="sm">
          <ProvenaMark size={26} title="Provena" />
          <Text fw={600}>{t('ui.welcomeTitle')}</Text>
        </Group>
      }
    >
      <Stack data-testid="welcome">
        <Text size="sm">{t('ui.welcomeBody')}</Text>
        <List size="sm" spacing={4}>
          <List.Item>{t('ui.welcomeShared')}</List.Item>
        </List>
        <Group justify="flex-end" gap="xs" mt="sm">
          <Button variant="subtle" color="gray" onClick={close} data-testid="welcome-skip">
            {t('ui.welcomeSkip')}
          </Button>
          <Button
            variant="default"
            onClick={() => {
              close();
              void navigate('/guide');
            }}
            data-testid="welcome-guide"
          >
            {t('ui.welcomeGuide')}
          </Button>
          <Button
            onClick={() => {
              close();
              startTour('orientation');
            }}
            data-testid="welcome-start"
          >
            {t('ui.welcomeStart')}
          </Button>
        </Group>
      </Stack>
    </Modal>
  );
}
