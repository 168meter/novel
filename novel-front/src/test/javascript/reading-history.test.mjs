import assert from 'node:assert/strict';
import {readFile} from 'node:fs/promises';
import test from 'node:test';
import vm from 'node:vm';

const first = '9007199254740992';
const second = '9007199254740993';
const commonPath = new URL('../../main/resources/static/mobile/js/common.js', import.meta.url);
const templatePath = new URL('../../main/resources/templates/mobile/book/book_content.html', import.meta.url);

async function environment() {
    const values = new Map();
    let currentId = first;
    const jquery = () => ({val: () => currentId, keypress() {}});
    jquery.post = () => {};
    const context = vm.createContext({
        window: {location: {search: '', pathname: '/book/1/2.html'}}, document: {cookie: ''}, $: jquery, jQuery: jquery,
        localStorage: {getItem: key => values.get(key) ?? null, setItem: (key, value) => values.set(key, value)}
    });
    vm.runInContext(await readFile(commonPath, 'utf8'), context);
    const template = await readFile(templatePath, 'utf8');
    const script = [...template.matchAll(/<script\b[^>]*>([\s\S]*?)<\/script>/g)]
        .map(match => match[1]).find(body => body.includes('localStorage.getItem("historyBooks")'));
    assert.ok(script, 'reading page must record history');
    return {context, values, visit(id) { currentId = id; vm.runInContext(script.split('function toMyCollect')[0], context); }};
}

test('adjacent snowflake IDs survive actual template write and JSON round trip', async () => {
    const env = await environment();
    env.visit(first);
    env.visit(second);
    assert.deepEqual(JSON.parse(env.values.get('historyBooks')), [first, second]);
    env.visit(first);
    assert.deepEqual(JSON.parse(env.values.get('historyBooks')), [second, first]);
});

test('legacy safe numbers migrate but unsafe numbers are discarded without fake recovery', async () => {
    const env = await environment();
    env.values.set('historyBooks', JSON.stringify([42, Number(first), first, second, null, 'invalid']));
    env.visit('7');
    assert.deepEqual(JSON.parse(env.values.get('historyBooks')), ['42', first, second, '7']);
    env.context.readHistory();
    assert.equal(env.context.window.location.href, `/book/search?historyBookIds=42,${first},${second},7`);
});

test('malformed history resets safely and keeps at most fifty unique strings', async () => {
    const env = await environment();
    env.values.set('historyBooks', '{broken');
    env.visit(first);
    assert.deepEqual(JSON.parse(env.values.get('historyBooks')), [first]);
    for (let i = 1; i <= 55; i++) env.visit(String(i));
    const ids = JSON.parse(env.values.get('historyBooks'));
    assert.equal(ids.length, 50);
    assert.equal(ids[0], '6');
    assert.equal(ids.at(-1), '55');
});

test('history reader sanitizes legacy IDs before any new chapter is visited', async () => {
    const env = await environment();
    env.values.set('historyBooks', JSON.stringify([12, Number(second), first, second]));
    env.context.readHistory();
    assert.deepEqual(JSON.parse(env.values.get('historyBooks')), ['12', first, second]);
    assert.equal(env.context.window.location.href, `/book/search?historyBookIds=12,${first},${second}`);
});

test('green production history resources match classpath resources', async () => {
    assert.equal(await readFile(commonPath, 'utf8'), await readFile(
        new URL('../../../../templates/green/static/mobile/js/common.js', import.meta.url), 'utf8'));
    assert.equal(await readFile(templatePath, 'utf8'), await readFile(
        new URL('../../../../templates/green/html/mobile/book/book_content.html', import.meta.url), 'utf8'));
});
