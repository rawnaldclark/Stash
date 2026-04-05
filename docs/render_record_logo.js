const puppeteer = require('puppeteer');
const path = require('path');
const fs = require('fs');

const sizes = { 'mdpi': 48, 'hdpi': 72, 'xhdpi': 96, 'xxhdpi': 144, 'xxxhdpi': 192 };

const makeHtml = (size) => `<!DOCTYPE html>
<html><head>
<link href="https://fonts.googleapis.com/css2?family=Righteous&display=swap" rel="stylesheet">
<style>
*{margin:0;padding:0;box-sizing:border-box}
body{background:transparent;width:${size}px;height:${size}px;overflow:hidden;display:flex;align-items:center;justify-content:center}
.record{width:${size}px;height:${size}px;border-radius:50%;position:relative;overflow:hidden}
.base{position:absolute;inset:0;border-radius:50%;background:radial-gradient(circle at center,#333 33%,#111 34%,#1a1a1a 100%)}
.rim{position:absolute;inset:0;border-radius:50%;box-shadow:inset 0 0 0 ${Math.max(1,Math.round(size*0.015))}px rgba(255,255,255,0.06)}
.groove-texture{position:absolute;inset:1.5%;border-radius:50%;background:conic-gradient(from 0deg,rgba(40,40,40,1) 0deg,rgba(50,50,50,1) 15deg,rgba(35,35,35,1) 30deg,rgba(48,48,48,1) 45deg,rgba(38,38,38,1) 60deg,rgba(52,52,52,1) 75deg,rgba(36,36,36,1) 90deg,rgba(46,46,46,1) 105deg,rgba(34,34,34,1) 120deg,rgba(50,50,50,1) 135deg,rgba(38,38,38,1) 150deg,rgba(48,48,48,1) 165deg,rgba(35,35,35,1) 180deg,rgba(52,52,52,1) 195deg,rgba(36,36,36,1) 210deg,rgba(46,46,46,1) 225deg,rgba(34,34,34,1) 240deg,rgba(50,50,50,1) 255deg,rgba(40,40,40,1) 270deg,rgba(48,48,48,1) 285deg,rgba(36,36,36,1) 300deg,rgba(52,52,52,1) 315deg,rgba(38,38,38,1) 330deg,rgba(46,46,46,1) 345deg,rgba(40,40,40,1) 360deg)}
.ring-grooves{position:absolute;inset:0;border-radius:50%;background:repeating-radial-gradient(circle at center,transparent 0px,transparent 2px,rgba(255,255,255,0.04) 2.5px,transparent 3px,transparent 4.5px,rgba(255,255,255,0.03) 5px,transparent 5.5px)}
.dead-wax{position:absolute;top:50%;left:50%;transform:translate(-50%,-50%);width:43%;height:43%;border-radius:50%;background:radial-gradient(circle,transparent 48%,rgba(255,255,255,0.04) 49%,rgba(255,255,255,0.02) 55%,transparent 56%)}
.label-circle{position:absolute;top:50%;left:50%;transform:translate(-50%,-50%);width:34%;height:34%;border-radius:50%;background:linear-gradient(135deg,#A78BFA,#7C3AED);display:flex;align-items:center;justify-content:center;z-index:2;font-family:'Righteous',sans-serif;font-weight:400;color:#fff;font-size:${Math.round(size*0.15)}px;box-shadow:inset 0 1px 3px rgba(0,0,0,0.3),0 0 0 1px rgba(255,255,255,0.1);letter-spacing:1px}
.shine{position:absolute;inset:0;border-radius:50%;background:linear-gradient(150deg,rgba(255,255,255,0.12) 0%,rgba(255,255,255,0.04) 20%,transparent 45%,transparent 70%,rgba(255,255,255,0.02) 100%);z-index:3}
.refraction{position:absolute;inset:9%;border-radius:50%;background:conic-gradient(from 200deg,transparent 0deg,rgba(139,92,246,0.06) 30deg,transparent 60deg,rgba(99,102,241,0.04) 120deg,transparent 150deg,rgba(139,92,246,0.05) 240deg,transparent 280deg);z-index:2}
</style></head>
<body>
<div class="record" id="target">
  <div class="base"></div><div class="rim"></div><div class="groove-texture"></div>
  <div class="ring-grooves"></div><div class="dead-wax"></div><div class="refraction"></div>
  <div class="label-circle">S</div><div class="shine"></div>
</div>
</body></html>`;

(async () => {
  const browser = await puppeteer.launch({ headless: 'new' });

  // Render app icons at all densities
  for (const [density, size] of Object.entries(sizes)) {
    const page = await browser.newPage();
    await page.setViewport({ width: size, height: size, deviceScaleFactor: 1 });
    await page.setContent(makeHtml(size), { waitUntil: 'networkidle0' });
    // Wait for font to load
    await page.evaluate(() => document.fonts.ready);
    await new Promise(r => setTimeout(r, 500));

    const dir = path.join(__dirname, '..', 'app', 'src', 'main', 'res', 'mipmap-' + density);
    fs.mkdirSync(dir, { recursive: true });

    const el = await page.$('#target');
    await el.screenshot({ path: path.join(dir, 'ic_launcher.png'), type: 'png', omitBackground: true });
    await el.screenshot({ path: path.join(dir, 'ic_launcher_round.png'), type: 'png', omitBackground: true });
    console.log('App icon ' + density + ': ' + size + 'px');
    await page.close();
  }

  // Render in-app logo (larger, for the drawable used by StashVinylLogo)
  const logoPage = await browser.newPage();
  const logoSize = 512;
  await logoPage.setViewport({ width: logoSize, height: logoSize, deviceScaleFactor: 1 });
  await logoPage.setContent(makeHtml(logoSize), { waitUntil: 'networkidle0' });
  await logoPage.evaluate(() => document.fonts.ready);
  await new Promise(r => setTimeout(r, 500));

  const drawableDir = path.join(__dirname, '..', 'feature', 'home', 'src', 'main', 'res', 'drawable');
  fs.mkdirSync(drawableDir, { recursive: true });

  const logoEl = await logoPage.$('#target');
  await logoEl.screenshot({ path: path.join(drawableDir, 'vinyl_record.png'), type: 'png', omitBackground: true });
  console.log('In-app logo: 512px');
  await logoPage.close();

  await browser.close();
  console.log('Done!');
})();
