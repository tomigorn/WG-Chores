# Idempotent Authentik setup for WG Chores v2 (Docker/WG-Chores-v2): the app's own "Sign in with Holy Grail" (OIDC, no forward auth), only group WG.
# The client secret leaves only as a "FILE <dir>/secrets/<name> 65035:65037 0400 value" line on the pipe, written by apply-sso-env.py; the target is $WG_CHORES_OIDC_FILE (relative to Docker/):
#   cd Docker/Authentik && docker compose exec -T -e WG_CHORES_OIDC_FILE=WG-Chores-v2/secrets/oidc_client_secret \
#     -e WG_CHORES_MEMBERS="tomigorn <flatmate's username>" server ak shell < scripts/provision-wg-chores-sso.py 2>/dev/null | python3 scripts/apply-sso-env.py
# Client id fixed "wg-chores", issuer https://sso.holy-grail.ch/application/o/wg-chores/ (both in Docker/WG-Chores-v2/docker-compose.yaml).
# The users in $WG_CHORES_MEMBERS (space-separated, default tomigorn; kept out of this public repo) join WG when they exist; rerun after a missing one has signed up.
from authentik.core.models import Application, Group, User
from authentik.policies.models import PolicyBinding
from authentik.providers.oauth2.models import (
    ClientType, OAuth2Provider, RedirectURI, RedirectURIMatchingMode, RedirectURIType, ScopeMapping)
import os
import secrets

HOST = "https://wg-chores.holy-grail.ch"
MEMBERS = os.environ.get("WG_CHORES_MEMBERS", "tomigorn").split()
secret_path = os.environ.get("WG_CHORES_OIDC_FILE", "WG-Chores-v2/secrets/oidc_client_secret")  # leak-check: ok
oidc_src = OAuth2Provider.objects.get(name="Nextcloud")  # flows + RS256 signing key to copy
if oidc_src.signing_key is None:
    raise SystemExit("Nextcloud provider has no signing key: WG Chores refuses HS256 ID tokens")

wg, created = Group.objects.get_or_create(name="WG")
if created:
    print("CHANGE group WG created")
for name in MEMBERS:
    user = User.objects.filter(username=name).first()
    if user is None:
        print(f"MISSING user {name}: not in Authentik yet, rerun after the signup")
    elif wg.users.filter(pk=user.pk).exists():
        print(f"OK {name} already in WG")
    else:
        wg.users.add(user)
        print(f"CHANGE {name} added to WG")

# The app requests scope "groups" and checks OIDC_REQUIRED_GROUP=WG in the ID token.
groups, _ = ScopeMapping.objects.update_or_create(name="WG Chores groups", defaults={
    "scope_name": "groups", "description": "Group membership",
    "expression": 'return {"groups": [g.name for g in request.user.groups.all()]}'})

p, _ = OAuth2Provider.objects.get_or_create(name="wg-chores", defaults={
    "client_id": "wg-chores", "client_secret": secrets.token_urlsafe(60),  # leak-check: ok
    "authorization_flow": oidc_src.authorization_flow, "invalidation_flow": oidc_src.invalidation_flow})
p.client_id = "wg-chores"
p.authorization_flow = oidc_src.authorization_flow
p.invalidation_flow = oidc_src.invalidation_flow
p.signing_key = oidc_src.signing_key
p.client_type = ClientType.CONFIDENTIAL
p.grant_types = ["authorization_code", "refresh_token"]  # empty = every request rejected
p.include_claims_in_id_token = True
p.redirect_uris = [RedirectURI(RedirectURIMatchingMode.STRICT, f"{HOST}/auth/oidc/callback",
                               RedirectURIType.AUTHORIZATION)]
p.save()
# By managed id, not scope_name: several custom mappings also use scope "email"/"profile".
p.property_mappings.set([ScopeMapping.objects.get(managed=f"goauthentik.io/providers/oauth2/scope-{s}")
                         for s in ("openid", "email", "profile")] + [groups])

app, _ = Application.objects.update_or_create(
    slug="wg-chores", defaults={"name": "WG Chores", "provider": p, "meta_launch_url": HOST})
# An application with at least one binding is denied to everyone the bindings don't pass.
PolicyBinding.objects.get_or_create(target=app, group=wg, defaults={"order": 0})
other = PolicyBinding.objects.filter(target=app).exclude(group=wg).count()
if other:
    print(f"WARN wg-chores has {other} other binding(s): check that only WG passes")

print(f"FILE {secret_path} 65035:65037 0400 {p.client_secret}")  # leak-check: ok
print("OK provisioned")
