const puppeteer = require('puppeteer');
const fs = require('fs');
const path = require('path');

const sizes = {
  'mdpi': 48,
  'hdpi': 72,
  'xhdpi': 96,
  'xxhdpi': 144,
  'xxxhdpi': 192,
};

const html = `<!DOCTYPE html>
<html><head><style>
*{margin:0;padding:0;box-sizing:border-box}
body{background:transparent}
.icon{position:relative;overflow:hidden;display:flex;align-items:center;justify-content:center;background:linear-gradient(140deg,#3b0764,#6D28D9)}
.icon .board{position:absolute;inset:0;display:grid;grid-template-columns:repeat(3,1fr);grid-template-rows:repeat(3,1fr);opacity:0.5}
.icon .board div:nth-child(2),.icon .board div:nth-child(4),.icon .board div:nth-child(6),.icon .board div:nth-child(8){background:#A78BFA}
.icon .record-wrap{position:relative;z-index:2;margin-top:-3%}
.icon .record{width:63%;height:0;padding-bottom:63%;border-radius:50%;position:relative;overflow:hidden;box-shadow:0 4px 20px rgba(0,0,0,0.6),0 0 0 2px rgba(167,139,250,0.12)}
.icon .record .base{position:absolute;inset:0;border-radius:50%;background:radial-gradient(circle at center,#333 33%,#111 34%,#1a1a1a 100%)}
.icon .record .rim{position:absolute;inset:0;border-radius:50%;box-shadow:inset 0 0 0 2px rgba(255,255,255,0.06)}
.icon .record .groove-texture{position:absolute;inset:2%;border-radius:50%;background:conic-gradient(from 0deg,rgba(40,40,40,1) 0deg,rgba(50,50,50,1) 15deg,rgba(35,35,35,1) 30deg,rgba(48,48,48,1) 45deg,rgba(38,38,38,1) 60deg,rgba(52,52,52,1) 75deg,rgba(36,36,36,1) 90deg,rgba(46,46,46,1) 105deg,rgba(34,34,34,1) 120deg,rgba(50,50,50,1) 135deg,rgba(38,38,38,1) 150deg,rgba(48,48,48,1) 165deg,rgba(35,35,35,1) 180deg,rgba(52,52,52,1) 195deg,rgba(36,36,36,1) 210deg,rgba(46,46,46,1) 225deg,rgba(34,34,34,1) 240deg,rgba(50,50,50,1) 255deg,rgba(40,40,40,1) 270deg,rgba(48,48,48,1) 285deg,rgba(36,36,36,1) 300deg,rgba(52,52,52,1) 315deg,rgba(38,38,38,1) 330deg,rgba(46,46,46,1) 345deg,rgba(40,40,40,1) 360deg)}
.icon .record .ring-grooves{position:absolute;inset:0;border-radius:50%;background:repeating-radial-gradient(circle at center,transparent 0px,transparent 2px,rgba(255,255,255,0.04) 2.5px,transparent 3px,transparent 4.5px,rgba(255,255,255,0.03) 5px,transparent 5.5px)}
.icon .record .dead-wax{position:absolute;top:50%;left:50%;transform:translate(-50%,-50%);width:43%;height:43%;border-radius:50%;background:radial-gradient(circle,transparent 48%,rgba(255,255,255,0.04) 49%,rgba(255,255,255,0.02) 55%,transparent 56%)}
.icon .record .label-circle{position:absolute;top:50%;left:50%;transform:translate(-50%,-50%);width:34%;height:34%;border-radius:50%;background:linear-gradient(135deg,#A78BFA,#7C3AED);display:flex;align-items:center;justify-content:center;z-index:2;font-weight:800;color:#fff;font-family:Georgia,serif;box-shadow:inset 0 1px 2px rgba(0,0,0,0.3),0 0 0 1px rgba(255,255,255,0.1)}
.icon .record .shine{position:absolute;inset:0;border-radius:50%;background:linear-gradient(150deg,rgba(255,255,255,0.12) 0%,rgba(255,255,255,0.04) 20%,transparent 45%,transparent 70%,rgba(255,255,255,0.02) 100%);z-index:3}
.icon .record .refraction{position:absolute;inset:9%;border-radius:50%;background:conic-gradient(from 200deg,transparent 0deg,rgba(139,92,246,0.06) 30deg,transparent 60deg,rgba(99,102,241,0.04) 120deg,transparent 150deg,rgba(139,92,246,0.05) 240deg,transparent 280deg);z-index:2}
</style></head>
<body>
<div class="icon" id="icon" style="width:SIZEpx;height:SIZEpx">
  <div class="board"><div></div><div></div><div></div><div></div><div></div><div></div><div></div><div></div><div></div></div>
  <div class="record-wrap"><div class="record">
    <div class="base"></div><div class="rim"></div><div class="groove-texture"></div>
    <div class="ring-grooves"></div><div class="dead-wax"></div><div class="refraction"></div>
    <div class="label-circle" style="font-size:FONTpx">S</div><div class="shine"></div>
  </div></div>
</div>
</body></html>`;

(async () => {
  const browser = await puppeteer.launch({ headless: 'new' });

  for (const [density, size] of Object.entries(sizes)) {
    const page = await browser.newPage();
    await page.setViewport({ width: size, height: size, deviceScaleFactor: 1 });

    const fontSize = Math.round(size * 0.13);
    const pageHtml = html.replace(/SIZE/g, String(size)).replace(/FONT/g, String(fontSize));
    await page.setContent(pageHtml, { waitUntil: 'networkidle0' });

    const dir = path.join(__dirname, '..', 'app', 'src', 'main', 'res', `mipmap-${density}`);
    fs.mkdirSync(dir, { recursive: true });

    const element = await page.$('#icon');
    await element.screenshot({
      path: path.join(dir, 'ic_launcher.png'),
      type: 'png',
      omitBackground: true,
    });
    // Round version (same image, Android handles clipping)
    await element.screenshot({
      path: path.join(dir, 'ic_launcher_round.png'),
      type: 'png',
      omitBackground: true,
    });

    console.log(`Rendered ${density}: ${size}x${size}px`);
    await page.close();
  }

  await browser.close();
  console.log('Done! All icons rendered.');
})();
