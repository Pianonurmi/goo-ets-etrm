import i18n from 'i18next';

/** The tour texts are their own namespace, loaded with the first tour or guide (not part of the first page). */
export async function loadTourTexts(): Promise<void> {
  if (i18n.hasResourceBundle('en', 'tour')) return;
  const texts = (await import('@/locales/tour.en.json')).default;
  i18n.addResourceBundle('en', 'tour', texts, true, true);
}
