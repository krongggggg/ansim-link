import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdtempSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { createHash, randomBytes, randomUUID } from 'node:crypto';
import { DatabaseSync } from 'node:sqlite';
import { createApp } from '../src/app.mjs';

const MINUTE = 60_000;
const DAY = 24 * 60 * MINUTE;

async function context(t, options = {}, initialize) {
  const dataDir = mkdtempSync(join(tmpdir(), 'ansim-test-'));
  let time = Date.parse('2026-01-01T00:00:00Z');
  initialize?.(dataDir, time);
  let app;
  let base;
  const open = async () => {
    app = createApp({ dataDir, now: () => time, ...options });
    await new Promise((resolve, reject) => {
      app.server.once('error', reject);
      app.server.listen(0, '127.0.0.1', resolve);
    });
    base = `http://127.0.0.1:${app.server.address().port}`;
  };
  await open();
  t.after(async () => { await app.close(); rmSync(dataDir, { recursive: true, force: true }); });
  const request = async (method, path, token, body) => {
    const response = await fetch(base + path, {
      method,
      headers: { ...(token ? { Authorization: `Bearer ${token}` } : {}), ...(body === undefined ? {} : { 'Content-Type': 'application/json' }) },
      ...(body === undefined ? {} : { body: JSON.stringify(body) })
    });
    return { status: response.status, body: await response.json() };
  };
  const member = async (name, role = 'protected') => {
    const id = randomUUID();
    const token = randomBytes(32).toString('base64url');
    const db = new DatabaseSync(join(dataDir, 'ansim.sqlite'));
    try {
      db.exec('PRAGMA foreign_keys=ON');
      db.prepare('INSERT INTO users(id,name,role,created_at) VALUES(?,?,?,?)').run(id, name, role, time);
      db.prepare('INSERT INTO sessions(token_hash,user_id,created_at) VALUES(?,?,?)')
        .run(createHash('sha256').update(token).digest('hex'), id, time);
    } finally { db.close(); }
    return { token, user: (await request('GET', '/api/state', token)).body.me };
  };
  const state = async account => {
    const result = await request('GET', '/api/state', account.token);
    assert.equal(result.status, 200);
    return result.body;
  };
  const share = async account => assert.equal((await request('PATCH', '/api/me', account.token, { sharing: true, inactivityMinutes: 60 })).status, 200);
  const fix = async (account, latitude = 37, longitude = 127, accuracy = 5) => {
    const result = await request('POST', '/api/locations', account.token, { latitude, longitude, accuracy, recordedAt: new Date(time).toISOString() });
    assert.equal(result.status, 200);
  };
  return {
    request, member, state, share, fix,
    raw: (path, options) => fetch(base + path, options),
    now: () => time,
    advance: amount => { time += amount; },
    sweep: () => app.sweep(),
    setupInvite: () => app.createSetupInvite(),
    profileCount: () => {
      const db = new DatabaseSync(join(dataDir, 'ansim.sqlite'), { readOnly: true });
      try { return db.prepare('SELECT count(*) AS count FROM users').get().count; }
      finally { db.close(); }
    },
    restart: async () => { await app.close(); await open(); }
  };
}

test('first-device setup requires a current local invitation and has exactly one winner', async t => {
  const c = await context(t, { publicBaseUrl: 'https://family.example' });
  assert.equal((await c.request('POST', '/api/auth/register', null, { name: 'outsider', email: 'outsider@example.com', password: 'obsolete-password' })).status, 401);
  assert.equal((await c.request('POST', '/api/auth/login', null, { email: 'outsider@example.com', password: 'obsolete-password' })).status, 401);
  assert.equal((await c.request('POST', '/api/invites', null, {})).status, 401);
  const replaced = c.setupInvite();
  const expired = c.setupInvite();
  assert.equal((await c.request('POST', '/api/invites/join', null, { code: replaced.code, name: 'replaced' })).status, 404);
  c.advance(10 * MINUTE);
  assert.equal((await c.request('POST', '/api/invites/join', null, { code: expired.code, name: 'expired' })).status, 404);
  const invitation = c.setupInvite();
  const preview = await c.request('GET', `/api/invites/${invitation.code}`);
  assert.equal(preview.body.isSetup, true);
  assert.equal(preview.body.inviterName, null);
  assert.equal(preview.body.role, 'guardian');
  assert.equal(c.profileCount(), 0);
  assert.equal((await c.request('POST', '/api/invites/join', null, { code: invitation.code, name: ' ' })).status, 400);
  const results = await Promise.all(['first-phone', 'racing-phone'].map(name =>
    c.request('POST', '/api/invites/join', null, { code: invitation.code, name })));
  assert.deepEqual(results.map(result => result.status).sort(), [201, 404]);
  const joined = results.find(result => result.status === 201).body;
  const state = await c.state(joined);
  assert.equal(state.me.sharing, false);
  assert.equal(state.me.role, 'guardian');
  assert.deepEqual(state.members, []);
  assert.equal(c.profileCount(), 1);
  assert.throws(() => c.setupInvite(), error => error.status === 409);
  assert.equal((await c.request('GET', `/api/invites/${invitation.code}`)).status, 404);
});

test('invite-only join does not replace an existing identity and races atomically with connection acceptance', async t => {
  const c = await context(t, { publicBaseUrl: 'https://family.example' });
  const owner = await c.member('inviting-phone');
  const recipient = await c.member('existing-phone');
  const invitation = (await c.request('POST', '/api/invites', owner.token, {})).body;
  assert.equal((await c.request('POST', '/api/invites/join', recipient.token, { code: invitation.code, name: 'replacement' })).status, 400);
  assert.equal((await c.state(recipient)).me.id, recipient.user.id);
  assert.equal((await c.request('POST', '/api/invites/join', null, { code: invitation.code, name: 'new-phone', sharing: true })).status, 400);
  const [join, accept] = await Promise.all([
    c.request('POST', '/api/invites/join', null, { code: invitation.code, name: 'new-phone' }),
    c.request('POST', '/api/invites/accept', recipient.token, { code: invitation.code })
  ]);
  assert.equal(join.status === 201 ? accept.status : join.status, 404);
  assert.equal(join.status === 201 ? join.status : accept.status, join.status === 201 ? 201 : 200);
  assert.equal(c.profileCount(), join.status === 201 ? 3 : 2);
  const connected = join.status === 201 ? join.body : recipient;
  const state = await c.state(connected);
  assert.equal(state.me.sharing, false);
  assert.deepEqual(state.members.map(member => member.id), [owner.user.id]);
  assert.deepEqual((await c.state(owner)).members.map(member => member.id), [connected.user.id]);
  assert.equal((await c.request('POST', '/api/invites/join', null, { code: invitation.code, name: 'replay' })).status, 404);
});

test('legacy migration preserves active device access and family data without reviving expired credentials', async t => {
  const owner = { token: randomBytes(32).toString('base64url') };
  const relative = { token: randomBytes(32).toString('base64url') };
  const expiredToken = randomBytes(32).toString('base64url');
  const code = randomBytes(12).toString('base64url');
  const digest = value => createHash('sha256').update(value).digest('hex');
  const c = await context(t, { publicBaseUrl: 'https://family.example' }, (dataDir, time) => {
    const db = new DatabaseSync(join(dataDir, 'ansim.sqlite'));
    try {
      db.exec(`
        PRAGMA foreign_keys=ON;
        CREATE TABLE users (
          id TEXT PRIMARY KEY, email TEXT NOT NULL UNIQUE, name TEXT NOT NULL,
          password_salt TEXT NOT NULL, password_hash TEXT NOT NULL,
          sharing INTEGER NOT NULL DEFAULT 0 CHECK(sharing IN (0,1)),
          inactivity_minutes INTEGER NOT NULL DEFAULT 720 CHECK(inactivity_minutes BETWEEN 60 AND 4320),
          created_at INTEGER NOT NULL, sharing_since INTEGER, last_seen_at INTEGER,
          anchor_latitude REAL, anchor_longitude REAL, anchor_accuracy REAL, last_moved_at INTEGER,
          inactivity_alerted INTEGER NOT NULL DEFAULT 0, offline_alerted INTEGER NOT NULL DEFAULT 0
        );
        CREATE TABLE sessions (
          token_hash TEXT PRIMARY KEY, user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
          created_at INTEGER NOT NULL, expires_at INTEGER NOT NULL
        );
        CREATE TABLE connections (
          user_a TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
          user_b TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
          created_at INTEGER NOT NULL, PRIMARY KEY(user_a,user_b), CHECK(user_a < user_b)
        );
        CREATE TABLE invites (
          code_hash TEXT PRIMARY KEY, user_id TEXT NOT NULL UNIQUE REFERENCES users(id) ON DELETE CASCADE,
          expires_at INTEGER NOT NULL
        );
        CREATE TABLE locations (
          id INTEGER PRIMARY KEY AUTOINCREMENT, user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
          latitude REAL NOT NULL, longitude REAL NOT NULL, accuracy REAL NOT NULL, recorded_at INTEGER NOT NULL,
          UNIQUE(user_id,recorded_at)
        );
        CREATE TABLE zones (
          id TEXT PRIMARY KEY, user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
          name TEXT NOT NULL, latitude REAL NOT NULL, longitude REAL NOT NULL, radius REAL NOT NULL,
          state INTEGER CHECK(state IN (0,1))
        );
        CREATE TABLE events (
          id INTEGER PRIMARY KEY AUTOINCREMENT, recipient_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
          actor_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
          type TEXT NOT NULL, title TEXT NOT NULL, body TEXT NOT NULL, created_at INTEGER NOT NULL
        );
        PRAGMA user_version=1;
      `);
      for (const [id, name, token] of [['legacy-a', '아빠', owner.token], ['legacy-b', '가족', relative.token]]) {
        db.prepare('INSERT INTO users(id,email,name,password_salt,password_hash,sharing,inactivity_minutes,created_at,sharing_since,last_seen_at) VALUES(?,?,?,?,?,1,180,?,?,?)')
          .run(id, `${id}@example.com`, name, 'old-salt', 'old-hash', time - DAY, time - DAY, time);
        db.prepare('INSERT INTO sessions VALUES(?,?,?,?)').run(digest(token), id, time - DAY, time + DAY);
      }
      db.prepare('INSERT INTO sessions VALUES(?,?,?,?)').run(digest(expiredToken), 'legacy-a', time - 31 * DAY, time);
      db.prepare('INSERT INTO connections VALUES(?,?,?)').run('legacy-a', 'legacy-b', time - MINUTE);
      db.prepare('INSERT INTO invites VALUES(?,?,?)').run(digest(code), 'legacy-a', time + MINUTE);
      db.prepare('INSERT INTO locations(user_id,latitude,longitude,accuracy,recorded_at) VALUES(?,?,?,?,?)')
        .run('legacy-a', 37, 127, 5, time);
      db.prepare('INSERT INTO zones VALUES(?,?,?,?,?,?,?)').run('old-zone', 'legacy-a', '집', 37, 127, 150, 1);
      db.prepare('INSERT INTO events(recipient_id,actor_id,type,title,body,created_at) VALUES(?,?,?,?,?,?)')
        .run('legacy-b', 'legacy-a', 'zone_enter', '도착', '집에 도착했습니다.', time);
    } finally { db.close(); }
  });
  const mine = await c.state(owner);
  assert.deepEqual(mine.me, { id: 'legacy-a', name: '아빠', role: null, sharing: false, inactivityMinutes: 180 });
  assert.deepEqual(mine.zones, [{ id: 'old-zone', name: '집', latitude: 37, longitude: 127, radius: 150 }]);
  let family = await c.state(relative);
  assert.deepEqual([family.members[0].role, family.members[0].canViewLocation, family.members[0].sharing, family.members[0].location], [null, false, null, null]);
  assert.deepEqual(family.events, []);
  assert.equal((await c.request('GET', `/api/invites/${code}`)).body.role, 'protected');
  assert.equal((await c.request('PATCH', '/api/me/role', relative.token, { role: 'guardian' })).status, 200);
  assert.equal((await c.request('PATCH', '/api/me/role', owner.token, { role: 'protected' })).status, 200);
  assert.equal((await c.request('PATCH', '/api/me/role', owner.token, { role: 'guardian' })).status, 409);
  family = await c.state(relative);
  assert.deepEqual(family.members[0].location, { latitude: 37, longitude: 127, accuracy: 5, recordedAt: new Date(c.now()).toISOString() });
  assert.equal(family.events[0].type, 'zone_enter');
  assert.equal((await c.request('GET', `/api/invites/${code}`)).body.inviterName, '아빠');
  assert.equal((await c.request('GET', '/api/state', expiredToken)).status, 401);
  c.advance(31 * DAY);
  c.sweep();
  await c.restart();
  assert.equal((await c.state(owner)).me.id, 'legacy-a');
  assert.equal((await c.request('GET', '/api/state', expiredToken)).status, 401);
  assert.equal((await c.request('DELETE', '/api/me', owner.token, { confirmName: '아빠' })).status, 200);
  await c.restart();
  assert.equal((await c.request('GET', '/api/state', owner.token)).status, 401);
  assert.deepEqual((await c.state(relative)).members, []);
});

test('version 2 storage migrates to unassigned roles without exposing legacy locations', async t => {
  const account = { token: randomBytes(32).toString('base64url') };
  const code = randomBytes(12).toString('base64url');
  const digest = value => createHash('sha256').update(value).digest('hex');
  const c = await context(t, { publicBaseUrl: 'https://family.example' }, (dataDir, time) => {
    const db = new DatabaseSync(join(dataDir, 'ansim.sqlite'));
    try {
      db.exec(`
        PRAGMA foreign_keys=ON;
        CREATE TABLE users (
          id TEXT PRIMARY KEY, name TEXT NOT NULL,
          sharing INTEGER NOT NULL DEFAULT 0 CHECK(sharing IN (0,1)),
          inactivity_minutes INTEGER NOT NULL DEFAULT 720 CHECK(inactivity_minutes BETWEEN 60 AND 4320),
          created_at INTEGER NOT NULL, sharing_since INTEGER, last_seen_at INTEGER,
          anchor_latitude REAL, anchor_longitude REAL, anchor_accuracy REAL, last_moved_at INTEGER,
          inactivity_alerted INTEGER NOT NULL DEFAULT 0, offline_alerted INTEGER NOT NULL DEFAULT 0
        );
        CREATE TABLE sessions (
          token_hash TEXT PRIMARY KEY, user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
          created_at INTEGER NOT NULL
        );
        CREATE TABLE invites (
          code_hash TEXT PRIMARY KEY, user_id TEXT UNIQUE REFERENCES users(id) ON DELETE CASCADE,
          expires_at INTEGER NOT NULL
        );
        PRAGMA user_version=2;
      `);
      db.prepare('INSERT INTO users(id,name,sharing,created_at,sharing_since,last_seen_at) VALUES(?,?,1,?,?,?)').run('v2-user', '기존 사용자', time - DAY, time - DAY, time);
      db.prepare('INSERT INTO sessions VALUES(?,?,?)').run(digest(account.token), 'v2-user', time - DAY);
      db.prepare('INSERT INTO invites VALUES(?,?,?)').run(digest(code), 'v2-user', time + MINUTE);
    } finally { db.close(); }
  });
  const state = await c.state(account);
  assert.deepEqual([state.me.role, state.me.sharing, state.location], [null, false, null]);
  assert.equal((await c.request('GET', `/api/invites/${code}`)).body.role, 'protected');
  assert.equal((await c.request('PATCH', '/api/me/role', account.token, { role: 'guardian' })).status, 200);
  assert.deepEqual([(await c.state(account)).me.role, (await c.state(account)).me.sharing], ['guardian', false]);
  await c.restart();
  assert.equal((await c.state(account)).me.role, 'guardian');
});

test('mutual consent, single-use invitations, sharing erasure and revocation persist', async t => {
  const c = await context(t);
  const owner = await c.member('owner', 'protected');
  const guardian = await c.member('guardian', 'guardian');
  const stranger = await c.member('stranger', 'guardian');
  const invitation = await c.request('POST', '/api/invites', owner.token, { role: 'guardian' });
  assert.equal((await c.request('POST', '/api/invites/accept', owner.token, { code: invitation.body.code })).status, 400);
  const attempts = await Promise.all([guardian, stranger].map(account => c.request('POST', '/api/invites/accept', account.token, { code: invitation.body.code })));
  assert.deepEqual(attempts.map(result => result.status).sort(), [200, 404]);
  const linked = attempts[0].status === 200 ? guardian : stranger;
  const unlinked = linked === guardian ? stranger : guardian;
  const connectedState = await c.state(owner);
  assert.equal(connectedState.me.sharing, false);
  assert.deepEqual(connectedState.members.map(member => [member.id, member.role, member.canViewLocation, member.sharing, member.location]), [[linked.user.id, 'guardian', false, null, null]]);
  await c.share(owner);
  await c.fix(owner);
  assert.equal((await c.state(linked)).members[0].location.latitude, 37);
  assert.equal((await c.request('GET', `/api/locations/${owner.user.id}`, unlinked.token)).status, 404);
  const journey = await c.request('POST', '/api/journeys', owner.token, { destination: '집', latitude: 37.1, longitude: 127, radius: 100, deadline: new Date(c.now() + 60 * MINUTE).toISOString() });
  assert.equal(journey.status, 201);
  assert.equal((await c.request('POST', `/api/journeys/${journey.body.journey.id}/cancel`, linked.token, {})).status, 404);
  const sos = await c.request('POST', '/api/sos', owner.token, { message: '도움이 필요합니다.', latitude: 37, longitude: 127 });
  const repeated = await c.request('POST', '/api/sos', owner.token, { message: '다시 누름' });
  assert.equal(repeated.body.sos.id, sos.body.sos.id);
  assert.equal((await c.state(linked)).events.filter(event => event.type === 'sos').length, 1);
  assert.equal((await c.request('POST', `/api/sos/${sos.body.sos.id}/ack`, owner.token, {})).status, 404);
  assert.equal((await c.request('POST', `/api/sos/${sos.body.sos.id}/ack`, linked.token, {})).status, 200);
  assert.equal((await c.request('POST', `/api/sos/${sos.body.sos.id}/ack`, linked.token, {})).status, 200);
  assert.equal((await c.state(owner)).sos[0].acknowledgedBy.length, 1);
  assert.equal((await c.state(owner)).events.some(event => event.type === 'sos_ack'), true);
  assert.equal((await c.request('POST', `/api/sos/${sos.body.sos.id}/media`, owner.token, { mime: 'image/jpeg', data: Buffer.from('not an image').toString('base64') })).status, 415);
  assert.equal((await c.request('PATCH', '/api/me', owner.token, { sharing: false })).status, 200);
  const afterOff = await c.state(linked);
  assert.equal(afterOff.members[0].location, null);
  assert.equal(afterOff.members[0].lastSeenAt, null);
  assert.deepEqual(afterOff.journeys, []);
  assert.equal(afterOff.sos[0].latitude, 37); // A separate, explicit SOS disclosure survives ordinary share-off.
  assert.equal((await c.state(owner)).journeys[0].status, 'cancelled');
  assert.deepEqual((await c.request('GET', `/api/locations/${owner.user.id}`, owner.token)).body.locations, []);
  assert.equal((await c.request('DELETE', `/api/members/${linked.user.id}`, owner.token)).status, 200);
  assert.deepEqual((await c.state(linked)).sos, []);
  assert.equal((await c.request('POST', `/api/sos/${sos.body.sos.id}/ack`, linked.token, {})).status, 404);
  await c.restart();
  assert.deepEqual((await c.state(owner)).members, []);
  assert.equal((await c.state(owner)).location, null);
  assert.equal((await c.request('DELETE', '/api/me', owner.token, { confirmName: 'wrong-name' })).status, 403);
  assert.equal((await c.request('DELETE', '/api/me', owner.token, { confirmName: owner.user.name })).status, 200);
  assert.equal((await c.request('GET', '/api/state', owner.token)).status, 401);
});

test('role matrix exposes protected locations only to connected guardians', async t => {
  const c = await context(t);
  const child = await c.member('피보호자', 'protected');
  const guardian = await c.member('보호자', 'guardian');
  const protectedPeer = await c.member('다른 피보호자', 'protected');
  const guardianPeer = await c.member('다른 보호자', 'guardian');

  const guardianInvite = (await c.request('POST', '/api/invites', child.token, { role: 'guardian' })).body;
  assert.equal(guardianInvite.role, 'guardian');
  assert.equal((await c.request('POST', '/api/invites/accept', protectedPeer.token, { code: guardianInvite.code })).status, 409);
  assert.equal((await c.request('POST', '/api/invites/accept', guardian.token, { code: guardianInvite.code })).status, 200);
  const protectedInvite = (await c.request('POST', '/api/invites', child.token, { role: 'protected' })).body;
  assert.equal((await c.request('POST', '/api/invites/accept', protectedPeer.token, { code: protectedInvite.code })).status, 200);
  const peerInvite = (await c.request('POST', '/api/invites', guardian.token, { role: 'guardian' })).body;
  assert.equal((await c.request('POST', '/api/invites/accept', guardianPeer.token, { code: peerInvite.code })).status, 200);

  await c.share(child);
  await c.fix(child);
  const journey = await c.request('POST', '/api/journeys', child.token, { destination: '학교', latitude: 37.1, longitude: 127, radius: 100, deadline: new Date(c.now() + 60 * MINUTE).toISOString() });
  assert.equal(journey.status, 201);
  const childSos = await c.request('POST', '/api/sos', child.token, { message: '도움 요청', latitude: 37, longitude: 127 });
  assert.equal(childSos.status, 201);

  const guardianState = await c.state(guardian);
  const visibleChild = guardianState.members.find(member => member.id === child.user.id);
  assert.deepEqual([visibleChild.role, visibleChild.canViewLocation, visibleChild.sharing, visibleChild.location.latitude], ['protected', true, true, 37]);
  assert.equal(guardianState.journeys.some(item => item.userId === child.user.id), true);
  assert.equal(guardianState.sos.some(item => item.id === childSos.body.sos.id), true);
  assert.equal((await c.request('GET', `/api/locations/${child.user.id}`, guardian.token)).status, 200);

  const childState = await c.state(child);
  const hiddenGuardian = childState.members.find(member => member.id === guardian.user.id);
  assert.deepEqual([hiddenGuardian.role, hiddenGuardian.canViewLocation, hiddenGuardian.sharing, hiddenGuardian.location], ['guardian', false, null, null]);
  assert.equal((await c.request('GET', `/api/locations/${guardian.user.id}`, child.token)).status, 404);
  assert.equal((await c.request('PATCH', '/api/me', guardian.token, { sharing: true })).status, 403);
  assert.equal((await c.request('POST', '/api/locations', guardian.token, { latitude: 38, longitude: 128, accuracy: 5, recordedAt: new Date(c.now()).toISOString() })).status, 403);
  assert.equal((await c.request('POST', '/api/zones', guardian.token, { name: '보호자 구역', latitude: 38, longitude: 128, radius: 100 })).status, 403);
  assert.equal((await c.request('POST', '/api/journeys', guardian.token, { destination: '보호자 목적지', latitude: 38, longitude: 128, radius: 100, deadline: new Date(c.now() + 60 * MINUTE).toISOString() })).status, 403);

  const parentSos = await c.request('POST', '/api/sos', guardian.token, { message: '보호자 도움 요청', latitude: 38, longitude: 128 });
  assert.equal(parentSos.status, 201);
  assert.equal((await c.state(child)).sos.some(item => item.id === parentSos.body.sos.id), false);
  assert.equal((await c.request('POST', `/api/sos/${parentSos.body.sos.id}/ack`, child.token, {})).status, 404);

  const protectedState = await c.state(protectedPeer);
  const hiddenProtected = protectedState.members.find(member => member.id === child.user.id);
  assert.deepEqual([hiddenProtected.canViewLocation, hiddenProtected.sharing, hiddenProtected.location], [false, null, null]);
  assert.equal(protectedState.journeys.some(item => item.userId === child.user.id), false);
  assert.equal(protectedState.sos.some(item => item.id === childSos.body.sos.id), false);
  assert.equal((await c.request('GET', `/api/locations/${child.user.id}`, protectedPeer.token)).status, 404);

  const guardianPeerState = await c.state(guardianPeer);
  const hiddenGuardianPeer = guardianPeerState.members.find(member => member.id === guardian.user.id);
  assert.deepEqual([hiddenGuardianPeer.canViewLocation, hiddenGuardianPeer.sharing, hiddenGuardianPeer.location], [false, null, null]);
  assert.equal((await c.request('GET', `/api/locations/${guardian.user.id}`, guardianPeer.token)).status, 404);
});

test('public invitation previews are origin-bound, escaped and read-only until explicit acceptance', async t => {
  const c = await context(t, { publicBaseUrl: 'https://family.example/' });
  const owner = await c.member('preview-owner');
  const recipient = await c.member('preview-recipient');
  const name = '<img src=x onerror="alert(1)">&가족';
  assert.equal((await c.request('PATCH', '/api/me', owner.token, { name })).status, 200);
  const invitation = await c.raw('/api/invites', {
    method: 'POST',
    headers: { Authorization: `Bearer ${owner.token}`, 'Content-Type': 'application/json', Host: 'attacker.example', 'X-Forwarded-Host': 'attacker.example', 'X-Forwarded-Proto': 'http' },
    body: '{}'
  });
  assert.equal(invitation.status, 201);
  const invite = await invitation.json();
  assert.equal(invite.inviteUrl, `https://family.example/invite/${invite.code}`);
  assert.equal(invite.expiresAt, new Date(c.now() + 10 * MINUTE).toISOString());
  const previewPath = `/api/invites/${invite.code}`;
  for (const method of ['HEAD', 'GET', 'GET']) {
    const response = await c.raw(previewPath, { method, headers: { Authorization: 'Bearer invalid', Host: 'attacker.example' } });
    assert.equal(response.status, 200);
    assert.equal(response.headers.get('cache-control'), 'no-store');
    if (method === 'HEAD') assert.equal(await response.text(), '');
    else assert.deepEqual(await response.json(), { inviterName: name, isSetup: false, role: 'protected', expiresAt: invite.expiresAt, serverUrl: 'https://family.example' });
  }
  const pagePath = `/invite/${invite.code}`;
  const page = await c.raw(pagePath, { headers: { Authorization: `Bearer ${owner.token}`, Host: 'attacker.example' } });
  assert.equal(page.status, 200);
  assert.equal(page.headers.get('referrer-policy'), 'no-referrer');
  assert.match(page.headers.get('content-security-policy'), /default-src 'none'/u);
  const html = await page.text();
  assert.ok(html.includes('&lt;img src=x onerror=&quot;alert(1)&quot;&gt;&amp;가족'));
  assert.ok(html.includes('초대 역할: 피보호자'));
  assert.ok(!html.includes('<img'));
  assert.ok(!html.includes('<script'));
  assert.ok(!html.includes(owner.token));
  assert.ok(!html.includes('attacker.example'));
  const intent = html.match(/href="(intent:[^"]+)"/u)[1].replaceAll('&amp;', '&');
  const [data, extras] = intent.split('#Intent;');
  const uri = new URL(data.replace('intent:', 'ansimlink:'));
  assert.equal(uri.host, 'invite');
  assert.deepEqual([...uri.searchParams], [['server', 'https://family.example'], ['code', invite.code]]);
  assert.ok(extras.includes('scheme=ansimlink;package=org.ansim.link;'));
  assert.equal(decodeURIComponent(extras.match(/S\.browser_fallback_url=([^;]+)/u)[1]), invite.inviteUrl);
  const head = await c.raw(pagePath, { method: 'HEAD' });
  assert.equal(head.status, 200);
  assert.equal(await head.text(), '');
  assert.deepEqual((await c.state(recipient)).members, []);
  assert.equal((await c.request('POST', '/api/invites/accept', recipient.token, { code: invite.code })).status, 200);
  assert.equal((await c.request('GET', previewPath)).status, 404);
  const consumed = await c.raw(pagePath);
  assert.equal(consumed.status, 404);
  assert.ok(!(await consumed.text()).includes('intent:'));
  assert.equal((await c.state(recipient)).me.sharing, false);
});

test('replaced and expired invitation links fail without relying on the periodic sweep', async t => {
  const c = await context(t, { publicBaseUrl: 'https://family.example' });
  const owner = await c.member('preview-expiry');
  const first = (await c.request('POST', '/api/invites', owner.token, {})).body;
  const second = (await c.request('POST', '/api/invites', owner.token, {})).body;
  assert.equal((await c.request('GET', `/api/invites/${first.code}`)).status, 404);
  c.advance(10 * MINUTE - 1);
  assert.equal((await c.request('GET', `/api/invites/${second.code}`)).status, 200);
  c.advance(1);
  assert.equal((await c.request('GET', `/api/invites/${second.code}`)).status, 404);
  const page = await c.raw(`/invite/${second.code}`);
  assert.equal(page.status, 404);
  assert.match(page.headers.get('content-type'), /^text\/html/u);
  assert.ok(!(await page.text()).includes('intent:'));
});

test('unconfigured onboarding stays unavailable while the existing code acceptance still works', async t => {
  const c = await context(t);
  const owner = await c.member('unconfigured-owner');
  const recipient = await c.member('unconfigured-recipient');
  const invite = (await c.request('POST', '/api/invites', owner.token, {})).body;
  assert.equal(invite.inviteUrl, null);
  assert.equal((await c.request('GET', `/api/invites/${invite.code}`)).status, 503);
  const page = await c.raw(`/invite/${invite.code}`);
  assert.equal(page.status, 503);
  assert.ok(!(await page.text()).includes('intent:'));
  const unavailable = await c.raw('/downloads/ansim-link.apk');
  assert.equal(unavailable.status, 503);
  assert.match(unavailable.headers.get('content-type'), /^text\/html/u);
  await unavailable.text();
  assert.equal((await c.request('POST', '/api/invites/accept', recipient.token, { code: invite.code })).status, 200);
});

test('APK download serves only the configured file, supports HEAD and tracks removal', async t => {
  const directory = mkdtempSync(join(tmpdir(), 'ansim-apk-test-'));
  t.after(() => rmSync(directory, { recursive: true, force: true }));
  const apkPath = join(directory, 'operator-build.apk');
  const privatePath = join(directory, 'private.txt');
  const bytes = Buffer.alloc(256 * 1024, 0x5a);
  writeFileSync(apkPath, bytes);
  writeFileSync(privatePath, 'not a downloadable file');
  const c = await context(t, { publicBaseUrl: 'https://family.example', androidApkPath: apkPath });
  const owner = await c.member('download-owner');
  const invitation = (await c.request('POST', '/api/invites', owner.token, {})).body;
  assert.equal(invitation.apkAvailable, true);
  const head = await c.raw('/downloads/ansim-link.apk', { method: 'HEAD' });
  assert.equal(head.status, 200);
  assert.equal(head.headers.get('content-type'), 'application/vnd.android.package-archive');
  assert.equal(head.headers.get('content-disposition'), 'attachment; filename="ansim-link.apk"');
  assert.equal(head.headers.get('content-length'), String(bytes.length));
  assert.equal(await head.text(), '');
  const download = await c.raw(`/downloads/ansim-link.apk?path=${encodeURIComponent(privatePath)}`);
  assert.equal(download.status, 200);
  assert.deepEqual(Buffer.from(await download.arrayBuffer()), bytes);
  const arbitrary = await c.raw('/downloads/%2e%2e%2fprivate.txt');
  assert.notEqual(arbitrary.status, 200);
  assert.ok(!(await arbitrary.text()).includes('not a downloadable file'));
  rmSync(apkPath);
  assert.equal((await c.state(owner)).onboarding.apkAvailable, false);
  const removed = await c.raw('/downloads/ansim-link.apk', { method: 'HEAD' });
  assert.equal(removed.status, 503);
  assert.equal(await removed.text(), '');
});

test('public invite lookup rate limiting does not require authentication', async t => {
  const c = await context(t, { publicBaseUrl: 'https://family.example' });
  for (let index = 0; index < 60; index++) {
    assert.equal((await c.request('GET', '/api/invites/AAAAAAAAAAAAAAAA')).status, 404);
  }
  const limited = await c.raw('/api/invites/AAAAAAAAAAAAAAAA');
  assert.equal(limited.status, 429);
  assert.equal(limited.headers.get('retry-after'), '60');
  await limited.text();
  c.advance(MINUTE);
  assert.equal((await c.request('GET', '/api/invites/AAAAAAAAAAAAAAAA')).status, 404);
});

test('invitation origin rejects credentials, paths and implicit insecure transport before opening storage', async t => {
  const dataDir = mkdtempSync(join(tmpdir(), 'ansim-origin-test-'));
  t.after(() => rmSync(dataDir, { recursive: true, force: true }));
  for (const publicBaseUrl of ['http://family.example', 'https://user:pass@family.example', 'https://family.example/path', 'https://family.example/../', 'https://family.example?query', 'https://family.example#fragment', 'https://family.example?', 'https://family.example#', 'https://family.example\\\\evil', '//family.example']) {
    assert.throws(() => createApp({ dataDir, publicBaseUrl }));
  }
  const c = await context(t, { publicBaseUrl: 'http://localhost:8080/', allowInsecureInvites: true });
  const owner = await c.member('development-origin');
  const invite = (await c.request('POST', '/api/invites', owner.token, {})).body;
  assert.equal(invite.inviteUrl, `http://localhost:8080/invite/${invite.code}`);
});

test('uncertain zone overlap neither creates crossings nor completes an overdue journey', async t => {
  const c = await context(t);
  const owner = await c.member('traveler');
  await c.share(owner);
  const zone = await c.request('POST', '/api/zones', owner.token, { name: '집', latitude: 0, longitude: 0, radius: 100 });
  assert.equal(zone.status, 201);
  await c.fix(owner, 0, 0.002, 5);
  assert.deepEqual((await c.state(owner)).events.filter(event => event.type.startsWith('zone_')), []);
  c.advance(MINUTE);
  await c.fix(owner, 0, 0.0009, 30);
  assert.deepEqual((await c.state(owner)).events.filter(event => event.type.startsWith('zone_')), []);
  c.advance(MINUTE);
  await c.fix(owner, 0, 0, 5);
  c.advance(MINUTE);
  await c.fix(owner, 0, 0, 5);
  assert.equal((await c.state(owner)).events.filter(event => event.type === 'zone_enter').length, 1);
  c.advance(MINUTE);
  await c.fix(owner, 0, 0.002, 5);
  assert.equal((await c.state(owner)).events.filter(event => event.type === 'zone_exit').length, 1);
  const journey = await c.request('POST', '/api/journeys', owner.token, { destination: '집', latitude: 0, longitude: 0, radius: 100, deadline: new Date(c.now() + MINUTE).toISOString() });
  assert.equal(journey.status, 201);
  c.advance(2 * MINUTE);
  c.sweep();
  c.sweep();
  assert.equal((await c.state(owner)).events.filter(event => event.type === 'journey_overdue').length, 1);
  await c.fix(owner, 0, 0.0009, 30);
  assert.equal((await c.state(owner)).journeys[0].status, 'overdue');
  c.advance(MINUTE);
  await c.fix(owner, 0, 0, 5);
  assert.equal((await c.state(owner)).journeys[0].status, 'arrived');
  const oldFix = await c.request('POST', '/api/locations', owner.token, { latitude: 0, longitude: 0.002, accuracy: 5, recordedAt: new Date(c.now() - 1).toISOString() });
  assert.equal(oldFix.status, 409);
  assert.equal((await c.state(owner)).location.longitude, 0);
});

test('stationary evidence must be recent and continuous; offline is a separate episode', async t => {
  const c = await context(t);
  const owner = await c.member('stationary');
  await c.share(owner);
  await c.fix(owner);
  for (let i = 0; i < 15; i++) {
    c.advance(4 * MINUTE);
    await c.fix(owner, 37 + (i % 2 ? 0.00001 : 0), 127, 5);
    c.sweep();
  }
  let events = (await c.state(owner)).events;
  assert.equal(events.filter(event => event.type === 'inactivity').length, 1);
  assert.equal(events.filter(event => event.type === 'offline').length, 0);
  c.advance(MINUTE);
  await c.fix(owner);
  c.sweep();
  assert.equal((await c.state(owner)).events.filter(event => event.type === 'inactivity').length, 1);
  c.advance(16 * MINUTE);
  c.sweep();
  c.sweep();
  events = (await c.state(owner)).events;
  assert.equal(events.filter(event => event.type === 'offline').length, 1);
  assert.equal(events.filter(event => event.type === 'inactivity').length, 1);
  await c.fix(owner);
  c.sweep();
  assert.equal((await c.state(owner)).events.filter(event => event.type === 'inactivity').length, 1);
  for (let i = 0; i < 16; i++) {
    c.advance(4 * MINUTE);
    await c.fix(owner, 37, 127, 150);
    c.sweep();
  }
  events = (await c.state(owner)).events;
  assert.equal(events.filter(event => event.type === 'inactivity').length, 1);
  assert.equal(events.filter(event => event.type === 'offline').length, 1);
  c.advance(16 * MINUTE);
  c.sweep();
  assert.equal((await c.state(owner)).events.filter(event => event.type === 'offline').length, 2);
});

test('expired invitations and deleted device credentials stay revoked while active devices remain signed in', async t => {
  const c = await context(t);
  const owner = await c.member('expiry-owner', 'protected');
  const guardian = await c.member('expiry-guardian', 'guardian');
  const invitation = await c.request('POST', '/api/invites', owner.token, { role: 'guardian' });
  c.advance(10 * MINUTE);
  assert.equal((await c.request('POST', '/api/invites/accept', guardian.token, { code: invitation.body.code })).status, 404);
  assert.equal((await c.request('DELETE', '/api/me', guardian.token, { confirmName: guardian.user.name })).status, 200);
  await c.restart();
  assert.equal((await c.request('GET', '/api/state', guardian.token)).status, 401);
  c.advance(90 * DAY);
  c.sweep();
  await c.restart();
  assert.equal((await c.request('GET', '/api/state', owner.token)).status, 200);
});

test('unresolved SOS remains visible until resolved, then expires seven days after resolution', async t => {
  const c = await context(t);
  const owner = await c.member('retention-owner', 'protected');
  const guardian = await c.member('retention-guardian', 'guardian');
  const invite = await c.request('POST', '/api/invites', owner.token, { role: 'guardian' });
  await c.request('POST', '/api/invites/accept', guardian.token, { code: invite.body.code });
  const created = await c.request('POST', '/api/sos', owner.token, { message: '아직 도움이 필요합니다.' });
  assert.equal(created.status, 201);
  const id = created.body.sos.id;
  c.advance(8 * DAY);
  c.sweep();
  assert.equal((await c.state(guardian)).sos.find(item => item.id === id)?.status, 'active');
  assert.equal((await c.request('POST', `/api/sos/${id}/resolve`, owner.token, {})).status, 200);
  c.sweep();
  assert.equal((await c.state(guardian)).sos.find(item => item.id === id)?.status, 'resolved');
  c.advance(7 * DAY + 1);
  c.sweep();
  assert.deepEqual((await c.state(owner)).sos, []);
  assert.deepEqual((await c.state(guardian)).sos, []);
});

test('Java Instant nanosecond deadlines are accepted and expire at millisecond precision', async t => {
  const c = await context(t);
  const owner = await c.member('android-instant');
  await c.share(owner);
  const deadline = new Date(c.now() + 30 * MINUTE).toISOString().replace('.000Z', '.123456789Z');
  const created = await c.request('POST', '/api/journeys', owner.token, {
    destination: '안심귀가', latitude: 37, longitude: 127, radius: 150, deadline
  });
  assert.equal(created.status, 201);
  c.advance(30 * MINUTE + 122);
  c.sweep();
  assert.equal((await c.state(owner)).journeys[0].status, 'active');
  c.advance(1);
  c.sweep();
  assert.equal((await c.state(owner)).journeys[0].status, 'overdue');
});
