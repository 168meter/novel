import assert from 'node:assert/strict';
import {readFile} from 'node:fs/promises';
import test from 'node:test';

import {createReadingHeartbeat} from '../../main/resources/static/javascript/reading-heartbeat.mjs';

const PAYLOAD = {
    bookId: 42,
    chapterId: 99,
    pageVisitId: 'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa'
};
const SNOWFLAKE_BOOK_ID = '2055879962859147264';
const SNOWFLAKE_CHAPTER_ID = '2055880123456789012';

class FakeEventTarget {
    constructor() {
        this.listeners = new Map();
    }

    addEventListener(type, listener) {
        if (!this.listeners.has(type)) {
            this.listeners.set(type, new Set());
        }
        this.listeners.get(type).add(listener);
    }

    removeEventListener(type, listener) {
        this.listeners.get(type)?.delete(listener);
    }

    dispatch(type, event = {}) {
        for (const listener of [...(this.listeners.get(type) ?? [])]) {
            listener(event);
        }
    }

    listenerCount(type) {
        return this.listeners.get(type)?.size ?? 0;
    }
}

class FakeTimers {
    constructor() {
        this.nextId = 1;
        this.pending = new Map();
    }

    setTimeout = (callback, delay) => {
        const id = this.nextId++;
        this.pending.set(id, {callback, delay});
        return id;
    };

    clearTimeout = (id) => {
        this.pending.delete(id);
    };

    runNext() {
        const next = this.pending.entries().next();
        assert.equal(next.done, false, 'expected a pending timer');
        const [id, timer] = next.value;
        this.pending.delete(id);
        timer.callback();
    }

    pendingCount() {
        return this.pending.size;
    }

    pendingDelay() {
        const next = this.pending.values().next();
        return next.done ? undefined : next.value.delay;
    }
}

function createEnvironment({fetchFn = () => Promise.resolve()} = {}) {
    const windowRef = new FakeEventTarget();
    windowRef.focused = true;

    const documentRef = new FakeEventTarget();
    documentRef.visibilityState = 'visible';
    documentRef.hasFocus = () => windowRef.focused;
    documentRef.getElementById = () => null;

    const timers = new FakeTimers();
    return {documentRef, windowRef, timers, fetchFn};
}

function buildTracker(environment, overrides = {}) {
    return createReadingHeartbeat({
        documentRef: environment.documentRef,
        windowRef: environment.windowRef,
        fetchFn: environment.fetchFn,
        setTimeoutFn: environment.timers.setTimeout,
        clearTimeoutFn: environment.timers.clearTimeout,
        intervalMs: 30000,
        payload: PAYLOAD,
        ...overrides
    });
}

function heartbeatResponse(status, outcome, onRead = () => {}) {
    return {
        status,
        ok: status >= 200 && status < 300,
        json: async () => {
            onRead();
            return {data: {outcome}};
        }
    };
}

async function settleHeartbeatResponse() {
    await Promise.resolve();
    await Promise.resolve();
    await Promise.resolve();
}

test('waits for a complete active interval before posting the first sequence', () => {
    const fetchCalls = [];
    const environment = createEnvironment({
        fetchFn: (url, options) => {
            fetchCalls.push({url, options});
            return Promise.resolve();
        }
    });
    const tracker = buildTracker(environment);

    tracker.start();

    assert.equal(fetchCalls.length, 0);
    assert.equal(environment.timers.pendingDelay(), 30000);

    environment.timers.runNext();

    assert.equal(fetchCalls.length, 1);
    assert.equal(fetchCalls[0].url, '/engagement/reading/heartbeat');
    assert.equal(fetchCalls[0].options.method, 'POST');
    assert.equal(fetchCalls[0].options.credentials, 'same-origin');
    assert.deepEqual(fetchCalls[0].options.headers, {'Content-Type': 'application/json'});
    assert.deepEqual(JSON.parse(fetchCalls[0].options.body), {...PAYLOAD, sequence: 1});
    assert.equal(environment.timers.pendingDelay(), 30000);
});

test('discards a hidden partial interval and resumes with a new full interval', () => {
    const fetchCalls = [];
    const environment = createEnvironment({
        fetchFn: (url, options) => {
            fetchCalls.push({url, options});
            return Promise.resolve();
        }
    });
    const tracker = buildTracker(environment);
    tracker.start();
    environment.timers.runNext();

    environment.documentRef.visibilityState = 'hidden';
    environment.documentRef.dispatch('visibilitychange');

    assert.equal(environment.timers.pendingCount(), 0);

    environment.documentRef.visibilityState = 'visible';
    environment.windowRef.focused = true;
    environment.windowRef.dispatch('focus');

    assert.equal(environment.timers.pendingDelay(), 30000);
    environment.timers.runNext();
    assert.equal(fetchCalls.length, 2);
    assert.equal(JSON.parse(fetchCalls[1].options.body).sequence, 2);
});

test('blur clears the timer and focus restarts a complete interval', () => {
    const environment = createEnvironment();
    const tracker = buildTracker(environment);
    tracker.start();

    environment.windowRef.dispatch('blur');
    assert.equal(environment.timers.pendingCount(), 0);

    environment.windowRef.dispatch('focus');
    assert.equal(environment.timers.pendingDelay(), 30000);
});

test('a rejected request does not trigger an immediate retry', async () => {
    const fetchCalls = [];
    const environment = createEnvironment({
        fetchFn: (url, options) => {
            fetchCalls.push({url, options});
            return Promise.reject(new Error('network unavailable'));
        }
    });
    const tracker = buildTracker(environment);
    tracker.start();

    environment.timers.runNext();
    await Promise.resolve();

    assert.equal(fetchCalls.length, 1);
    assert.equal(environment.timers.pendingCount(), 1);
    assert.equal(environment.timers.pendingDelay(), 30000);
});

test('accepted and non-credit outcomes are read and continue on the normal interval', async () => {
    const cases = [
        [200, 'ACCEPTED'],
        [200, 'TOO_EARLY'],
        [429, 'RATE_LIMIT'],
        [503, 'REDIS_ERROR']
    ];

    for (const [status, outcome] of cases) {
        let responseRead = false;
        const environment = createEnvironment({
            fetchFn: () => Promise.resolve(heartbeatResponse(status, outcome, () => {
                responseRead = true;
            }))
        });
        buildTracker(environment).start();

        environment.timers.runNext();
        await settleHeartbeatResponse();

        assert.equal(responseRead, true, `${outcome} response should be read`);
        assert.equal(environment.timers.pendingCount(), 1);
        assert.equal(environment.timers.pendingDelay(), 30000);
    }
});

test('invalid page response stops the current tracker without refreshing credentials', async () => {
    const diagnostics = [];
    const environment = createEnvironment({
        fetchFn: () => Promise.resolve(heartbeatResponse(409, 'INVALID_PAGE'))
    });
    const tracker = buildTracker(environment, {
        onDiagnostic: state => diagnostics.push(state)
    });
    tracker.start();

    environment.timers.runNext();
    await settleHeartbeatResponse();

    assert.deepEqual(diagnostics, ['INVALID_PAGE']);
    assert.equal(environment.timers.pendingCount(), 0);
    assert.equal(environment.documentRef.listenerCount('visibilitychange'), 0);
    assert.equal(environment.windowRef.listenerCount('focus'), 0);
    assert.equal(environment.windowRef.listenerCount('blur'), 0);
    assert.equal(environment.windowRef.listenerCount('pagehide'), 0);
    assert.equal(environment.windowRef.listenerCount('pageshow'), 0);
});

test('invalid page still stops when diagnostic reporting fails', async () => {
    const environment = createEnvironment({
        fetchFn: () => Promise.resolve(heartbeatResponse(409, 'INVALID_PAGE'))
    });
    buildTracker(environment, {
        onDiagnostic: () => {
            throw new Error('diagnostic unavailable');
        }
    }).start();

    environment.timers.runNext();
    await settleHeartbeatResponse();

    assert.equal(environment.timers.pendingCount(), 0);
    assert.equal(environment.documentRef.listenerCount('visibilitychange'), 0);
    assert.equal(environment.windowRef.listenerCount('focus'), 0);
    assert.equal(environment.windowRef.listenerCount('blur'), 0);
    assert.equal(environment.windowRef.listenerCount('pagehide'), 0);
    assert.equal(environment.windowRef.listenerCount('pageshow'), 0);
});

test('destroy removes every listener and pending timer', () => {
    const environment = createEnvironment();
    const tracker = buildTracker(environment);
    tracker.start();

    tracker.destroy();

    assert.equal(environment.timers.pendingCount(), 0);
    assert.equal(environment.documentRef.listenerCount('visibilitychange'), 0);
    assert.equal(environment.windowRef.listenerCount('focus'), 0);
    assert.equal(environment.windowRef.listenerCount('blur'), 0);
    assert.equal(environment.windowRef.listenerCount('pagehide'), 0);
    assert.equal(environment.windowRef.listenerCount('pageshow'), 0);
});

test('pagehide suspends the tracker without sending partial credit', () => {
    const fetchCalls = [];
    const environment = createEnvironment({
        fetchFn: (url, options) => {
            fetchCalls.push({url, options});
            return Promise.resolve();
        }
    });
    const tracker = buildTracker(environment);
    tracker.start();

    environment.windowRef.dispatch('pagehide', {persisted: true});

    assert.equal(fetchCalls.length, 0);
    assert.equal(environment.timers.pendingCount(), 0);
    assert.equal(environment.windowRef.listenerCount('pagehide'), 1);
    assert.equal(environment.windowRef.listenerCount('pageshow'), 1);
});

test('persisted pageshow resumes after a full interval and preserves sequence', () => {
    const fetchCalls = [];
    const environment = createEnvironment({
        fetchFn: (url, options) => {
            fetchCalls.push({url, options});
            return Promise.resolve();
        }
    });
    const tracker = buildTracker(environment);
    tracker.start();
    environment.timers.runNext();

    environment.windowRef.dispatch('pagehide', {persisted: true});
    environment.windowRef.dispatch('pageshow', {persisted: true});

    assert.equal(fetchCalls.length, 1);
    assert.equal(environment.timers.pendingCount(), 1);
    assert.equal(environment.timers.pendingDelay(), 30000);
    environment.timers.runNext();
    assert.equal(JSON.parse(fetchCalls[1].options.body).sequence, 2);
});

test('non-persisted pageshow and repeated start do not duplicate timers or listeners', () => {
    const environment = createEnvironment();
    const tracker = buildTracker(environment);
    tracker.start();
    tracker.start();
    environment.windowRef.dispatch('pageshow', {persisted: false});

    assert.equal(environment.timers.pendingCount(), 1);
    assert.equal(environment.documentRef.listenerCount('visibilitychange'), 1);
    assert.equal(environment.windowRef.listenerCount('focus'), 1);
    assert.equal(environment.windowRef.listenerCount('blur'), 1);
    assert.equal(environment.windowRef.listenerCount('pagehide'), 1);
    assert.equal(environment.windowRef.listenerCount('pageshow'), 1);
});

test('repeated BFCache restores keep one timer and continue sequence', () => {
    const fetchCalls = [];
    const environment = createEnvironment({
        fetchFn: (url, options) => {
            fetchCalls.push({url, options});
            return Promise.resolve();
        }
    });
    buildTracker(environment).start();

    for (let cycle = 1; cycle <= 3; cycle++) {
        environment.windowRef.dispatch('pagehide', {persisted: true});
        environment.windowRef.dispatch('pageshow', {persisted: true});
        environment.windowRef.dispatch('focus');
        assert.equal(environment.timers.pendingCount(), 1);
        environment.timers.runNext();
        assert.equal(JSON.parse(fetchCalls.at(-1).options.body).sequence, cycle);
    }
});

test('focus visibility and BFCache transitions share one timer', () => {
    const fetchCalls = [];
    const environment = createEnvironment({
        fetchFn: (url, options) => {
            fetchCalls.push({url, options});
            return Promise.resolve();
        }
    });
    buildTracker(environment).start();

    environment.windowRef.dispatch('focus');
    environment.windowRef.focused = false;
    environment.windowRef.dispatch('blur');
    environment.windowRef.focused = true;
    environment.windowRef.dispatch('focus');
    environment.documentRef.visibilityState = 'hidden';
    environment.documentRef.dispatch('visibilitychange');
    environment.documentRef.visibilityState = 'visible';
    environment.documentRef.dispatch('visibilitychange');
    environment.windowRef.dispatch('pagehide', {persisted: true});
    environment.windowRef.dispatch('pageshow', {persisted: true});

    assert.equal(environment.timers.pendingCount(), 1);
    assert.equal(environment.timers.pendingDelay(), 30000);
    environment.timers.runNext();
    assert.equal(fetchCalls.length, 1);
    assert.equal(JSON.parse(fetchCalls[0].options.body).sequence, 1);
});

test('persisted restore while hidden waits until visible and focused', () => {
    const environment = createEnvironment();
    buildTracker(environment).start();

    environment.windowRef.dispatch('pagehide', {persisted: true});
    environment.documentRef.visibilityState = 'hidden';
    environment.windowRef.focused = false;
    environment.windowRef.dispatch('pageshow', {persisted: true});
    assert.equal(environment.timers.pendingCount(), 0);

    environment.documentRef.visibilityState = 'visible';
    environment.documentRef.dispatch('visibilitychange');
    assert.equal(environment.timers.pendingCount(), 0);

    environment.windowRef.focused = true;
    environment.windowRef.dispatch('focus');
    assert.equal(environment.timers.pendingCount(), 1);
    assert.equal(environment.timers.pendingDelay(), 30000);
});

test('INVALID_PAGE remains stopped after BFCache and activity events', async () => {
    const fetchCalls = [];
    const environment = createEnvironment({
        fetchFn: (url, options) => {
            fetchCalls.push({url, options});
            return Promise.resolve(heartbeatResponse(409, 'INVALID_PAGE'));
        }
    });
    buildTracker(environment).start();
    environment.timers.runNext();
    await settleHeartbeatResponse();

    environment.windowRef.dispatch('pageshow', {persisted: true});
    environment.windowRef.dispatch('focus');
    environment.documentRef.dispatch('visibilitychange');

    assert.equal(fetchCalls.length, 1);
    assert.equal(environment.timers.pendingCount(), 0);
});

test('does not schedule while the page is inactive', () => {
    const environment = createEnvironment();
    environment.documentRef.visibilityState = 'hidden';
    const tracker = buildTracker(environment);

    tracker.start();

    assert.equal(environment.timers.pendingCount(), 0);
});

test('automatic bootstrap preserves snowflake ids as strings', async () => {
    const fetchCalls = [];
    const environment = createEnvironment({
        fetchFn: (url, options) => {
            fetchCalls.push({url, options});
            return Promise.resolve();
        }
    });
    environment.documentRef.getElementById = (id) => id === 'reading-engagement' ? {
        dataset: {
            bookId: SNOWFLAKE_BOOK_ID,
            chapterId: SNOWFLAKE_CHAPTER_ID,
            pageVisitId: '0123456789abcdef0123456789abcdef'
        }
    } : null;

    await withBrowserGlobals(environment, () =>
        import(`../../main/resources/static/javascript/reading-heartbeat.mjs?bootstrap-snowflake=${Date.now()}`));

    assert.equal(environment.timers.pendingDelay(), 10000);
    environment.timers.runNext();
    assert.equal(fetchCalls.length, 1);
    const body = JSON.parse(fetchCalls[0].options.body);
    assert.equal(body.bookId, SNOWFLAKE_BOOK_ID);
    assert.equal(body.chapterId, SNOWFLAKE_CHAPTER_ID);
    assert.equal(typeof body.bookId, 'string');
    assert.equal(typeof body.chapterId, 'string');
});

test('production external heartbeat asset matches the canonical module', async () => {
    const canonical = await readFile(new URL(
        '../../main/resources/static/javascript/reading-heartbeat.mjs', import.meta.url), 'utf8');
    const production = await readFile(new URL(
        '../../../../templates/green/static/javascript/reading-heartbeat.mjs', import.meta.url), 'utf8');

    assert.equal(production, canonical);
});

test('automatic bootstrap stays silent for absent or invalid data', async () => {
    const datasets = [
        null,
        {bookId: '42', chapterId: '99'},
        {bookId: '0', chapterId: '99', pageVisitId: '0123456789abcdef0123456789abcdef'},
        {bookId: '42', chapterId: 'not-a-number', pageVisitId: '0123456789abcdef0123456789abcdef'},
        {bookId: '42', chapterId: '99', pageVisitId: 'not-a-valid-token'}
    ];

    for (const [index, dataset] of datasets.entries()) {
        const environment = createEnvironment();
        environment.documentRef.getElementById = () => dataset === null ? null : {dataset};

        await withBrowserGlobals(environment, () =>
            import(`../../main/resources/static/javascript/reading-heartbeat.mjs?bootstrap-invalid=${index}-${Date.now()}`));

        assert.equal(environment.timers.pendingCount(), 0);
    }
});

async function withBrowserGlobals(environment, action) {
    const previous = {
        document: globalThis.document,
        window: globalThis.window,
        fetch: globalThis.fetch,
        setTimeout: globalThis.setTimeout,
        clearTimeout: globalThis.clearTimeout
    };
    globalThis.document = environment.documentRef;
    globalThis.window = environment.windowRef;
    globalThis.fetch = environment.fetchFn;
    globalThis.setTimeout = environment.timers.setTimeout;
    globalThis.clearTimeout = environment.timers.clearTimeout;
    try {
        await action();
    } finally {
        restoreGlobal('document', previous.document);
        restoreGlobal('window', previous.window);
        restoreGlobal('fetch', previous.fetch);
        restoreGlobal('setTimeout', previous.setTimeout);
        restoreGlobal('clearTimeout', previous.clearTimeout);
    }
}

function restoreGlobal(name, value) {
    if (value === undefined) {
        delete globalThis[name];
    } else {
        globalThis[name] = value;
    }
}
