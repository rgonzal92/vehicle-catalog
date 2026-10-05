-- The broad classes of vehicle. A fixed list with no screen.
CREATE TABLE vehicle_type (
    code text PRIMARY KEY,
    name text NOT NULL
);

INSERT INTO vehicle_type (code, name) VALUES
    ('CAR', 'Car'),
    ('SUV', 'SUV'),
    ('TRUCK', 'Truck'),
    ('VAN', 'Van');

-- The vehicle systems a feature can belong to. A fixed list with no screen.
CREATE TABLE category (
    code       text PRIMARY KEY,
    name       text NOT NULL,
    sort_order int  NOT NULL
);

INSERT INTO category (code, name, sort_order) VALUES
    ('POWERTRAIN', 'Powertrain', 1),
    ('CHASSIS', 'Chassis', 2),
    ('STEERING', 'Steering', 3),
    ('BRAKING', 'Braking', 4),
    ('WHEELS_TIRES', 'Wheels and tires', 5),
    ('EXTERIOR', 'Exterior', 6),
    ('INTERIOR', 'Interior', 7),
    ('ELECTRICAL', 'Electrical', 8),
    ('CLIMATE', 'Climate', 9),
    ('SAFETY_ADAS', 'Safety and driver assistance', 10),
    ('INFOTAINMENT', 'Infotainment', 11),
    ('THERMAL', 'Thermal', 12),
    ('PACKAGES', 'Packages', 13);

-- A product sold across model years. Never deleted, only deactivated.
CREATE TABLE vehicle_line (
    id                bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    code              text    NOT NULL UNIQUE,
    name              text    NOT NULL UNIQUE,
    vehicle_type_code text    NOT NULL REFERENCES vehicle_type (code),
    active            boolean NOT NULL DEFAULT true
);
