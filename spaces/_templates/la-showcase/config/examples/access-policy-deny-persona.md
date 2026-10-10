# EXAMPLE Enterprise access policy - deny one persona's Investigation read

> **This is an illustrative example, not shipped configuration.** It is inert here. The Enterprise policy decision point
> (`providers/inspecto-policy`) reads `access-policies.toon` from the Space config root; it is not loaded in other editions.
> Activate it on an Enterprise build with `PUT /access/policies` (`canConfigureAccess`) using the JSON below.
> Effect (DR-S8): `ra.analyst` - a reviewer member of the seeded Investigation - gets 404 on every `/inv/investigations/*`
> route (the PDP can only narrow, and answers as absence). Not tested for a persona-based `when`: the repository's own
> test (`ControlApiInvestigationPolicyTest`) denies by Dataset. Check with `POST /access/policies/preview` first.

```json
{
  "policies": [
    {
      "name": "deny-ra-analyst-investigations",
      "effect": "deny",
      "target": { "resourceKinds": ["investigation"] },
      "when": "subject.id == 'ra.analyst'"
    }
  ]
}
```
