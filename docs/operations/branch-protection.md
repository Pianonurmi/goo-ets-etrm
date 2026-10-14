# Branch protection

The repository owner applies these rules under **Settings → Rules → Rulesets → New branch ruleset**. They make the
process in [contributing](../contributing.md) enforceable; until they are on, it is a convention only.

| Setting | Value |
|---|---|
| Name | `protected` |
| Enforcement | Active |
| Target branches | `main`, `release/*` |
| Restrict deletions | on |
| Block force pushes | on |
| Require linear history | off (merge commits keep pull requests visible) |
| Require a pull request before merging | on: 1 approval, dismiss stale approvals on new commits, require review from Code Owners, require conversation resolution |
| Require status checks to pass | on: `chart`, `build`; require branches to be up to date |
| Require signed commits | on once every contributor has a signing key |
| Bypass list | empty (the Release workflow only pushes tags, which this ruleset does not cover) |

With a single maintainer, "1 approval" cannot be met by the author. Until a second maintainer joins, either add the
owner to the bypass list for pull requests only (and say so in each pull request), or set the required approvals to
0 and keep the status checks; record which in the table above.

Also under **Settings**:
- **Code security**: Dependabot alerts and security updates on, secret scanning and push protection on, private
  vulnerability reporting on (see `SECURITY.md`).
- **Actions → General**: workflow permissions "Read repository contents"; allow GitHub Actions to create pull
  requests off.
