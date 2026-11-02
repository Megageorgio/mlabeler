import {defineConfig} from '@playwright/test';
export default defineConfig({
  testDir: './tests/browser', timeout: 90000, workers: 1, retries: process.env.CI ? 1 : 0,
  globalTimeout: process.env.CI ? 15 * 60 * 1000 : undefined,
  use: {baseURL:'http://127.0.0.1:4173/mlabeler/', screenshot:'only-on-failure', trace:'retain-on-failure'},
  projects: [
    {name:'chromium-desktop', use:{browserName:'chromium', viewport:{width:1365,height:900}, launchOptions:{args:['--use-fake-device-for-media-stream','--use-fake-ui-for-media-stream']}}},
    // Linux headless Firefox can deny WebGL2; CI supplies Xvfb for a real windowed context.
    {name:'firefox-desktop', use:{browserName:'firefox', headless:!(process.env.CI && process.platform === 'linux'), viewport:{width:1365,height:900}}},
    {name:'webkit-desktop', use:{browserName:'webkit', viewport:{width:1365,height:900}}},
    {name:'chromium-phone', use:{browserName:'chromium', viewport:{width:390,height:844}, isMobile:true, hasTouch:true, launchOptions:{args:['--use-fake-device-for-media-stream','--use-fake-ui-for-media-stream']}}},
    {name:'webkit-phone', use:{browserName:'webkit', viewport:{width:390,height:844}, isMobile:true, hasTouch:true}},
    ...(process.env.WEB_EDGE ? [{name:'edge-desktop',use:{browserName:'chromium',channel:'msedge',viewport:{width:1365,height:900}}}] : []),
  ],
  webServer: {command:'node serve.mjs', url:'http://127.0.0.1:4173/', reuseExistingServer:!process.env.CI},
});
