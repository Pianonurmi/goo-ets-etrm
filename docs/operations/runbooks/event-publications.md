# Event publications

**Signal.** `EtrmEventPublicationsStuck`: domain events have not completed for half an hour. Events going to Kafka
(ADR 0029) wait while Kafka is down; a listener that keeps failing (a poison publication) never completes.

**Confirm.**
```sql
SELECT listener_id, event_type, publication_date, completion_attempts, last_resubmission_date
  FROM etrm_platform.event_publication WHERE completion_date IS NULL ORDER BY publication_date LIMIT 50;
```

**Do.**
- **Kafka outage**: nothing to do in the application; publications are resubmitted every ten minutes
  (`etrm.events.resubmit-after`) and reach Kafka once it is back. Consumers receive them late, in order per key.
- **Poison publication**: one listener fails for one event every time. Read the stack trace in the log, fix the
  cause or ship a fix; meanwhile it is resubmitted harmlessly. Do not delete rows unless the event has been handled
  another way, and record why in the incident.

**Verify.** The gauge `etrm_events_publications_incomplete` returns to 0.
