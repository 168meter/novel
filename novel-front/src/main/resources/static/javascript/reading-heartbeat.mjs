const DEFAULT_INTERVAL_MS = 30000;
const PAGE_VISIT_ID_PATTERN = /^[0-9a-f]{32}$/;
const POSITIVE_INTEGER_PATTERN = /^[1-9]\d*$/;

export function createReadingHeartbeat({
    documentRef,
    windowRef,
    fetchFn,
    setTimeoutFn,
    clearTimeoutFn,
    intervalMs = DEFAULT_INTERVAL_MS,
    payload
}) {
    let timerId = null;
    let sequence = 0;
    let started = false;
    let destroyed = false;

    const isActive = () =>
        documentRef.visibilityState === 'visible' && documentRef.hasFocus();

    const clearTimer = () => {
        if (timerId !== null) {
            clearTimeoutFn(timerId);
            timerId = null;
        }
    };

    const schedule = () => {
        if (!started || destroyed || timerId !== null || !isActive()) {
            return;
        }
        timerId = setTimeoutFn(onInterval, intervalMs);
    };

    const onActivityChange = () => {
        if (isActive()) {
            schedule();
        } else {
            clearTimer();
        }
    };

    const onBlur = () => {
        clearTimer();
    };

    const destroy = () => {
        if (destroyed) {
            return;
        }
        destroyed = true;
        clearTimer();
        documentRef.removeEventListener('visibilitychange', onActivityChange);
        windowRef.removeEventListener('focus', onActivityChange);
        windowRef.removeEventListener('blur', onBlur);
        windowRef.removeEventListener('pagehide', destroy);
    };

    function onInterval() {
        timerId = null;
        if (!started || destroyed || !isActive()) {
            return;
        }

        sequence += 1;
        try {
            fetchFn('/engagement/reading/heartbeat', {
                method: 'POST',
                credentials: 'same-origin',
                headers: {'Content-Type': 'application/json'},
                body: JSON.stringify({...payload, sequence})
            }).catch(() => {});
        } catch (ignored) {
            // A later full active interval remains eligible after a synchronous transport failure.
        }

        schedule();
    }

    const start = () => {
        if (started || destroyed) {
            return;
        }
        started = true;
        documentRef.addEventListener('visibilitychange', onActivityChange);
        windowRef.addEventListener('focus', onActivityChange);
        windowRef.addEventListener('blur', onBlur);
        windowRef.addEventListener('pagehide', destroy);
        schedule();
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

    const parsedBookId = Number(bookId);
    const parsedChapterId = Number(chapterId);
    if (!Number.isSafeInteger(parsedBookId) || !Number.isSafeInteger(parsedChapterId)) {
        return null;
    }

    return {bookId: parsedBookId, chapterId: parsedChapterId, pageVisitId};
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
        payload
    }).start();
}

if (typeof document !== 'undefined' && typeof window !== 'undefined') {
    bootstrap();
}
