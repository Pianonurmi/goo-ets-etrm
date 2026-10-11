import { needsNavigation, placeCard, TOURS, tourById, tourForPath } from './tours';
import tourTexts from '@/locales/tour.en.json';

describe('tours', () => {
  it('has unique ids and a text for every tour and step', () => {
    expect(new Set(TOURS.map((tour) => tour.id)).size).toBe(TOURS.length);
    const texts = tourTexts as unknown as Record<
      string,
      { title: string; steps: Record<string, { title: string; body: string }> }
    >;
    for (const tour of TOURS) {
      expect(texts[tour.id]?.title, tour.id).toBeTruthy();
      for (const step of tour.steps) expect(texts[tour.id]?.steps[step.id]?.body, `${tour.id}.${step.id}`).toBeTruthy();
    }
  });

  it('finds the tour of a screen and whether to navigate', () => {
    expect(tourById('trade')?.personas).toContain('trader');
    expect(tourForPath('/month-end')?.id).toBe('monthEnd');
    expect(tourForPath('/nowhere')).toBeUndefined();
    expect(needsNavigation({ id: 'a', path: '/inventory?tab=search' }, '/inventory', '?tab=lots')).toBe(true);
    expect(needsNavigation({ id: 'a', path: '/inventory?tab=search' }, '/inventory', '?tab=search&x=1')).toBe(false);
    expect(needsNavigation({ id: 'a' }, '/anywhere', '')).toBe(false);
  });

  it('places the card below, above or in the middle, inside the screen', () => {
    const viewport = { width: 1200, height: 800 };
    const card = { width: 300, height: 200 };
    expect(placeCard({ top: 100, left: 500, width: 100, height: 40 }, card, viewport)).toEqual({ top: 152, left: 400 });
    expect(placeCard({ top: 700, left: 500, width: 100, height: 40 }, card, viewport)).toEqual({ top: 488, left: 400 });
    expect(placeCard({ top: 10, left: 0, width: 50, height: 20 }, card, viewport).left).toBe(16);
    expect(placeCard(null, card, viewport)).toEqual({ top: 300, left: 450 });
  });

  it('keeps the card on screen while its target is below or above the screen', () => {
    const viewport = { width: 1280, height: 720 };
    const card = { width: 360, height: 220 };
    expect(placeCard({ top: 1100, left: 300, width: 200, height: 20 }, card, viewport).top).toBe(484);
    expect(placeCard({ top: -400, left: 300, width: 200, height: 20 }, card, viewport).top).toBe(484);
  });
});
