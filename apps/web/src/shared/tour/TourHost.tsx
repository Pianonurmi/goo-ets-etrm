import { Button, FocusTrap, Group, Paper, Portal, Progress, Stack, Text } from '@mantine/core';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { useCallback, useEffect, useRef, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { useLocation, useNavigate } from 'react-router';
import { choosePersona, personas } from '@/shared/auth/personas';
import { useSession } from '@/shared/auth/SessionContext';
import { endTour, goToStep, type ActiveTour } from './store';
import { loadTourTexts } from './texts';
import { needsNavigation, placeCard, tourById, type Rect } from './tours';

const CARD_WIDTH = 360;
const FIND_TIMEOUT_MS = 4000;

/** Finds the step's element, waiting for the screen to render it; null when it does not appear in time. */
function useTarget(selector: string | undefined, key: string): Element | null {
  const [found, setFound] = useState<{ key: string; element: Element } | null>(null);
  useEffect(() => {
    if (!selector) return;
    const check = () => {
      const element = document.querySelector(selector);
      if (element) setFound({ key, element });
      return element !== null;
    };
    const frame = window.requestAnimationFrame(check);
    const observer = new MutationObserver(() => {
      if (check()) observer.disconnect();
    });
    observer.observe(document.body, { childList: true, subtree: true });
    const timer = window.setTimeout(() => observer.disconnect(), FIND_TIMEOUT_MS);
    return () => {
      window.cancelAnimationFrame(frame);
      observer.disconnect();
      window.clearTimeout(timer);
    };
  }, [selector, key]);
  return found?.key === key ? found.element : null;
}

/** The element's box in the viewport, following scrolling and resizing. */
function useRect(element: Element | null): Rect | null {
  const [measured, setMeasured] = useState<{ element: Element; rect: Rect } | null>(null);
  useEffect(() => {
    if (!element) return;
    element.scrollIntoView({ block: 'center', behavior: 'auto' });
    const measure = () => {
      const box = element.getBoundingClientRect();
      setMeasured({ element, rect: { top: box.top, left: box.left, width: box.width, height: box.height } });
    };
    // Content loading above the target (a grid filling in) moves it without a scroll event: follow the page's
    // layout, and bring the target back into view when it has been pushed off screen.
    const relayout = () => {
      const box = element.getBoundingClientRect();
      if (box.bottom < 0 || box.top > window.innerHeight) element.scrollIntoView({ block: 'center', behavior: 'auto' });
      measure();
    };
    const frame = window.requestAnimationFrame(measure);
    const resize = new ResizeObserver(relayout);
    resize.observe(element);
    resize.observe(document.body);
    window.addEventListener('scroll', measure, true);
    window.addEventListener('resize', measure);
    return () => {
      window.cancelAnimationFrame(frame);
      resize.disconnect();
      window.removeEventListener('scroll', measure, true);
      window.removeEventListener('resize', measure);
    };
  }, [element]);
  return measured && measured.element === element ? measured.rect : null;
}

/** The card's height, for placing it, kept current by a resize observer. */
function useHeight(fallback: number): [number, (node: HTMLDivElement | null) => void] {
  const [height, setHeight] = useState(fallback);
  const observer = useRef<ResizeObserver | null>(null);
  const ref = useCallback((node: HTMLDivElement | null) => {
    observer.current?.disconnect();
    if (!node) return;
    observer.current = new ResizeObserver(() => setHeight(node.offsetHeight));
    observer.current.observe(node);
  }, []);
  return [height, ref];
}

/**
 * Shows the running tour's current step: navigates to its screen, spotlights its element and explains it in a card
 * (a dialog: arrow keys move, Escape ends). The page stays usable underneath, so a step can be tried out.
 */
export default function TourHost({ active }: { active: ActiveTour }) {
  const { t } = useTranslation('tour');
  const texts = useQuery({
    queryKey: ['tour-texts'],
    queryFn: async () => (await loadTourTexts(), true),
    staleTime: Infinity,
  });
  const navigate = useNavigate();
  const location = useLocation();
  const queryClient = useQueryClient();
  const { me } = useSession();
  const tour = tourById(active.tour);
  const step = tour?.steps[active.step];
  const [cardHeight, cardRef] = useHeight(220);
  const element = useTarget(step?.target, `${active.tour}:${active.step}`);
  const rect = useRect(element);

  // A tour that no longer exists (or a step past its end) is ended.
  useEffect(() => {
    if (!tour || !step) endTour(false);
  }, [tour, step]);

  useEffect(() => {
    if (step && needsNavigation(step, location.pathname, location.search)) void navigate(step.path ?? '/');
  }, [step, location.pathname, location.search, navigate]);

  const total = tour?.steps.length ?? 0;
  const next = useCallback(() => {
    if (active.step + 1 >= total) endTour(true);
    else goToStep(active.step + 1);
  }, [active.step, total]);
  const back = useCallback(() => {
    if (active.step > 0) goToStep(active.step - 1);
  }, [active.step]);

  useEffect(() => {
    const onKey = (event: KeyboardEvent) => {
      if (event.key === 'Escape') endTour(false);
      else if (event.key === 'ArrowRight') next();
      else if (event.key === 'ArrowLeft') back();
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [next, back]);

  if (!tour || !step || !texts.data) return null;

  const persona = step.persona ? personas().find((candidate) => candidate.id === step.persona) : undefined;
  const wrongPersona = persona !== undefined && persona.id !== me.userId;
  const position = placeCard(
    rect,
    { width: CARD_WIDTH, height: cardHeight },
    { width: window.innerWidth, height: window.innerHeight },
  );
  const pad = 6;
  return (
    <Portal>
      {rect ? (
        <>
          {/* The page dimmed around the target in four bands, and a ring on the target itself. */}
          <div
            aria-hidden
            className="etrm-tour-veil"
            style={{ top: 0, left: 0, right: 0, height: Math.max(0, rect.top - pad) }}
          />
          <div
            aria-hidden
            className="etrm-tour-veil"
            style={{ top: rect.top + rect.height + pad, left: 0, right: 0, bottom: 0 }}
          />
          <div
            aria-hidden
            className="etrm-tour-veil"
            style={{ top: rect.top - pad, left: 0, width: Math.max(0, rect.left - pad), height: rect.height + 2 * pad }}
          />
          <div
            aria-hidden
            className="etrm-tour-veil"
            style={{ top: rect.top - pad, left: rect.left + rect.width + pad, right: 0, height: rect.height + 2 * pad }}
          />
          <div
            aria-hidden
            className="etrm-tour-spotlight"
            style={{
              top: rect.top - pad,
              left: rect.left - pad,
              width: rect.width + 2 * pad,
              height: rect.height + 2 * pad,
            }}
          />
        </>
      ) : (
        <div aria-hidden className="etrm-tour-veil" style={{ inset: 0 }} />
      )}
      <FocusTrap active>
        <Paper
          ref={cardRef}
          role="dialog"
          aria-modal="false"
          aria-labelledby="etrm-tour-title"
          aria-describedby="etrm-tour-body"
          shadow="lg"
          radius="md"
          withBorder
          p="md"
          className="etrm-tour-card"
          style={{ top: position.top, left: position.left, width: CARD_WIDTH }}
          data-testid="tour-card"
          data-anchor={step.target ? (rect ? 'found' : 'missing') : 'none'}
        >
          <Stack gap="xs">
            <Group justify="space-between" align="center">
              <Text size="xs" c="dimmed" fw={500}>
                {t(`${tour.id}.title`)}
              </Text>
              <Text size="xs" c="dimmed" data-testid="tour-progress">
                {t('ui.progress', { current: active.step + 1, total })}
              </Text>
            </Group>
            <Progress value={((active.step + 1) / total) * 100} size="xs" aria-hidden />
            <Text fw={600} id="etrm-tour-title" data-autofocus tabIndex={-1}>
              {t(`${tour.id}.steps.${step.id}.title`)}
            </Text>
            <Text size="sm" id="etrm-tour-body">
              {t(`${tour.id}.steps.${step.id}.body`)}
            </Text>
            {step.target && !rect && (
              <Text size="xs" c="dimmed">
                {t('ui.notFound')}
              </Text>
            )}
            {wrongPersona && persona && (
              <Paper withBorder p="xs" radius="sm" bg="var(--mantine-primary-color-light)">
                <Group justify="space-between" gap="xs">
                  <Text size="xs">{t('ui.switchTo', { name: persona.name })}</Text>
                  <Button
                    size="compact-xs"
                    onClick={() => {
                      choosePersona(persona.id);
                      void queryClient.resetQueries();
                    }}
                    data-testid="tour-switch-persona"
                  >
                    {t('ui.switchButton', { name: persona.name })}
                  </Button>
                </Group>
              </Paper>
            )}
            <Group justify="space-between" mt={4}>
              <Button variant="subtle" color="gray" size="xs" onClick={() => endTour(false)} data-testid="tour-end">
                {t('ui.skip')}
              </Button>
              <Group gap="xs">
                {active.step > 0 && (
                  <Button variant="default" size="xs" onClick={back} data-testid="tour-back">
                    {t('ui.back')}
                  </Button>
                )}
                <Button size="xs" onClick={next} data-testid="tour-next">
                  {active.step + 1 >= total ? t('ui.finish') : t('ui.next')}
                </Button>
              </Group>
            </Group>
          </Stack>
        </Paper>
      </FocusTrap>
    </Portal>
  );
}
