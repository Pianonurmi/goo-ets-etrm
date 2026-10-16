# Registry instructions

**Signal.** `EtrmRegistryDispatchLag`: instructions wait more than ten minutes to be sent.
`EtrmRegistryInstructionsUnknown`: an instruction's outcome is unknown (a timeout); it is never resent automatically
(ADR 0012), because the registry may have carried it out.

**Confirm.** Operations, Registry: filter by status `UNKNOWN` or `PENDING`. Each instruction shows its attempts, last
error and, in `registry_instruction_event`, every state it passed through.

**Do.**
- Lag: the dispatcher job (`registry-dispatch`) is not running or the registry is down; see
  [job failures](job-failures.md) and the gateway's last error.
- `UNKNOWN`: look the transfer up in the registry's own interface. Done there: **Confirm** it with the registry
  reference. Not done: **Fail** it, then **Retry** resubmits it (through approval again when unplanned instructions
  need one). Never retry before checking.

**Verify.** No instruction stays `UNKNOWN`; the lag gauge falls under a minute.

## A registry is paused

**Signal.** `EtrmRegistryPaused`: `etrm.registry.circuit.failures` (5) transport failures in a row to one registry
(connection refused, 408/429/503, timeouts) paused its dispatch. The registry screen shows it above the cards, and
`GET /api/v1/registry/circuits` lists it with the last failure and the next probe.

**Confirm.** The registry's status page or support desk; the gateway's last error in the circuit and in the logs
(`Registry … paused after …`).

**Do.**
- Nothing, while the registry is down: after `etrm.registry.circuit.cool-down` (5 min) the dispatcher sends one
  instruction as a probe, and the first answer resumes the rest. A probe that times out becomes `UNKNOWN` like any
  other attempt and is never resent (see above).
- When the registry is back sooner, **Resume** it on the registry screen (or
  `POST /api/v1/registry/circuits/{registryCode}/resume`, registry operator or back office with every legal entity).
- When the failures come from our side (credentials, a certificate, the egress policy), fix that first; resuming
  only pauses the registry again.

**Verify.** The circuit is gone from the list; the dispatch lag gauge falls.
