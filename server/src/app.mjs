import { createServer } from 'node:http';
import { DatabaseSync } from 'node:sqlite';
import { createHash, randomBytes, randomUUID } from 'node:crypto';
import { isIP } from 'node:net';
import { chmodSync, closeSync, constants, createReadStream, existsSync, fstatSync, mkdirSync, openSync, readFileSync, readdirSync, rmSync, writeFileSync } from 'node:fs';
import { join, resolve } from 'node:path';
import { pipeline } from 'node:stream/promises';
import { invitationPage, invitationPageCsp } from './invitation-page.mjs';

const MINUTE = 60_000;
const DAY = 24 * 60 * MINUTE;
const RETENTION = 7 * DAY;
const MEDIA_LIMIT = 5 * 1024 * 1024;
const ENCODED_LIMIT = Math.ceil(MEDIA_LIMIT / 3) * 4;
const hash = value => createHash('sha256').update(value).digest('hex');
const iso = value => value == null ? null : new Date(value).toISOString();
const userView = row => ({ id: row.id, name: row.name, role: row.role ?? null, sharing: row.role === 'protected' && Boolean(row.sharing), inactivityMinutes: row.inactivity_minutes });
const locationView = row => row ? ({ latitude: row.latitude, longitude: row.longitude, accuracy: row.accuracy, recordedAt: iso(row.recorded_at) }) : null;
const zoneView = row => ({ id: row.id, name: row.name, latitude: row.latitude, longitude: row.longitude, radius: row.radius });
const journeyView = row => ({ id: row.id, userId: row.user_id, userName: row.user_name, destination: row.destination, latitude: row.latitude, longitude: row.longitude, radius: row.radius, deadline: iso(row.deadline), status: row.status, createdAt: iso(row.created_at) });

class HttpError extends Error {
  constructor(status, message) { super(message); this.status = status; }
}
const fail = (status, message) => { throw new HttpError(status, message); };

function text(value, field, min, max) {
  if (typeof value !== 'string' || value.trim().length < min || value.length > max || /[\u0000-\u0008\u000b\u000c\u000e-\u001f\u007f]/u.test(value)) fail(400, `${field} 입력을 확인해 주세요.`);
  return value.trim();
}
function invitationCode(value) {
  if (typeof value !== 'string' || !/^[A-Za-z0-9_-]{16}$/u.test(value)) fail(400, '초대 코드 형식을 확인해 주세요.');
  return value;
}
function userRole(value, field = '역할') {
  if (value !== 'guardian' && value !== 'protected') fail(400, `${field}을 확인해 주세요.`);
  return value;
}
function number(value, field, min, max) {
  if (typeof value !== 'number' || !Number.isFinite(value) || value < min || value > max) fail(400, `${field} 범위를 확인해 주세요.`);
  return value;
}
function point(body) {
  return { latitude: number(body.latitude, '위도', -90, 90), longitude: number(body.longitude, '경도', -180, 180) };
}
function timestamp(value, field) {
  if (typeof value !== 'string' || value.length > 40 || !/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d{1,9})?(?:Z|[+-]\d{2}:\d{2})$/u.test(value)) fail(400, `${field} 날짜 형식을 확인해 주세요.`);
  const parsed = Date.parse(value);
  if (!Number.isFinite(parsed)) fail(400, `${field} 날짜 형식을 확인해 주세요.`);
  return parsed;
}
function fields(body, allowed) {
  if (Object.keys(body).some(key => !allowed.includes(key))) fail(400, '지원하지 않는 입력 항목입니다.');
}
function distance(a, b) {
  const radians = Math.PI / 180;
  const dLat = (b.latitude - a.latitude) * radians;
  const dLon = (b.longitude - a.longitude) * radians;
  const h = Math.sin(dLat / 2) ** 2 + Math.cos(a.latitude * radians) * Math.cos(b.latitude * radians) * Math.sin(dLon / 2) ** 2;
  return 6_371_000 * 2 * Math.atan2(Math.sqrt(Math.min(1, h)), Math.sqrt(Math.max(0, 1 - h)));
}
function classify(fix, zone) {
  const meters = distance(fix, zone);
  if (meters + fix.accuracy <= zone.radius) return 1;
  if (meters - fix.accuracy > zone.radius) return 0;
  return null;
}

function decodeMedia(body) {
  fields(body, ['mime', 'data']);
  if (body.mime !== 'image/jpeg' && body.mime !== 'audio/mp4') fail(415, 'JPEG 사진 또는 MP4 음성만 첨부할 수 있습니다.');
  if (typeof body.data !== 'string' || body.data.length === 0 || body.data.length > ENCODED_LIMIT || body.data.length % 4 !== 0) fail(400, '첨부 파일 인코딩을 확인해 주세요.');
  const padding = body.data.endsWith('==') ? 2 : body.data.endsWith('=') ? 1 : 0;
  const invalidAt = body.data.search(/[^A-Za-z0-9+/]/u);
  if (invalidAt !== (padding ? body.data.length - padding : -1)) fail(400, '첨부 파일 인코딩을 확인해 주세요.');
  const bytes = Buffer.from(body.data, 'base64');
  if (bytes.length > MEDIA_LIMIT) fail(413, '첨부 파일은 각각 5MB 이하여야 합니다.');
  if (body.mime === 'image/jpeg') {
    if (bytes.length < 32 || bytes[0] !== 0xff || bytes[1] !== 0xd8 || bytes[2] !== 0xff || bytes[bytes.length - 2] !== 0xff || bytes[bytes.length - 1] !== 0xd9) fail(415, '올바른 JPEG 파일이 아닙니다.');
    let offset = 2;
    let frame = false;
    let scan = false;
    while (offset < bytes.length - 2) {
      if (bytes[offset++] !== 0xff) fail(415, 'JPEG 파일 구조를 확인해 주세요.');
      while (bytes[offset] === 0xff) offset++;
      const marker = bytes[offset++];
      if (offset + 2 > bytes.length || marker === 0x00 || marker === 0xd8 || marker === 0xd9) fail(415, 'JPEG 파일 구조를 확인해 주세요.');
      const size = bytes.readUInt16BE(offset);
      if (size < 2 || offset + size > bytes.length - 2) fail(415, 'JPEG 파일 구조를 확인해 주세요.');
      if ([0xc0, 0xc1, 0xc2].includes(marker)) {
        if (size < 8) fail(415, 'JPEG 크기 정보를 확인해 주세요.');
        const height = bytes.readUInt16BE(offset + 3);
        const width = bytes.readUInt16BE(offset + 5);
        if (height < 1 || width < 1 || height > 16_384 || width > 16_384) fail(415, 'JPEG 이미지 크기가 허용 범위를 벗어났습니다.');
        frame = true;
      }
      if (marker === 0xda) { scan = true; break; }
      offset += size;
    }
    if (!frame || !scan) fail(415, 'JPEG 이미지 정보가 없습니다.');
  } else {
    // Walk bounded ISO-BMFF boxes and require an audio track, not only a forged ftyp prefix.
    if (bytes.length < 24 || bytes.toString('ascii', 4, 8) !== 'ftyp') fail(415, '올바른 MP4 음성 파일이 아닙니다.');
    let boxes = 0;
    let fileType = false;
    let audio = false;
    let video = false;
    let mediaData = false;
    const walk = (start, end, depth) => {
      if (depth > 8) fail(415, '첨부 파일 구조가 올바르지 않습니다.');
      let offset = start;
      while (offset < end) {
        if (++boxes > 10_000 || end - offset < 8) fail(415, '첨부 파일 구조가 올바르지 않습니다.');
        let size = bytes.readUInt32BE(offset);
        const type = bytes.toString('ascii', offset + 4, offset + 8);
        let header = 8;
        if (size === 1) {
          if (end - offset < 16) fail(415, '첨부 파일 구조가 올바르지 않습니다.');
          const wide = bytes.readBigUInt64BE(offset + 8);
          if (wide > BigInt(end - offset)) fail(415, '첨부 파일 구조가 올바르지 않습니다.');
          size = Number(wide);
          header = 16;
        } else if (size === 0) size = end - offset;
        if (size < header || size > end - offset) fail(415, '첨부 파일 구조가 올바르지 않습니다.');
        const payload = offset + header;
        if (depth === 0 && type === 'ftyp') {
          if (size < header + 8 || (size - header) % 4 !== 0) fail(415, '첨부 파일 형식이 올바르지 않습니다.');
          const brands = new Set(['isom', 'iso2', 'mp41', 'mp42', 'M4A ', '3gp4', '3gp5', '3gp6']);
          fileType = brands.has(bytes.toString('ascii', payload, payload + 4));
          for (let brand = payload + 8; brand + 4 <= offset + size; brand += 4) fileType ||= brands.has(bytes.toString('ascii', brand, brand + 4));
        }
        if (depth === 0 && type === 'mdat' && size > header) mediaData = true;
        if (depth === 3 && type === 'hdlr' && size >= header + 12) {
          const handler = bytes.toString('ascii', payload + 8, payload + 12);
          audio ||= handler === 'soun';
          video ||= handler === 'vide';
        }
        if (['moov', 'trak', 'mdia'].includes(type)) walk(payload, offset + size, depth + 1);
        offset += size;
      }
    };
    walk(0, bytes.length, 0);
    if (!fileType || !audio || video || !mediaData) fail(415, '음성 트랙이 있는 MP4 파일만 첨부할 수 있습니다.');
  }
  return bytes;
}

const USER_COLUMNS = `
  id TEXT PRIMARY KEY, name TEXT NOT NULL,
  role TEXT CHECK(role IN ('guardian','protected')),
  sharing INTEGER NOT NULL DEFAULT 0 CHECK(sharing IN (0,1)),
  inactivity_minutes INTEGER NOT NULL DEFAULT 720 CHECK(inactivity_minutes BETWEEN 60 AND 4320),
  created_at INTEGER NOT NULL, sharing_since INTEGER, last_seen_at INTEGER,
  anchor_latitude REAL, anchor_longitude REAL, anchor_accuracy REAL, last_moved_at INTEGER,
  inactivity_alerted INTEGER NOT NULL DEFAULT 0, offline_alerted INTEGER NOT NULL DEFAULT 0
`;
const SESSION_COLUMNS = `
  token_hash TEXT PRIMARY KEY, user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  created_at INTEGER NOT NULL
`;
const INVITE_COLUMNS = `
  code_hash TEXT PRIMARY KEY, user_id TEXT UNIQUE REFERENCES users(id) ON DELETE CASCADE,
  role TEXT NOT NULL CHECK(role IN ('guardian','protected')),
  expires_at INTEGER NOT NULL
`;
const SCHEMA = `
CREATE TABLE IF NOT EXISTS users (${USER_COLUMNS});
CREATE TABLE IF NOT EXISTS sessions (${SESSION_COLUMNS});
CREATE INDEX IF NOT EXISTS sessions_user ON sessions(user_id, created_at);
CREATE TABLE IF NOT EXISTS connections (
  user_a TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  user_b TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  created_at INTEGER NOT NULL, PRIMARY KEY(user_a,user_b), CHECK(user_a < user_b)
);
CREATE INDEX IF NOT EXISTS connections_b ON connections(user_b);
CREATE TABLE IF NOT EXISTS invites (${INVITE_COLUMNS});
CREATE UNIQUE INDEX IF NOT EXISTS invites_one_setup ON invites((1)) WHERE user_id IS NULL;
CREATE TABLE IF NOT EXISTS locations (
  id INTEGER PRIMARY KEY AUTOINCREMENT, user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  latitude REAL NOT NULL, longitude REAL NOT NULL, accuracy REAL NOT NULL, recorded_at INTEGER NOT NULL,
  UNIQUE(user_id, recorded_at)
);
CREATE INDEX IF NOT EXISTS locations_time ON locations(recorded_at);
CREATE TABLE IF NOT EXISTS zones (
  id TEXT PRIMARY KEY, user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  name TEXT NOT NULL, latitude REAL NOT NULL, longitude REAL NOT NULL, radius REAL NOT NULL,
  state INTEGER CHECK(state IN (0,1))
);
CREATE INDEX IF NOT EXISTS zones_user ON zones(user_id);
CREATE TABLE IF NOT EXISTS journeys (
  id TEXT PRIMARY KEY, user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  destination TEXT NOT NULL, latitude REAL NOT NULL, longitude REAL NOT NULL, radius REAL NOT NULL,
  deadline INTEGER NOT NULL, status TEXT NOT NULL CHECK(status IN ('active','overdue','arrived','cancelled')),
  created_at INTEGER NOT NULL, finished_at INTEGER
);
CREATE INDEX IF NOT EXISTS journeys_user ON journeys(user_id,created_at);
CREATE UNIQUE INDEX IF NOT EXISTS journeys_one_open ON journeys(user_id) WHERE status IN ('active','overdue');
CREATE TABLE IF NOT EXISTS events (
  id INTEGER PRIMARY KEY AUTOINCREMENT, recipient_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  actor_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  type TEXT NOT NULL, title TEXT NOT NULL, body TEXT NOT NULL, created_at INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS events_recipient ON events(recipient_id,id DESC);
CREATE INDEX IF NOT EXISTS events_time ON events(created_at);
CREATE TABLE IF NOT EXISTS sos (
  id TEXT PRIMARY KEY, user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  message TEXT NOT NULL, latitude REAL, longitude REAL,
  status TEXT NOT NULL CHECK(status IN ('active','resolved')), created_at INTEGER NOT NULL, resolved_at INTEGER
);
CREATE UNIQUE INDEX IF NOT EXISTS sos_one_active ON sos(user_id) WHERE status='active';
CREATE INDEX IF NOT EXISTS sos_user ON sos(user_id,created_at);
CREATE TABLE IF NOT EXISTS acknowledgements (
  sos_id TEXT NOT NULL REFERENCES sos(id) ON DELETE CASCADE,
  user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  created_at INTEGER NOT NULL, PRIMARY KEY(sos_id,user_id)
);
CREATE TABLE IF NOT EXISTS media (
  id TEXT PRIMARY KEY, sos_id TEXT NOT NULL REFERENCES sos(id) ON DELETE CASCADE,
  mime TEXT NOT NULL CHECK(mime IN ('image/jpeg','audio/mp4')), size INTEGER NOT NULL, created_at INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS media_sos ON media(sos_id);
CREATE TABLE IF NOT EXISTS media_gc (id TEXT PRIMARY KEY);
`;

function initializeDatabase(db, now) {
  // Rebuild parents without cascading into child tables or rewriting their
  // references. SQLite requires disabling foreign keys before the transaction.
  db.exec('PRAGMA foreign_keys=OFF; BEGIN IMMEDIATE;');
  try {
    let version = db.prepare('PRAGMA user_version').get().user_version;
    if (version > 3) throw new Error('지원하지 않는 데이터베이스 버전입니다.');
    if (version === 0 && db.prepare("SELECT 1 FROM sqlite_schema WHERE type='table' AND name NOT LIKE 'sqlite_%'").get()) {
      throw new Error('버전 정보가 없는 기존 데이터베이스는 자동으로 변경할 수 없습니다.');
    }
    if (version === 1) {
      db.exec(`
        CREATE TABLE users_v2 (${USER_COLUMNS});
        INSERT INTO users_v2(id,name,sharing,inactivity_minutes,created_at,sharing_since,last_seen_at,anchor_latitude,anchor_longitude,anchor_accuracy,last_moved_at,inactivity_alerted,offline_alerted)
          SELECT id,name,sharing,inactivity_minutes,created_at,sharing_since,last_seen_at,anchor_latitude,anchor_longitude,anchor_accuracy,last_moved_at,inactivity_alerted,offline_alerted FROM users;
        CREATE TABLE sessions_v2 (${SESSION_COLUMNS});
        CREATE TABLE invites_v2 (${INVITE_COLUMNS});
        INSERT INTO invites_v2(code_hash,user_id,role,expires_at) SELECT code_hash,user_id,'protected',expires_at FROM invites;
      `);
      // Never turn an already-expired login into a persistent device credential.
      db.prepare('INSERT INTO sessions_v2(token_hash,user_id,created_at) SELECT token_hash,user_id,created_at FROM sessions WHERE expires_at>?').run(now());
      db.exec(`
        DROP TABLE sessions;
        DROP TABLE invites;
        DROP TABLE users;
        ALTER TABLE users_v2 RENAME TO users;
        ALTER TABLE sessions_v2 RENAME TO sessions;
        ALTER TABLE invites_v2 RENAME TO invites;
      `);
      version = 3;
    }
    if (version === 2) {
      db.exec(`
        ALTER TABLE users ADD COLUMN role TEXT CHECK(role IN ('guardian','protected'));
        ALTER TABLE invites ADD COLUMN role TEXT NOT NULL DEFAULT 'protected' CHECK(role IN ('guardian','protected'));
        UPDATE invites SET role='guardian' WHERE user_id IS NULL;
      `);
      version = 3;
    }
    db.exec(SCHEMA);
    if (db.prepare('PRAGMA foreign_key_check').get()) throw new Error('데이터베이스 참조 무결성 확인에 실패했습니다.');
    db.exec('PRAGMA user_version=3; COMMIT;');
  } catch (error) {
    db.exec('ROLLBACK');
    throw error;
  } finally {
    db.exec('PRAGMA foreign_keys=ON');
  }
}

function invitationOrigin(value, allowInsecure) {
  if (value == null || value === '') return null;
  if (typeof value !== 'string' || value.length > 2048 || !/^https?:\/\/[^/?#\\@\s]+\/?$/u.test(value)) throw new Error('PUBLIC_BASE_URL은 경로 없는 명시적인 HTTP(S) 원점이어야 합니다.');
  const url = new URL(value);
  if (url.username || url.password || url.pathname !== '/' || url.search || url.hash || (url.protocol !== 'https:' && !(allowInsecure === true && url.protocol === 'http:'))) {
    throw new Error('PUBLIC_BASE_URL은 HTTPS 원점이어야 합니다. 개발용 HTTP에는 ALLOW_INSECURE_INVITES=true가 필요합니다.');
  }
  return url.origin;
}

export function createApp({ dataDir = './data', now = Date.now, trustProxy = process.env.TRUST_PROXY === 'true', publicBaseUrl = null, androidApkPath = null, allowInsecureInvites = false } = {}) {
  publicBaseUrl = invitationOrigin(publicBaseUrl, allowInsecureInvites);
  const apkPath = androidApkPath ? resolve(androidApkPath) : null;
  const openApk = () => {
    if (!apkPath) return null;
    let fd;
    try {
      fd = openSync(apkPath, constants.O_RDONLY | constants.O_NONBLOCK);
      const stat = fstatSync(fd);
      if (stat.isFile() && stat.size > 0) return { fd, size: stat.size };
    } catch { /* A missing or unreadable operator-provided APK is unavailable. */ }
    if (fd !== undefined) closeSync(fd);
    return null;
  };
  const apkAvailable = () => {
    const apk = openApk();
    if (!apk) return false;
    closeSync(apk.fd);
    return true;
  };
  const directory = resolve(dataDir);
  const mediaDir = join(directory, 'media');
  mkdirSync(directory, { recursive: true, mode: 0o700 });
  mkdirSync(mediaDir, { recursive: true, mode: 0o700 });
  chmodSync(directory, 0o700);
  chmodSync(mediaDir, 0o700);
  const databasePath = join(directory, 'ansim.sqlite');
  const db = new DatabaseSync(databasePath);
  try {
    db.exec('PRAGMA journal_mode=WAL; PRAGMA busy_timeout=5000; PRAGMA synchronous=FULL; PRAGMA secure_delete=ON;');
    initializeDatabase(db, now);
  } catch (error) {
    db.close();
    throw error;
  }
  for (const path of [databasePath, `${databasePath}-wal`, `${databasePath}-shm`]) if (existsSync(path)) chmodSync(path, 0o600);
  const statements = new Map();
  const sql = query => {
    if (!statements.has(query)) statements.set(query, db.prepare(query));
    return statements.get(query);
  };
  const transaction = work => {
    db.exec('BEGIN IMMEDIATE');
    try { const result = work(); db.exec('COMMIT'); return result; }
    catch (error) { db.exec('ROLLBACK'); throw error; }
  };
  const connected = (a, b) => {
    const [first, second] = a < b ? [a, b] : [b, a];
    return Boolean(sql('SELECT 1 FROM connections WHERE user_a=? AND user_b=?').get(first, second));
  };
  const canViewLocation = (viewer, target) => viewer.id === target.id ||
    (viewer.role === 'guardian' && target.role === 'protected' && connected(viewer.id, target.id));
  const safetyRecipients = actor => {
    const target = sql('SELECT id,role FROM users WHERE id=?').get(actor);
    return target ? [actor, ...connections(actor).filter(viewer => canViewLocation(viewer, target)).map(viewer => viewer.id)] : [actor];
  };
  const connections = id => sql('SELECT u.* FROM users u JOIN connections c ON (c.user_a=? AND u.id=c.user_b) OR (c.user_b=? AND u.id=c.user_a) ORDER BY u.name,u.id').all(id, id);
  const connectionCount = id => sql('SELECT COUNT(*) AS count FROM connections WHERE user_a=? OR user_b=?').get(id, id).count;
  const notify = (actor, type, title, body, recipients = null) => {
    const targets = recipients ?? safetyRecipients(actor);
    const at = now();
    for (const recipient of new Set(targets)) sql('INSERT INTO events(recipient_id,actor_id,type,title,body,created_at) VALUES(?,?,?,?,?,?)').run(recipient, actor, type, title, body, at);
  };
  const latest = id => sql('SELECT latitude,longitude,accuracy,recorded_at FROM locations WHERE user_id=? AND recorded_at>=? ORDER BY recorded_at DESC LIMIT 1').get(id, now() - RETENTION);
  const getJourney = id => sql('SELECT j.*,u.name AS user_name FROM journeys j JOIN users u ON u.id=j.user_id WHERE j.id=?').get(id);
  const getSos = id => sql('SELECT s.*,u.name AS user_name,u.role AS user_role FROM sos s JOIN users u ON u.id=s.user_id WHERE s.id=?').get(id);
  const sosView = row => ({
    id: row.id, userId: row.user_id, userName: row.user_name, message: row.message,
    latitude: row.latitude, longitude: row.longitude, status: row.status, createdAt: iso(row.created_at),
    acknowledgedBy: sql('SELECT u.id,u.name FROM acknowledgements a JOIN users u ON u.id=a.user_id WHERE a.sos_id=? ORDER BY a.created_at,u.id').all(row.id).map(user => ({ id: user.id, name: user.name })),
    media: sql('SELECT id,mime FROM media WHERE sos_id=? ORDER BY created_at,id').all(row.id).map(item => ({ id: item.id, mime: item.mime }))
  });
  const canAccessSos = (user, sos) => sos && canViewLocation(user, { id: sos.user_id, role: sos.user_role });
  const mediaPath = id => join(mediaDir, id);
  const collectMedia = () => {
    for (const row of sql('SELECT id FROM media_gc LIMIT 1000').all()) {
      try { rmSync(mediaPath(row.id), { force: true }); sql('DELETE FROM media_gc WHERE id=?').run(row.id); }
      catch { /* Persist the deletion queue so the next sweep retries an I/O failure. */ }
    }
  };
  // Crash recovery: a file written before its metadata commit must not survive indefinitely.
  for (const entry of readdirSync(mediaDir, { withFileTypes: true })) {
    if (entry.isFile() && /^[0-9a-f-]{36}$/u.test(entry.name) && !sql('SELECT 1 FROM media WHERE id=?').get(entry.name)) sql('INSERT OR IGNORE INTO media_gc(id) VALUES(?)').run(entry.name);
  }
  collectMedia();

  const rateBuckets = new Map();
  const rate = (key, limit, windowMs) => {
    const at = now();
    let bucket = rateBuckets.get(key);
    if (!bucket || bucket.until <= at) {
      if (rateBuckets.size >= 10_000) {
        for (const [candidate, value] of rateBuckets) if (value.until <= at) rateBuckets.delete(candidate);
        if (rateBuckets.size >= 10_000) fail(429, '요청이 많습니다. 잠시 후 다시 시도해 주세요.');
      }
      bucket = { count: 0, until: at + windowMs };
      rateBuckets.set(key, bucket);
    }
    if (++bucket.count > limit) fail(429, '요청이 많습니다. 잠시 후 다시 시도해 주세요.');
  };
  const authenticate = req => {
    const authorization = req.headers.authorization;
    if (typeof authorization !== 'string' || !/^Bearer [A-Za-z0-9_-]{43}$/u.test(authorization)) fail(401, '이 기기의 가족 참여 정보가 필요합니다. 초대를 입력해 주세요.');
    const row = sql('SELECT u.* FROM sessions s JOIN users u ON u.id=s.user_id WHERE s.token_hash=?').get(hash(authorization.slice(7)));
    if (!row) fail(401, '이 기기의 가족 참여 정보를 사용할 수 없습니다. 새 초대를 입력해 주세요.');
    return row;
  };
  const newSession = id => {
    const token = randomBytes(32).toString('base64url');
    sql('INSERT INTO sessions(token_hash,user_id,created_at) VALUES(?,?,?)').run(hash(token), id, now());
    return token;
  };
  const inviteView = (code, expiresAt, role) => ({
    code, role, expiresAt: iso(expiresAt),
    inviteUrl: publicBaseUrl ? `${publicBaseUrl}/invite/${encodeURIComponent(code)}` : null,
    apkAvailable: apkAvailable()
  });
  const createSetupInvite = () => transaction(() => {
    if (sql('SELECT 1 FROM users LIMIT 1').get()) fail(409, '이미 가족 참여자가 있습니다. 참여 중인 기기에서 가족 초대를 만들어 주세요.');
    const code = randomBytes(12).toString('base64url');
    const expiresAt = now() + 10 * MINUTE;
    sql('DELETE FROM invites WHERE user_id IS NULL').run();
    sql("INSERT INTO invites(code_hash,user_id,role,expires_at) VALUES(?,NULL,'guardian',?)").run(hash(code), expiresAt);
    return inviteView(code, expiresAt, 'guardian');
  });

  function sweep() {
    transaction(() => {
      const at = now();
      sql('DELETE FROM invites WHERE expires_at<=?').run(at);
      sql('DELETE FROM locations WHERE recorded_at<?').run(at - RETENTION);
      sql('DELETE FROM events WHERE created_at<?').run(at - RETENTION);
      sql("INSERT OR IGNORE INTO media_gc(id) SELECT m.id FROM media m JOIN sos s ON s.id=m.sos_id WHERE s.status='resolved' AND s.resolved_at<?").run(at - RETENTION);
      sql("DELETE FROM sos WHERE status='resolved' AND resolved_at<?").run(at - RETENTION);
      sql("DELETE FROM journeys WHERE status IN ('arrived','cancelled') AND finished_at<?").run(at - RETENTION);
      for (const journey of sql("SELECT j.*,u.name AS user_name FROM journeys j JOIN users u ON u.id=j.user_id WHERE j.status='active' AND j.deadline<=? AND u.sharing=1").all(at)) {
        sql("UPDATE journeys SET status='overdue' WHERE id=? AND status='active'").run(journey.id);
        notify(journey.user_id, 'journey_overdue', '안심귀가 예정 시간 지남', `${journey.user_name}님의 ${journey.destination} 도착이 아직 확인되지 않았습니다. 직접 안부를 확인해 주세요.`);
      }
      for (const user of sql('SELECT * FROM users WHERE sharing=1').all()) {
        const silence = at - (user.last_seen_at ?? user.sharing_since);
        if (silence >= 15 * MINUTE && !user.offline_alerted) {
          sql('UPDATE users SET offline_alerted=1 WHERE id=?').run(user.id);
          notify(user.id, 'offline', '위치 수신 중단', `${user.name}님의 위치가 15분 이상 수신되지 않았습니다. 연결·권한·배터리 상태를 확인해 주세요. 움직임이 없다는 뜻은 아닙니다.`);
        }
        if (user.last_seen_at != null && silence < 5 * MINUTE && user.last_moved_at != null && at - user.last_moved_at >= user.inactivity_minutes * MINUTE && !user.inactivity_alerted) {
          sql('UPDATE users SET inactivity_alerted=1 WHERE id=?').run(user.id);
          notify(user.id, 'inactivity', '장시간 이동 변화 없음', `${user.name}님의 최근 위치 보고는 계속되고 있으나 ${user.inactivity_minutes}분 이상 정확도 범위를 넘는 이동이 확인되지 않았습니다. 직접 안부를 확인해 주세요.`);
        }
      }
    });
    collectMedia();
    for (const [key, value] of rateBuckets) if (value.until <= now()) rateBuckets.delete(key);
  }

  const json = (res, status, value) => {
    const bytes = Buffer.from(JSON.stringify(value));
    res.writeHead(status, { 'Content-Type': 'application/json; charset=utf-8', 'Content-Length': bytes.length });
    res.end(bytes);
  };
  const html = (req, res, status, options) => {
    const page = invitationPage(options);
    res.setHeader('Content-Security-Policy', invitationPageCsp);
    res.setHeader('X-Robots-Tag', 'noindex, nofollow');
    res.writeHead(status, { 'Content-Type': 'text/html; charset=utf-8', 'Content-Length': Buffer.byteLength(page) });
    res.end(req.method === 'HEAD' ? undefined : page);
  };
  const readBody = async (req, limit) => {
    const length = req.headers['content-length'];
    if (length != null && (!/^\d+$/u.test(length) || Number(length) > limit)) fail(413, '요청 데이터가 너무 큽니다.');
    if (req.headers['content-encoding'] && req.headers['content-encoding'] !== 'identity') fail(415, '압축된 요청은 지원하지 않습니다.');
    if (!req.headers['transfer-encoding'] && (length === undefined || Number(length) === 0)) return {};
    if (!/^application\/json(?:\s*;\s*charset=utf-8)?$/iu.test(req.headers['content-type'] ?? '')) fail(415, 'JSON 형식으로 요청해 주세요.');
    const chunks = [];
    let total = 0;
    for await (const chunk of req) {
      total += chunk.length;
      if (total > limit) fail(413, '요청 데이터가 너무 큽니다.');
      chunks.push(chunk);
    }
    let body;
    try { body = JSON.parse(Buffer.concat(chunks, total).toString('utf8')); }
    catch { fail(400, 'JSON 데이터를 확인해 주세요.'); }
    if (!body || typeof body !== 'object' || Array.isArray(body)) fail(400, 'JSON 객체가 필요합니다.');
    return body;
  };

  async function route(req, res, url, body, ip) {
    const path = url.pathname;
    const method = req.method;
    if (method === 'GET' && path === '/health') return json(res, 200, { ok: true });
    const reading = method === 'GET' || method === 'HEAD';
    if (reading && path === '/downloads/ansim-link.apk') {
      rate(`apk:${ip}`, 20, MINUTE);
      const apk = openApk();
      if (!apk) fail(503, 'Android 설치 파일이 아직 준비되지 않았습니다. 서버 운영자에게 APK 제공을 요청해 주세요.');
      res.setHeader('X-Robots-Tag', 'noindex, nofollow');
      res.writeHead(200, { 'Content-Type': 'application/vnd.android.package-archive', 'Content-Length': apk.size, 'Content-Disposition': 'attachment; filename="ansim-link.apk"' });
      if (method === 'HEAD') {
        closeSync(apk.fd);
        return res.end();
      }
      return pipeline(createReadStream(apkPath, { fd: apk.fd, autoClose: true }), res);
    }
    const preview = /^\/api\/invites\/([^/]+)$/u.exec(path);
    const landing = /^\/invite\/([^/]+)$/u.exec(path);
    if (reading && (preview || landing)) {
      rate(`invite-preview:${ip}`, 60, MINUTE);
      res.setHeader('X-Robots-Tag', 'noindex, nofollow');
      const code = (preview || landing)[1];
      const invite = /^[A-Za-z0-9_-]{16}$/u.test(code)
        ? sql('SELECT u.name AS inviter_name,i.user_id,i.role,i.expires_at FROM invites i LEFT JOIN users u ON u.id=i.user_id WHERE i.code_hash=? AND i.expires_at>? AND (i.user_id IS NOT NULL OR NOT EXISTS(SELECT 1 FROM users))').get(hash(code), now())
        : null;
      if (!invite) fail(404, '초대가 없거나 만료되었거나 이미 사용되었습니다. 초대한 가족에게 새 초대를 요청해 주세요.');
      if (!publicBaseUrl) fail(503, '서버의 공개 초대 주소가 아직 설정되지 않았습니다. 서버 운영자에게 문의하거나 앱에서 초대 코드를 직접 입력해 주세요.');
      if (preview) return json(res, 200, { inviterName: invite.inviter_name, isSetup: invite.user_id === null, role: invite.role, expiresAt: iso(invite.expires_at), serverUrl: publicBaseUrl });
      return html(req, res, 200, { invite, code, publicBaseUrl, apkAvailable: apkAvailable() });
    }
    if (method === 'POST' && path === '/api/invites/join') {
      if (req.headers.authorization !== undefined) fail(400, '이미 참여한 기기에서는 새 프로필을 만들 수 없습니다. 기존 가족 연결 기능을 이용해 주세요.');
      rate(`join:${ip}`, 15, 15 * MINUTE);
      fields(body, ['code', 'name']);
      const codeHash = hash(invitationCode(body.code));
      const name = text(body.name, '이름', 1, 60);
      const response = transaction(() => {
        const invite = sql('SELECT * FROM invites WHERE code_hash=? AND expires_at>?').get(codeHash, now());
        if (!invite) fail(404, '초대 코드가 없거나 만료되었거나 이미 사용되었습니다.');
        if (invite.user_id === null) {
          if (sql('SELECT 1 FROM users LIMIT 1').get()) fail(409, '첫 기기 설정 초대는 가족 참여자가 없을 때만 사용할 수 있습니다.');
        } else if (connectionCount(invite.user_id) >= 100) {
          fail(409, '연결은 계정당 최대 100명까지 가능합니다.');
        }
        const id = randomUUID();
        sql('INSERT INTO users(id,name,role,created_at) VALUES(?,?,?,?)').run(id, name, invite.role, now());
        if (invite.user_id !== null) {
          const [a, b] = id < invite.user_id ? [id, invite.user_id] : [invite.user_id, id];
          sql('INSERT INTO connections(user_a,user_b,created_at) VALUES(?,?,?)').run(a, b, now());
          notify(id, 'connection_added', '가족 연결 완료', `${name}님과 연결되었습니다. 역할별 위치 권한과 위치 공유 동의는 별개입니다.`, [id, invite.user_id]);
        }
        sql('DELETE FROM invites WHERE code_hash=?').run(codeHash);
        return { token: newSession(id), user: userView(sql('SELECT * FROM users WHERE id=?').get(id)) };
      });
      return json(res, 201, response);
    }

    // Body I/O completes before authentication; ownership reads and writes below
    // are synchronous, so a revoked device cannot resume an in-flight mutation.
    const user = authenticate(req);
    rate(`user:${user.id}`, 180, MINUTE);
    if (method === 'GET' && path === '/api/state') {
      const members = connections(user.id).map(member => {
        const allowed = canViewLocation(user, member);
        return {
          id: member.id, name: member.name, role: member.role ?? null, canViewLocation: allowed,
          sharing: allowed ? Boolean(member.sharing) : null,
          location: allowed && member.sharing ? locationView(latest(member.id)) : null,
          lastSeenAt: allowed && member.sharing ? iso(member.last_seen_at) : null
        };
      });
      const journeys = sql(`SELECT j.*,u.name AS user_name FROM journeys j JOIN users u ON u.id=j.user_id
        WHERE (u.id=? OR (?='guardian' AND u.role='protected' AND u.sharing=1 AND EXISTS(SELECT 1 FROM connections c WHERE (c.user_a=? AND c.user_b=u.id) OR (c.user_b=? AND c.user_a=u.id))))
        AND (j.created_at>=? OR j.status IN ('active','overdue')) ORDER BY (j.status IN ('active','overdue')) DESC,j.created_at DESC LIMIT 300`).all(user.id, user.role, user.id, user.id, now() - RETENTION).map(journeyView);
      const sos = sql(`SELECT s.*,u.name AS user_name,u.role AS user_role FROM sos s JOIN users u ON u.id=s.user_id WHERE (s.status='active' OR s.resolved_at>=?)
        AND (s.user_id=? OR (?='guardian' AND u.role='protected' AND EXISTS(SELECT 1 FROM connections c WHERE (c.user_a=? AND c.user_b=s.user_id) OR (c.user_b=? AND c.user_a=s.user_id))))
        ORDER BY (s.status='active') DESC,s.created_at DESC LIMIT 300`).all(now() - RETENTION, user.id, user.role, user.id, user.id).map(sosView);
      const events = sql('SELECT e.id,e.type,e.title,e.body,e.actor_id,e.created_at,u.role AS actor_role FROM events e JOIN users u ON u.id=e.actor_id WHERE e.recipient_id=? AND e.created_at>=? ORDER BY e.id DESC LIMIT 100').all(user.id, now() - RETENTION)
        .filter(event => event.actor_id === user.id || event.type === 'connection_added' || event.type === 'sos_ack' || canViewLocation(user, { id: event.actor_id, role: event.actor_role }))
        .map(event => ({ id: event.id, type: event.type, title: event.title, body: event.body, actorId: event.actor_id, createdAt: iso(event.created_at) }));
      const ownSharing = user.role === 'protected' && Boolean(user.sharing);
      return json(res, 200, { me: userView(user), members, zones: sql('SELECT * FROM zones WHERE user_id=? ORDER BY name,id').all(user.id).map(zoneView), journeys, events, sos, location: ownSharing ? locationView(latest(user.id)) : null, onboarding: { publicBaseUrl, apkAvailable: apkAvailable() } });
    }
    if (method === 'PATCH' && path === '/api/me/role') {
      fields(body, ['role']);
      const role = userRole(body.role);
      transaction(() => {
        if (sql('SELECT role FROM users WHERE id=?').get(user.id).role !== null) fail(409, '역할은 한 번 정하면 앱에서 변경할 수 없습니다.');
        sql('UPDATE users SET role=? WHERE id=? AND role IS NULL').run(role, user.id);
        if (role === 'guardian') {
          sql('UPDATE users SET sharing=0,sharing_since=NULL,last_seen_at=NULL,anchor_latitude=NULL,anchor_longitude=NULL,anchor_accuracy=NULL,last_moved_at=NULL,inactivity_alerted=0,offline_alerted=0 WHERE id=?').run(user.id);
          sql('DELETE FROM locations WHERE user_id=?').run(user.id);
          sql("UPDATE journeys SET status='cancelled',finished_at=? WHERE user_id=? AND status IN ('active','overdue')").run(now(), user.id);
          sql('UPDATE zones SET state=NULL WHERE user_id=?').run(user.id);
        }
      });
      return json(res, 200, { user: userView(sql('SELECT * FROM users WHERE id=?').get(user.id)) });
    }
    if (method === 'PATCH' && path === '/api/me') {
      fields(body, ['name', 'sharing', 'inactivityMinutes']);
      const name = body.name === undefined ? user.name : text(body.name, '이름', 1, 60);
      if (body.sharing !== undefined && typeof body.sharing !== 'boolean') fail(400, '위치 공유 설정을 확인해 주세요.');
      if (body.sharing === true && user.role !== 'protected') fail(403, '피보호자 역할만 자신의 위치 공유를 켤 수 있습니다.');
      const sharing = body.sharing === undefined ? user.sharing : Number(body.sharing);
      const inactivity = body.inactivityMinutes === undefined ? user.inactivity_minutes : number(body.inactivityMinutes, '알림 시간', 60, 4320);
      if (!Number.isInteger(inactivity)) fail(400, '알림 시간은 정수 분으로 입력해 주세요.');
      transaction(() => {
        sql('UPDATE users SET name=?,sharing=?,inactivity_minutes=? WHERE id=?').run(name, sharing, inactivity, user.id);
        if (sharing !== user.sharing) {
          sql('UPDATE users SET sharing_since=?,last_seen_at=NULL,anchor_latitude=NULL,anchor_longitude=NULL,anchor_accuracy=NULL,last_moved_at=NULL,inactivity_alerted=0,offline_alerted=0 WHERE id=?').run(sharing ? now() : null, user.id);
          sql('UPDATE zones SET state=NULL WHERE user_id=?').run(user.id);
          if (!sharing) {
            sql('DELETE FROM locations WHERE user_id=?').run(user.id);
            sql("UPDATE journeys SET status='cancelled',finished_at=? WHERE user_id=? AND status IN ('active','overdue')").run(now(), user.id);
            notify(user.id, 'sharing_off', '위치 공유 중지', `${name}님이 위치 공유를 중지했습니다. 저장된 위치 기록이 삭제되었습니다.`);
          }
        }
      });
      return json(res, 200, { user: userView(sql('SELECT * FROM users WHERE id=?').get(user.id)) });
    }
    if (method === 'DELETE' && path === '/api/me') {
      fields(body, ['confirmName']);
      rate(`delete:${user.id}`, 5, 15 * MINUTE);
      const confirmName = body.confirmName;
      text(confirmName, '확인 이름', 1, 60);
      transaction(() => {
        const current = authenticate(req);
        if (confirmName !== current.name) fail(403, '현재 표시 이름을 정확히 입력해 주세요.');
        sql('INSERT OR IGNORE INTO media_gc(id) SELECT m.id FROM media m JOIN sos s ON s.id=m.sos_id WHERE s.user_id=?').run(user.id);
        sql('DELETE FROM users WHERE id=?').run(user.id);
      });
      collectMedia();
      return json(res, 200, { ok: true });
    }
    if (method === 'POST' && path === '/api/invites') {
      fields(body, ['role']);
      rate(`invite:${user.id}`, 10, 60 * MINUTE);
      const role = body.role === undefined ? 'protected' : userRole(body.role, '초대 역할');
      const code = randomBytes(12).toString('base64url');
      const expiresAt = now() + 10 * MINUTE;
      sql('INSERT INTO invites(code_hash,user_id,role,expires_at) VALUES(?,?,?,?) ON CONFLICT(user_id) DO UPDATE SET code_hash=excluded.code_hash,role=excluded.role,expires_at=excluded.expires_at').run(hash(code), user.id, role, expiresAt);
      return json(res, 201, inviteView(code, expiresAt, role));
    }
    if (method === 'POST' && path === '/api/invites/accept') {
      fields(body, ['code']);
      rate(`accept:${user.id}`, 15, 15 * MINUTE);
      const codeHash = hash(invitationCode(body.code));
      transaction(() => {
        const invite = sql('SELECT * FROM invites WHERE code_hash=? AND expires_at>? AND user_id IS NOT NULL').get(codeHash, now());
        if (!invite) fail(404, '초대 코드가 없거나 만료되었거나 이미 사용되었습니다.');
        if (invite.user_id === user.id) fail(400, '자신의 초대 코드는 사용할 수 없습니다.');
        if (user.role === null) fail(409, '기존 프로필의 역할을 먼저 선택해 주세요.');
        if (user.role !== invite.role) fail(409, `이 초대는 ${invite.role === 'guardian' ? '보호자' : '피보호자'} 역할용입니다. 현재 프로필 역할과 같은 초대를 요청해 주세요.`);
        if (connected(user.id, invite.user_id)) fail(409, '이미 연결된 사용자입니다.');
        if (connectionCount(user.id) >= 100 || connectionCount(invite.user_id) >= 100) fail(409, '연결은 계정당 최대 100명까지 가능합니다.');
        const [a, b] = [user.id, invite.user_id].sort();
        sql('INSERT INTO connections(user_a,user_b,created_at) VALUES(?,?,?)').run(a, b, now());
        sql('DELETE FROM invites WHERE code_hash=?').run(codeHash);
        notify(user.id, 'connection_added', '가족 연결 완료', `${user.name}님과 연결되었습니다. 역할별 위치 권한과 위치 공유 동의는 별개입니다.`, [user.id, invite.user_id]);
      });
      return json(res, 200, { ok: true });
    }
    let match;
    if (method === 'DELETE' && (match = /^\/api\/members\/([^/]+)$/u.exec(path))) {
      fields(body, []);
      const member = match[1];
      transaction(() => {
        const [a, b] = [user.id, member].sort();
        if (!sql('DELETE FROM connections WHERE user_a=? AND user_b=?').run(a, b).changes) fail(404, '연결된 사용자를 찾을 수 없습니다.');
        sql('DELETE FROM events WHERE (recipient_id=? AND actor_id=?) OR (recipient_id=? AND actor_id=?)').run(user.id, member, member, user.id);
        sql('DELETE FROM acknowledgements WHERE (user_id=? AND sos_id IN (SELECT id FROM sos WHERE user_id=?)) OR (user_id=? AND sos_id IN (SELECT id FROM sos WHERE user_id=?))').run(user.id, member, member, user.id);
      });
      return json(res, 200, { ok: true });
    }
    if (method === 'POST' && path === '/api/locations') {
      fields(body, ['latitude', 'longitude', 'accuracy', 'recordedAt']);
      rate(`location:${user.id}`, 60, MINUTE);
      if (user.role !== 'protected') fail(403, '피보호자 역할만 위치를 전송할 수 있습니다.');
      if (!user.sharing) fail(403, '위치 공유가 꺼져 있습니다.');
      const fix = { ...point(body), accuracy: number(body.accuracy, '위치 정확도', 0, 10_000) };
      const recordedAt = timestamp(body.recordedAt, '위치 기록');
      if (recordedAt > now() + 2 * MINUTE || recordedAt < now() - 10 * MINUTE) fail(400, '위치 기록 시간이 허용 범위를 벗어났습니다.');
      if (user.last_seen_at != null && recordedAt <= user.last_seen_at) fail(409, '최신 위치만 전송해 주세요.');
      transaction(() => {
        sql('INSERT INTO locations(user_id,latitude,longitude,accuracy,recorded_at) VALUES(?,?,?,?,?)').run(user.id, fix.latitude, fix.longitude, fix.accuracy, recordedAt);
        const evidenceGap = user.last_seen_at == null || recordedAt - user.last_seen_at >= 5 * MINUTE;
        const reliable = fix.accuracy <= 100;
        const moved = user.anchor_latitude != null && distance({ latitude: user.anchor_latitude, longitude: user.anchor_longitude }, fix) > Math.max(25, user.anchor_accuracy + fix.accuracy);
        sql('UPDATE users SET last_seen_at=?,offline_alerted=0 WHERE id=?').run(recordedAt, user.id);
        if (!reliable) {
          sql('UPDATE users SET anchor_latitude=NULL,anchor_longitude=NULL,anchor_accuracy=NULL,last_moved_at=NULL,inactivity_alerted=0 WHERE id=?').run(user.id);
        } else if (evidenceGap || user.anchor_latitude == null || moved) {
          sql('UPDATE users SET anchor_latitude=?,anchor_longitude=?,anchor_accuracy=?,last_moved_at=?,inactivity_alerted=0 WHERE id=?').run(fix.latitude, fix.longitude, fix.accuracy, recordedAt, user.id);
        }
        for (const zone of sql('SELECT * FROM zones WHERE user_id=?').all(user.id)) {
          const state = classify(fix, zone);
          if (state == null || state === zone.state) continue;
          sql('UPDATE zones SET state=? WHERE id=? AND user_id=?').run(state, zone.id, user.id);
          if (zone.state != null) notify(user.id, state ? 'zone_enter' : 'zone_exit', state ? '안심구역 진입' : '안심구역 이탈', `${user.name}님이 ${zone.name} ${state ? '안으로 들어왔습니다' : '밖으로 나갔습니다'}. 위치 정확도 범위 밖의 변화가 확인되었습니다.`);
        }
        const journey = sql("SELECT * FROM journeys WHERE user_id=? AND status IN ('active','overdue')").get(user.id);
        if (journey && recordedAt >= journey.created_at && classify(fix, journey) === 1) {
          sql("UPDATE journeys SET status='arrived',finished_at=? WHERE id=?").run(now(), journey.id);
          notify(user.id, 'journey_arrived', '안심귀가 도착 확인', `${user.name}님의 ${journey.destination} 도착이 위치로 확인되었습니다.`);
        }
      });
      return json(res, 200, { ok: true });
    }
    if (method === 'GET' && (match = /^\/api\/locations\/([^/]+)$/u.exec(path))) {
      const target = sql('SELECT id,role,sharing FROM users WHERE id=?').get(match[1]);
      if (!target || !canViewLocation(user, target) || (target.id !== user.id && !target.sharing)) fail(404, '조회할 수 있는 위치 기록이 없습니다.');
      const since = url.searchParams.has('since') ? timestamp(url.searchParams.get('since'), '조회 시작') : now() - RETENTION;
      const rows = sql('SELECT latitude,longitude,accuracy,recorded_at FROM locations WHERE user_id=? AND recorded_at>=? ORDER BY recorded_at DESC LIMIT 2000').all(target.id, Math.max(since, now() - RETENTION));
      return json(res, 200, { locations: rows.reverse().map(locationView) });
    }
    if (method === 'POST' && path === '/api/zones') {
      fields(body, ['name', 'latitude', 'longitude', 'radius']);
      if (user.role !== 'protected') fail(403, '피보호자 역할만 안심구역을 등록할 수 있습니다.');
      const name = text(body.name, '안심구역 이름', 1, 100);
      const center = point(body);
      const radius = number(body.radius, '반경', 50, 5000);
      const id = randomUUID();
      transaction(() => {
        if (sql('SELECT COUNT(*) AS count FROM zones WHERE user_id=?').get(user.id).count >= 20) fail(409, '안심구역은 최대 20개까지 등록할 수 있습니다.');
        sql('INSERT INTO zones(id,user_id,name,latitude,longitude,radius) VALUES(?,?,?,?,?,?)').run(id, user.id, name, center.latitude, center.longitude, radius);
      });
      return json(res, 201, { zone: { id, name, ...center, radius } });
    }
    if (method === 'DELETE' && (match = /^\/api\/zones\/([^/]+)$/u.exec(path))) {
      fields(body, []);
      if (!sql('DELETE FROM zones WHERE id=? AND user_id=?').run(match[1], user.id).changes) fail(404, '안심구역을 찾을 수 없습니다.');
      return json(res, 200, { ok: true });
    }
    if (method === 'POST' && path === '/api/journeys') {
      fields(body, ['destination', 'latitude', 'longitude', 'radius', 'deadline']);
      if (user.role !== 'protected') fail(403, '피보호자 역할만 안심귀가를 시작할 수 있습니다.');
      if (!user.sharing) fail(403, '안심귀가를 시작하려면 직접 위치 공유를 켜 주세요.');
      rate(`journey:${user.id}`, 20, 60 * MINUTE);
      const destination = text(body.destination, '목적지 이름', 1, 100);
      const center = point(body);
      const radius = number(body.radius, '반경', 50, 5000);
      const deadline = timestamp(body.deadline, '도착 예정');
      if (deadline <= now() || deadline > now() + DAY) fail(400, '도착 예정 시간은 지금부터 24시간 이내여야 합니다.');
      const id = randomUUID();
      transaction(() => {
        if (sql("SELECT 1 FROM journeys WHERE user_id=? AND status IN ('active','overdue')").get(user.id)) fail(409, '진행 중인 안심귀가를 먼저 종료해 주세요.');
        sql("INSERT INTO journeys(id,user_id,destination,latitude,longitude,radius,deadline,status,created_at) VALUES(?,?,?,?,?,?,?,'active',?)").run(id, user.id, destination, center.latitude, center.longitude, radius, deadline, now());
        notify(user.id, 'journey_started', '안심귀가 시작', `${user.name}님이 ${destination}(으)로 안심귀가를 시작했습니다.`);
      });
      return json(res, 201, { journey: journeyView(getJourney(id)) });
    }
    if (method === 'POST' && (match = /^\/api\/journeys\/([^/]+)\/cancel$/u.exec(path))) {
      fields(body, []);
      transaction(() => {
        const journey = sql('SELECT * FROM journeys WHERE id=? AND user_id=?').get(match[1], user.id);
        if (!journey) fail(404, '안심귀가를 찾을 수 없습니다.');
        if (journey.status === 'arrived' || journey.status === 'cancelled') return;
        sql("UPDATE journeys SET status='cancelled',finished_at=? WHERE id=? AND user_id=?").run(now(), journey.id, user.id);
        notify(user.id, 'journey_cancelled', '안심귀가 취소', `${user.name}님이 안심귀가를 취소했습니다.`);
      });
      return json(res, 200, { ok: true });
    }
    if (method === 'POST' && path === '/api/sos') {
      fields(body, ['message', 'latitude', 'longitude']);
      const message = text(body.message, '도움 요청 메시지', 1, 1000);
      const hasPoint = body.latitude !== undefined || body.longitude !== undefined;
      const center = hasPoint ? point(body) : { latitude: null, longitude: null };
      const response = transaction(() => {
        const active = sql("SELECT id FROM sos WHERE user_id=? AND status='active'").get(user.id);
        if (active) return { existed: true, sos: sosView(getSos(active.id)) };
        rate(`sos:${user.id}`, 12, 60 * MINUTE);
        const id = randomUUID();
        sql("INSERT INTO sos(id,user_id,message,latitude,longitude,status,created_at) VALUES(?,?,?,?,?,'active',?)").run(id, user.id, message, center.latitude, center.longitude, now());
        notify(user.id, 'sos', '긴급 도움 요청', `${user.name}님: ${message} · 앱은 긴급기관에 자동 신고하지 않습니다. 직접 연락하고 필요하면 112·119에 신고해 주세요.`);
        return { existed: false, sos: sosView(getSos(id)) };
      });
      return json(res, response.existed ? 200 : 201, { sos: response.sos });
    }
    if (method === 'POST' && (match = /^\/api\/sos\/([^/]+)\/ack$/u.exec(path))) {
      fields(body, []);
      transaction(() => {
        const sos = getSos(match[1]);
        if (!sos || sos.user_id === user.id || !canAccessSos(user, sos)) fail(404, '확인할 수 있는 도움 요청이 없습니다.');
        if (sql('SELECT 1 FROM acknowledgements WHERE sos_id=? AND user_id=?').get(sos.id, user.id)) return;
        if (sos.status !== 'active') fail(409, '이미 종료된 도움 요청입니다.');
        sql('INSERT INTO acknowledgements(sos_id,user_id,created_at) VALUES(?,?,?)').run(sos.id, user.id, now());
        notify(user.id, 'sos_ack', '도움 요청 확인됨', `${user.name}님이 도움 요청을 확인했습니다. 실제 지원이나 긴급기관 출동을 보장하지 않습니다.`, [sos.user_id, user.id]);
      });
      return json(res, 200, { ok: true });
    }
    if (method === 'POST' && (match = /^\/api\/sos\/([^/]+)\/resolve$/u.exec(path))) {
      fields(body, []);
      transaction(() => {
        const sos = sql('SELECT * FROM sos WHERE id=? AND user_id=?').get(match[1], user.id);
        if (!sos) fail(404, '도움 요청을 찾을 수 없습니다.');
        if (sos.status === 'resolved') return;
        sql("UPDATE sos SET status='resolved',resolved_at=? WHERE id=? AND user_id=?").run(now(), sos.id, user.id);
        notify(user.id, 'sos_resolved', '도움 요청 종료', `${user.name}님이 직접 도움 요청을 종료했습니다.`);
      });
      return json(res, 200, { ok: true });
    }
    if (method === 'POST' && (match = /^\/api\/sos\/([^/]+)\/media$/u.exec(path))) {
      rate(`media:${user.id}`, 12, 60 * MINUTE);
      const sos = sql("SELECT id FROM sos WHERE id=? AND user_id=? AND status='active'").get(match[1], user.id);
      if (!sos) fail(404, '본인의 진행 중인 도움 요청에만 첨부할 수 있습니다.');
      if (sql('SELECT COUNT(*) AS count FROM media WHERE sos_id=?').get(sos.id).count >= 4) fail(409, '첨부 파일은 최대 4개입니다.');
      const bytes = decodeMedia(body);
      const id = randomUUID();
      try {
        writeFileSync(mediaPath(id), bytes, { flag: 'wx', mode: 0o600, flush: true });
        transaction(() => {
          sql('INSERT INTO media(id,sos_id,mime,size,created_at) VALUES(?,?,?,?,?)').run(id, sos.id, body.mime, bytes.length, now());
        });
      } catch (error) {
        sql('INSERT OR IGNORE INTO media_gc(id) VALUES(?)').run(id);
        collectMedia();
        throw error;
      }
      return json(res, 201, { media: { id, mime: body.mime } });
    }
    if (method === 'GET' && (match = /^\/api\/media\/([^/]+)$/u.exec(path))) {
      const item = sql('SELECT m.*,s.user_id,u.role AS user_role FROM media m JOIN sos s ON s.id=m.sos_id JOIN users u ON u.id=s.user_id WHERE m.id=?').get(match[1]);
      if (!canAccessSos(user, item)) fail(404, '첨부 파일을 조회할 권한이 없습니다.');
      const bytes = readFileSync(mediaPath(item.id));
      res.writeHead(200, { 'Content-Type': item.mime, 'Content-Length': bytes.length, 'Content-Disposition': `attachment; filename="${item.id}.${item.mime === 'image/jpeg' ? 'jpg' : 'm4a'}"` });
      return res.end(bytes);
    }
    fail(404, '요청한 경로를 찾을 수 없습니다.');
  }

  let closing = false;
  let inFlight = 0;
  let mediaInFlight = 0;
  const server = createServer({ maxHeaderSize: 8192 }, async (req, res) => {
    res.setHeader('Cache-Control', 'no-store');
    res.setHeader('X-Content-Type-Options', 'nosniff');
    res.setHeader('X-Frame-Options', 'DENY');
    res.setHeader('Content-Security-Policy', "default-src 'none'; frame-ancestors 'none'");
    res.setHeader('Referrer-Policy', 'no-referrer');
    let counted = false;
    let mediaCounted = false;
    const release = () => {
      if (counted) { counted = false; inFlight--; }
    };
    res.once('finish', release);
    res.once('close', release);
    try {
      if (closing) fail(503, '서버를 종료하는 중입니다. 잠시 후 다시 시도해 주세요.');
      if (inFlight >= 32) fail(503, '서버가 혼잡합니다. 잠시 후 다시 시도해 주세요.');
      inFlight++;
      counted = true;
      if (!req.url || req.url.length > 2048) fail(414, '요청 주소가 너무 깁니다.');
      const url = new URL(req.url, 'http://localhost');
      let ip = req.socket.remoteAddress ?? 'unknown';
      if (trustProxy && typeof req.headers['x-forwarded-for'] === 'string') {
        const forwarded = req.headers['x-forwarded-for'].split(',').at(-1).trim();
        if (isIP(forwarded)) ip = forwarded;
      }
      rate(`ip:${ip}`, 300, MINUTE);
      const mediaUpload = req.method === 'POST' && /^\/api\/sos\/[^/]+\/media$/u.test(url.pathname);
      if (mediaUpload) {
        if (mediaInFlight >= 2) fail(503, '첨부 요청이 많습니다. 잠시 후 다시 시도해 주세요.');
        // Reject unauthenticated multi-megabyte uploads before buffering, then
        // reauthenticate after body I/O so revocations cannot race the mutation.
        authenticate(req);
        mediaInFlight++;
        mediaCounted = true;
      }
      const takesBody = ['POST', 'PATCH', 'DELETE'].includes(req.method);
      const body = takesBody ? await readBody(req, mediaUpload ? ENCODED_LIMIT + 1024 : 16 * 1024) : {};
      if (!takesBody && (Number(req.headers['content-length'] ?? 0) > 0 || req.headers['transfer-encoding'])) fail(400, '이 요청에는 본문을 보낼 수 없습니다.');
      await route(req, res, url, body, ip);
    } catch (error) {
      if (!res.headersSent && !res.destroyed) {
        const status = error instanceof HttpError ? error.status : 500;
        if (status === 429) res.setHeader('Retry-After', '60');
        if (!req.complete) res.setHeader('Connection', 'close');
        const message = error instanceof HttpError ? error.message : '서버 처리 중 오류가 발생했습니다. 잠시 후 다시 시도해 주세요.';
        if (/^\/(?:invite\/|downloads\/ansim-link\.apk(?:[?]|$))/u.test(req.url ?? '')) {
          html(req, res, status, { error: { title: status === 404 ? '사용할 수 없는 초대입니다' : '지금은 열 수 없습니다', message } });
        } else {
          json(res, status, { error: message });
        }
      } else if (!res.writableEnded) res.destroy();
    } finally {
      if (res.destroyed) release();
      if (mediaCounted) mediaInFlight--;
    }
  });
  server.requestTimeout = 30_000;
  server.headersTimeout = 10_000;
  server.keepAliveTimeout = 5000;
  server.maxHeadersCount = 50;
  server.maxRequestsPerSocket = 100;
  server.maxConnections = 128;
  server.on('clientError', (_error, socket) => {
    const body = JSON.stringify({ error: 'HTTP 요청을 확인해 주세요.' });
    if (socket.writable) socket.end(`HTTP/1.1 400 Bad Request\r\nConnection: close\r\nContent-Type: application/json; charset=utf-8\r\nContent-Length: ${Buffer.byteLength(body)}\r\n\r\n${body}`);
  });
  const timer = setInterval(() => {
    if (!closing) {
      try { sweep(); }
      catch { console.error('안전 상태 점검에 실패했습니다. 저장소 상태를 확인해 주세요.'); }
    }
  }, 30_000);
  timer.unref();
  let closePromise;
  const close = () => {
    if (closePromise) return closePromise;
    closing = true;
    clearInterval(timer);
    closePromise = new Promise((resolveClose, rejectClose) => {
      const force = setTimeout(() => server.closeAllConnections(), 10_000);
      force.unref();
      server.close(error => {
        clearTimeout(force);
        try { collectMedia(); db.close(); }
        catch (closeError) { rejectClose(closeError); return; }
        if (error && error.code !== 'ERR_SERVER_NOT_RUNNING') rejectClose(error);
        else resolveClose();
      });
      server.closeIdleConnections();
    });
    return closePromise;
  };
  sweep();
  return { server, close, sweep, createSetupInvite };
}
