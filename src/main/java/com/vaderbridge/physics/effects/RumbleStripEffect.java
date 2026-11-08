package com.vaderbridge.physics.effects;

import com.vaderbridge.model.ForzaTelemetry;
import com.vaderbridge.model.TriggerFeedback;
import com.vaderbridge.physics.FeedbackEffect;

/**
 * Изолированный эффект обочины, травы, гравия и бордюров.
 * Использует дельту хода подвески для 100% детекта любых дорожных неровностей.
 */
public class RumbleStripEffect implements FeedbackEffect {

    private boolean enabled = true;

    // Предыдущие значения хода подвески для расчета динамического удара
    private float lastSuspFL = 0.0f;
    private float lastSuspFR = 0.0f;

    @Override
    public String getName() {
        return "RumbleStrip";
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
        // 1. Прямой сигнал от бордюров (если игра его передает)
        float directL = Math.max(data.curbRumbleFL(), data.surfaceRumbleFL());
        float directR = Math.max(data.curbRumbleFR(), data.surfaceRumbleFR());

        // 2. Динамический детект неровностей через колебания подвески (трава, грунт, кочки)
        float deltaFL = Math.abs(data.suspTravelFL() - lastSuspFL);
        float deltaFR = Math.abs(data.suspTravelFR() - lastSuspFR);

        lastSuspFL = data.suspTravelFL();
        lastSuspFR = data.suspTravelFR();

        // Работает только на скорости выше 10 км/ч
        float roadBumpL = (data.speedKmh() > 10.0f && deltaFL > 0.015f) ? Math.min(1.0f, deltaFL * 18.0f) : 0.0f;
        float roadBumpR = (data.speedKmh() > 10.0f && deltaFR > 0.015f) ? Math.min(1.0f, deltaFR * 18.0f) : 0.0f;

        float totalL = Math.max(directL, roadBumpL);
        float totalR = Math.max(directR, roadBumpR);

        int gripL = 0;
        int gripR = 0;

        if (totalL > 0.05f) {
            gripL = (int) (60 + Math.min(1.0f, totalL) * 120); // Диапазон 60..180
        }
        if (totalR > 0.05f) {
            gripR = (int) (60 + Math.min(1.0f, totalR) * 120);
        }

        if (gripL > 0 || gripR > 0) {
            return new TriggerFeedback(gripL, gripR);
        }

        return TriggerFeedback.SILENT;
    }

    @Override
    public void reset() {
        lastSuspFL = 0.0f;
        lastSuspFR = 0.0f;
    }
}