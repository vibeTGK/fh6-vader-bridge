package com.vaderbridge.physics.effects;

import com.vaderbridge.model.ForzaTelemetry;
import com.vaderbridge.model.TriggerFeedback;
import com.vaderbridge.physics.FeedbackEffect;

/**
 * Прецизионный эффект высоких оборотов и отсечки (RevLimiter) под аппаратный поток 500 Гц (2 мс):
 * 1. Зеленая зона (< 83%): тишина, руки отдыхают.
 * 2. Красная зона (83% .. 91.5%): чистое квадратичное нарастание перед переключением (правый 20..75, левый 0..25).
 * 3. Отсечка (>= 91.5% при газе):
 *    - Замок гистерезиса удерживает режим при сбросе зажигания игрой.
 *    - Синхронная двухмоторная пульсация (~22.7 Гц):
 *      Пик (26 мс): правый 255 (максимум), левый 120 (мощный бас).
 *      Спад (18 мс): правый 50, левый 35 (упругий подпор без провала в кашу).
 */
public class RevLimiterEffect implements FeedbackEffect {

    private boolean enabled = true;
    private int lastCarOrdinal = -1;
    private boolean inCutoffLock = false;

    // Границы зон оборотов
    private static final float RAMP_START_RATIO = 0.83f;   // Начало нарастания
    private static final float CUTOFF_ENTER     = 0.915f;  // Вход в отсечку
    private static final float CUTOFF_EXIT      = 0.860f;  // Выход из отсечки (гистерезис)

    // Тайминги пульсации отсечки под шину 500 Гц (шаг 2 мс):
    // 26 мс пик (13 тиков потока) / 18 мс спад (9 тиков потока) = цикл 44 мс (~22.7 Гц)
    private static final long STRIKE_DURATION_MS = 26;
    private static final long CYCLE_PERIOD_MS    = 44;

    @Override
    public String getName() {
        return "RevLimiter";
    }

    @Override
    public boolean isEnabled() {
        return enabled;
    }

    @Override
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    @Override
    public TriggerFeedback calculate(ForzaTelemetry data, long nowMs) {
        if (data.engineMaxRpm() <= 500.0f || data.currentEngineRpm() <= 0) {
            reset();
            return TriggerFeedback.SILENT;
        }

        // Автосброс при смене автомобиля
        if (lastCarOrdinal != -1 && data.carOrdinal() != lastCarOrdinal) {
            reset();
        }
        lastCarOrdinal = data.carOrdinal();

        // Если газ брошен — моментально снимаем отсечку
        if (data.accelPedal() < 0.15f) {
            inCutoffLock = false;
            return TriggerFeedback.SILENT;
        }

        float currentRpm = data.currentEngineRpm();
        float maxRpm = data.engineMaxRpm();
        float ratio = currentRpm / maxRpm;

        // Управление замком отсечки
        if (ratio >= CUTOFF_ENTER) {
            inCutoffLock = true;
        } else if (ratio < CUTOFF_EXIT) {
            inCutoffLock = false;
        }

        // 1. РЕЖИМ ЖЕСТКОЙ ОТСЕЧКИ (Активен замок)
        if (inCutoffLock) {
            long phaseInCycle = nowMs % CYCLE_PERIOD_MS;
            if (phaseInCycle < STRIKE_DURATION_MS) {
                // ПИК: Правый 255 (максимум скорости), левый 120 (удар в кузов)
                return new TriggerFeedback(120, 255);
            } else {
                // СПАД: Правый 50, левый 35 (сброс энергии, не дающий моторам заглохнуть)
                return new TriggerFeedback(35, 50);
            }
        }

        // 2. ДО КРАСНОЙ ЗОНЫ (< 83%) — тишина
        if (ratio < RAMP_START_RATIO) {
            return TriggerFeedback.SILENT;
        }

        // 3. ЗОНА НАРАСТАНИЯ (83% .. 91.5%): деликатное нарастание зуда
        float progress = (ratio - RAMP_START_RATIO) / (CUTOFF_ENTER - RAMP_START_RATIO);
        progress = Math.max(0.0f, Math.min(1.0f, progress));

        float curve = progress * progress;

        int rightPower = (int) (20 + curve * 55); // 20..75
        int leftPower  = (int) (curve * 25);       // 0..25

        return new TriggerFeedback(leftPower, rightPower);
    }

    @Override
    public void reset() {
        lastCarOrdinal = -1;
        inCutoffLock = false;
    }
}