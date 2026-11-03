// Images for an agent: shrink big photos in the browser, then upload them.
// The server hands each to hc, which keeps it beside the agent's terminal and
// returns its path; the reply carries the path (Claude Code and omp read it).

const MAX_SIDE = 2000;                 // longest side after shrinking
const MAX_BYTES = 3.5 * 1024 * 1024;   // Claude refuses images over ~5 MB
const KEEP = new Set(['image/png', 'image/jpeg', 'image/gif', 'image/webp']);

// The image as it will be uploaded: as is when small and of a known type,
// else redrawn as a JPEG at most MAX_SIDE on its longest side.
export async function prepare(file: Blob): Promise<Blob> {
  if (KEEP.has(file.type) && file.size <= MAX_BYTES && file.type !== 'image/jpeg') return file;
  const bmp = await createImageBitmap(file, { imageOrientation: 'from-image' });
  const scale = Math.min(1, MAX_SIDE / Math.max(bmp.width, bmp.height));
  if (scale === 1 && KEEP.has(file.type) && file.size <= MAX_BYTES) { bmp.close(); return file; }
  const canvas = document.createElement('canvas');
  canvas.width = Math.round(bmp.width * scale); canvas.height = Math.round(bmp.height * scale);
  canvas.getContext('2d')!.drawImage(bmp, 0, 0, canvas.width, canvas.height);
  bmp.close();
  return new Promise((resolve, reject) => canvas.toBlob((b) => (b ? resolve(b) : reject(new Error('could not encode the image'))), 'image/jpeg', 0.85));
}

export async function uploadImage(addr: string, file: Blob): Promise<string> {
  const body = await prepare(file);
  const res = await fetch(`/api/terminals/${addr}/upload`, { method: 'POST', headers: { 'content-type': body.type || 'application/octet-stream' }, body });
  const data = await res.json().catch(() => ({}));
  if (!res.ok || !data.path) throw new Error(data.error || `HTTP ${res.status}`);
  return data.path as string;
}
