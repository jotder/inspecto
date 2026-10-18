"""Identity and access: request path through authenticate -> ABAC route PEP -> capability gate -> write gate."""
import sys, os
sys.path.insert(0, os.path.dirname(__file__))
from svglib import Svg

s = Svg(940, 400, 'Identity and access request path',
        'A request is authenticated by an Authenticator, checked by an optional AccessDecider, '
        'then by the route capability gate and the write-root gate.')
s.box(20, 40, 150, 60, 'gray', 'Browser / SPA', 'Bearer access token')
s.box(220, 40, 170, 60, 'blue', 'POST /auth/exchange', 'TokenRelay (BFF session)')
s.box(440, 40, 230, 60, 'teal', 'OidcTokenRelay', 'Keycloak / WSO2 / generic IdP')
s.box(720, 40, 200, 60, 'coral', 'DemoTokenRelay', 'code demo:<id>, loopback only')
s.arrow(170, 70, 220, 70)
s.arrow(390, 70, 440, 70)
s.path('M390 90 C 560 120, 700 110, 760 100', dash=True)

s.group(20, 140, 900, 120, 'ControlApi request gating (in order)')
s.box(40, 170, 200, 70, 'teal', '1 Authenticator', 'JWT or demo token ->\nSubject (else 401)')
s.box(260, 170, 200, 70, 'purple', '2 AccessDecider', 'PolicyEngine (Advanced)\nALLOW / DENY / ABSTAIN')
s.box(480, 170, 200, 70, 'teal', '3 withCapability', 'role -> capabilities\n(else 403)')
s.box(700, 170, 200, 70, 'teal', '4 WriteGates', 'no write root -> 503')
for x in (240, 460, 680):
    s.arrow(x, 205, x + 20, 205)
s.arrow(95, 100, 140, 170)

s.box(40, 300, 200, 70, 'gray', 'roles.toon', 'authored roles over the\nRoles.SEED table')
s.box(260, 300, 200, 70, 'gray', 'access-policies.toon', 'deny overrides; unreadable\n-> deny all')
s.box(480, 300, 200, 70, 'gray', 'Access Profiles', 'component store:\naccess-profile denials')
s.box(700, 300, 200, 70, 'gray', 'SecretResolver', 'ENV / SYS core; FILE /\nKEYSTORE via inspecto-secrets')
s.arrow(140, 300, 140, 240, dash=True)
s.arrow(360, 300, 360, 240, dash=True)
s.arrow(580, 300, 580, 240, dash=True)
s.save('docs/stakeholders/modules/assets/identity-access.svg')
