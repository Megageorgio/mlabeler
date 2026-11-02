import {defineConfig} from '@playwright/test';
export default defineConfig({
  testDir: './tests/browser', timeout: 90000, workers: 1, retries: process.env.CI ? 1 : 0,
  use: {baseURL:'http://127.0.0.1:4173/mlabeler/', screenshot:'only-on-failure', trace:'retain-on-failure'},
  projects: [
    {name:'chromium-desktop', use:{browserName:'chromium', viewport:{width:1365,height:900}, launchOptions:{args:['--use-fake-device-for-media-stream','--use-fake-ui-for-media-stream']}}},
    {name:'firefox-desktop', use:{browserName:'firefox', viewport:{width:1365,height:900}}},
    {name:'webkit-desktop', use:{browserName:'webkit', viewport:{width:1365,height:900}}},
    {name:'chromium-phone', use:{browserName:'chromium', viewport:{width:390,height:844}, isMobile:true, hasTouch:true, launchOptions:{args:['--use-fake-device-for-media-stream','--use-fake-ui-for-media-stream']}}},
    {name:'webkit-phone', use:{browserName:'webkit', viewport:{width:390,height:844}, isMobile:true, hasTouch:true}},
    ...(process.env.WEB_EDGE ? [{name:'edge-desktop',use:{browserName:'chromium',channel:'msedge',viewport:{width:1365,height:900}}}] : []),
  ],
  webServer: {command:'node serve.mjs', url:'http://127.0.0.1:4173/', reuseExistingServer:!process.env.CI},
});
