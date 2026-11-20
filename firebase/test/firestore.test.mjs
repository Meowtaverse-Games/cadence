import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { after, before, beforeEach, test } from 'node:test';
import { assertFails, assertSucceeds, initializeTestEnvironment } from '@firebase/rules-unit-testing';
import {
  collection, collectionGroup, deleteDoc, deleteField, doc, documentId, getDoc, getDocs,
  query, serverTimestamp, setDoc, Timestamp, updateDoc, where,
} from 'firebase/firestore';

// Never fall back to a real project when this file is run outside emulators:exec.
assert.equal(process.env.GCLOUD_PROJECT, 'demo-cadence');
assert.equal(process.env.FIRESTORE_EMULATOR_HOST, '127.0.0.1:8080');

let environment;
const ownPath = 'users/owner-test/config/timetable';
const storedTime = Timestamp.fromMillis(0);
const timetable = (overrides = {}) => ({
  schemaVersion: 1,
  timeZone: 'Asia/Tokyo',
  patterns: [],
  updatedAt: serverTimestamp(),
  ...overrides,
});
const ownerDb = () => environment.authenticatedContext('owner-test').firestore();

before(async () => {
  environment = await initializeTestEnvironment({
    projectId: 'demo-cadence',
    firestore: {
      host: '127.0.0.1', port: 8080,
      rules: await readFile(new URL('../firestore.rules', import.meta.url), 'utf8'),
    },
  });
});
beforeEach(async () => { await environment.clearFirestore(); });
after(async () => { await environment?.cleanup(); });

async function seed(path = ownPath) {
  await environment.withSecurityRulesDisabled(async (context) => {
    await setDoc(doc(context.firestore(), path), timetable({ updatedAt: storedTime }));
  });
}

test('owner can get their timetable', async () => {
  await seed();
  const result = await assertSucceeds(getDoc(doc(ownerDb(), ownPath)));
  assert.equal(result.data().schemaVersion, 1);
});
test('owner can create with zero patterns and a server timestamp', async () => {
  const ref = doc(ownerDb(), ownPath);
  await assertSucceeds(setDoc(ref, timetable()));
  assert.ok((await getDoc(ref)).data().updatedAt instanceof Timestamp);
});
test('owner can update with a fresh server timestamp', async () => {
  await seed();
  const ref = doc(ownerDb(), ownPath);
  await assertSucceeds(updateDoc(ref, { patterns: [{ id: 'synthetic-pattern' }], updatedAt: serverTimestamp() }));
  assert.equal((await getDoc(ref)).data().patterns.length, 1);
});
test('owner can overwrite an existing timetable without merge', async () => {
  await seed();
  const ref = doc(ownerDb(), ownPath);
  const patterns = [{ id: 'replacement-pattern' }];
  await assertSucceeds(setDoc(ref, timetable({ patterns })));
  const result = (await getDoc(ref)).data();
  assert.deepEqual(result.patterns, patterns);
  assert.ok(result.updatedAt instanceof Timestamp);
  assert.ok(result.updatedAt.toMillis() > storedTime.toMillis());
});
test('owner can write 20 patterns; rule intentionally does not validate their contents', async () => {
  await assertSucceeds(setDoc(doc(ownerDb(), ownPath), timetable({ patterns: Array(20).fill(null) })));
});
test('owner cannot delete', async () => {
  await seed();
  await assertFails(deleteDoc(doc(ownerDb(), ownPath)));
});

for (const [name, getQuery] of [
  ['config collection', (db) => collection(db, 'users/owner-test/config')],
  ['document ID filtered config query', (db) => query(collection(db, 'users/owner-test/config'), where(documentId(), '==', 'timetable'))],
  ['users collection', (db) => collection(db, 'users')],
  ['config collection group', (db) => collectionGroup(db, 'config')],
]) {
  test(`owner cannot list ${name}`, async () => {
    await seed();
    await assertFails(getDocs(getQuery(ownerDb())));
  });
}

for (const [name, getDb] of [
  ['another user', () => environment.authenticatedContext('other-test').firestore()],
  ['unauthenticated client', () => environment.unauthenticatedContext().firestore()],
]) {
  for (const operation of ['get', 'create', 'update', 'delete', 'list']) {
    test(`${name} cannot ${operation} owner's timetable`, async () => {
      if (operation !== 'create') await seed();
      const db = getDb();
      const ref = doc(db, ownPath);
      const run = {
        get: () => getDoc(ref),
        create: () => setDoc(ref, timetable()),
        update: () => updateDoc(ref, { patterns: [], updatedAt: serverTimestamp() }),
        delete: () => deleteDoc(ref),
        list: () => getDocs(collection(db, 'users/owner-test/config')),
      };
      await assertFails(run[operation]());
    });
  }
}

for (const path of [
  'users/owner-test',
  'users/owner-test/config/other',
  'users/owner-test/config/timetable/nested/document',
  'unrelated/document',
]) {
  for (const operation of ['get', 'create', 'update', 'delete']) {
    test(`owner cannot ${operation} ${path}`, async () => {
      if (operation !== 'create') await seed(path);
      const ref = doc(ownerDb(), path);
      const run = {
        get: () => getDoc(ref),
        create: () => setDoc(ref, timetable()),
        update: () => updateDoc(ref, { updatedAt: serverTimestamp() }),
        delete: () => deleteDoc(ref),
      };
      await assertFails(run[operation]());
    });
  }
}

const invalidChanges = [
  ['extra key', { extra: true }],
  ['schema version 2', { schemaVersion: 2 }],
  ['schema version string', { schemaVersion: '1' }],
  ['schema version boolean', { schemaVersion: true }],
  ['different time zone', { timeZone: 'UTC' }],
  ['21 patterns', { patterns: Array(21).fill(null) }],
  ['patterns map', { patterns: {} }],
  ['patterns string', { patterns: 'invalid' }],
  ['patterns null', { patterns: null }],
  ['client timestamp', { updatedAt: storedTime }],
  ['timestamp string', { updatedAt: '2026-01-01T00:00:00Z' }],
  ['timestamp null', { updatedAt: null }],
];
for (const [name, changes] of invalidChanges) {
  for (const operation of ['create', 'update']) {
    test(`rejects ${name} on ${operation}`, async () => {
      if (operation === 'update') await seed();
      const ref = doc(ownerDb(), ownPath);
      await assertFails(operation === 'create'
        ? setDoc(ref, timetable(changes))
        : updateDoc(ref, { updatedAt: serverTimestamp(), ...changes }));
    });
  }
}
for (const key of ['schemaVersion', 'timeZone', 'patterns', 'updatedAt']) {
  test(`rejects missing ${key} on create`, async () => {
    const data = timetable();
    delete data[key];
    await assertFails(setDoc(doc(ownerDb(), ownPath), data));
  });
  test(`rejects removing ${key} on update`, async () => {
    await seed();
    await assertFails(updateDoc(doc(ownerDb(), ownPath), { updatedAt: serverTimestamp(), [key]: deleteField() }));
  });
}
test('rejects update that leaves the old timestamp unchanged', async () => {
  await seed();
  await assertFails(updateDoc(doc(ownerDb(), ownPath), { patterns: [] }));
});
