#!/usr/bin/env node
// Generates the PWA icons in web/public/icons/ with no dependencies: a tiny
// rasteriser (4x4 supersampling) and a PNG writer (node:zlib + CRC32).
//
//   node scripts/icons.js

import fs from 'node:fs';
import path from 'node:path';
import zlib from 'node:zlib';
import { fileURLToPath } from 'node:url';

const OUT = path.join(path.dirname(fileURLToPath(import.meta.url)), '..', 'web', 'public', 'icons');
const BG = [0x13, 0x14, 0x17];
const TEAL = [0x3f, 0xbf, 0xb0];
const SS = 4; // supersamples per axis

// ------------------------------------------------------------------- PNG

const CRC_TABLE = new Uint32Array(256).map((_, n) => {
  let c = n;
  for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1;
  return c >>> 0;
});

function crc32(buf) {
  let c = 0xffffffff;
  for (const b of buf) c = CRC_TABLE[(c ^ b) & 0xff] ^ (c >>> 8);
  return (c ^ 0xffffffff) >>> 0;
}

function chunk(type, data) {
  const len = Buffer.alloc(4);
  len.writeUInt32BE(data.length);
  const body = Buffer.concat([Buffer.from(type, 'ascii'), data]);
  const crc = Buffer.alloc(4);
  crc.writeUInt32BE(crc32(body));
  return Buffer.concat([len, body, crc]);
}

// Writes RGBA 8-bit, or RGB 8-bit when every pixel is opaque (no alpha channel at all).
export function encodePng(width, height, rgba) {
  let opaque = true;
  for (let i = 3; i < rgba.length; i += 4) if (rgba[i] !== 255) { opaque = false; break; }
  const bpp = opaque ? 3 : 4;
  const ihdr = Buffer.alloc(13);
  ihdr.writeUInt32BE(width, 0);
  ihdr.writeUInt32BE(height, 4);
  ihdr[8] = 8; // bit depth
  ihdr[9] = opaque ? 2 : 6; // colour type RGB / RGBA
  const stride = width * bpp + 1;
  const raw = Buffer.alloc(stride * height);
  for (let y = 0; y < height; y++) {
    raw[y * stride] = 0; // filter: none
    for (let x = 0; x < width; x++) {
      for (let k = 0; k < bpp; k++) raw[y * stride + 1 + x * bpp + k] = rgba[(y * width + x) * 4 + k];
    }
  }
  return Buffer.concat([
    Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]),
    chunk('IHDR', ihdr),
    chunk('IDAT', zlib.deflateSync(raw, { level: 9 })),
    chunk('IEND', Buffer.alloc(0)),
  ]);
}

// ------------------------------------------------------------- geometry

// Inside test for a rounded square centred at (cx, cy) with half-side h, corner radius r.
function inRoundRect(x, y, cx, cy, h, r) {
  const dx = Math.max(Math.abs(x - cx) - (h - r), 0);
  const dy = Math.max(Math.abs(y - cy) - (h - r), 0);
  return Math.abs(x - cx) <= h && Math.abs(y - cy) <= h && dx * dx + dy * dy <= r * r;
}

const inCircle = (x, y, cx, cy, r) => (x - cx) ** 2 + (y - cy) ** 2 <= r * r;

// bgRadius: corner radius of the background as a fraction of size (null = full opaque square).
// markFrac: side of the teal mark as a fraction of size.
function render(size, { bgRadius, markFrac }) {
  const c = size / 2;
  const mh = (size * markFrac) / 2; // mark half-side
  const s = mh * 2;
  const mr = s * 0.22;
  const dotR = s * 0.085;
  const dots = [
    [c, c - s * 0.15],
    [c - s * 0.17, c + s * 0.12],
    [c + s * 0.17, c + s * 0.12],
  ];
  const px = Buffer.alloc(size * size * 4);
  for (let y = 0; y < size; y++) {
    for (let x = 0; x < size; x++) {
      let r = 0, g = 0, b = 0, a = 0;
      for (let sy = 0; sy < SS; sy++) {
        for (let sx = 0; sx < SS; sx++) {
          const fx = x + (sx + 0.5) / SS;
          const fy = y + (sy + 0.5) / SS;
          if (bgRadius != null && !inRoundRect(fx, fy, c, c, c, size * bgRadius)) continue;
          let col = BG;
          if (inRoundRect(fx, fy, c, c, mh, mr) && !dots.some(([dx, dy]) => inCircle(fx, fy, dx, dy, dotR))) col = TEAL;
          r += col[0]; g += col[1]; b += col[2]; a += 1;
        }
      }
      const i = (y * size + x) * 4;
      if (a) {
        // colours are averaged over covered samples only (straight alpha)
        px[i] = Math.round(r / a);
        px[i + 1] = Math.round(g / a);
        px[i + 2] = Math.round(b / a);
      }
      px[i + 3] = Math.round((a / (SS * SS)) * 255);
    }
  }
  return encodePng(size, size, px);
}

// The maskable safe zone is a centred circle of diameter 80%. The mark's farthest
// point (a corner arc) sits at ~0.616 x side from the centre, so side 0.56 keeps
// it at 0.345 x size, inside the 0.4 radius with margin.
const ICONS = [
  ['icon-192.png', 192, { bgRadius: 0.18, markFrac: 0.62 }],
  ['icon-512.png', 512, { bgRadius: 0.18, markFrac: 0.62 }],
  ['icon-maskable-512.png', 512, { bgRadius: null, markFrac: 0.56 }],
  ['apple-touch-icon.png', 180, { bgRadius: null, markFrac: 0.62 }],
];

if (process.argv[1] === fileURLToPath(import.meta.url)) {
  fs.mkdirSync(OUT, { recursive: true });
  for (const [name, size, opts] of ICONS) {
    const png = render(size, opts);
    fs.writeFileSync(path.join(OUT, name), png);
    console.log(`${name}  ${size}x${size}  ${png.length} bytes`);
  }
}
