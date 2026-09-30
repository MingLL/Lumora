// 服务端只收标准 UUID，所以这里三层兜底都必须产出 UUID 形状，不能像以前
// 那样退化成 `${Date.now()}-${Math.random()}`：
//   1. crypto.randomUUID —— 最好，但要求安全上下文，且相对新（Safari 15.4+）；
//   2. crypto.getRandomValues —— 老得多，微信内置 webview 上更靠得住；
//   3. Math.random —— 最后兜底。随机性不足，但 visitId 只用来把同一次访问的
//      两条事件串起来，不承担安全语义，撞了也只是少关联一条。
const newVisitId = () => {
  const webCrypto = globalThis.crypto;
  if (webCrypto?.randomUUID) return webCrypto.randomUUID();

  const bytes = new Uint8Array(16);
  if (webCrypto?.getRandomValues) {
    webCrypto.getRandomValues(bytes);
  } else {
    for (let i = 0; i < bytes.length; i++) bytes[i] = Math.floor(Math.random() * 256);
  }
  bytes[6] = (bytes[6] & 0x0f) | 0x40; // version 4
  bytes[8] = (bytes[8] & 0x3f) | 0x80; // variant 10xx
  const hex = Array.from(bytes, (b) => b.toString(16).padStart(2, '0')).join('');
  return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}`
    + `-${hex.slice(16, 20)}-${hex.slice(20)}`;
};
const visitId = newVisitId();
export const reportEvent = (type: string, properties: Record<string, unknown>) => {
  const payload = JSON.stringify({ visitId, type, url: location.origin + location.pathname, properties, referrer: document.referrer ? new URL(document.referrer).origin : "" });
  if (navigator.sendBeacon) {
    if (navigator.sendBeacon('/client-events', new Blob([payload], { type: 'application/json' }))) return;
  }
  {
    fetch('/client-events', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: payload,
      keepalive: true
    }).catch(() => {});
  }
};

