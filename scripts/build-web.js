const fs = require('node:fs/promises');
const path = require('node:path');
const sharp = require('sharp');

const root = path.resolve(__dirname, '..');
const output = path.join(root, 'public');
const staticFiles = [
  '_headers',
  '404.html',
  'CREDITS.md',
  'PRIVACY.html',
  'index.html',
  'manifest.webmanifest',
  'offline.html',
  'robots.txt',
  'sw.js',
  'runtime-config.js'
];

function configuredSiteUrl() {
  const value = process.env.MYLO_SITE_URL
    || process.env.VERCEL_URL
    || process.env.VERCEL_PROJECT_PRODUCTION_URL
    || process.env.URL;
  if (!value) return '';

  const url = new URL(value.includes('://') ? value : `https://${value}`);
  if (url.protocol !== 'https:') {
    throw new Error('MYLO_SITE_URL must use HTTPS.');
  }
  return url.origin;
}

function publicConfig() {
  const url = process.env.MYLO_SUPABASE_URL || '';
  const anonKey = process.env.MYLO_SUPABASE_ANON_KEY || '';
  if (Boolean(url) !== Boolean(anonKey)) {
    throw new Error('Set both MYLO_SUPABASE_URL and MYLO_SUPABASE_ANON_KEY, or leave both unset.');
  }
  if (url && new URL(url).protocol !== 'https:') {
    throw new Error('MYLO_SUPABASE_URL must use HTTPS.');
  }

  return {
    SUPABASE_URL: url,
    SUPABASE_ANON_KEY: anonKey,
    YOUTUBE_API_KEY: process.env.MYLO_YOUTUBE_API_KEY || ''
  };
}

async function copyStaticFiles() {
  await fs.rm(output, { recursive: true, force: true });
  await fs.mkdir(output, { recursive: true });
  await Promise.all(staticFiles.map(file => fs.copyFile(path.join(root, file), path.join(output, file))));
  await fs.cp(path.join(root, 'assets'), path.join(output, 'assets'), { recursive: true });
}

async function makeIcons() {
  const source = path.join(root, 'assets/icons/logo-mark.svg');
  const icons = path.join(output, 'assets/icons');
  await Promise.all([
    ['icon-192.png', 192],
    ['icon-512.png', 512],
    ['icon-maskable-512.png', 512]
  ].map(([name, size]) =>
    sharp(source).resize(size, size, { fit: 'contain' }).png().toFile(path.join(icons, name))
  ));
}

async function writeRuntimeConfig() {
  const config = JSON.stringify(publicConfig()).replace(/</g, '\\u003c');
  await fs.writeFile(
    path.join(output, 'runtime-config.js'),
    `window.MYLO_CONFIG = Object.freeze(${config});\n`
  );
}

async function writeSiteMetadata(siteUrl) {
  const robotsPath = path.join(output, 'robots.txt');
  let robots = await fs.readFile(robotsPath, 'utf8');
  robots = robots.replace(/^Sitemap:.*\r?\n?/m, '');

  const sitemapPath = path.join(output, 'sitemap.xml');
  if (siteUrl) {
    robots += `\nSitemap: ${siteUrl}/sitemap.xml\n`;
    const sitemap = `<?xml version="1.0" encoding="UTF-8"?>
<urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9">
  <url><loc>${siteUrl}/</loc><changefreq>weekly</changefreq><priority>1.0</priority></url>
  <url><loc>${siteUrl}/PRIVACY.html</loc><changefreq>monthly</changefreq><priority>0.3</priority></url>
</urlset>
`;
    await fs.writeFile(sitemapPath, sitemap);
    const indexPath = path.join(output, 'index.html');
    const index = await fs.readFile(indexPath, 'utf8');
    await fs.writeFile(indexPath, index.replaceAll('content="assets/icons/icon-512.png"', `content="${siteUrl}/assets/icons/icon-512.png"`));
  } else {
    await fs.rm(sitemapPath, { force: true });
  }
  await fs.writeFile(robotsPath, robots);
}

async function main() {
  await copyStaticFiles();
  await Promise.all([makeIcons(), writeRuntimeConfig()]);
  await writeSiteMetadata(configuredSiteUrl());
  console.log(`Built MYLO web app in ${path.relative(root, output)}.`);
}

main().catch(error => {
  console.error('Web build failed:', error.message);
  process.exitCode = 1;
});
