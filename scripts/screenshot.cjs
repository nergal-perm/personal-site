const { chromium } = require('playwright');
const path = require('path');

async function run() {
  const browser = await chromium.launch({ headless: true });
  const page = await browser.newPage();
  
  await page.setViewportSize({ width: 1440, height: 1000 });
  
  console.log('Navigating to local site...');
  await page.goto('http://localhost:4321/ru/', { waitUntil: 'networkidle' });
  
  console.log('Forcing light theme...');
  await page.evaluate(() => {
    localStorage.setItem('quiet-theme', 'light');
    document.documentElement.dataset.theme = 'light';
    const toggle = document.getElementById('theme-toggle');
    if (toggle) toggle.setAttribute('aria-pressed', 'false');
  });
  
  await page.waitForTimeout(500);
  
  const screenshotPath = '/Users/eugene/.gemini/antigravity-cli/brain/44e3bd7b-2ac3-497d-8c94-6e8d3c707be9/current_home.png';
  console.log(`Saving screenshot to ${screenshotPath}...`);
  await page.screenshot({ path: screenshotPath, fullPage: false });
  
  await browser.close();
  console.log('Done!');
}

run().catch(err => {
  console.error(err);
  process.exit(1);
});
