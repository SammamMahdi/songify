// Serves the latest APK to anyone on the local network.
// Usage: node tools/share-apk.js   (PORT env var overrides 8080)
const http = require('node:http');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');

const PORT = Number(process.env.PORT) || 8080;
const APK = path.resolve(__dirname, '../dist/Songify.apk');

const page = (sizeMb, built) => `<!doctype html>
<html lang="en"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>Songify</title>
<style>
  body { margin: 0; min-height: 100vh; display: grid; place-items: center; background: #0e0e12;
         color: #eee; font-family: system-ui, sans-serif; text-align: center; padding: 16px; box-sizing: border-box; }
  .logo { width: 88px; height: 88px; border-radius: 22px; background: #7c5cff; display: grid;
          place-items: center; font-size: 48px; margin: 0 auto 16px; }
  a.btn { display: inline-block; margin: 24px 0; padding: 16px 40px; border-radius: 999px; background: #9d85ff;
          color: #111; font-weight: 700; font-size: 18px; text-decoration: none; }
  p { color: #aaa; max-width: 340px; margin: 8px auto; line-height: 1.5; }
</style></head><body><div>
  <div class="logo">♪</div>
  <h1>Songify</h1>
  <p>Stream full songs, import your playlists from Spotify and Apple Music, and keep everything on your phone.</p>
  <a class="btn" href="/Songify.apk">Download APK (${sizeMb} MB)</a>
  <p>After downloading, open the file and allow “Install unknown apps” if Android asks. Requires Android 8.0+.</p>
  <p style="font-size:13px">Built ${built}</p>
</div></body></html>`;

http.createServer((req, res) => {
  let stat;
  try {
    stat = fs.statSync(APK); // re-read each time so rebuilds are picked up
  } catch {
    res.writeHead(503, { 'Content-Type': 'text/plain' }).end('APK not built yet. Build it and copy it to dist/Songify.apk.');
    return;
  }
  console.log(`${new Date().toLocaleTimeString()} ${req.socket.remoteAddress} ${req.method} ${req.url}`);

  if (req.url === '/Songify.apk') {
    res.writeHead(200, {
      'Content-Type': 'application/vnd.android.package-archive',
      'Content-Length': stat.size,
      'Content-Disposition': 'attachment; filename="Songify.apk"',
    });
    fs.createReadStream(APK).pipe(res);
  } else if (req.url === '/') {
    const html = page((stat.size / 1048576).toFixed(1), stat.mtime.toLocaleString());
    res.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8' }).end(html);
  } else {
    res.writeHead(404).end();
  }
}).listen(PORT, '0.0.0.0', () => {
  const ips = Object.values(os.networkInterfaces()).flat()
    .filter((n) => n && n.family === 'IPv4' && !n.internal)
    .map((n) => n.address);
  console.log('Songify download page is live:');
  for (const ip of ips) console.log(`  http://${ip}:${PORT}`);
});
