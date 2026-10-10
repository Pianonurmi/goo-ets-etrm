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
  CP[Counterparties<br/>confirmations, invoices]
  BANK[Banks<br/>statements, payments]
  UR[Union Registry<br/>EU ETS]
  GL[General ledger<br/>SAP or other]
  IDP[Identity provider<br/>OIDC]
  MD[Market data<br/>EEX / ICE / GoO platforms]
  T & MO & BO & AC & AU --> API
  API -- instructions, statements --> REG
  API -- instruction packs, statements --> UR
  API -- journal export --> GL
  API -- confirmations, invoices, netting statements (PDF / JSON / CSV) --> CP
  CP -- confirmations, supplier invoices --> API
  BANK -- statement CSV --> API
  API -- tokens --> IDP
  MD -. increment 3 .-> API
```

**In scope (increments 1 and 2)**:
- everything inside the ETRM box;
- registry gateways: simulator, manual (the Union Registry), file, and an API channel with ETRM's generic REST
  contract;
- statement formats as data;
- counterparty confirmations as JSON, CSV or manual entry;
- invoices, credit notes and netting statements as PDF, CSV and JSON;
- bank statement CSV;
- CSV GL export.

**Out of scope for now**:
- registry-specific API mappings (G-REX, NECS) until their specifications are available;
- market data feeds;
- exchange and clearing connectivity;
- EFETnet / CpML transport;
- e-invoicing (Peppol);
- bank payment files (pain.001, camt.053);
- the web UI (increment 4);
- EMIR / MiFIR reporting.
