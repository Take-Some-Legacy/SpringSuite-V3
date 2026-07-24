(() => {
    'use strict';

    const MIN_LIFETIME_MS = 1_000;
    const MAX_LIFETIME_MS = 120_000;
    const MIN_ROTATION_MS = 250;
    const MAX_ROTATION_MS = 60_000;
    const DEFAULT_LIFETIME_MS = 8_000;
    const DEFAULT_ROTATION_MS = 1_000;
    const DEFAULT_MAX_QUEUE_SIZE = 100;

    function clampInteger(value, fallback, minimum, maximum) {
        const number = Number(value);
        if (!Number.isFinite(number)) return fallback;
        return Math.min(maximum, Math.max(minimum, Math.round(number)));
    }

    class BrowserNotificationRotator {
        constructor(options = {}) {
            const root = typeof globalThis !== 'undefined' ? globalThis : window;
            const defaultFactory = (title, notificationOptions) => new root.Notification(title, notificationOptions);

            this.notificationFactory = options.notificationFactory || defaultFactory;
            this.setTimeoutFn = options.setTimeoutFn || root.setTimeout.bind(root);
            this.clearTimeoutFn = options.clearTimeoutFn || root.clearTimeout.bind(root);
            this.nowFn = options.nowFn || Date.now;
            this.lifetimeMs = clampInteger(
                options.lifetimeMs,
                DEFAULT_LIFETIME_MS,
                MIN_LIFETIME_MS,
                MAX_LIFETIME_MS
            );
            this.rotationMs = clampInteger(
                options.rotationMs,
                DEFAULT_ROTATION_MS,
                MIN_ROTATION_MS,
                MAX_ROTATION_MS
            );
            this.maxQueueSize = clampInteger(
                options.maxQueueSize,
                DEFAULT_MAX_QUEUE_SIZE,
                1,
                10_000
            );
            this.queue = [];
            this.pendingKeys = new Set();
            this.active = null;
            this.rotationTimer = null;
            this.lastRotationAt = null;
            this.disposed = false;
        }

        enqueue(item) {
            if (this.disposed || !item || typeof item !== 'object') return false;

            const title = String(item.title || '').trim();
            if (!title) return false;

            const options = item.options && typeof item.options === 'object'
                ? { ...item.options }
                : {};
            const key = String(item.key || options.tag || title);
            if (this.pendingKeys.has(key) || this.active?.key === key) return false;

            while (this.queue.length >= this.maxQueueSize) {
                const dropped = this.queue.shift();
                if (dropped) this.pendingKeys.delete(dropped.key);
            }

            const entry = {
                key,
                title,
                options,
                onClick: typeof item.onClick === 'function' ? item.onClick : null,
                onClose: typeof item.onClose === 'function' ? item.onClose : null,
                onError: typeof item.onError === 'function' ? item.onError : null
            };
            this.queue.push(entry);
            this.pendingKeys.add(key);
            this.scheduleRotation();
            return true;
        }

        setLifetimeMs(value) {
            this.lifetimeMs = clampInteger(value, this.lifetimeMs, MIN_LIFETIME_MS, MAX_LIFETIME_MS);
            this.rescheduleActiveLifetime();
            return this.lifetimeMs;
        }

        setRotationMs(value) {
            this.rotationMs = clampInteger(value, this.rotationMs, MIN_ROTATION_MS, MAX_ROTATION_MS);
            if (this.rotationTimer !== null) {
                this.clearTimeoutFn(this.rotationTimer);
                this.rotationTimer = null;
            }
            this.scheduleRotation();
            return this.rotationMs;
        }

        clear() {
            this.queue.length = 0;
            this.pendingKeys.clear();
            this.lastRotationAt = null;
            if (this.rotationTimer !== null) {
                this.clearTimeoutFn(this.rotationTimer);
                this.rotationTimer = null;
            }
            this.closeActive('clear');
        }

        dispose() {
            if (this.disposed) return;
            this.clear();
            this.disposed = true;
        }

        snapshot() {
            return Object.freeze({
                queued: this.queue.length,
                active: Boolean(this.active),
                lifetimeMs: this.lifetimeMs,
                rotationMs: this.rotationMs,
                maxQueueSize: this.maxQueueSize,
                disposed: this.disposed
            });
        }

        scheduleRotation() {
            if (this.disposed || this.rotationTimer !== null || this.queue.length === 0) return;

            if (this.lastRotationAt === null) {
                this.rotateOne();
                return;
            }

            const elapsed = Math.max(0, this.nowFn() - this.lastRotationAt);
            const delay = Math.max(0, this.rotationMs - elapsed);
            this.rotationTimer = this.setTimeoutFn(() => {
                this.rotationTimer = null;
                this.rotateOne();
            }, delay);
        }

        rotateOne() {
            if (this.disposed || this.queue.length === 0) return;

            const entry = this.queue.shift();
            this.pendingKeys.delete(entry.key);
            this.lastRotationAt = this.nowFn();
            this.closeActive('rotation');

            let notification;
            try {
                notification = this.notificationFactory(entry.title, entry.options);
            } catch (error) {
                entry.onError?.(error, entry);
                this.scheduleRotation();
                return;
            }

            const active = {
                key: entry.key,
                entry,
                notification,
                openedAt: this.nowFn(),
                closeTimer: null
            };
            this.active = active;

            notification.onclick = event => {
                entry.onClick?.(notification, event, entry);
            };
            notification.onclose = event => {
                if (this.active === active) {
                    if (active.closeTimer !== null) this.clearTimeoutFn(active.closeTimer);
                    this.active = null;
                }
                entry.onClose?.(event, entry);
            };

            active.closeTimer = this.setTimeoutFn(
                () => this.closeRecord(active, 'expired'),
                this.lifetimeMs
            );
            this.scheduleRotation();
        }

        rescheduleActiveLifetime() {
            const active = this.active;
            if (!active) return;
            if (active.closeTimer !== null) this.clearTimeoutFn(active.closeTimer);

            const elapsed = Math.max(0, this.nowFn() - active.openedAt);
            const remaining = this.lifetimeMs - elapsed;
            if (remaining <= 0) {
                this.closeRecord(active, 'lifetime-changed');
                return;
            }
            active.closeTimer = this.setTimeoutFn(
                () => this.closeRecord(active, 'expired'),
                remaining
            );
        }

        closeActive(reason) {
            if (this.active) this.closeRecord(this.active, reason);
        }

        closeRecord(active, reason) {
            if (!active) return;
            if (active.closeTimer !== null) {
                this.clearTimeoutFn(active.closeTimer);
                active.closeTimer = null;
            }
            if (this.active === active) this.active = null;
            try {
                active.notification.close();
            } catch (error) {
                active.entry.onError?.(error, { ...active.entry, reason });
            }
        }
    }

    const api = Object.freeze({
        BrowserNotificationRotator,
        DEFAULT_LIFETIME_MS,
        DEFAULT_ROTATION_MS
    });

    if (typeof module !== 'undefined' && module.exports) module.exports = api;
    if (typeof globalThis !== 'undefined') {
        globalThis.BrowserNotificationRotator = BrowserNotificationRotator;
        globalThis.BROWSER_NOTIFICATION_DEFAULT_LIFETIME_MS = DEFAULT_LIFETIME_MS;
        globalThis.BROWSER_NOTIFICATION_DEFAULT_ROTATION_MS = DEFAULT_ROTATION_MS;
    }
})();
