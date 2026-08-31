package com.devappmobile.flowfuel.refuel;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RefuelConsumptionCalculatorTest {

    private Refuel refuel(int odometer, double energyAmount) {
        Refuel refuel = new Refuel();
        refuel.setOdometer(odometer);
        refuel.setEnergyAmount(BigDecimal.valueOf(energyAmount));
        refuel.setFullTank(true);
        refuel.setRefuelDate(LocalDateTime.now());
        return refuel;
    }

    @Test
    void calculateAverageConsumption_tresAbastecimentosTanqueCheio_aplicaFormulaOficial() {
        Refuel refuelC = refuel(3000, 40.0);
        Refuel refuelB = refuel(2200, 35.0);
        Refuel refuelA = refuel(1500, 30.0);

        Double result = RefuelConsumptionCalculator.calculateAverageConsumption(
                List.of(refuelC, refuelB, refuelA));

        assertThat(result).isEqualTo(20.0);
    }

    @Test
    void calculateAverageConsumption_menosDeDoisAbastecimentos_retornaZero() {
        Double result = RefuelConsumptionCalculator.calculateAverageConsumption(
                List.of(refuel(3000, 40.0)));

        assertThat(result).isEqualTo(0.0);
    }

    @Test
    void calculateAverageConsumption_semParesValidos_retornaZero() {
        Refuel refuelB = refuel(2000, 35.0);
        Refuel refuelA = refuel(2000, 30.0);

        Double result = RefuelConsumptionCalculator.calculateAverageConsumption(
                List.of(refuelB, refuelA));

        assertThat(result).isEqualTo(0.0);
    }
}
