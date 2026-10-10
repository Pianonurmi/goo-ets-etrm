-- Seed taxonomies. Internal codes are stable system identifiers; external registry codes (AIB EECS Fact Sheet 5
-- energy-source / technology codes, national registry codes) are maintained in `external_codes` and must be
-- reconciled against the current AIB fact sheet and each registry's code list before production use.

CREATE OR REPLACE FUNCTION etrm_taxonomy_seed(p_scheme TEXT, p_code TEXT, p_parent TEXT, p_name TEXT, p_external JSONB DEFAULT '{}'::jsonb)
RETURNS VOID LANGUAGE plpgsql AS $$
DECLARE
    v_parent_id UUID;
    v_parent_path public.ltree;
    v_depth INT;
BEGIN
    IF p_parent IS NULL THEN
        v_parent_id := NULL;
        v_parent_path := NULL;
        v_depth := 0;
    ELSE
        SELECT id, path, depth + 1 INTO v_parent_id, v_parent_path, v_depth
        FROM taxonomy_node WHERE scheme = p_scheme AND code = p_parent;
        IF v_parent_id IS NULL THEN
            RAISE EXCEPTION 'Unknown parent % in scheme %', p_parent, p_scheme;
        END IF;
    END IF;
    INSERT INTO taxonomy_node (id, scheme, code, parent_id, path, depth, name, external_codes, valid_from)
    VALUES (
        gen_random_uuid(), p_scheme, p_code, v_parent_id,
        CASE WHEN v_parent_path IS NULL THEN p_code::public.ltree ELSE v_parent_path || p_code::public.ltree END,
        v_depth, p_name, p_external, DATE '2000-01-01'
    );
END $$;

-- ---------------------------------------------------------------------------------------------------------------
-- Energy sources (what was converted). Structure follows AIB EECS Fact Sheet 5.
-- ---------------------------------------------------------------------------------------------------------------
SELECT etrm_taxonomy_seed('ENERGY_SOURCE', 'RENEWABLE', NULL, 'Renewable');
SELECT etrm_taxonomy_seed('ENERGY_SOURCE', 'SOLAR', 'RENEWABLE', 'Solar');
SELECT etrm_taxonomy_seed('ENERGY_SOURCE', 'WIND', 'RENEWABLE', 'Wind');
SELECT etrm_taxonomy_seed('ENERGY_SOURCE', 'WIND_ONSHORE', 'WIND', 'Wind onshore');
SELECT etrm_taxonomy_seed('ENERGY_SOURCE', 'WIND_OFFSHORE', 'WIND', 'Wind offshore');
SELECT etrm_taxonomy_seed('ENERGY_SOURCE', 'HYDRO', 'RENEWABLE', 'Hydro');
SELECT etrm_taxonomy_seed('ENERGY_SOURCE', 'HYDRO_RUN_OF_RIVER', 'HYDRO', 'Hydro run-of-river');
SELECT etrm_taxonomy_seed('ENERGY_SOURCE', 'HYDRO_RESERVOIR', 'HYDRO', 'Hydro reservoir');
SELECT etrm_taxonomy_seed('ENERGY_SOURCE', 'HYDRO_PUMPED', 'HYDRO', 'Hydro pumped storage (natural inflow share)');
SELECT etrm_taxonomy_seed('ENERGY_SOURCE', 'MARINE', 'RENEWABLE', 'Marine (tidal, wave)');
SELECT etrm_taxonomy_seed('ENERGY_SOURCE', 'GEOTHERMAL', 'RENEWABLE', 'Geothermal');
SELECT etrm_taxonomy_seed('ENERGY_SOURCE', 'BIOMASS', 'RENEWABLE', 'Biomass');
SELECT etrm_taxonomy_seed('ENERGY_SOURCE', 'BIOMASS_SOLID', 'BIOMASS', 'Solid biomass');
SELECT etrm_taxonomy_seed('ENERGY_SOURCE', 'BIOMASS_SOLID_FOREST', 'BIOMASS_SOLID', 'Forest-based solid biomass');
SELECT etrm_taxonomy_seed('ENERGY_SOURCE', 'BIOMASS_SOLID_AGRI', 'BIOMASS_SOLID', 'Agricultural solid biomass');
SELECT etrm_taxonomy_seed('ENERGY_SOURCE', 'BIOMASS_SOLID_WASTE', 'BIOMASS_SOLID', 'Biodegradable fraction of waste');
SELECT etrm_taxonomy_seed('ENERGY_SOURCE', 'BIOMASS_LIQUID', 'BIOMASS', 'Liquid biofuel');
SELECT etrm_taxonomy_seed('ENERGY_SOURCE', 'BIOGAS', 'BIOMASS', 'Biogas');
SELECT etrm_taxonomy_seed('ENERGY_SOURCE', 'BIOGAS_LANDFILL', 'BIOGAS', 'Landfill gas');
SELECT etrm_taxonomy_seed('ENERGY_SOURCE', 'BIOGAS_SEWAGE', 'BIOGAS', 'Sewage gas');
SELECT etrm_taxonomy_seed('ENERGY_SOURCE', 'BIOGAS_AGRI', 'BIOGAS', 'Agricultural biogas');
SELECT etrm_taxonomy_seed('ENERGY_SOURCE', 'NON_RENEWABLE', NULL, 'Non-renewable');
SELECT etrm_taxonomy_seed('ENERGY_SOURCE', 'FOSSIL', 'NON_RENEWABLE', 'Fossil');
SELECT etrm_taxonomy_seed('ENERGY_SOURCE', 'FOSSIL_GAS', 'FOSSIL', 'Natural gas');
SELECT etrm_taxonomy_seed('ENERGY_SOURCE', 'FOSSIL_COAL', 'FOSSIL', 'Coal');
SELECT etrm_taxonomy_seed('ENERGY_SOURCE', 'FOSSIL_LIGNITE', 'FOSSIL', 'Lignite');
SELECT etrm_taxonomy_seed('ENERGY_SOURCE', 'FOSSIL_OIL', 'FOSSIL', 'Oil');
SELECT etrm_taxonomy_seed('ENERGY_SOURCE', 'FOSSIL_PEAT', 'FOSSIL', 'Peat');
SELECT etrm_taxonomy_seed('ENERGY_SOURCE', 'NUCLEAR', 'NON_RENEWABLE', 'Nuclear');
SELECT etrm_taxonomy_seed('ENERGY_SOURCE', 'WASTE_NON_RENEWABLE', 'NON_RENEWABLE', 'Non-biodegradable waste');

-- ---------------------------------------------------------------------------------------------------------------
-- Technologies (how it was converted).
-- ---------------------------------------------------------------------------------------------------------------
SELECT etrm_taxonomy_seed('TECHNOLOGY', 'SOLAR_PV', NULL, 'Photovoltaic');
SELECT etrm_taxonomy_seed('TECHNOLOGY', 'SOLAR_PV_ROOFTOP', 'SOLAR_PV', 'Rooftop photovoltaic');
SELECT etrm_taxonomy_seed('TECHNOLOGY', 'SOLAR_PV_GROUND', 'SOLAR_PV', 'Ground-mounted photovoltaic');
SELECT etrm_taxonomy_seed('TECHNOLOGY', 'SOLAR_THERMAL', NULL, 'Concentrated solar thermal');
SELECT etrm_taxonomy_seed('TECHNOLOGY', 'WIND_TURBINE', NULL, 'Wind turbine');
SELECT etrm_taxonomy_seed('TECHNOLOGY', 'WIND_TURBINE_ONSHORE', 'WIND_TURBINE', 'Onshore wind turbine');
SELECT etrm_taxonomy_seed('TECHNOLOGY', 'WIND_TURBINE_OFFSHORE', 'WIND_TURBINE', 'Offshore wind turbine');
SELECT etrm_taxonomy_seed('TECHNOLOGY', 'HYDRO_TURBINE', NULL, 'Hydro turbine');
SELECT etrm_taxonomy_seed('TECHNOLOGY', 'HYDRO_TURBINE_ROR', 'HYDRO_TURBINE', 'Run-of-river turbine');
SELECT etrm_taxonomy_seed('TECHNOLOGY', 'HYDRO_TURBINE_STORAGE', 'HYDRO_TURBINE', 'Storage / reservoir turbine');
SELECT etrm_taxonomy_seed('TECHNOLOGY', 'HYDRO_TURBINE_PUMPED', 'HYDRO_TURBINE', 'Pumped storage turbine');
SELECT etrm_taxonomy_seed('TECHNOLOGY', 'THERMAL', NULL, 'Thermal conversion');
SELECT etrm_taxonomy_seed('TECHNOLOGY', 'THERMAL_STEAM_TURBINE', 'THERMAL', 'Steam turbine');
SELECT etrm_taxonomy_seed('TECHNOLOGY', 'THERMAL_STEAM_TURBINE_CHP', 'THERMAL_STEAM_TURBINE', 'Steam turbine, combined heat and power');
SELECT etrm_taxonomy_seed('TECHNOLOGY', 'THERMAL_GAS_TURBINE', 'THERMAL', 'Gas turbine');
SELECT etrm_taxonomy_seed('TECHNOLOGY', 'THERMAL_CCGT', 'THERMAL', 'Combined cycle gas turbine');
SELECT etrm_taxonomy_seed('TECHNOLOGY', 'THERMAL_ENGINE', 'THERMAL', 'Internal combustion engine');
SELECT etrm_taxonomy_seed('TECHNOLOGY', 'THERMAL_FUEL_CELL', 'THERMAL', 'Fuel cell');
SELECT etrm_taxonomy_seed('TECHNOLOGY', 'NUCLEAR_REACTOR', NULL, 'Nuclear reactor');
SELECT etrm_taxonomy_seed('TECHNOLOGY', 'MARINE_TIDAL', NULL, 'Tidal');
SELECT etrm_taxonomy_seed('TECHNOLOGY', 'MARINE_WAVE', NULL, 'Wave');
SELECT etrm_taxonomy_seed('TECHNOLOGY', 'GEOTHERMAL_PLANT', NULL, 'Geothermal plant');

-- ---------------------------------------------------------------------------------------------------------------
-- Independent criteria schemes / labels that can be attached to certificates.
-- ---------------------------------------------------------------------------------------------------------------
SELECT etrm_taxonomy_seed('LABEL', 'EKOENERGY', NULL, 'EKOenergy', '{"scheme_owner": "EKOenergy Network"}'::jsonb);
SELECT etrm_taxonomy_seed('LABEL', 'BRA_MILJOVAL', NULL, 'Bra Miljöval', '{"scheme_owner": "Naturskyddsföreningen"}'::jsonb);
SELECT etrm_taxonomy_seed('LABEL', 'NATUREMADE_STAR', NULL, 'naturemade star', '{"scheme_owner": "VUE"}'::jsonb);
SELECT etrm_taxonomy_seed('LABEL', 'NATUREMADE_BASIC', NULL, 'naturemade basic', '{"scheme_owner": "VUE"}'::jsonb);
SELECT etrm_taxonomy_seed('LABEL', 'TUV_SUD_EE01', NULL, 'TÜV SÜD EE01', '{"scheme_owner": "TÜV SÜD"}'::jsonb);
SELECT etrm_taxonomy_seed('LABEL', 'TUV_SUD_EE02', NULL, 'TÜV SÜD EE02', '{"scheme_owner": "TÜV SÜD"}'::jsonb);
SELECT etrm_taxonomy_seed('LABEL', 'OK_POWER', NULL, 'ok-power', '{"scheme_owner": "EnergieVision e.V."}'::jsonb);
SELECT etrm_taxonomy_seed('LABEL', 'GRUENER_STROM', NULL, 'Grüner Strom Label', '{"scheme_owner": "Grüner Strom Label e.V."}'::jsonb);

-- ---------------------------------------------------------------------------------------------------------------
-- Support schemes (generic categories; country specific schemes are added through the API).
-- ---------------------------------------------------------------------------------------------------------------
SELECT etrm_taxonomy_seed('SUPPORT_SCHEME', 'INVESTMENT_SUPPORT', NULL, 'Investment support');
SELECT etrm_taxonomy_seed('SUPPORT_SCHEME', 'PRODUCTION_SUPPORT', NULL, 'Production support');
SELECT etrm_taxonomy_seed('SUPPORT_SCHEME', 'FEED_IN_TARIFF', 'PRODUCTION_SUPPORT', 'Feed-in tariff');
SELECT etrm_taxonomy_seed('SUPPORT_SCHEME', 'FEED_IN_PREMIUM', 'PRODUCTION_SUPPORT', 'Feed-in premium / contract for difference');
SELECT etrm_taxonomy_seed('SUPPORT_SCHEME', 'GREEN_CERTIFICATE_SCHEME', 'PRODUCTION_SUPPORT', 'Tradable green certificate scheme (e.g. el-cert)');

-- ---------------------------------------------------------------------------------------------------------------
-- Region sets over ISO 3166-1 alpha-2 countries.
-- ---------------------------------------------------------------------------------------------------------------
SELECT etrm_taxonomy_seed('REGION', 'EUROPE', NULL, 'Europe (all configured countries)');
SELECT etrm_taxonomy_seed('REGION', 'EU27', 'EUROPE', 'European Union');
SELECT etrm_taxonomy_seed('REGION', 'EEA', 'EUROPE', 'European Economic Area');
SELECT etrm_taxonomy_seed('REGION', 'NORDIC', 'EUROPE', 'Nordic countries');
SELECT etrm_taxonomy_seed('REGION', 'BALTIC', 'EUROPE', 'Baltic countries');
SELECT etrm_taxonomy_seed('REGION', 'CENTRAL_WESTERN_EUROPE', 'EUROPE', 'Central Western Europe');
SELECT etrm_taxonomy_seed('REGION', 'IBERIA', 'EUROPE', 'Iberian peninsula');
SELECT etrm_taxonomy_seed('REGION', 'AIB_HUB', 'EUROPE', 'AIB hub member domains (verify against current AIB membership)');

CREATE OR REPLACE FUNCTION etrm_region_seed(p_region TEXT, p_countries TEXT[])
RETURNS VOID LANGUAGE plpgsql AS $$
DECLARE
    v_id UUID;
BEGIN
    SELECT id INTO v_id FROM taxonomy_node WHERE scheme = 'REGION' AND code = p_region;
    INSERT INTO region_member (region_node_id, country)
    SELECT v_id, unnest(p_countries)
    ON CONFLICT DO NOTHING;
END $$;

SELECT etrm_region_seed('EU27', ARRAY['AT','BE','BG','HR','CY','CZ','DK','EE','FI','FR','DE','GR','HU','IE','IT','LV','LT','LU','MT','NL','PL','PT','RO','SK','SI','ES','SE']);
SELECT etrm_region_seed('EEA', ARRAY['AT','BE','BG','HR','CY','CZ','DK','EE','FI','FR','DE','GR','HU','IE','IT','LV','LT','LU','MT','NL','PL','PT','RO','SK','SI','ES','SE','IS','LI','NO']);
SELECT etrm_region_seed('NORDIC', ARRAY['FI','SE','NO','DK','IS']);
SELECT etrm_region_seed('BALTIC', ARRAY['EE','LV','LT']);
SELECT etrm_region_seed('CENTRAL_WESTERN_EUROPE', ARRAY['DE','FR','NL','BE','LU','AT','CH']);
SELECT etrm_region_seed('IBERIA', ARRAY['ES','PT']);
SELECT etrm_region_seed('AIB_HUB', ARRAY['AT','BE','HR','CY','CZ','DK','EE','FI','FR','DE','GR','IS','IE','IT','LV','LT','LU','NL','NO','PT','SK','SI','ES','SE','CH']);
SELECT etrm_region_seed('EUROPE', ARRAY['AT','BE','BG','HR','CY','CZ','DK','EE','FI','FR','DE','GR','HU','IE','IT','LV','LT','LU','MT','NL','PL','PT','RO','SK','SI','ES','SE','IS','LI','NO','CH','GB','RS','ME','MK','AL','BA','UA','MD','GE']);

DROP FUNCTION etrm_region_seed(TEXT, TEXT[]);
DROP FUNCTION etrm_taxonomy_seed(TEXT, TEXT, TEXT, TEXT, JSONB);
