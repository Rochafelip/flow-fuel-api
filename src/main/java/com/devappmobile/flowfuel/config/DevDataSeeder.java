package com.devappmobile.flowfuel.config;

import com.devappmobile.flowfuel.user.User;
import com.devappmobile.flowfuel.user.UserRepository;
import com.devappmobile.flowfuel.vehicle.Vehicle;
import com.devappmobile.flowfuel.vehicle.VehicleRepository;
import com.devappmobile.flowfuel.vehicle.EnergyType;
import com.devappmobile.flowfuel.refuel.Refuel;
import com.devappmobile.flowfuel.refuel.RefuelRepository;
import com.devappmobile.flowfuel.refuel.RefuelType;
import java.math.BigDecimal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

@Component
@Profile("dev")
public class DevDataSeeder implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(DevDataSeeder.class);

    private static final String SEED_EMAIL = "dev@flowfuel.local";
    private static final String SEED_PASSWORD = "Dev@12345";
    private static final String SEED_NAME = "Dev User";

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final VehicleRepository vehicleRepository;
    private final RefuelRepository refuelRepository;

    public DevDataSeeder(UserRepository userRepository,
                         PasswordEncoder passwordEncoder,
                         VehicleRepository vehicleRepository,
                         RefuelRepository refuelRepository) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.vehicleRepository = vehicleRepository;
        this.refuelRepository = refuelRepository;
    }

    @Override
    public void run(String... args) {
        if (userRepository.existsByEmail(SEED_EMAIL)) {
            return;
        }
        User user = new User(SEED_EMAIL, passwordEncoder.encode(SEED_PASSWORD), SEED_NAME);
        userRepository.save(user);
        log.info("[DevDataSeeder] Usuario seed criado -> email={} senha={}", SEED_EMAIL, SEED_PASSWORD);

        // Criar um veículo de exemplo para o usuário de dev
        Vehicle vehicle = new Vehicle();
        vehicle.setType("Carro");
        vehicle.setEnergyType(EnergyType.COMBUSTION);
        vehicle.setFuelSubType("Gasolina");
        vehicle.setCapacity(50); // capacidade em litros
        vehicle.setCurrentKm(120000);
        vehicle.setBrand("Toyota");
        vehicle.setModel("Corolla");
        vehicle.setManufactureYear(2018);
        vehicle.setModelYear(2019);
        vehicle.setColor("Prata");
        vehicle.setLicensePlate("ABC1D23");
        vehicle.setUser(user);
        vehicle = vehicleRepository.save(vehicle);

        // Criar 3 abastecimentos coerentes
        Refuel r1 = new Refuel();
        r1.setOdometer(120500);
        r1.setKmSinceLastRefuel(500);
        r1.setEnergyAmount(new BigDecimal("50"));
        r1.setPricePerUnit(new BigDecimal("5.50"));
        r1.setFullTank(true);
        r1.setRefuelType(RefuelType.FUEL);
        r1.setVehicle(vehicle);
        refuelRepository.save(r1);

        Refuel r2 = new Refuel();
        r2.setOdometer(120800);
        r2.setKmSinceLastRefuel(300);
        r2.setEnergyAmount(new BigDecimal("30"));
        r2.setPricePerUnit(new BigDecimal("5.80"));
        r2.setFullTank(false);
        r2.setRefuelType(RefuelType.FUEL);
        r2.setVehicle(vehicle);
        refuelRepository.save(r2);

        Refuel r3 = new Refuel();
        r3.setOdometer(121300);
        r3.setKmSinceLastRefuel(500);
        r3.setEnergyAmount(new BigDecimal("50"));
        r3.setPricePerUnit(new BigDecimal("5.60"));
        r3.setFullTank(true);
        r3.setRefuelType(RefuelType.FUEL);
        r3.setVehicle(vehicle);
        refuelRepository.save(r3);
    }
}
