package com.sidis.cardash;

public class VehicleState {
    public volatile int speedKmh = 0;
    public volatile int rpm = 0;
    public volatile int coolantC = 0;
    public volatile double voltage = 0.0;
    public volatile double fuelPercent = -1.0;
    public volatile double consumptionL100 = -1.0;
    public volatile int rangeKm = -1;
    public volatile boolean connected = false;
    public volatile String source = "DEMO";
}
