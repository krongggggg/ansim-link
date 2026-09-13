#!/usr/bin/env node
import { createApp } from './app.mjs';

process.umask(0o077);
const host = process.env.HOST ?? '127.0.0.1';
const rawPort = process.env.PORT ?? '8080';
const port = Number(rawPort);
if (!/^\d+$/u.test(rawPort) || !Number.isInteger(port) || port < 1 || port > 65535) {
  console.error('PORT는 1~65535 범위의 정수여야 합니다.');
  process.exitCode = 1;
} else if (process.env.TRUST_PROXY !== undefined && !['true', 'false'].includes(process.env.TRUST_PROXY)) {
  console.error('TRUST_PROXY는 true 또는 false여야 합니다.');
  process.exitCode = 1;
} else if (process.env.ALLOW_INSECURE_INVITES !== undefined && !['true', 'false'].includes(process.env.ALLOW_INSECURE_INVITES)) {
  console.error('ALLOW_INSECURE_INVITES는 true 또는 false여야 합니다.');
  process.exitCode = 1;
} else {
  try {
    const app = createApp({
      dataDir: process.env.DATA_DIR ?? './data',
      publicBaseUrl: process.env.PUBLIC_BASE_URL,
      androidApkPath: process.env.ANDROID_APK_PATH,
      allowInsecureInvites: process.env.ALLOW_INSECURE_INVITES === 'true'
    });
    let stopping = false;
    const stop = async () => {
      if (stopping) return;
      stopping = true;
      try { await app.close(); }
      catch { console.error('서버 종료 중 저장소 오류가 발생했습니다.'); process.exitCode = 1; }
    };
    process.once('SIGTERM', stop);
    process.once('SIGINT', stop);
    app.server.once('error', async () => {
      console.error('서버를 열 수 없습니다. 주소·포트·저장소 권한을 확인해 주세요.');
      process.exitCode = 1;
      await stop();
    });
    app.server.listen(port, host, () => {
      console.log(`안심연결 서버 준비 완료: ${host}:${port}`);
      console.log('외부 서비스는 HTTPS 역방향 프록시 뒤에서 운영하세요. 긴급기관 자동 신고 기능은 없습니다.');
    });
  } catch {
    console.error('서버 초기화에 실패했습니다. Node.js 22.13 이상, 저장소 권한, PUBLIC_BASE_URL 및 ANDROID_APK_PATH 설정을 확인해 주세요.');
    process.exitCode = 1;
  }
}
