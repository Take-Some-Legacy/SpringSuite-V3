'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const {
    BrowserNotificationRotator,
    DEFAULT_LIFETIME_MS,
    DEFAULT_ROTATION_MS
} = require('../../../../static/js/browser-notifications.js');

class FakeClock {
    constructor() {
        this.now = 0;
        this.sequence = 0;
        this.tasks = new Map();
    }

    setTimeout(callback, delay) {
        const id = ++this.sequence;
        this.tasks.set(id, { id, dueAt: this.now + Math.max(0, Number(delay) || 0), callback });
        return id;
    }

    clearTimeout(id) {
        this.tasks.delete(id);
    }

    advance(milliseconds) {
        const target = this.now + milliseconds;
        for (;;) {
            const next = [...this.tasks.values()]
                .filter(task => task.dueAt <= target)
                .sort((left, right) => left.dueAt - right.dueAt || left.id - right.id)[0];
            if (!next) break;
            this.tasks.delete(next.id);
            this.now = next.dueAt;
            next.callback();
        }
        this.now = target;
    }
}

class FakeNotification {
    constructor(title, options) {
        this.title = title;
        this.options = options;
        this.closed = false;
        this.onclick = null;
        this.onclose = null;
    }

    close() {
        if (this.closed) return;
        this.closed = true;
        this.onclose?.({ type: 'close' });
    }

    click() {
        this.onclick?.({ type: 'click' });
    }
}

function createHarness(options = {}) {
    const clock = new FakeClock();
    const notifications = [];
    const rotator = new BrowserNotificationRotator({
        notificationFactory: (title, notificationOptions) => {
            const notification = new FakeNotification(title, notificationOptions);
            notifications.push(notification);
            return notification;
        },
        setTimeoutFn: (callback, delay) => clock.setTimeout(callback, delay),
        clearTimeoutFn: id => clock.clearTimeout(id),
        nowFn: () => clock.now,
        ...options
    });
    return { clock, notifications, rotator };
}

function entry(id) {
    return {
        key: `request-${id}`,
        title: `GET /requests/${id}`,
        options: { tag: `request-${id}` }
    };
}

test('uses the configured defaults', () => {
    const { rotator } = createHarness();
    assert.equal(rotator.snapshot().lifetimeMs, DEFAULT_LIFETIME_MS);
    assert.equal(rotator.snapshot().rotationMs, DEFAULT_ROTATION_MS);
});

test('shows the first notification immediately and rotates at most once per second', () => {
    const { clock, notifications, rotator } = createHarness();

    assert.equal(rotator.enqueue(entry(1)), true);
    assert.equal(rotator.enqueue(entry(2)), true);
    assert.equal(rotator.enqueue(entry(3)), true);
    assert.equal(notifications.length, 1);
    assert.equal(notifications[0].title, 'GET /requests/1');

    clock.advance(999);
    assert.equal(notifications.length, 1);
    assert.equal(notifications[0].closed, false);

    clock.advance(1);
    assert.equal(notifications.length, 2);
    assert.equal(notifications[0].closed, true);
    assert.equal(notifications[1].title, 'GET /requests/2');

    clock.advance(1_000);
    assert.equal(notifications.length, 3);
    assert.equal(notifications[1].closed, true);
    assert.equal(notifications[2].title, 'GET /requests/3');
    assert.equal(rotator.snapshot().queued, 0);
    assert.equal(rotator.snapshot().active, true);
});

test('closes the active notification when its lifetime expires', () => {
    const { clock, notifications, rotator } = createHarness({ lifetimeMs: 8_000 });

    rotator.enqueue(entry(1));
    clock.advance(7_999);
    assert.equal(notifications[0].closed, false);

    clock.advance(1);
    assert.equal(notifications[0].closed, true);
    assert.equal(rotator.snapshot().active, false);
});

test('changing lifetime reschedules the active notification', () => {
    const { clock, notifications, rotator } = createHarness({ lifetimeMs: 8_000 });

    rotator.enqueue(entry(1));
    clock.advance(1_500);
    assert.equal(rotator.setLifetimeMs(2_000), 2_000);
    assert.equal(notifications[0].closed, false);

    clock.advance(499);
    assert.equal(notifications[0].closed, false);
    clock.advance(1);
    assert.equal(notifications[0].closed, true);
});

test('deduplicates active and queued request notifications', () => {
    const { notifications, rotator } = createHarness();

    assert.equal(rotator.enqueue(entry(1)), true);
    assert.equal(rotator.enqueue(entry(1)), false);
    assert.equal(rotator.enqueue(entry(2)), true);
    assert.equal(rotator.enqueue(entry(2)), false);
    assert.equal(notifications.length, 1);
    assert.equal(rotator.snapshot().queued, 1);
});

test('clear removes the queue, timer and active notification', () => {
    const { clock, notifications, rotator } = createHarness();

    rotator.enqueue(entry(1));
    rotator.enqueue(entry(2));
    rotator.clear();

    assert.equal(notifications[0].closed, true);
    assert.deepEqual(rotator.snapshot(), {
        queued: 0,
        active: false,
        lifetimeMs: DEFAULT_LIFETIME_MS,
        rotationMs: DEFAULT_ROTATION_MS,
        maxQueueSize: 100,
        disposed: false
    });

    clock.advance(60_000);
    assert.equal(notifications.length, 1);
});

test('click handler receives the live notification', () => {
    const { notifications, rotator } = createHarness();
    let clicked = null;

    rotator.enqueue({
        ...entry(1),
        onClick: notification => {
            clicked = notification;
            notification.close();
        }
    });
    notifications[0].click();

    assert.equal(clicked, notifications[0]);
    assert.equal(notifications[0].closed, true);
    assert.equal(rotator.snapshot().active, false);
});
