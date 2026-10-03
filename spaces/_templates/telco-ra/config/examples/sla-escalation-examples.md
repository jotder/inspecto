# EXAMPLE SLA and escalation values - Telecom revenue assurance

> **These are illustrative examples, not shipped configuration.** Nothing here is active: a Space Template never
> carries a Workflow, an SLA policy or an Escalation Rule (they are governance, written only through their
> `canAdminister` routes, and no import lands them). The numbers are a starting point for a office hours operation, not a
> recommendation - set your own service levels before applying them.

Apply with `POST /components/sla-policy` and `POST /components/escalation-rule` (Settings > Incident governance), as an
administrator. The calendar zone is an explicit IANA zone; change it to yours. Targets are working minutes in that
calendar. A Case is swept exactly like an Incident: the policy stamps its deadlines, a breach is raised once, and
Escalation Rules fire once per breach.

## Incident SLA policy (example)

```json
{
  "id": "incident",
  "objectType": "INCIDENT",
  "calendar": {
    "zone": "Europe/London",
    "workingDays": [
      "MON",
      "TUE",
      "WED",
      "THU",
      "FRI"
    ],
    "start": "09:00",
    "end": "17:00",
    "holidays": []
  },
  "targets": [
    {
      "priority": "CRITICAL",
      "responseMinutes": 60,
      "resolutionMinutes": 480
    },
    {
      "priority": "MAJOR",
      "responseMinutes": 240,
      "resolutionMinutes": 1440
    },
    {
      "priority": "*",
      "resolutionMinutes": 2400
    }
  ]
}
```

## Case SLA policy (example)

```json
{
  "id": "case",
  "objectType": "CASE",
  "calendar": {
    "zone": "Europe/London",
    "workingDays": [
      "MON",
      "TUE",
      "WED",
      "THU",
      "FRI"
    ],
    "start": "09:00",
    "end": "17:00",
    "holidays": []
  },
  "targets": [
    {
      "priority": "*",
      "resolutionMinutes": 4800
    }
  ]
}
```

## Escalation Rules (examples)

Raise priority and notify when an Incident breaches its resolution target:

```json
{
  "id": "example-breach-escalate",
  "objectType": "INCIDENT",
  "on": "breach",
  "target": "resolution",
  "notify": true,
  "raisePriority": true
}
```

Notify when a Case has been open for half of its longest resolution target. To also hand it over, add
`"reassign": "<a user id that exists in your IAM>"` - with an IAM connected, an unknown user id is refused when the
rule is saved:

```json
{
  "id": "example-case-age",
  "objectType": "CASE",
  "on": "age",
  "afterMinutes": 2400,
  "notify": true
}
```
