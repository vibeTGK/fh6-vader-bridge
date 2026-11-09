package com.vaderbridge.model;

public record ForzaTelemetry(
        boolean isRaceOn,
        long timestampMs,

        float engineMaxRpm,
        float engineIdleRpm,
        float currentEngineRpm,
        float speedKmh,

        float accelPedal,
        float brakePedal,
        float clutchPedal,
        boolean handbrake,
        int gear,
        int drivetrainType,
        int carOrdinal,

        // Продольное скольжение
        float slipRatioFL,
        float slipRatioFR,
        float slipRatioRL,
        float slipRatioRR,

        // Боковой угол увода
        float slipAngleFL,
        float slipAngleFR,
        float slipAngleRL,
        float slipAngleRR,

        // Ход подвески
        float suspTravelFL,
        float suspTravelFR,
        float suspTravelRL,
        float suspTravelRR,

        // Рельеф
        float curbRumbleFL,
        float curbRumbleFR,
        float surfaceRumbleFL,
        float surfaceRumbleFR,

        // УГЛОВАЯ СКОРОСТЬ ВРАЩЕНИЯ КОЛЕС (рад/с, байты 100..115)
        float wheelSpeedFL,
        float wheelSpeedFR,
        float wheelSpeedRL,
        float wheelSpeedRR
) {}