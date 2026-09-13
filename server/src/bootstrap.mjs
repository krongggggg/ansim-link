#!/usr/bin/env node
import { createApp } from './app.mjs';

process.umask(0o077);
let app;
try {
  if (process.env.TRUST_PROXY !== undefined && !['true', 'false'].includes(process.env.TRUST_PROXY)) {
    throw new Error('TRUST_PROXY는 true 또는 false여야 합니다.');
  }
  if (process.env.ALLOW_INSECURE_INVITES !== undefined && !['true', 'false'].includes(process.env.ALLOW_INSECURE_INVITES)) {
    throw new Error('ALLOW_INSECURE_INVITES는 true 또는 false여야 합니다.');
  }
  if (!process.env.PUBLIC_BASE_URL) throw new Error('PUBLIC_BASE_URL을 가족이 접속할 HTTPS 원점으로 설정해 주세요.');
  app = createApp({
    dataDir: process.env.DATA_DIR ?? './data',
    publicBaseUrl: process.env.PUBLIC_BASE_URL,
    androidApkPath: process.env.ANDROID_APK_PATH,
    allowInsecureInvites: process.env.ALLOW_INSECURE_INVITES === 'true'
  });
  const invite = app.createSetupInvite();
  console.log(invite.inviteUrl);
  console.log(`첫 기기 설정 초대입니다. 한 번만 사용할 수 있으며 ${invite.expiresAt}에 만료됩니다.`);
} catch (error) {
  console.error(`첫 기기 설정 초대를 만들지 못했습니다: ${error.message}`);
  process.exitCode = 1;
} finally {
  if (app) {
    try { await app.close(); }
    catch { console.error('저장소를 닫는 중 오류가 발생했습니다.'); process.exitCode = 1; }
  }
}
