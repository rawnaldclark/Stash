const puppeteer = require('puppeteer');
const path = require('path');
const fs = require('fs');

// Adaptive icon foreground is 108dp. At xxxhdpi (4x), that's 432px.
// The record must fill the FULL 432px so when the launcher masks to
// a circle (~72dp visible = 288px), the record fills it edge-to-edge.
const SIZE = 432;

const html = `<!DOCTYPE html>
<html><head>
<link href="https://fonts.googleapis.com/css2?family=Righteous&display=swap" rel="stylesheet">
<style>
*{margin:0;padding:0;box-sizing:border-box}
body{width:${SIZE}px;height:${SIZE}px;overflow:hidden;background:transparent;display:flex;align-items:center;justify-content:center}
.record{width:${SIZE}px;height:${SIZE}px;border-radius:50%;position:relative;overflow:hidden}
.base{position:absolute;inset:0;border-radius:50%;background:radial-gradient(circle at center,#333 33%,#111 34%,#1a1a1a 100%)}
.rim{position:absolute;inset:0;border-radius:50%;box-shadow:inset 0 0 0 3px rgba(255,255,255,0.06)}
.groove-texture{position:absolute;inset:1.5%;border-radius:50%;background:conic-gradient(from 0deg,rgba(40,40,40,1) 0deg,rgba(50,50,50,1) 15deg,rgba(35,35,35,1) 30deg,rgba(48,48,48,1) 45deg,rgba(38,38,38,1) 60deg,rgba(52,52,52,1) 75deg,rgba(36,36,36,1) 90deg,rgba(46,46,46,1) 105deg,rgba(34,34,34,1) 120deg,rgba(50,50,50,1) 135deg,rgba(38,38,38,1) 150deg,rgba(48,48,48,1) 165deg,rgba(35,35,35,1) 180deg,rgba(52,52,52,1) 195deg,rgba(36,36,36,1) 210deg,rgba(46,46,46,1) 225deg,rgba(34,34,34,1) 240deg,rgba(50,50,50,1) 255deg,rgba(40,40,40,1) 270deg,rgba(48,48,48,1) 285deg,rgba(36,36,36,1) 300deg,rgba(52,52,52,1) 315deg,rgba(38,38,38,1) 330deg,rgba(46,46,46,1) 345deg,rgba(40,40,40,1) 360deg)}
.ring-grooves{position:absolute;inset:0;border-radius:50%;background:repeating-radial-gradient(circle at center,transparent 0px,transparent 3px,rgba(255,255,255,0.04) 3.5px,transparent 4px,transparent 6px,rgba(255,255,255,0.03) 6.5px,transparent 7px)}
.dead-wax{position:absolute;top:50%;left:50%;transform:translate(-50%,-50%);width:43%;height:43%;border-radius:50%;background:radial-gradient(circle,transparent 48%,rgba(255,255,255,0.04) 49%,rgba(255,255,255,0.02) 55%,transparent 56%)}
.label-circle{position:absolute;top:50%;left:50%;transform:translate(-50%,-50%);width:34%;height:34%;border-radius:50%;background:linear-gradient(135deg,#A78BFA,#7C3AED);display:flex;align-items:center;justify-content:center;z-index:2;font-family:'Righteous',sans-serif;font-weight:400;color:#fff;font-size:${Math.round(SIZE*0.13)}px;box-shadow:inset 0 2px 5px rgba(0,0,0,0.3),0 0 0 2px rgba(255,255,255,0.1);letter-spacing:2px}
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
  const page = await browser.newPage();
  await page.setViewport({ width: SIZE, height: SIZE, deviceScaleFactor: 1 });
  await page.setContent(html, { waitUntil: 'networkidle0' });
  await page.evaluate(() => document.fonts.ready);
  await new Promise(r => setTimeout(r, 500));

  // Save as the adaptive icon foreground PNG
  const dir = path.join(__dirname, '..', 'app', 'src', 'main', 'res', 'drawable');
  const el = await page.$('#target');
  await el.screenshot({
    path: path.join(dir, 'ic_launcher_foreground.png'),
    type: 'png',
    omitBackground: true,
  });
  console.log('Adaptive foreground rendered: ' + SIZE + 'px');
  await page.close();
  await browser.close();
  console.log('Done!');
})();
