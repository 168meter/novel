const DEFAULT_INTERVAL_MS = 10000;
const PAGE_VISIT_ID_PATTERN = /^[0-9a-f]{32}$/;
const POSITIVE_INTEGER_PATTERN = /^[1-9]\d*$/;

export function createReadingHeartbeat({
    documentRef,
    windowRef,
    fetchFn,
    setTimeoutFn,
    clearTimeoutFn,
    intervalMs = DEFAULT_INTERVAL_MS,
    payload,
    onDiagnostic = () => {}
}) {
    let timerId = null;
    let sequence = 0;
    let listenersRegistered = false;
    let pageSuspended = false;
    let state = 'suspended';

    const isActive = () =>
        documentRef.visibilityState === 'visible' && documentRef.hasFocus();

    const clearTimer = () => {
        if (timerId !== null) {
            clearTimeoutFn(timerId);
            timerId = null;
        }
    };

    const schedule = () => {
        if (!listenersRegistered || state !== 'active' || pageSuspended
            || timerId !== null || !isActive()) {
            return;
        }
        timerId = setTimeoutFn(onInterval, intervalMs);
    };

    const suspend = () => {
        if (state === 'stopped') {
            return;
        }
        state = 'suspended';
        clearTimer();
    };

    const reconcileActivity = () => {
        if (!listenersRegistered || state === 'stopped' || pageSuspended || !isActive()) {
            suspend();
        } else {
            state = 'active';
            schedule();
        }
    };

    const onActivityChange = () => {
        reconcileActivity();
    };

    const onBlur = () => {
        suspend();
    };

    const onPageHide = () => {
        if (state !== 'stopped') {
            pageSuspended = true;
            suspend();
        }
    };

    const onPageShow = event => {
        if (state === 'stopped' || event?.persisted !== true) {
            return;
        }
        pageSuspended = false;
        reconcileActivity();
    };

    const destroy = () => {
        if (state === 'stopped') {
            return;
        }
        state = 'stopped';
        pageSuspended = true;
        clearTimer();
        if (!listenersRegistered) {
            return;
        }
        listenersRegistered = false;
        documentRef.removeEventListener('visibilitychange', onActivityChange);
        windowRef.removeEventListener('focus', onActivityChange);
        windowRef.removeEventListener('blur', onBlur);
        windowRef.removeEventListener('pagehide', onPageHide);
        windowRef.removeEventListener('pageshow', onPageShow);
    };

    const handleResponse = async response => {
        if (!response || typeof response.json !== 'function') {
            return;
        }
        try {
            const body = await response.json();
            const outcome = body?.data?.outcome;
            if (outcome === 'INVALID_PAGE') {
                destroy();
                onDiagnostic('INVALID_PAGE');
            }
        } catch (ignored) {
            // Malformed or unexpected responses do not interrupt best-effort reading telemetry.
        }
    };

    function onInterval() {
        timerId = null;
        if (!listenersRegistered || state !== 'active' || pageSuspended || !isActive()) {
            reconcileActivity();
            return;
        }

        sequence += 1;
        try {
            Promise.resolve(fetchFn('/engagement/reading/heartbeat', {
                method: 'POST',
                credentials: 'same-origin',
                headers: {'Content-Type': 'application/json'},
                body: JSON.stringify({...payload, sequence})
            })).then(handleResponse).catch(() => {});
        } catch (ignored) {
            // A later full active interval remains eligible after a synchronous transport failure.
        }

        schedule();
    }

    const start = () => {
        if (listenersRegistered || state === 'stopped') {
            return;
        }
        listenersRegistered = true;
        pageSuspended = false;
        documentRef.addEventListener('visibilitychange', onActivityChange);
        windowRef.addEventListener('focus', onActivityChange);
        windowRef.addEventListener('blur', onBlur);
        windowRef.addEventListener('pagehide', onPageHide);
        windowRef.addEventListener('pageshow', onPageShow);
        reconcileActivity();
    };

    return {start, destroy};
}

function readBootstrapPayload(element) {
    const {bookId, chapterId, pageVisitId} = element.dataset ?? {};
    if (!POSITIVE_INTEGER_PATTERN.test(bookId)
        || !POSITIVE_INTEGER_PATTERN.test(chapterId)
        || !PAGE_VISIT_ID_PATTERN.test(pageVisitId)) {
        return null;
    }

    return {bookId, chapterId, pageVisitId};
}

function bootstrap() {
    const element = document.getElementById('reading-engagement');
    if (!element) {
        return;
    }

    const payload = readBootstrapPayload(element);
    if (!payload) {
        return;
    }

    createReadingHeartbeat({
        documentRef: document,
        windowRef: window,
        fetchFn: fetch,
        setTimeoutFn: setTimeout,
        clearTimeoutFn: clearTimeout,
        intervalMs: DEFAULT_INTERVAL_MS,
        payload,
        onDiagnostic: state => {
            element.dataset.heartbeatState = state;
        }
    }).start();
}

if (typeof document !== 'undefined' && typeof window !== 'undefined') {
    bootstrap();
}
