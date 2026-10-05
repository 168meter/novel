import assert from 'node:assert/strict';
import {readFile} from 'node:fs/promises';
import test from 'node:test';
import vm from 'node:vm';

const resources = new URL('../../main/resources/', import.meta.url);
const read = path => readFile(new URL(path, resources), 'utf8');

async function loginEnvironment(originUrl, mobile = false, existingSession = false) {
    const handlers = new Map();
    const pending = [];
    const values = new Map([['#loginAccount', 'reader@example.invalid'], ['#loginPassword', 'example-password']]);
    const location = {origin: 'http://127.0.0.1:8083', pathname: '/user/login.html',
        search: originUrl == null ? '' : '?originUrl=' + encodeURIComponent(originUrl), href: '/user/login.html'};
    const jquery = selector => ({
        val(value) { if (value === undefined) return values.get(selector) || ''; values.set(selector, value); return this; },
        on(event, callback) { handlers.set(selector + ':' + event, callback); return this; },
        prop() { return this; }, is() { return false; }, keypress() {}, remove() {},
        html() { return this; }, text() { return this; }, attr() { return this; },
        empty() { return this; }, append() { return this; }
    });
    jquery.cookie = (name, value) => value === undefined && existingSession ? 'test-session' : null;
    jquery.trim = value => value.trim();
    jquery.each = (items, callback) => items.forEach((item, index) => callback(index, item));
    jquery.ajax = options => {
        const response = {code: 200, data: {token: 'test-session', nickName: 'Reader'}};
        if (options.success) pending.push(() => options.success(response));
        return {done(callback) { callback(response); return this; }, fail() { return this; },
            always(callback) { callback(); return this; }};
    };
    const context = vm.createContext({URL, window: {location}, location, document: {
        cookie: existingSession ? 'Authorization=test-session' : ''},
        $: jquery, jQuery: jquery, localStorage: {getItem: () => null, setItem() {}}, layer: {alert() {}}});
    vm.runInContext(await read(mobile ? 'static/mobile/js/common.js' : 'static/javascript/common.js'), context);
    vm.runInContext(await read('static/javascript/user.js'), context);
    pending.forEach(callback => callback());
    return {context, handlers, location};
}

const allowed = [
    ['/user/bookshelf.html', '/user/bookshelf.html'],
    ['/book/xxx.html?a=1#reading', '/book/xxx.html?a=1#reading'],
    ['http://127.0.0.1:8083/user/favorites.html?curr=2#books', '/user/favorites.html?curr=2#books'],
    ['user/favorites.html', '/user/favorites.html']
];
const rejected = [
    'https://evil.example.com/', 'http://evil.example.com/', '//evil.example.com/',
    'javascript:alert(1)', 'data:text/html,test', 'https://127.0.0.1:8083/',
    'http://127.0.0.1:8084/', '/\\evil.example.com/', 'http://127.0.0.1:8083//evil.example.com/',
    '\n//evil.example.com/', 'http://user:password@127.0.0.1:8083/', null
];

for (const mobile of [false, true]) {
    test(`${mobile ? 'mobile' : 'desktop'} successful login preserves same-origin destinations`, async () => {
        for (const [input, expected] of allowed) {
            const env = await loginEnvironment(input, mobile);
            env.context.AuthPage.initLogin();
            env.handlers.get('#btnLogin:click')();
            assert.equal(env.location.href, expected, input);
        }
    });
    test(`${mobile ? 'mobile' : 'desktop'} login rejects unsafe redirect destinations`, async () => {
        for (const input of rejected) {
            const env = await loginEnvironment(input, mobile);
            env.context.AuthPage.initLogin();
            env.handlers.get('#btnLogin:click')();
            assert.equal(env.location.href, '/', input);
        }
    });
    test(`${mobile ? 'mobile' : 'desktop'} existing-session login redirect uses the same safety policy`, async () => {
        for (const [input, expected] of [...allowed, ...rejected.map(input => [input, '/'])]) {
            const env = await loginEnvironment(input, mobile, true);
            assert.equal(env.location.href, expected, input);
        }
    });
}

async function searchEnvironment() {
    const html = await read('templates/book/bookclass.html');
    const script = [...html.matchAll(/<script\b[^>]*>([\s\S]*?)<\/script>/g)]
        .map(match => match[1]).find(body => body.includes('function search(curr, limit)'));
    const writes = new Map();
    const requests = [];
    const pagination = [];
    const jquery = selector => ({val: () => 'test keyword', attr: () => undefined,
        html: value => writes.set(selector, value)});
    jquery.ajax = request => requests.push(request);
    const context = vm.createContext({$: jquery, getSearchString: () => undefined,
        layui: {use(name, callback) { callback(); }, laypage: {render(options) { pagination.push(options); }}},
        console: {log() {}}, layer: {alert() {}}});
    vm.runInContext(script, context);
    return {writes, requests, pagination};
}

test('desktop zero search results show guidance and are replaced by later results', async () => {
    const env = await searchEnvironment();
    const request = env.requests.find(request => request.url === '/book/searchByPage');
    request.success({code: 200, data: {list: [], total: 0, pageNum: 1, pageSize: 20}});
    assert.match(env.writes.get('#bookList'), /没有找到相关书籍/);
    assert.match(env.writes.get('#bookList'), /其他关键词/);
    assert.match(env.writes.get('#bookList'), /colspan="6"/);
    request.success({code: 200, data: {list: [{id: '9007199254740993', bookName: 'Book',
        catId: 8, catName: 'Category', authorName: 'Author', wordCount: 10000, lastIndexName: 'Chapter'}],
        total: 1, pageNum: 1, pageSize: 20}});
    assert.match(env.writes.get('#bookList'), /\/book\/9007199254740993\.html/);
    assert.doesNotMatch(env.writes.get('#bookList'), /没有找到相关书籍/);
});

test('desktop search pagination preserves the keyword and requested page', async () => {
    const env = await searchEnvironment();
    env.requests.find(request => request.url === '/book/searchByPage')
        .success({code: 200, data: {list: [], total: 40, pageNum: 1, pageSize: 20}});
    env.pagination[0].jump({curr: 2, limit: 20}, false);
    const request = env.requests.at(-1);
    assert.equal(request.data.curr, 2);
    assert.equal(request.data.limit, 20);
    assert.equal(request.data.keyword, 'test keyword');
});

test('error page has readable guidance and an explicit home link without a forced redirect', async () => {
    const html = await read('templates/404.html');
    assert.match(html, /页面不存在/);
    assert.match(html, /返回首页/);
    assert.match(html, /href="\/"/);
    assert.doesNotMatch(html, /setTimeout|location\.href/);
});

test('green distribution contains the same user-state fixes as classpath resources', async () => {
    for (const [source, distribution] of [
        ['templates/404.html', 'html/404.html'],
        ['templates/book/bookclass.html', 'html/book/bookclass.html'],
        ['static/javascript/common.js', 'static/javascript/common.js'],
        ['static/javascript/user.js', 'static/javascript/user.js'],
        ['static/mobile/js/common.js', 'static/mobile/js/common.js']
    ]) {
        assert.equal(await read(source), await readFile(new URL('../../../../templates/green/' + distribution,
            import.meta.url), 'utf8'), distribution);
    }
});
