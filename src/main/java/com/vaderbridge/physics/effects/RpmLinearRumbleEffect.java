package com.vaderbridge.physics.effects;

import com.vaderbridge.model.ForzaTelemetry;
import com.vaderbridge.model.TriggerFeedback;
import com.vaderbridge.physics.FeedbackEffect;

/**
 * Честный линейный тахометр: зависит ИСКЛЮЧИТЕЛЬНО от оборотов двигателя.
 * Не зависит от педали газа (работает при торможении двигателем и перегазовках).
 */
public class RpmLinearRumbleEffect implements FeedbackEffect {

    private boolean enabled = true;
    private float smoothedRpmRatio = 0.0f;

    @Override
    public String getName() {
        return "RpmLinearRumble";
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
        float maxRpm = data.engineMaxRpm();
        float currentRpm = data.currentEngineRpm();
        float idleRpm = data.engineIdleRpm();

        // Если данных нет или обороты на холостых — моторы спят
        if (maxRpm <= 100.0f || currentRpm <= idleRpm) {
            smoothedRpmRatio = 0.0f;
            return TriggerFeedback.SILENT;
        }

        // Реальный рабочий предел отсечки для Forza (90.5% от теоретического maxRpm)
        float realRedline = maxRpm * 0.905f;

        // Расчет чистого процента тахометра от холостых (0.0) до красной зоны (1.0)
        float targetRatio = (currentRpm - idleRpm) / (realRedline - idleRpm);
        targetRatio = Math.max(0.0f, Math.min(1.0f, targetRatio));

        // Если обороты уперлись в красную зону (98%+) — фиксируем честные 100%
        if (targetRatio >= 0.96f) {
            targetRatio = 1.0f;
        }

        // Фильтр сглаживания (Exponential Moving Average):
        // Сглаживает скачки отсечки зажигания, устраняя рывки транзисторов мотора
        if (targetRatio >= smoothedRpmRatio) {
            // Быстрый отклик на набор оборотов (раскрутка)
            smoothedRpmRatio += (targetRatio - smoothedRpmRatio) * 0.40f;
        } else {
            // Естественный плавный спад оборотов мотора при отпускании газа
            smoothedRpmRatio += (targetRatio - smoothedRpmRatio) * 0.15f;
        }

        int finalGripForce = Math.round(smoothedRpmRatio * 255.0f);

        // Отправляем силу синхронно в обе рукоятки (Grip L и Grip R)
        return new TriggerFeedback(finalGripForce, finalGripForce);
    }

    @Override
    public void reset() {
        smoothedRpmRatio = 0.0f;
    }
}