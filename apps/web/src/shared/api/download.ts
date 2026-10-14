import { authorisedFetch } from './client';

/** The file name from `Content-Disposition`, if the server sent one. */
export function fileNameFrom(disposition: string | null, fallback: string): string {
  if (!disposition) return fallback;
  const encoded = /filename\*=UTF-8''([^;]+)/i.exec(disposition)?.[1];
  if (encoded) return decodeURIComponent(encoded);
  const plain = /filename="?([^";]+)"?/i.exec(disposition)?.[1];
  return plain ?? fallback;
}

/** How long the object URL stays valid after the click. */
export const REVOKE_DELAY_MS = 10_000;

/** Downloads an authenticated resource (CSV, PDF) and saves it under the server's file name. */
export async function download(url: string, fallbackName: string, init?: RequestInit): Promise<Response> {
  const response = await authorisedFetch(url, init);
  const blob = await response.clone().blob();
  const link = document.createElement('a');
  const objectUrl = URL.createObjectURL(blob);
  link.href = objectUrl;
  link.download = fileNameFrom(response.headers.get('Content-Disposition'), fallbackName);
  document.body.appendChild(link);
  link.click();
  link.remove();
  // Revoking at once can cancel the save in some browsers; release the blob after the download has started.
  setTimeout(() => URL.revokeObjectURL(objectUrl), REVOKE_DELAY_MS);
  return response;
}
