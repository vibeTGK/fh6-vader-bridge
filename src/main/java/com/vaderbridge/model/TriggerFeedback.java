package com.vaderbridge.model;

/**
 * Честная тактильная модель контроллера: Левая и Правая сторона.
 *
 * В XInput физически существует только два независимых канала:
 * - left:  Левая сторона (Тормоз / ABS / левая рукоятка)
 * - right: Правая сторона (Газ / Пробуксовка / отсечка / правая рукоятка)
 *
 * Диапазон мощности: 0..255.
 */
public record TriggerFeedback(int left, int right) {

    public static final TriggerFeedback SILENT = new TriggerFeedback(0, 0);

    public TriggerFeedback {
        left = Math.max(0, Math.min(255, left));
        right = Math.max(0, Math.min(255, right));
    }
}