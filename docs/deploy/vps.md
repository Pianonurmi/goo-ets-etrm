# Deploying the public demo on a VPS

The showcase (ADR 0032, ADR 0033) runs on one small server with Docker: PostgreSQL, the application in the
`showcase` profile, and Caddy in front. Files: `ops/demo/`. The image comes from GitHub Container Registry; CI
publishes `ghcr.io/pianonurmi/goo-ets-etrm:main` and `:<commit>` after the vulnerability gate on every push to `main`.

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
| `ETRM_IMAGE` | `ghcr.io/pianonurmi/goo-ets-etrm:main`, or a commit tag to pin a version. |

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

## Running it

| Task | How |
|---|---|
| Update to the latest image | `docker compose pull app && docker compose up -d app` (a restart resets the demo) |
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
| Out of memory | Lower `mem_limit` of the app only with care; the seed needs about 1.5 GB. Use a 4 GB server. |

## Security checklist

- [ ] SSH keys only, firewall with 22, 80 and 443, unattended upgrades on.
- [ ] `.env` is `chmod 600` and holds strong, unique passwords.
- [ ] HTTPS with a domain before sharing the link widely.
- [ ] The image is a published CI build (vulnerability gate passed); pin a commit tag for a talk or a customer visit.
- [ ] Nobody relies on the data: it is shared by every visitor and reset every night (see `docs/security/showcase.md`).
