import { createHash } from 'node:crypto';

const escapeHtml = value => String(value).replace(/[&<>"']/gu, character => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[character]);
const style = `:root{color-scheme:light;font-family:system-ui,-apple-system,sans-serif;color:#20243c;background:#f5f6fa}*{box-sizing:border-box}body{margin:0;padding:16px}main{max-width:520px;margin:16px auto;background:white;border-radius:20px;padding:22px;box-shadow:0 8px 32px #20243c0d}h1{font-size:1.55rem;line-height:1.4;margin:12px 0 18px}p,li{line-height:1.65;overflow-wrap:anywhere}ol{padding-left:24px}li{margin:12px 0}.brand{font-weight:700;color:#5355d9}.button{display:block;padding:15px 18px;margin:12px 0;border-radius:12px;background:#5355d9;color:white;text-align:center;font-weight:700;text-decoration:none}.button:focus-visible,summary:focus-visible{outline:3px solid #107968;outline-offset:4px}.secondary{background:#eeedff;color:#5355d9}.notice{background:#f5f6fa;border-radius:12px;padding:14px}.detail{font-size:.875rem;color:#72788e}time{font-weight:600}details{margin:22px 0;border-block:1px solid #e4e6ef}summary{padding:16px 0;min-height:48px;cursor:pointer;font-weight:600}`;
export const invitationPageCsp = `default-src 'none'; style-src 'sha256-${createHash('sha256').update(style).digest('base64')}'; base-uri 'none'; form-action 'none'; frame-ancestors 'none'`;

export function invitationPage({ invite, code, publicBaseUrl, apkAvailable = false, error } = {}) {
  const valid = invite && publicBaseUrl && !error;
  const title = error?.title ?? (valid ? (invite.user_id === null ? '첫 가족 기기 연결하기' : '가족 초대가 도착했어요') : '초대를 열 수 없습니다');
  let content;
  if (valid) {
    const inviteUrl = `${publicBaseUrl}/invite/${encodeURIComponent(code)}`;
    const appUrl = `intent://invite?server=${encodeURIComponent(publicBaseUrl)}&code=${encodeURIComponent(code)}#Intent;scheme=ansimlink;package=org.ansim.link;S.browser_fallback_url=${encodeURIComponent(inviteUrl)};end`;
    const expiresAt = new Date(invite.expires_at).toISOString();
    const expiry = new Intl.DateTimeFormat('ko-KR', { timeZone: 'Asia/Seoul', dateStyle: 'medium', timeStyle: 'medium' }).format(new Date(invite.expires_at));
    const roleLabel = invite.role === 'guardian' ? '보호자' : '피보호자';
    content = `${invite.user_id === null ? '<p>서버 운영자가 발급한 첫 기기 초대입니다. 첫 기기는 보호자 역할로 시작합니다.</p>' : `<p><strong>${escapeHtml(invite.inviter_name)}</strong>님이 가족으로 초대했어요.</p>`}
<p><strong>초대 역할: ${roleLabel}</strong> · <span class="detail">${roleLabel === 'guardian' ? '가족 그룹의 공유 위치를 모두 확인합니다.' : '보호자 위치를 제외한 가족 그룹의 공유 위치를 확인합니다.'}</span></p>
<p class="detail"><time datetime="${expiresAt}">${escapeHtml(expiry)} (한국 시간)</time>까지 · 한 사람만 사용 가능</p>
${apkAvailable ? `<a class="button" href="${escapeHtml(publicBaseUrl)}/downloads/ansim-link.apk" download="ansim-link.apk" rel="noreferrer">1. Android 앱 설치하기</a>` : '<p class="notice">설치 파일이 아직 준비되지 않았습니다. 서버 운영자에게 APK 제공을 요청해 주세요. 이미 앱이 있다면 아래에서 초대를 열 수 있습니다.</p>'}
<a class="button secondary" href="${escapeHtml(appUrl)}" rel="noreferrer">2. 앱에서 초대 열기</a>
<p><strong>설치가 끝나면 이 페이지로 돌아와 두 번째 버튼을 누르거나, 어딧 첫 화면에서 같은 초대 QR을 다시 비추세요.</strong> 초대 코드를 다시 입력할 필요가 없습니다.</p>
<p class="detail">Android 전용입니다. iPhone에서는 설치할 수 없습니다.</p>
<details><summary>설치가 처음이신가요?</summary><ol><li>믿을 수 있는 가족이나 서버 운영자가 보낸 초대인지 확인하고 위에서 APK를 내려받으세요.</li><li>내려받은 파일을 열고 Android의 다운로드·설치 승인 안내를 확인하세요. 필요한 경우 해당 브라우저의 ‘이 출처 허용’을 켜고, 설치 후에는 다시 꺼 주세요. QR만으로 앱이 자동 설치되지는 않습니다.</li><li>이 초대 페이지로 돌아와 ‘앱에서 초대 열기’를 누르거나, 설치한 어딧의 ‘초대 QR 스캔’으로 가족 폰에 받은 같은 QR을 다시 비추세요. 서버 주소와 초대 코드는 자동으로 전달됩니다.</li><li>앱에서 초대한 사람과 서버를 확인하고, 처음 사용하는 기기라면 표시할 이름만 입력한 뒤 직접 참여하세요. 이메일이나 비밀번호는 필요하지 않습니다. 초대가 만료되면 새 초대를 요청하세요.</li></ol><p class="detail">앱이 열리지 않으면 설치 여부를 확인하고 Android의 기본 브라우저에서 이 페이지를 다시 열거나 앱의 QR 스캔을 사용해 주세요.</p></details>
<p class="notice">수락하면 두 가족 그룹이 합쳐지지만 위치 공유가 자동으로 켜지지는 않습니다. 보호자는 그룹의 공유 위치를 모두 볼 수 있고, 피보호자는 보호자 위치와 이동 기록을 볼 수 없습니다.</p>
<p class="detail">연결할 서버: ${escapeHtml(publicBaseUrl)}<br>Tailscale 주소라면 가족 폰도 Tailscale에 연결되어 있어야 합니다.</p>
<p class="detail">개인 초대 링크이므로 공개 게시하지 마세요. 사용되었거나 새 QR이 만들어지면 이전 초대는 무효가 됩니다. 초대 상태는 앱에서 연결하기 전에 다시 확인합니다.</p>`;
  } else {
    content = `<p class="notice">${escapeHtml(error?.message ?? '초대가 없거나 만료되었거나 이미 사용되었습니다. 초대한 가족이나 서버 운영자에게 새 초대를 요청해 주세요.')}</p><p>이 페이지를 여는 것만으로 가족 연결이나 위치 공유가 이루어지지 않습니다.</p><p class="detail">어딧 앱은 현재 Android만 지원합니다. iPhone에서는 설치할 수 없습니다.</p>`;
  }
  return `<!doctype html>
<html lang="ko"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1"><meta name="referrer" content="no-referrer"><meta name="robots" content="noindex,nofollow"><title>${escapeHtml(title)} · 어딧</title><style>${style}</style></head><body><main><div class="brand">어딧</div><h1>${escapeHtml(title)}</h1>${content}</main></body></html>`;
}
