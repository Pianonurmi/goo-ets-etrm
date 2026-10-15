# Deploying the public demo on a VPS

The showcase (ADR 0032, ADR 0033) runs on one small server with Docker: PostgreSQL, the application in the
`showcase` profile, and Caddy in front. Files: `ops/demo/`. The image comes from GitHub Container Registry; CI
publishes `ghcr.io/pianonurmi/goo-ets-etrm-showcase:main` and `:<commit>` after the vulnerability gate on every push
to `main`, and `:X.Y.Z`, `:X.Y` and `:latest` for every release ([releases](../operations/releases.md)). The showcase
image is the application image plus the demo seeder; the production image `goo-ets-etrm` leaves the seeder out.

For a VM in your own network without a domain, the shorter [homelab guide](homelab.md) is enough.

## 1. The server

- Ubuntu 24.04 LTS, 2 vCPUs, 4 GB RAM, 40 GB disk. The application takes up to 2.5 GB, PostgreSQL 768 MB.
- Sign in with an SSH key; disable password logins (`PasswordAuthentication no` in `/etc/ssh/sshd_config`).
- Firewall: only SSH, HTTP and HTTPS.

  ```bash
  sudo ufw allow OpenSSH && sudo ufw allow 80/tcp && sudo ufw allow 443/tcp && sudo ufw allow 443/udp && sudo ufw enable
  sudo apt-get install -y unattended-upgrades && sudo dpkg-reconfigure -plow unattended-upgrades
  ```
- Install Docker Engine with the compose plugin from Docker's own repository (docs.docker.com, "Install Docker
  Engine on Ubuntu"). Add your user to the `docker` group.

## 2. Configure

```bash
sudo mkdir -p /opt/provena && sudo chown "$USER" /opt/provena && cd /opt/provena
git clone --depth 1 https://github.com/Pianonurmi/goo-ets-etrm.git .
cd ops/demo
cp .env.example .env && chmod 600 .env
nano .env
```

| Variable | What to put |
|---|---|
| `POSTGRES_PASSWORD` | A long random string (`openssl rand -base64 24`). Only the containers use it. |
| `ETRM_DEMO_PASSWORD` | The password of the sign-in `demo`, 12 characters or more. Obvious ones are refused at start. |
| `ETRM_DEMO_USERS` | Instead, several sign-ins: `alice:secret-one,bob:secret-two`. A value may be a `{bcrypt}` hash. |
| `ETRM_SITE_ADDRESS` | `:80` for plain HTTP on the server's address; a domain name for HTTPS (step 5). |
| `ETRM_IMAGE` | `ghcr.io/pianonurmi/goo-ets-etrm-showcase:main` for every green commit, a release (`:0.9.0`, or `:0.9` for its patches), or a commit tag. |

If the repository's package is private, sign in to the registry once:
`echo <token with read:packages> | docker login ghcr.io -u <github user> --password-stdin`.

## 3. Start

```bash
docker compose pull && docker compose up -d
docker compose logs -f app      # "Demo data created in … s" when the example portfolio is ready
```

The first start migrates the database and seeds about six months of business through the API (around a minute on
2 vCPUs). Until then Caddy shows the maintenance page.

## 4. First look

Open `http://<server address>/`, sign in as `demo`, and switch personas from the top right. Check from your machine:

```bash
curl -sI http://<server>/ | grep -i x-robots-tag        # noindex, nofollow
curl -s -o /dev/null -w '%{http_code}\n' http://<server>/actuator/health   # 404: operational endpoints stay inside
curl -s -o /dev/null -w '%{http_code}\n' http://<server>/api/v1/trades     # 401 without signing in
```

**Over plain HTTP the password travels unencrypted.** Use a password you use nowhere else, and add a domain soon.

## 5. HTTPS with a domain

Point an `A` (and `AAAA`) record at the server, set `ETRM_SITE_ADDRESS=demo.example.com` in `.env`, and run
`docker compose up -d caddy`. Caddy obtains and renews the certificate itself; ports 80 and 443 must be open.

## 6. Invite-only behind a Cloudflare Tunnel

For a demo that only the people you invite can open. The server publishes **no port at all**: a connector
(`cloudflared`) inside the stack dials out to Cloudflare, Cloudflare Access asks every visitor for their e-mail address
and lets in only those you listed, and then everyone signs in with the shared demo password. Nobody can skip Access by
going to the server's address, because nothing answers there. You need a domain on Cloudflare (its DNS at Cloudflare)
and a free Zero Trust account.

### The server

Only SSH comes in; nothing else needs to be open, not even 80 or 443. Docker's published ports bypass UFW, which is
why the tunnel variant publishes none.

```bash
sudo ufw default deny incoming && sudo ufw default allow outgoing
sudo ufw allow from <your address> to any port 22 proto tcp     # or: sudo ufw allow OpenSSH
sudo ufw enable
```

Keep SSH keys only and unattended upgrades on, as in step 1.

### The tunnel

1. Zero Trust dashboard → **Networks → Tunnels → Create a tunnel** → Cloudflared, name it (e.g. `provena-demo`).
   Choose Docker as the environment and copy the token from the command shown (the long value after `--token`).
2. Add a **public hostname** (published application route): subdomain `demo`, your domain, service type **HTTP**,
   URL **`caddy:80`**. Always Caddy, never `app:8080`: Caddy hides the actuator, limits request bodies and shows the
   maintenance page while the demo seeds.
3. In `.env` on the server, uncomment and fill in:

   ```bash
   COMPOSE_FILE=docker-compose.yml:docker-compose.tunnel.yml
   CLOUDFLARE_TUNNEL_TOKEN=<the token>
   ETRM_DEMO_PASSWORD=<the shared demo password>
   ```

   `ETRM_SITE_ADDRESS` and the port variables are not used in this variant.
4. `docker compose up -d`. The tunnel shows **Healthy** in the dashboard within a minute
   (`docker compose logs cloudflared` otherwise).

### Who may come in: Cloudflare Access

1. **Access → Applications → Add an application → Self-hosted.** Application domain: `demo.<your domain>`. Session
   duration: 24 hours.
2. Policy **Allow**, rule **Emails** with the addresses you invite (or an **Emails list** you keep under Lists).
   Nobody else gets past the sign-in page.
3. Login method: **One-time PIN** works for any e-mail address without accounts; add Google or GitHub if your guests
   prefer it.
4. Back on the tunnel's public hostname → **Additional application settings → Access → Protect with Access**, with
   your team name and the application's **AUD tag** (on its overview). The connector then refuses any request without a
   valid Access token, should the hostname ever lose its Access application.

Tell your guests the address and the demo password. They confirm their e-mail address once a day, then sign in to
Provena as `demo` and switch personas from the top right.

### Zone settings the demo needs

The application's content security policy refuses scripts that Cloudflare would inject into its pages, so turn these
off for the zone (or for the hostname with a configuration rule):

- **Rocket Loader** (Speed → Optimization),
- **Email Address Obfuscation** (Security → Settings, or Scrape Shield),
- automatic setup of **Web Analytics** for the hostname.

Turn **Always Use HTTPS** on. The edge sends HSTS from Caddy (`Caddyfile.tunnel`), and the session cookie is always
`Secure` in this variant.

### Inviting and removing people

| Task | How |
|---|---|
| Invite someone | Add their address to the policy (or the list); send them the link and the demo password |
| Remove someone | Remove the address, then **Revoke existing tokens** on the application (or the user under Users), and change `ETRM_DEMO_PASSWORD` followed by `docker compose up -d app` (this resets the demo) |
| See who came | Zero Trust → Logs → Access: every sign-in with the e-mail address |
| An Access session ends while someone works | The page reloads once and Access asks for the e-mail confirmation again |

Everyone shares the one demo sign-in, so the audit trail inside Provena shows personas, not people: the Access log
says who was in. Five wrong demo passwords from one visitor lock only that visitor for 15 minutes; Caddy takes the
visitor's address from `CF-Connecting-IP`, and only from the connector's network.

### Check it

```bash
curl -m 5 http://<server address>/            # from your machine: no answer, nothing listens
docker compose ps                             # caddy shows no host ports, cloudflared and app are healthy
curl -sI https://demo.<your domain>/ | head -1   # a redirect to <team>.cloudflareaccess.com
```

Then open the address in a private window with an e-mail address that is not on the list: Access refuses it. Signed
in with an invited one, `https://demo.<your domain>/actuator/health` is a 404.

## Running it

| Task | How |
|---|---|
| Update the image | Set the version in `ETRM_IMAGE` if you pin one, then `docker compose pull app && docker compose up -d app` (a restart resets the demo) |
| Reset the data now | Administration → Demo as the `admin` persona (needs a reason), or `docker compose restart app` |
| Nightly reset | Automatic at 04:15 Europe/Helsinki (`ETRM_SHOWCASE_RESET_CRON` to change) |
| Logs | `docker compose logs -f app` (JSON lines; the sign-in name is in `etrm.login`) |
| Status | `docker compose ps`; the app is `healthy` once the seed has finished |
| Backup | `ops/demo/backup.sh` from cron, e.g. `30 2 * * *` (keeps seven dumps in `/var/backups/provena`) |
| Restore a dump to look at | `gunzip -c dump.sql.gz \| docker compose exec -T postgres psql -U etrm -d postgres -c 'create database look'` then load into `look`; the showcase itself always starts clean |
| Stop | `docker compose down` (data volumes stay; `down -v` removes them) |

## Troubleshooting

| Symptom | Cause and fix |
|---|---|
| Maintenance page for more than five minutes | `docker compose logs app`: a failing demo step names the request and response. A restart resumes at that step. |
| App restarts in a loop | Usually a missing or weak `ETRM_DEMO_PASSWORD`, or `ETRM_SHOWCASE_DATABASE` not matching the database: the log says which. |
| `429 Too many attempts` at sign-in | Five wrong passwords lock that name or address for 15 minutes. |
| Certificate not issued | The domain must resolve to this server and ports 80/443 must be reachable; see `docker compose logs caddy`. |
| Tunnel down, or `502 Bad gateway` from Cloudflare | `docker compose logs cloudflared`: an invalid token stops it at once; the public hostname's service must be `http://caddy:80`. |
| Blank page behind the tunnel, CSP errors in the browser console | A Cloudflare feature injects a script: turn off Rocket Loader, Email Address Obfuscation and Web Analytics' automatic setup (step 6). |
| Out of memory | Lower `mem_limit` of the app only with care; the seed needs about 1.5 GB. Use a 4 GB server. |

## Security checklist

- [ ] SSH keys only, unattended upgrades on; firewall with 22, 80 and 443, or with 22 alone behind a tunnel.
- [ ] Behind a tunnel: an Access application with an e-mail allowlist on the hostname, "Protect with Access" on the
      tunnel's route, and `docker compose ps` showing no published port.
- [ ] `.env` is `chmod 600` and holds strong, unique passwords.
- [ ] HTTPS with a domain (or the tunnel) before sharing the link.
- [ ] The image is a published CI build (vulnerability gate passed); pin a release (`:0.9.0`) for a talk or a customer visit.
- [ ] Nobody relies on the data: it is shared by every visitor and reset every night (see `docs/security/showcase.md`).
