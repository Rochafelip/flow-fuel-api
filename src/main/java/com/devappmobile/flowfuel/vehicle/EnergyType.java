package com.devappmobile.flowfuel.vehicle;

public enum EnergyType {
    COMBUSTION(0, "Combustão"), 
    ELECTRIC(1, "Elétrico");
    
    private final int code;
    private final String description;
    
    EnergyType(int code, String description) {
        this.code = code;
        this.description = description;
    }
    
    public int getCode() { return code; }
    public String getDescription() { return description; }
    
    public static EnergyType fromCode(int code) {
        return code == 1 ? ELECTRIC : COMBUSTION;
    }
    
    public boolean isElectric() {
        return this == ELECTRIC;
    }
}