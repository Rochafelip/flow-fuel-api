package com.devappmobile.flowfuel.vehicle;

public enum EnergyType {
    COMBUSTION(0, "Combustão"),
    ELECTRIC(1, "Elétrico"),
    HYBRID(2, "Híbrido");

    private final int code;
    private final String description;

    EnergyType(int code, String description) {
        this.code = code;
        this.description = description;
    }

    public int getCode() { return code; }
    public String getDescription() { return description; }

    public static EnergyType fromCode(int code) {
        for (EnergyType type : values()) {
            if (type.code == code) return type;
        }
        return COMBUSTION;
    }

    public boolean isElectric() {
        return this == ELECTRIC;
    }

    public boolean usesElectricityOnly() {
        return this == ELECTRIC;
    }

    public boolean usesFuel() {
        return this == COMBUSTION || this == HYBRID;
    }

    public String getEnergyUnit() {
        return this == ELECTRIC ? "kWh" : "litros";
    }

    public String getPriceUnit() {
        return this == ELECTRIC ? "R$/kWh" : "R$/litro";
    }

    public String getConsumptionUnit() {
        return this == ELECTRIC ? "km/kWh" : "km/L";
    }
}