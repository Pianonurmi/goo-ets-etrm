# GoO certificate attribute schema

A lot (`etrm_inventory.certificate_lot`) carries every EECS attribute shared by its certificates. Attributes that
criteria can query are promoted columns with indexes; everything else is JSONB.

| Group | Attribute | Column | Criteria attribute |
|---|---|---|---|
| Identity | issuance reference, certificate number range | `issuance_ref`, `cert_no_start`, `cert_no_end`, `cert_range` | – |
| Carrier and scheme | energy carrier, certificate scheme | `energy_carrier`, `certificate_scheme` | `ENERGY_CARRIER`, `CERTIFICATE_SCHEME` |
| Issuer | issuing body, country, registry, account | `issuing_body`, `issuing_country`, `registry_code`, `registry_account_id` | `ISSUING_BODY`, `ISSUING_COUNTRY`, `REGISTRY` |
| Device | GSRN, name, country, region, commissioning date, capacity | `device_gsrn`, `device_name`, `device_country`, `device_region`, `device_commissioning_date`, `device_capacity_kw` | `DEVICE_ID`, `DEVICE_COUNTRY`, `DEVICE_REGION`, `COMMISSIONING_DATE`, `DEVICE_CAPACITY_KW` |
| Taxonomy | energy source, technology (ltree paths) | `energy_source_path`, `technology_path` | `ENERGY_SOURCE`, `TECHNOLOGY` (descendant-of) |
| Time | production period (daterange), issue date, expiry date | `production_period`, `issue_date`, `expiry_date` | `PRODUCTION_PERIOD` (vintage), `ISSUE_DATE`, `EXPIRY_DATE` |
| Support | production support, investment support, scheme | `support_production`, `support_investment`, `support_scheme_code` | `SUPPORT_PRODUCTION`, `SUPPORT_INVESTMENT`, `SUPPORT_SCHEME` |
| Labels | independent criteria schemes | `labels text[]` | `LABELS` (any / all) |
| State | status, root and parent lot, reservation, instruction | `status`, `root_lot_id`, `parent_lot_id`, `reservation_ref`, `instruction_ref` | – |
| Cost | acquisition source and reference, date, cost basis per MWh | `acquisition_*`, `cost_basis_per_mwh`, `cost_currency` | – |
| Cancellation | purpose, beneficiary, consumption country and period, registry reference | `cancellation` (JSONB) | – |

**Expiry**: default production period end + 12 months (RED II art. 19(3)); registries may set an earlier date.

**Lifecycle**: `HELD ⇄ RESERVED → IN_TRANSFER → TRANSFERRED_OUT | HELD`; `HELD/RESERVED/IN_TRANSFER → CANCELLED`;
`HELD/RESERVED → EXPIRED`; `live → WITHDRAWN`; `HELD/RESERVED → SPLIT` (children created).

**Lot class**: lots sharing entity, account, device, production period, labels, support flags, scheme, expiry, cost
basis, energy source and technology. The allocation engine reasons about classes.

**Taxonomy codes**: internal codes (e.g. `RENEWABLE.WIND.WIND_ONSHORE`) with external code maps per registry. The
seeded hierarchy follows the structure of AIB EECS Fact Sheet 5 but the external AIB and registry codes must be
loaded and verified against the current fact sheet and registry code lists before production use.
