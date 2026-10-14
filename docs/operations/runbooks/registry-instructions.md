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
