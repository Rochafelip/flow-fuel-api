package com.devappmobile.flowfuel.refuel;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

public final class RefuelConsumptionCalculator {

    private RefuelConsumptionCalculator() {
    }

    /**
     * Fórmula oficial de consumo médio do produto.
     *
     * <p>Para cada par consecutivo de abastecimentos tanque-cheio ordenados do mais recente
     * ao mais antigo (current, previous), calcula:
     * <pre>
     *   kmDriven   = current.odometer - previous.odometer
     *   energyUsed = current.energyAmount
     * </pre>
     * O consumo final é {@code SUM(kmDriven) / SUM(energyUsed)} sobre todos os pares válidos
     * (kmDriven > 0 e energyUsed > 0), arredondado para 2 casas decimais (HALF_UP).
     *
     * <p>Exemplo: 3 abastecimentos [C(3000 km, 40 L), B(2200 km, 35 L), A(1500 km, 30 L)]
     * → pares: (C-B): 800 km / 40 L; (B-A): 700 km / 35 L
     * → consumo = (800+700) / (40+35) = 1500/75 = 20,00 km/L
     *
     * <p>Retorna 0.0 se houver menos de 2 abastecimentos tanque-cheio ou energia total zero.
     *
     * <p>Nota: o campo {@code kmSinceLastRefuel} persistido na entidade Refuel é deliberadamente
     * ignorado para garantir consistência com o odômetro registrado pelo usuário.
     */
    public static Double calculateAverageConsumption(List<Refuel> fullRefuels) {
        if (fullRefuels.size() < 2) {
            return 0.0;
        }

        double totalKm = 0;
        double totalEnergyUsed = 0;

        for (int i = 0; i < fullRefuels.size() - 1; i++) {
            Refuel current = fullRefuels.get(i);
            Refuel previous = fullRefuels.get(i + 1);

            double kmDriven = current.getOdometer() - previous.getOdometer();
            double energyUsed = current.getEnergyAmount().doubleValue();

            if (kmDriven > 0 && energyUsed > 0) {
                totalKm += kmDriven;
                totalEnergyUsed += energyUsed;
            }
        }

        if (totalEnergyUsed == 0) {
            return 0.0;
        }

        double consumption = totalKm / totalEnergyUsed;

        return BigDecimal.valueOf(consumption)
                .setScale(2, RoundingMode.HALF_UP)
                .doubleValue();
    }
}
