package com.vaderbridge.model;

/**
 * Каноническая модель телеметрии Forza Horizon / Motorsport.
 * Содержит все физические параметры, извлекаемые из бинарного пакета UDP (Sled + Dash).
 */
public record ForzaTelemetry(
        // Статус заезда и временная метка
        boolean isRaceOn,
        long timestampMs,

        // Двигатель и динамика
        float engineMaxRpm,
        float engineIdleRpm,
        float currentEngineRpm,
        float speedKmh,

        // Органы управления (Dash: 0.0 .. 1.0)
        float accelPedal,       // Газ (RT)
        float brakePedal,       // Тормоз (LT)
        float clutchPedal,      // Сцепление
        boolean handbrake,      // Ручник
        int gear,               // Передача: 0=R, 1=N, 2=1st, 3=2nd...
        int drivetrainType,     // 0 = FWD, 1 = RWD, 2 = AWD
        int carOrdinal,         // Уникальный ID машины в базе игры

        // Продольное скольжение шин (TireSlipRatio, Offset 84..99)
        // > 0 = пробуксовка (газ), < 0 = блокировка юзом (ABS)
        float slipRatioFL,
        float slipRatioFR,
        float slipRatioRL,
        float slipRatioRR,

        // Боковой угол увода шин (TireSlipAngle, Offset 164..179)
        // FL/FR = снос передка (Understeer), RL/RR = занос кормы (Oversteer)
        float slipAngleFL,
        float slipAngleFR,
        float slipAngleRL,
        float slipAngleRR,

        // Нормализованный ход подвески (SuspensionTravel, Offset 68..83)
        // 0.0 = колесо вывешено в воздухе, 1.0 = амортизатор пробит до упора
        float suspTravelFL,
        float suspTravelFR,
        float suspTravelRL,
        float suspTravelRR,

        // Рельеф покрытия и бордюры
        float curbRumbleFL,     // Наезд на поребрик апекса (116)
        float curbRumbleFR,     // Наезд на поребрик апекса (120)
        float surfaceRumbleFL,  // Шероховатость (гравий, обочина) (148)
        float surfaceRumbleFR   // Шероховатость (гравий, обочина) (152)
) {}