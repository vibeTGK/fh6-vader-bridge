package com.vaderbridge.physics.effects;

import com.vaderbridge.config.BridgeConfig;
import com.vaderbridge.model.ForzaTelemetry;
import com.vaderbridge.model.TriggerFeedback;
import com.vaderbridge.physics.FeedbackEffect;

/**
 * Изолированный эффект пробуксовки ведущих колес (RT).
 * Работает в диапазоне 25%..50% мощности, оставляя запас для удара КПП.
 */
public class TractionSlipEffect implements FeedbackEffect {

    private final BridgeConfig config;
    private boolean enabled = true;

    private static final float MIN_ACCEL_PEDAL = 0.05f;
    private static final long CHATTER_PERIOD_MS = 25;

    // Ограничиваем диапазон пробуксовки: от 60 до 130 (из 255)
    private static final int MIN_TRACTION_FORCE = 60;
    private static final int MAX_TRACTION_FORCE = 130;

    public TractionSlipEffect(BridgeConfig config) {
        this.config = config;
    }

    @Override
    public String getName() {
        return "Traction";
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
        if (data.accelPedal() <= MIN_ACCEL_PEDAL) {
            return TriggerFeedback.SILENT;
        }

        float driveSlip;
        if (data.drivetrainType() == 0) {
            driveSlip = Math.max(data.slipRatioFL(), data.slipRatioFR());
        } else if (data.drivetrainType() == 1) {
            driveSlip = Math.max(data.slipRatioRL(), data.slipRatioRR());
        } else {
            driveSlip = Math.max(
                    Math.max(data.slipRatioFL(), data.slipRatioFR()),
                    Math.max(data.slipRatioRL(), data.slipRatioRR())
            );
        }

        if (driveSlip > config.tractionSlipThreshold()) {
            double depth = Math.min(1.0, (driveSlip - config.tractionSlipThreshold()) * 2.0);
            boolean chatter = ((nowMs / CHATTER_PERIOD_MS) % 2) == 0;

            // Масштабируем строго в диапазон [60..130]
            int baseForce = MIN_TRACTION_FORCE + (int) (depth * (MAX_TRACTION_FORCE - MIN_TRACTION_FORCE));
            int rtForce = chatter ? baseForce : (int) (baseForce * 0.40);

            return new TriggerFeedback(0, rtForce);
        }

        return TriggerFeedback.SILENT;
    }
}