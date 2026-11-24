"""Create pilot-only credentials on the host. Existing credentials are reused."""
import json
import pathlib
import secrets
import sys
import uuid

state, book_host, login_host = sys.argv[1:]
state = pathlib.Path(state)
env_file = state / "pilot.env"
realm_file = state / "raschet-pilot-realm.json"
credentials_file = state / "accounts.txt"

if env_file.exists():
    saved_hosts = dict(line.split("=", 1) for line in env_file.read_text().splitlines())
    if (saved_hosts["BOOK_HOST"], saved_hosts["LOGIN_HOST"]) != (book_host, login_host):
        sys.exit("Pilot state already exists with different hostnames")
    if not realm_file.is_file() or not credentials_file.is_file():
        sys.exit("Pilot credentials are incomplete. Restore them before retrying.")
    sys.exit(0)
if realm_file.exists() or credentials_file.exists():
    sys.exit("Partial pilot state exists. Inspect it before retrying.")

client_secret = secrets.token_urlsafe(32)
db_password = secrets.token_urlsafe(32)
users = []
lines = []
for username in ("operator", "buyer"):
    password = secrets.token_urlsafe(24)
    lines.append(f"{username} {password}")
    users.append({
        "id": str(uuid.uuid4()),
        "username": username,
        "enabled": True,
        "email": f"{username}@example.test",
        "emailVerified": True,
        "firstName": "Pilot",
        "lastName": username,
        "credentials": [{"type": "password", "value": password, "temporary": False}],
    })
realm = {
    "realm": "raschet-pilot",
    "enabled": True,
    "registrationAllowed": False,
    "resetPasswordAllowed": False,
    "bruteForceProtected": True,
    "sslRequired": "external",
    "users": users,
    "clients": [{
        "clientId": "raschet-pilot",
        "protocol": "openid-connect",
        "enabled": True,
        "clientAuthenticatorType": "client-secret",
        "secret": client_secret,
        "redirectUris": [f"https://{book_host}/signin/callback"],
        "standardFlowEnabled": True,
        "directAccessGrantsEnabled": False,
        "attributes": {"pkce.code.challenge.method": "S256"},
        "protocolMappers": [{
            "name": "Raschet client audience",
            "protocol": "openid-connect",
            "protocolMapper": "oidc-audience-mapper",
            "config": {
                "included.custom.audience": "raschet-pilot",
                "access.token.claim": "true",
                "introspection.token.claim": "true",
            },
        }],
    }],
}
realm_file.write_text(json.dumps(realm, indent=2) + "\n")
# Docker mounts the file itself. Its host parent is readable only by root.
realm_file.chmod(0o444)
credentials_file.write_text("\n".join(lines) + "\n")
credentials_file.chmod(0o600)
env_file.write_text(
    f"BOOK_HOST={book_host}\nLOGIN_HOST={login_host}\n"
    f"DB_PASSWORD={db_password}\nCLIENT_SECRET={client_secret}\n"
    f"OPERATOR_SUB={users[0]['id']}\n"
)
env_file.chmod(0o600)
