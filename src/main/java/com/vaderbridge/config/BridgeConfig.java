package com.vaderbridge.config;

/**
 * Централизованная конфигурация моста.
 * Позволяет независимо включать/выключать каждый эффект и настраивать его силу от 0 до 100%.
 */
public record BridgeConfig(
        int udpPort,
        double absSlipThreshold,
        double tractionSlipThreshold,
        // ABS (Тормоз)
        boolean absEnabled,
        int absPowerPercent,
        // Пробуксовка (Газ)
        boolean tractionEnabled,
        int tractionPowerPercent,
        // Поребрики
        boolean rumbleStripEnabled,
        int rumblePowerPercent,
        // Переключение передач
        boolean gearShiftKickEnabled,
        int gearShiftPowerPercent,
        // Отсечка оборотов
        boolean revLimiterEnabled,
        int revLimiterPowerPercent
) {
    public static BridgeConfig fromArgs(String[] args) {
        int port = 5301;
        double abs = 0.85;
        double traction = 0.90;

        // =========================================================================
        // НАСТРОЙКА ЭФФЕКТОВ: Включение (true/false) и Сила в процентах (0..100%)
        // =========================================================================
        boolean absOn = true;          int absPwr = 100;     // <-- ТЕСТИРУЕМ ABS
        boolean tractionOn = false;    int tractionPwr = 100;
        boolean rumbleOn = false;      int rumblePwr = 100;
        boolean shiftOn = false;       int shiftPwr = 100;
        boolean revLimiterOn = false;  int revPwr = 100;

        for (int i = 0; i < args.length; i++) {
            String arg = args[i].toLowerCase();
            if (arg.equals("--port") && i + 1 < args.length) {
                port = Integer.parseInt(args[++i]);
            } else if (arg.equals("--no-abs")) {
                absOn = false;
            } else if (arg.equals("--abs-power") && i + 1 < args.length) {
                absPwr = Math.max(0, Math.min(100, Integer.parseInt(args[++i])));
            }
        }

        return new BridgeConfig(
                port, abs, traction,
                absOn, absPwr,
                tractionOn, tractionPwr,
                rumbleOn, rumblePwr,
                shiftOn, shiftPwr,
                revLimiterOn, revPwr
        );
    }
}