// CloudFront runs app_routes.js on its own, without modules, so the test reads the file as CloudFront does.
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { test } from 'node:test';

const source = readFileSync(new URL('./app_routes.js', import.meta.url), 'utf8');
const handler = new Function(`${source}; return handler;`)();
const asked = (uri) => handler({ request: { uri, method: 'GET', headers: {} } }).uri;

test('a route of the app is answered with the page of the app', () => {
  assert.equal(asked('/dashboard'), '/index.html');
  assert.equal(asked('/catalogs/12'), '/index.html');
  assert.equal(asked('/admin/library/features'), '/index.html');
  assert.equal(asked('/'), '/index.html');
});

test('a file is asked for as it is, so that one that is missing stays missing', () => {
  assert.equal(asked('/main-HYQI2XLO.js'), '/main-HYQI2XLO.js');
  assert.equal(asked('/favicon.ico'), '/favicon.ico');
  assert.equal(asked('/media/missing.png'), '/media/missing.png');
  assert.equal(asked('/index.html'), '/index.html');
});

test('only the last part of the path says whether it is a file', () => {
  assert.equal(asked('/v1.2/dashboard'), '/index.html');
});

test('the rest of the request is passed on untouched', () => {
  const request = {
    uri: '/dashboard',
    method: 'GET',
    querystring: { tab: { value: 'history' } },
    headers: { accept: { value: 'text/html' } },
  };
  const answered = handler({ request: structuredClone(request) });
  assert.deepEqual(answered, { ...request, uri: '/index.html' });
});
