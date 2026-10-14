# Event publications

**Signal.** `EtrmEventPublicationsStuck`: domain events have not completed for half an hour. Events going to Kafka
(ADR 0029) wait while Kafka is down; a listener that keeps failing (a poison publication) never completes.
`EtrmEventPublicationsParked`: a publication failed `etrm.events.max-attempts` times (10) and is no longer
resubmitted automatically.

**Confirm.**
```sql
SELECT listener_id, event_type, publication_date, completion_attempts, last_resubmission_date
  FROM etrm_platform.event_publication WHERE completion_date IS NULL ORDER BY publication_date LIMIT 50;
```

**Do.**
- **Kafka outage**: nothing to do in the application; publications are resubmitted every ten minutes
  (`etrm.events.resubmit-after`) and reach Kafka once it is back. Consumers receive them late, in order per key.
- **Poison publication**: one listener fails for one event every time. Read the stack trace in the log, fix the
  cause or ship a fix; meanwhile it is resubmitted until it is parked. Do not delete rows unless the event has been
  handled another way, and record why in the incident.
- **Parked publication**: after the fix, an administrator lists them with `GET /api/v1/admin/event-publications/parked`
  and sends each again with `POST /api/v1/admin/event-publications/{id}/resubmit`. Listeners are idempotent on the
  event id, so a publication that did partly complete before is safe to send again.

**Verify.** The gauges `etrm_events_publications_incomplete` and `etrm_events_publications_parked` return to 0.
