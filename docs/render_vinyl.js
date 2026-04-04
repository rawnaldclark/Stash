const puppeteer = require('puppeteer');
const path = require('path');

(async () => {
  const browser = await puppeteer.launch({ headless: 'new' });
  const drawableDir = path.join(__dirname, '..', 'app', 'src', 'main', 'res', 'drawable');

  // 1. Render the checkerboard background (static, no record)
  const bgPage = await browser.newPage();
  await bgPage.setViewport({ width: 512, height: 512, deviceScaleFactor: 1 });
  await bgPage.setContent(`<!DOCTYPE html>
<html><head><style>
*{margin:0;padding:0;box-sizing:border-box}
body{background:transparent}
.icon{width:512px;height:512px;position:relative;overflow:hidden;display:flex;align-items:center;justify-content:center;background:linear-gradient(140deg,#3b0764,#6D28D9)}
.board{position:absolute;inset:0;display:grid;grid-template-columns:repeat(3,1fr);grid-template-rows:repeat(3,1fr);opacity:0.5}
.board div:nth-child(2),.board div:nth-child(4),.board div:nth-child(6),.board div:nth-child(8){background:#A78BFA}
</style></head>
<body><div class="icon" id="target"><div class="board"><div></div><div></div><div></div><div></div><div></div><div></div><div></div><div></div><div></div></div></div></body></html>`, { waitUntil: 'networkidle0' });
  const bgEl = await bgPage.$('#target');
  await bgEl.screenshot({ path: path.join(drawableDir, 'vinyl_bg.png'), type: 'png' });
  console.log('Rendered vinyl_bg.png');
  await bgPage.close();

  // 2. Render just the vinyl record on transparent background
  const recPage = await browser.newPage();
  await recPage.setViewport({ width: 512, height: 512, deviceScaleFactor: 1 });
  await recPage.setContent(`<!DOCTYPE html>
<html><head><style>
*{margin:0;padding:0;box-sizing:border-box}
body{background:transparent;display:flex;align-items:center;justify-content:center;width:512px;height:512px}
.record{width:322px;height:322px;border-radius:50%;position:relative;overflow:hidden;box-shadow:0 4px 20px rgba(0,0,0,0.6),0 0 0 2px rgba(167,139,250,0.12)}
.base{position:absolute;inset:0;border-radius:50%;background:radial-gradient(circle at center,#333 33%,#111 34%,#1a1a1a 100%)}
.rim{position:absolute;inset:0;border-radius:50%;box-shadow:inset 0 0 0 2px rgba(255,255,255,0.06)}
.groove-texture{position:absolute;inset:2%;border-radius:50%;background:conic-gradient(from 0deg,rgba(40,40,40,1) 0deg,rgba(50,50,50,1) 15deg,rgba(35,35,35,1) 30deg,rgba(48,48,48,1) 45deg,rgba(38,38,38,1) 60deg,rgba(52,52,52,1) 75deg,rgba(36,36,36,1) 90deg,rgba(46,46,46,1) 105deg,rgba(34,34,34,1) 120deg,rgba(50,50,50,1) 135deg,rgba(38,38,38,1) 150deg,rgba(48,48,48,1) 165deg,rgba(35,35,35,1) 180deg,rgba(52,52,52,1) 195deg,rgba(36,36,36,1) 210deg,rgba(46,46,46,1) 225deg,rgba(34,34,34,1) 240deg,rgba(50,50,50,1) 255deg,rgba(40,40,40,1) 270deg,rgba(48,48,48,1) 285deg,rgba(36,36,36,1) 300deg,rgba(52,52,52,1) 315deg,rgba(38,38,38,1) 330deg,rgba(46,46,46,1) 345deg,rgba(40,40,40,1) 360deg)}
.ring-grooves{position:absolute;inset:0;border-radius:50%;background:repeating-radial-gradient(circle at center,transparent 0px,transparent 2px,rgba(255,255,255,0.04) 2.5px,transparent 3px,transparent 4.5px,rgba(255,255,255,0.03) 5px,transparent 5.5px)}
.dead-wax{position:absolute;top:50%;left:50%;transform:translate(-50%,-50%);width:43%;height:43%;border-radius:50%;background:radial-gradient(circle,transparent 48%,rgba(255,255,255,0.04) 49%,rgba(255,255,255,0.02) 55%,transparent 56%)}
.label-circle{position:absolute;top:50%;left:50%;transform:translate(-50%,-50%);width:34%;height:34%;border-radius:50%;background:linear-gradient(135deg,#A78BFA,#7C3AED);display:flex;align-items:center;justify-content:center;z-index:2;font-weight:800;color:#fff;font-family:Georgia,serif;font-size:44px;box-shadow:inset 0 1px 2px rgba(0,0,0,0.3),0 0 0 1px rgba(255,255,255,0.1)}
.shine{position:absolute;inset:0;border-radius:50%;background:linear-gradient(150deg,rgba(255,255,255,0.12) 0%,rgba(255,255,255,0.04) 20%,transparent 45%,transparent 70%,rgba(255,255,255,0.02) 100%);z-index:3}
.refraction{position:absolute;inset:9%;border-radius:50%;background:conic-gradient(from 200deg,transparent 0deg,rgba(139,92,246,0.06) 30deg,transparent 60deg,rgba(99,102,241,0.04) 120deg,transparent 150deg,rgba(139,92,246,0.05) 240deg,transparent 280deg);z-index:2}
</style></head>
<body><div class="record" id="target"><div class="base"></div><div class="rim"></div><div class="groove-texture"></div><div class="ring-grooves"></div><div class="dead-wax"></div><div class="refraction"></div><div class="label-circle">S</div><div class="shine"></div></div></body></html>`, { waitUntil: 'networkidle0' });
  const recEl = await recPage.$('#target');
  await recEl.screenshot({ path: path.join(drawableDir, 'vinyl_record.png'), type: 'png', omitBackground: true });
  console.log('Rendered vinyl_record.png');
  await recPage.close();

  await browser.close();
  console.log('Done!');
})();
