# 3. Context and scope

```mermaid
flowchart LR
  subgraph Users
    T[Traders] ; MO[Middle office / risk] ; BO[Back office / registry ops] ; AC[Accountants] ; AU[Auditors]
  end
  subgraph ETRM[GoO / EU ETS ETRM]
    API[REST API + modules]
  end
  REG[GoO registries<br/>Fingrid, Cesar, NECS, Energinet, HKNR, CertiQ ... via AIB hub]
  UR[Union Registry<br/>EU ETS]
  GL[General ledger<br/>SAP or other]
  IDP[Identity provider<br/>OIDC]
  MD[Market data<br/>EEX / ICE / GoO platforms]
  T & MO & BO & AC & AU --> API
  API -- instructions, statements --> REG
  API -- instruction packs, statements --> UR
  API -- journal export --> GL
  API -- tokens --> IDP
  MD -. increment 3 .-> API
```

**In scope (increment 1)**: everything inside the ETRM box, the simulator / manual / file registry gateways, CSV
statement import, CSV GL export.

**Out of scope for now**: registry APIs, market data feeds, exchange and clearing connectivity, counterparty
confirmation matching platforms, web UI (increment 4), EMIR / MiFIR reporting.
