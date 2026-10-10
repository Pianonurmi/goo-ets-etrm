import { authorisedFetch } from './client';

/** The file name from `Content-Disposition`, if the server sent one. */
export function fileNameFrom(disposition: string | null, fallback: string): string {
  if (!disposition) return fallback;
  const encoded = /filename\*=UTF-8''([^;]+)/i.exec(disposition)?.[1];
  if (encoded) return decodeURIComponent(encoded);
  const plain = /filename="?([^";]+)"?/i.exec(disposition)?.[1];
  return plain ?? fallback;
}

/** Downloads an authenticated resource (CSV, PDF) and saves it under the server's file name. */
export async function download(url: string, fallbackName: string, init?: RequestInit): Promise<Response> {
  const response = await authorisedFetch(url, init);
  const blob = await response.clone().blob();
  const link = document.createElement('a');
  link.href = URL.createObjectURL(blob);
  link.download = fileNameFrom(response.headers.get('Content-Disposition'), fallbackName);
  document.body.appendChild(link);
  link.click();
  link.remove();
  URL.revokeObjectURL(link.href);
  return response;
}
